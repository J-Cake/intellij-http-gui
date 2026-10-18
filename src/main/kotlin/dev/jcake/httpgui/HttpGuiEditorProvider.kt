package dev.jcake.httpgui

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.TextEditorWithPreviewProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Replaces the editor of `.http` / `.rest` files with the text editor and the designer side by side.
 * The toolbar switches between text only, split and designer only, like the Markdown editor; the
 * text editor keeps the HTTP Client's run buttons.
 */
class HttpGuiEditorProvider : TextEditorWithPreviewProvider(DesignerProvider()), DumbAware {
    override fun createSplitEditor(firstEditor: TextEditor, secondEditor: FileEditor): FileEditor =
        TextEditorWithPreview(firstEditor, secondEditor, "HTTP Request Designer", TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW, false)

    /** Creates the designer half; not registered on its own. */
    private class DesignerProvider : FileEditorProvider, DumbAware {
        override fun accept(project: Project, file: VirtualFile): Boolean =
            !file.isDirectory && file.extension?.lowercase() in EXTENSIONS

        override fun createEditor(project: Project, file: VirtualFile): FileEditor = HttpGuiFileEditor(project, file)

        override fun getEditorTypeId(): String = "http-request-designer"

        override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR
    }

    companion object {
        val EXTENSIONS = setOf("http", "rest")
    }
}
