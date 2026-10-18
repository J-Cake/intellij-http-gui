package dev.jcake.httpgui.services

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import dev.jcake.httpgui.core.EnvironmentFile
import dev.jcake.httpgui.core.EnvironmentSet
import dev.jcake.httpgui.core.PRIVATE_ENV_FILE
import dev.jcake.httpgui.core.PUBLIC_ENV_FILE
import dev.jcake.httpgui.core.updateAuthConfig

/** Locates and reads the HTTP Client environment files that apply to an `.http` file. */
object HttpEnvironments {
    val FILE_NAMES = setOf(PUBLIC_ENV_FILE, PRIVATE_ENV_FILE)

    fun load(project: Project, httpFile: VirtualFile): EnvironmentSet = ReadAction.compute<EnvironmentSet, RuntimeException> {
        val files = findEnvFiles(project, httpFile)
        EnvironmentSet(files.map { EnvironmentFile.parse(it.path, it.name == PRIVATE_ENV_FILE, text(it)) })
    }

    /**
     * Env files next to the `.http` file or in one of its parent directories win; otherwise all env
     * files of the project are used (the HTTP Client offers environments from the whole project).
     */
    private fun findEnvFiles(project: Project, httpFile: VirtualFile): List<VirtualFile> {
        val root = project.guessProjectDir()
        var dir = httpFile.parent
        while (dir != null) {
            val current: VirtualFile = dir
            val nearby = FILE_NAMES.mapNotNull { current.findChild(it) }
            if (nearby.isNotEmpty()) return nearby
            if (current == root) break
            dir = current.parent
        }
        if (DumbService.isDumb(project)) return emptyList()
        val scope = GlobalSearchScope.projectScope(project)
        return FILE_NAMES.flatMap { FilenameIndex.getVirtualFilesByName(it, scope) }
    }

    private fun text(file: VirtualFile): String =
        FileDocumentManager.getInstance().getCachedDocument(file)?.text ?: runCatching { VfsUtilCore.loadText(file) }.getOrDefault("")

    /** Writes an OAuth configuration into the env files next to [httpFile], creating them if needed. */
    fun saveAuthConfig(
        project: Project,
        httpFile: VirtualFile,
        environment: String,
        id: String,
        publicValues: Map<String, Any?>,
        privateValues: Map<String, Any?>,
    ) {
        val dir = existingEnvDir(project, httpFile) ?: httpFile.parent ?: return
        WriteCommandAction.runWriteCommandAction(project, "Save HTTP Client Auth Configuration", null, Runnable {
            update(dir, PUBLIC_ENV_FILE, environment, id, publicValues)
            if (privateValues.values.any { it != null && it != "" }) update(dir, PRIVATE_ENV_FILE, environment, id, privateValues)
        })
    }

    private fun existingEnvDir(project: Project, httpFile: VirtualFile): VirtualFile? =
        findEnvFiles(project, httpFile).firstOrNull { it.name == PUBLIC_ENV_FILE }?.parent

    private fun update(dir: VirtualFile, name: String, environment: String, id: String, values: Map<String, Any?>) {
        val file = dir.findChild(name) ?: dir.createChildData(this, name)
        val documents = FileDocumentManager.getInstance()
        val document = documents.getDocument(file)
        val current = document?.text ?: VfsUtilCore.loadText(file)
        val updated = updateAuthConfig(current.ifBlank { "{}" }, environment, id, values)
        if (document != null) {
            document.setText(updated)
            documents.saveDocument(document)
        } else {
            VfsUtil.saveText(file, updated)
        }
    }
}
