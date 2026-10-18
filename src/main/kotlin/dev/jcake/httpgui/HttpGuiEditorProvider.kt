package dev.jcake.httpgui

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/** Adds a "Designer" tab next to the text editor of `.http` / `.rest` files. */
class HttpGuiEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean =
        !file.isDirectory && file.extension?.lowercase() in EXTENSIONS

    override fun createEditor(project: Project, file: VirtualFile): FileEditor = HttpGuiFileEditor(project, file)

    override fun getEditorTypeId(): String = "http-request-designer"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR

    companion object {
        val EXTENSIONS = setOf("http", "rest")
    }
}
