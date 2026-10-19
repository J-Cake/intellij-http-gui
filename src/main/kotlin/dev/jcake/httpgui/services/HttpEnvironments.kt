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
import dev.jcake.httpgui.core.setAuthConfig
import dev.jcake.httpgui.core.splitSecrets

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

    /** A change to one `Security.Auth` entry; a null [config] removes it. */
    data class AuthChange(val environment: String, val id: String, val config: Map<String, Any?>?)

    /**
     * Writes OAuth configurations to the env files next to [httpFile] (or the ones it uses), creating
     * them if needed. Secrets go to the private file, everything else to the public one.
     */
    fun writeAuthConfigs(project: Project, httpFile: VirtualFile, changes: List<AuthChange>) {
        if (changes.isEmpty()) return
        val dir = existingEnvDir(project, httpFile) ?: httpFile.parent ?: return
        WriteCommandAction.runWriteCommandAction(project, "Save HTTP Client Auth Configurations", null, Runnable {
            for (change in changes) {
                val (public, private) = change.config?.let { splitSecrets(it) } ?: (null to null)
                update(dir, PUBLIC_ENV_FILE, create = public != null) { setAuthConfig(it, change.environment, change.id, public) }
                update(dir, PRIVATE_ENV_FILE, create = !private.isNullOrEmpty()) { setAuthConfig(it, change.environment, change.id, private) }
            }
        })
    }

    private fun existingEnvDir(project: Project, httpFile: VirtualFile): VirtualFile? =
        findEnvFiles(project, httpFile).firstOrNull { it.name == PUBLIC_ENV_FILE }?.parent

    private fun update(dir: VirtualFile, name: String, create: Boolean, transform: (String) -> String) {
        val file = dir.findChild(name) ?: if (create) dir.createChildData(this, name) else return
        val documents = FileDocumentManager.getInstance()
        val document = documents.getDocument(file)
        val current = document?.text ?: VfsUtilCore.loadText(file)
        val updated = transform(current.ifBlank { "{}" })
        if (updated == current) return
        if (document != null) {
            document.setText(updated)
            documents.saveDocument(document)
        } else {
            VfsUtil.saveText(file, updated)
        }
    }
}
