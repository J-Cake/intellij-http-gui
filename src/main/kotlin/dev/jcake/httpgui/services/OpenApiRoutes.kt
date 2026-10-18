package dev.jcake.httpgui.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import dev.jcake.httpgui.core.ApiRoute
import dev.jcake.httpgui.core.OpenApi
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/**
 * Collects routes for prefilling requests.
 *
 * Primary source is the IDE's Endpoints tool window (the `com.intellij.microservices.endpointsProvider`
 * extension point, which the OpenAPI/Swagger plugin and framework plugins contribute to). That API
 * is not available in every IDE, so it is accessed reflectively; OpenAPI files of the project are
 * scanned directly as well.
 *
 * Must be called inside a read action, in smart mode.
 */
object OpenApiRoutes {
    private val LOG = logger<OpenApiRoutes>()
    private const val ENDPOINTS_EP = "com.intellij.microservices.endpointsProvider"

    /** How the Endpoints view splits a project into filterable parts (modules, Cargo packages, ...). */
    private const val PROJECT_MODEL_EP = "com.intellij.microservices.endpointsProjectModel"
    private const val MAX_SPEC_SIZE = 4L * 1024 * 1024

    fun collect(project: Project): List<ApiRoute> {
        val routes = specFileRoutes(project) + endpointRoutes(project)
        return routes.distinctBy { "${it.method} ${it.path}" }.sortedWith(compareBy({ it.path }, { it.method }))
    }

    // ---- OpenAPI files --------------------------------------------------------------------------

    private fun specFileRoutes(project: Project): List<ApiRoute> {
        val scope = GlobalSearchScope.projectScope(project)
        val candidates = listOf("yaml", "yml", "json").flatMap { FilenameIndex.getAllFilesByExt(project, it, scope) }
        return candidates.filter { it.length in 1..MAX_SPEC_SIZE && it.name !in HttpEnvironments.FILE_NAMES }
            .flatMap { file ->
                ProgressManager.checkCanceled()
                val text = text(file) ?: return@flatMap emptyList()
                if (!OpenApi.looksLikeSpec(text)) emptyList() else OpenApi.parse(text, file.name)
            }
    }

    private fun text(file: VirtualFile): String? =
        FileDocumentManager.getInstance().getCachedDocument(file)?.text ?: runCatching { VfsUtilCore.loadText(file) }.getOrNull()

    // ---- Endpoints tool window ------------------------------------------------------------------

    private fun endpointRoutes(project: Project): List<ApiRoute> {
        val area = ApplicationManager.getApplication().extensionArea
        if (!area.hasExtensionPoint(ENDPOINTS_EP)) return emptyList()
        val providers = area.getExtensionPoint<Any>(ENDPOINTS_EP).extensionList
        return providers.flatMap { provider ->
            ProgressManager.checkCanceled()
            try {
                routesOf(project, provider)
            } catch (e: Exception) {
                rethrowIfCanceled(e)
                LOG.debug("Could not read endpoints from ${provider.javaClass.name}", e)
                emptyList()
            }
        }
    }

    private fun routesOf(project: Project, provider: Any): List<ApiRoute> {
        val type = provider.javaClass
        val getGroups = type.method("getEndpointGroups", 2) ?: return emptyList()
        val getEndpoints = type.method("getEndpoints", 1) ?: return emptyList()
        val getUrlTargets = type.method("getUrlTargetInfo", 2)
        val getPresentation = type.method("getEndpointPresentation", 2)
        val source = (type.method("getPresentation", 0)?.invoke(provider))?.let { call(it, "getTitle") as? String } ?: "Endpoints"

        val routes = mutableListOf<ApiRoute>()
        for (filter in filters(project, getGroups.parameterTypes[1])) {
            val groups = getGroups.invoke(provider, project, filter) as? Iterable<*> ?: continue
            for (group in groups) {
                val endpoints = getEndpoints.invoke(provider, group) as? Iterable<*> ?: continue
                for (endpoint in endpoints) {
                    ProgressManager.checkCanceled()
                    val targets = (getUrlTargets?.invoke(provider, group, endpoint) as? Iterable<*>)?.toList().orEmpty()
                    if (targets.isNotEmpty()) {
                        targets.forEach { target -> routes += routesFromTarget(target ?: return@forEach, source) }
                    } else {
                        val presentation = getPresentation?.invoke(provider, group, endpoint)
                        routeFromPresentation(presentation, source)?.let { routes += it }
                    }
                }
            }
        }
        return routes
    }

    /**
     * The filters the Endpoints view itself would query with. They come from the IDE's project models,
     * since some providers only understand their own filters (e.g. RustRover's, per Cargo package);
     * plain per-module filters and an accept-all proxy are fallbacks.
     */
    private fun filters(project: Project, filterType: Class<*>): List<Any> {
        val loader = filterType.classLoader
        val scope = GlobalSearchScope.projectScope(project)
        projectModelFilters(project).takeIf { it.isNotEmpty() }?.let { return it }

        runCatching {
            val moduleFilter = loader.loadClass("com.intellij.microservices.endpoints.ModuleEndpointsFilter")
            val ctor = moduleFilter.constructors.firstOrNull { it.parameterCount == 3 } ?: error("no ctor")
            ModuleManager.getInstance(project).modules.map { ctor.newInstance(it, false, false) }
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }

        if (filterType.isInterface) {
            // Accept everything: booleans answer true, scopes are the project scope.
            val proxy = Proxy.newProxyInstance(loader, arrayOf(filterType)) { self, method, args ->
                when {
                    method.name == "equals" && method.parameterCount == 1 -> self === args?.get(0)
                    method.name == "hashCode" && method.parameterCount == 0 -> System.identityHashCode(self)
                    method.name == "toString" && method.parameterCount == 0 -> "HttpGuiEndpointsFilter"
                    method.returnType == java.lang.Boolean.TYPE -> true
                    method.returnType != Any::class.java && method.returnType.isAssignableFrom(GlobalSearchScope::class.java) -> scope
                    else -> null
                }
            }
            return listOf(proxy)
        }
        return emptyList()
    }

    private fun projectModelFilters(project: Project): List<Any> {
        val area = ApplicationManager.getApplication().extensionArea
        if (!area.hasExtensionPoint(PROJECT_MODEL_EP)) return emptyList()
        return area.getExtensionPoint<Any>(PROJECT_MODEL_EP).extensionList.flatMap { model ->
            try {
                val entities = model.javaClass.method("getModuleEntities", 1)?.invoke(model, project) as? Collection<*> ?: emptyList<Any>()
                val createFilter = model.javaClass.method("createFilter", 3) ?: return@flatMap emptyList()
                // Production code only: no tests, no libraries.
                entities.mapNotNull { entity -> entity?.let { createFilter.invoke(model, it, false, false) } }
            } catch (e: Exception) {
                rethrowIfCanceled(e)
                LOG.debug("Could not create endpoint filters with ${model.javaClass.name}", e)
                emptyList()
            }
        }
    }

    private fun rethrowIfCanceled(e: Exception) {
        if (e is ProcessCanceledException) throw e
        (e as? InvocationTargetException)?.targetException?.let { if (it is ProcessCanceledException) throw it }
    }

    private fun routesFromTarget(target: Any, source: String): List<ApiRoute> {
        val path = urlPath(call(target, "getPath")) ?: return emptyList()
        val methods = (call(target, "getMethods") as? Collection<*>)?.map { it.toString().uppercase() }?.filter { it.isNotBlank() }
            ?.ifEmpty { null } ?: listOf("GET")
        val schemes = (call(target, "getSchemes") as? Collection<*>)?.map { it.toString() }.orEmpty()
        val authorities = (call(target, "getAuthorities") as? Collection<*>)?.mapNotNull { a ->
            (call(a ?: return@mapNotNull null, "getText") as? String)?.takeIf { it.isNotBlank() }
        }.orEmpty()
        val server = authorities.firstOrNull()?.let { (schemes.firstOrNull() ?: "http://").let { s -> if (s.endsWith("://")) s else "$s://" } + it }
        val query = (call(target, "getQueryParameters") as? Iterable<*>)?.mapNotNull { p -> p?.let { call(it, "getName") as? String } }.orEmpty()
        val contentType = (call(target, "getContentTypes") as? Collection<*>)?.firstOrNull()?.toString()
        return methods.map {
            ApiRoute(method = it, path = path, servers = listOfNotNull(server), queryParams = query.distinct(), contentType = contentType, source = source)
        }
    }

    /** Renders a `UrlPath` with path variables as `{name}` (its own presentation uses `*`). */
    private fun urlPath(path: Any?): String? {
        if (path == null) return null
        val segments = call(path, "getSegments") as? List<*> ?: return call(path, "getPresentation") as? String ?: path.toString()
        val rendered = segments.joinToString("/") { renderSegment(it) }
        return if (rendered.startsWith("/")) rendered else "/$rendered"
    }

    private fun renderSegment(segment: Any?): String {
        if (segment == null) return ""
        (call(segment, "getValue") as? String)?.let { return it }
        if (segment.javaClass.simpleName == "Variable" || segment.javaClass.name.endsWith("\$Variable")) {
            return "{" + ((call(segment, "getVariableName") as? String)?.ifBlank { null } ?: "param") + "}"
        }
        (call(segment, "getSegments") as? List<*>)?.let { parts -> return parts.joinToString("") { renderSegment(it) } }
        return "{param}"
    }

    private val METHOD_WORD = Regex("""\b(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|TRACE)\b""")
    private val PATH = Regex("""(/[^\s\[\]]*)""")

    /** Fallback for providers without URL target info: "GET /pets", "/pets [GET]", or "/pets" plus a location string. */
    private fun routeFromPresentation(presentation: Any?, source: String): ApiRoute? {
        presentation ?: return null
        val text = (call(presentation, "getPresentableText") as? String)?.trim() ?: return null
        val location = (call(presentation, "getLocationString") as? String).orEmpty()
        val path = PATH.find(text)?.groupValues?.get(1) ?: return null
        val method = METHOD_WORD.find(text)?.value ?: METHOD_WORD.find(location)?.value ?: "GET"
        return ApiRoute(method, path, source = source)
    }

    private fun Class<*>.method(name: String, params: Int): Method? =
        methods.firstOrNull { it.name == name && it.parameterCount == params && !Modifier.isStatic(it.modifiers) }

    private fun call(target: Any, name: String): Any? = runCatching {
        target.javaClass.method(name, 0)?.also { it.isAccessible = true }?.invoke(target)
    }.getOrNull()
}
