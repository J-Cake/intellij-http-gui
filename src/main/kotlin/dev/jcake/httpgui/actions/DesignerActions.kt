package dev.jcake.httpgui.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import dev.jcake.httpgui.HttpGuiFileEditor

/** Actions in the HTTP Client's editor toolbar that act on the designer of that file. */
abstract class DesignerAction(private val needsVisibleDesigner: Boolean) : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    protected fun designer(e: AnActionEvent): HttpGuiFileEditor? {
        val project = e.project ?: return null
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        return HttpGuiFileEditor.find(project, file)
    }

    override fun update(e: AnActionEvent) {
        val designer = designer(e)
        e.presentation.isEnabledAndVisible = designer != null && (!needsVisibleDesigner || designer.component.isShowing)
    }
}

class ExpandAllRequestsAction : DesignerAction(needsVisibleDesigner = true) {
    override fun actionPerformed(e: AnActionEvent) {
        designer(e)?.expandAll(true)
    }
}

class CollapseAllRequestsAction : DesignerAction(needsVisibleDesigner = true) {
    override fun actionPerformed(e: AnActionEvent) {
        designer(e)?.expandAll(false)
    }
}

class EnvironmentAuthenticationAction : DesignerAction(needsVisibleDesigner = false) {
    override fun actionPerformed(e: AnActionEvent) {
        designer(e)?.openAuthSettings(null)
    }
}
