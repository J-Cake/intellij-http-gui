package dev.jcake.httpgui.core

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/** A route offered for prefilling a request. */
data class ApiRoute(
    val method: String,
    val path: String,
    val summary: String? = null,
    val operationId: String? = null,
    val servers: List<String> = emptyList(),
    val queryParams: List<String> = emptyList(),
    val headerParams: List<String> = emptyList(),
    val contentType: String? = null,
    val bodyExample: String? = null,
    val source: String = "",
) {
    fun label(): String = "$method $path" + (summary?.let { " — $it" } ?: "")

    /** Builds a request for this route; [baseUrl] is prepended to the path. */
    fun toRequest(baseUrl: String): HttpRequest {
        val path = path.replace(Regex("""\{([^{}/]+)}"""), "{{$1}}")
        val base = baseUrl.trimEnd('/')
        val url = Urls.join(if (path.startsWith("/") || base.isEmpty()) base + path else "$base/$path", queryParams.map { QueryParam(it, "") })
        val headers = mutableListOf<HttpHeader>()
        if (contentType != null) headers += HttpHeader("Content-Type", contentType)
        if (contentType?.contains("json") == true || contentType == null) headers += HttpHeader("Accept", "application/json")
        headerParams.forEach { headers += HttpHeader(it, "") }
        return HttpRequest(
            title = summary ?: operationId,
            tags = listOfNotNull(operationId?.let { RequestTag("name", it) }),
            method = method,
            url = url,
            headers = headers,
            body = bodyExample ?: "",
            responseHandler = Script.Inline(ScriptTemplates.RESPONSE_HANDLER),
        )
    }
}

object OpenApi {
    private val METHODS = setOf("get", "put", "post", "delete", "options", "head", "patch", "trace")

    /** Cheap check used before parsing a candidate file. */
    fun looksLikeSpec(text: String): Boolean =
        Regex("""(^|\n|\{)\s*["']?(openapi|swagger)["']?\s*:""").containsMatchIn(text.take(4096))

    fun parse(text: String, source: String = ""): List<ApiRoute> {
        val root = load(text, source) as? Map<*, *> ?: return emptyList()
        val servers = servers(root)
        val paths = root["paths"] as? Map<*, *> ?: return emptyList()
        val routes = mutableListOf<ApiRoute>()
        for ((path, item) in paths) {
            if (path !is String || item !is Map<*, *>) continue
            val shared = (item["parameters"] as? List<*>).orEmpty()
            for ((method, op) in item) {
                if (method !is String || method.lowercase() !in METHODS || op !is Map<*, *>) continue
                val params = (shared + (op["parameters"] as? List<*>).orEmpty()).mapNotNull { resolve(root, it) as? Map<*, *> }
                val (contentType, example) = requestBody(root, op)
                routes += ApiRoute(
                    method = method.uppercase(),
                    path = path,
                    summary = (op["summary"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
                    operationId = op["operationId"] as? String,
                    servers = servers,
                    queryParams = params.filter { it["in"] == "query" }.mapNotNull { it["name"] as? String }.distinct(),
                    headerParams = params.filter { it["in"] == "header" }.mapNotNull { it["name"] as? String }.distinct(),
                    contentType = contentType,
                    bodyExample = example,
                    source = source,
                )
            }
        }
        return routes
    }

    private fun load(text: String, source: String): Any? = runCatching {
        if (source.endsWith(".json", ignoreCase = true)) Json.parse(text)
        else Yaml(SafeConstructor(LoaderOptions())).load<Any?>(text)
    }.getOrNull()

    private fun servers(root: Map<*, *>): List<String> {
        (root["servers"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.get("url") as? String }?.takeIf { it.isNotEmpty() }?.let { return it }
        // Swagger 2.0
        val host = root["host"] as? String ?: return emptyList()
        val scheme = (root["schemes"] as? List<*>)?.firstOrNull() as? String ?: "https"
        return listOf("$scheme://$host${root["basePath"] as? String ?: ""}")
    }

    private fun requestBody(root: Map<*, *>, op: Map<*, *>): Pair<String?, String?> {
        val body = resolve(root, op["requestBody"]) as? Map<*, *>
        val content = body?.get("content") as? Map<*, *>
        if (content != null && content.isNotEmpty()) {
            val type = content.keys.filterIsInstance<String>().let { keys -> keys.firstOrNull { it.contains("json") } ?: keys.first() }
            val media = content[type] as? Map<*, *>
            val example = media?.get("example")
                ?: ((media?.get("examples") as? Map<*, *>)?.values?.firstOrNull() as? Map<*, *>)?.get("value")
                ?: (media?.get("schema"))?.let { sample(root, it, 0) }
            return type to example?.let { render(it, type) }
        }
        // Swagger 2.0 body parameter
        val bodyParam = (op["parameters"] as? List<*>)?.mapNotNull { resolve(root, it) as? Map<*, *> }?.firstOrNull { it["in"] == "body" }
        if (bodyParam != null) {
            val type = ((op["consumes"] ?: root["consumes"]) as? List<*>)?.firstOrNull() as? String ?: "application/json"
            return type to bodyParam["schema"]?.let { sample(root, it, 0) }?.let { render(it, type) }
        }
        return null to null
    }

    private fun render(value: Any, contentType: String): String =
        if (value is String && !contentType.contains("json")) value else Json.write(value)

    private fun resolve(root: Map<*, *>, node: Any?): Any? {
        var current = node
        repeat(8) {
            val ref = (current as? Map<*, *>)?.get("\$ref") as? String ?: return current
            if (!ref.startsWith("#/")) return current
            var target: Any? = root
            for (part in ref.removePrefix("#/").split('/')) {
                target = (target as? Map<*, *>)?.get(part.replace("~1", "/").replace("~0", "~"))
            }
            current = target
        }
        return current
    }

    /** Produces a small example value from a JSON schema. */
    private fun sample(root: Map<*, *>, schemaNode: Any?, depth: Int): Any? {
        val schema = resolve(root, schemaNode) as? Map<*, *> ?: return null
        schema["example"]?.let { return it }
        schema["default"]?.let { return it }
        (schema["enum"] as? List<*>)?.firstOrNull()?.let { return it }
        if (depth > 4) return null
        (schema["allOf"] as? List<*>)?.let { parts ->
            val merged = LinkedHashMap<String, Any?>()
            parts.forEach { (sample(root, it, depth + 1) as? Map<*, *>)?.forEach { (k, v) -> merged[k.toString()] = v } }
            return merged
        }
        ((schema["oneOf"] ?: schema["anyOf"]) as? List<*>)?.firstOrNull()?.let { return sample(root, it, depth + 1) }
        val type = schema["type"] as? String ?: if (schema["properties"] != null) "object" else null
        return when (type) {
            "object" -> LinkedHashMap<String, Any?>().also { obj ->
                (schema["properties"] as? Map<*, *>)?.forEach { (k, v) -> obj[k.toString()] = sample(root, v, depth + 1) }
            }
            "array" -> listOfNotNull(sample(root, schema["items"], depth + 1))
            "integer" -> 0
            "number" -> 0.0
            "boolean" -> false
            "string" -> when (schema["format"]) {
                "date-time" -> "2024-01-01T00:00:00Z"
                "date" -> "2024-01-01"
                "uuid" -> "00000000-0000-0000-0000-000000000000"
                "email" -> "user@example.com"
                else -> ""
            }
            else -> null
        }
    }
}
