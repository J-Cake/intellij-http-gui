package dev.jcake.httpgui.core

object HttpSerializer {
    /** Serializes a request without trailing whitespace. */
    fun serialize(request: HttpRequest): String {
        val out = mutableListOf<String>()
        if (request.hasSeparator) out += if (request.title.isNullOrBlank()) "###" else "### ${request.title}"
        out += request.prelude
        request.tags.forEach { out += if (it.value.isNullOrBlank()) "# @${it.name}" else "# @${it.name} ${it.value}" }
        when (val script = request.preRequestScript) {
            is Script.Inline -> out += scriptLines("<", script.code)
            is Script.FileRef -> out += "< ${script.path}"
            null -> {}
        }
        out += requestLines(request)
        request.headers.filter { it.name.isNotBlank() }.forEach { out += "${it.name.trim()}: ${it.value}" }
        if (request.body.isNotBlank()) {
            out += ""
            out += request.body.trimEnd().split('\n')
        }
        request.responseHandler?.let { handler ->
            out += ""
            out += when (handler) {
                is Script.Inline -> scriptLines(">", handler.code)
                is Script.FileRef -> listOf("> ${handler.path}")
            }
        }
        if (request.trailer.isNotEmpty()) {
            if (request.responseHandler == null) out += ""
            out += request.trailer
        }
        return out.joinToString("\n")
    }

    /** Serializes [request] as the replacement text for [original]'s block, keeping its trailing whitespace. */
    fun serializeBlock(request: HttpRequest, original: HttpBlock?, isLast: Boolean): String {
        val trailing = original?.trailingWhitespace?.takeIf { it.contains('\n') } ?: if (isLast) "\n" else "\n\n"
        return serialize(request) + trailing
    }

    private fun scriptLines(marker: String, code: String): List<String> =
        if (code.isBlank()) listOf("$marker {% %}") else listOf("$marker {%") + code.trimEnd().split('\n') + "%}"

    private fun requestLines(request: HttpRequest): List<String> {
        val method = request.method.trim().ifEmpty { "GET" }
        val version = request.httpVersion?.let { " $it" } ?: ""
        val url = request.url.trim()
        if (!request.multilineUrl) return listOf("$method $url$version")
        val (base, params) = Urls.split(url)
        if (params.isEmpty()) return listOf("$method $url$version")
        val lines = mutableListOf("$method $base")
        params.forEachIndexed { index, param -> lines += "    ${if (index == 0) "?" else "&"}${param.encode()}" }
        lines[lines.lastIndex] = lines.last() + version
        return lines
    }
}

data class QueryParam(val name: String, val value: String?) {
    fun encode(): String = if (value == null) name else "$name=$value"
}

object Urls {
    /** Splits a URL into the part before `?` and its query parameters. A `#fragment` stays in the base. */
    fun split(url: String): Pair<String, List<QueryParam>> {
        val q = url.indexOf('?')
        if (q < 0) return url to emptyList()
        var query = url.substring(q + 1)
        var fragment = ""
        val hash = query.indexOf('#')
        if (hash >= 0) {
            fragment = query.substring(hash)
            query = query.substring(0, hash)
        }
        val params = query.split('&').filter { it.isNotEmpty() }.map {
            val eq = it.indexOf('=')
            if (eq < 0) QueryParam(it, null) else QueryParam(it.substring(0, eq), it.substring(eq + 1))
        }
        return url.substring(0, q) + fragment to params
    }

    fun join(base: String, params: List<QueryParam>): String {
        val usable = params.filter { it.name.isNotEmpty() }
        if (usable.isEmpty()) return base
        val hash = base.indexOf('#')
        val (path, fragment) = if (hash >= 0) base.substring(0, hash) to base.substring(hash) else base to ""
        return path + "?" + usable.joinToString("&") { it.encode() } + fragment
    }
}
