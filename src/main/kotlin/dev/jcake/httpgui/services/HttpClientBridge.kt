package dev.jcake.httpgui.services

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.lang.reflect.Method

/**
 * Access to the bundled HTTP Client plugin, which is optional: without it the designer shows its own
 * environment selector. Its classes are internal, so they are used reflectively.
 */
object HttpClientBridge {
    private val LOG = logger<HttpClientBridge>()
    private val PLUGIN_ID = PluginId.getId("com.jetbrains.restClient")
    private const val SELECTED_ENVIRONMENTS = "com.intellij.httpClient.http.request.environment.HttpClientSelectedEnvironments"

    private class Accessors(val getInstance: Method, val get: Method, val isSelectBeforeRun: Method?)

    private val accessors: Accessors? by lazy {
        try {
            val loader = PluginManagerCore.getPlugin(PLUGIN_ID)?.takeIf { PluginManagerCore.isLoaded(PLUGIN_ID) }?.pluginClassLoader
                ?: return@lazy null
            val type = loader.loadClass(SELECTED_ENVIRONMENTS)
            Accessors(
                getInstance = type.getMethod("getInstance", Project::class.java),
                get = type.getMethod("get", VirtualFile::class.java),
                isSelectBeforeRun = runCatching { type.getMethod("isSelectBeforeRun", String::class.java) }.getOrNull(),
            )
        } catch (e: Exception) {
            LOG.info("HTTP Client environment selection is not available", e)
            null
        }
    }

    /** Whether the HTTP Client's own environment selector ("Run with") can be followed. */
    val isAvailable: Boolean get() = accessors != null

    /** The environment selected for [file] in the HTTP Client's "Run with" selector, if any. */
    fun selectedEnvironment(project: Project, file: VirtualFile): String? {
        val a = accessors ?: return null
        return try {
            val service = a.getInstance.invoke(null, project)
            val name = a.get.invoke(service, file) as? String
            when {
                name.isNullOrBlank() -> null
                a.isSelectBeforeRun?.invoke(null, name) == true -> null
                else -> name
            }
        } catch (e: Exception) {
            LOG.debug("Could not read the selected HTTP Client environment", e)
            null
        }
    }
}
