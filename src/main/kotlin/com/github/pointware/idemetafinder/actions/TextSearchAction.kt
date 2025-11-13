package com.github.pointware.idemetafinder.actions

import com.github.pointware.idemetafinder.dialog.TextSearchDialog
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class TextSearchAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dialog = TextSearchDialog(project)
        dialog.show()
    }

    override fun update(e: AnActionEvent) {
        // 액션은 프로젝트가 열려있을 때만 활성화
        e.presentation.isEnabled = e.project != null
    }
}
