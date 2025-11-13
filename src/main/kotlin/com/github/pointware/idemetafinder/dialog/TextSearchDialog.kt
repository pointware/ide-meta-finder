package com.github.pointware.idemetafinder.dialog

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

class TextSearchDialog(private val project: Project) : DialogWrapper(project) {
    private val searchField = JBTextField()
    private val resultListModel = DefaultListModel<SearchResult>()
    private val resultList = JBList(resultListModel)
    private val statusLabel = JLabel("검색어를 입력하세요")

    data class SearchResult(
        val file: VirtualFile,
        val lineNumber: Int,
        val lineText: String,
        val columnNumber: Int
    ) {
        override fun toString(): String {
            return "${file.name} (${file.path}:${lineNumber + 1}) - ${lineText.trim()}"
        }
    }

    init {
        title = "텍스트 검색"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(5, 5))
        panel.preferredSize = Dimension(800, 600)

        // 검색 입력 패널
        val searchPanel = JPanel(BorderLayout(5, 5))
        searchPanel.add(JLabel("검색어:"), BorderLayout.WEST)
        searchPanel.add(searchField, BorderLayout.CENTER)

        val searchButton = JButton("검색")
        searchButton.addActionListener { performSearch() }
        searchPanel.add(searchButton, BorderLayout.EAST)

        // 검색 필드에서 엔터키로 검색
        searchField.addActionListener { performSearch() }

        // 결과 리스트
        resultList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        resultList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) {
                    openSelectedFile()
                }
            }
        })

        val scrollPane = JBScrollPane(resultList)

        // 상태 라벨
        val bottomPanel = JPanel(BorderLayout())
        bottomPanel.add(statusLabel, BorderLayout.WEST)

        // 패널 조립
        panel.add(searchPanel, BorderLayout.NORTH)
        panel.add(scrollPane, BorderLayout.CENTER)
        panel.add(bottomPanel, BorderLayout.SOUTH)

        return panel
    }

    private fun performSearch() {
        val searchText = searchField.text.trim()

        if (searchText.isEmpty()) {
            statusLabel.text = "검색어를 입력하세요"
            return
        }

        resultListModel.clear()
        statusLabel.text = "검색 중..."

        // 백그라운드 작업으로 검색 실행
        ProgressManager.getInstance().run(object : com.intellij.openapi.progress.Task.Backgroundable(
            project, "텍스트 검색 중...", true
        ) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                val results = mutableListOf<SearchResult>()

                try {
                    ReadAction.run<Exception> {
                        val psiManager = PsiManager.getInstance(project)
                        val scope = GlobalSearchScope.projectScope(project)

                        // 프로젝트의 모든 파일을 검색
                        val allFiles = FileTypeIndex.getFiles(
                            com.intellij.openapi.fileTypes.PlainTextFileType.INSTANCE,
                            scope
                        ).toList()

                        // 추가로 다른 파일 타입도 포함
                        val allProjectFiles = mutableSetOf<VirtualFile>()
                        allProjectFiles.addAll(allFiles)

                        // 프로젝트의 모든 파일을 검색하는 더 포괄적인 방법
                        com.intellij.openapi.vfs.VfsUtil.iterateChildrenRecursively(
                            project.baseDir,
                            { file -> !file.isDirectory },
                            { file ->
                                if (!file.isDirectory) {
                                    allProjectFiles.add(file)
                                }
                                true
                            }
                        )

                        indicator.text = "검색 중... (${allProjectFiles.size}개 파일)"

                        allProjectFiles.forEachIndexed { index, virtualFile ->
                            if (indicator.isCanceled) {
                                return@ReadAction
                            }

                            indicator.fraction = index.toDouble() / allProjectFiles.size
                            indicator.text2 = virtualFile.name

                            try {
                                val psiFile = psiManager.findFile(virtualFile)
                                if (psiFile != null) {
                                    searchInFile(psiFile, searchText, results)
                                }
                            } catch (e: Exception) {
                                // 파일 읽기 오류 무시
                            }
                        }
                    }

                    // UI 스레드에서 결과 업데이트
                    ApplicationManager.getApplication().invokeLater {
                        results.forEach { resultListModel.addElement(it) }
                        statusLabel.text = "${results.size}개의 결과를 찾았습니다"

                        if (results.isEmpty()) {
                            statusLabel.text = "검색 결과가 없습니다"
                        }
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        statusLabel.text = "검색 중 오류 발생: ${e.message}"
                    }
                }
            }
        })
    }

    private fun searchInFile(psiFile: PsiFile, searchText: String, results: MutableList<SearchResult>) {
        val virtualFile = psiFile.virtualFile ?: return
        val text = psiFile.text
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return

        var index = text.indexOf(searchText, 0, ignoreCase = true)
        while (index >= 0) {
            val lineNumber = document.getLineNumber(index)
            val lineStartOffset = document.getLineStartOffset(lineNumber)
            val lineEndOffset = document.getLineEndOffset(lineNumber)
            val lineText = text.substring(lineStartOffset, lineEndOffset)
            val columnNumber = index - lineStartOffset

            results.add(
                SearchResult(
                    file = virtualFile,
                    lineNumber = lineNumber,
                    lineText = lineText,
                    columnNumber = columnNumber
                )
            )

            // 다음 검색 위치로 이동
            index = text.indexOf(searchText, index + 1, ignoreCase = true)
        }
    }

    private fun openSelectedFile() {
        val selected = resultList.selectedValue ?: return

        FileEditorManager.getInstance(project)
            .openFile(selected.file, true)
            .firstOrNull()
            ?.let { editor ->
                if (editor is TextEditor) {
                    val textEditor = editor.editor
                    val offset = textEditor.document.getLineStartOffset(selected.lineNumber) + selected.columnNumber
                    textEditor.caretModel.moveToOffset(offset)
                    textEditor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
                }
            }
    }

    override fun createActions(): Array<Action> {
        return arrayOf(cancelAction)
    }
}
