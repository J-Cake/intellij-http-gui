package dev.jcake.httpgui.core

/** HTTP methods offered in the method picker. Any other token is accepted as a custom method. */
val STANDARD_METHODS = listOf(
    "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE", "CONNECT",
    "GRAPHQL", "WEBSOCKET",
)

data class HttpHeader(val name: String, val value: String)

/** A `# @name value` / `// @no-redirect` style request tag. */
data class RequestTag(val name: String, val value: String? = null)

/** A pre-request (`< {% %}`) or response handler (`> {% %}`) script. */
sealed class Script {
    data class Inline(val code: String) : Script()
    data class FileRef(val path: String) : Script()
}

/**
 * One request of an `.http` file.
 *
 * [prelude] holds lines between the `###` separator and the request line that are not tags or
 * scripts (comments, in-place variables, blank lines); they are written back verbatim.
 */
data class HttpRequest(
    val title: String? = null,
    val hasSeparator: Boolean = true,
    val prelude: List<String> = emptyList(),
    val tags: List<RequestTag> = emptyList(),
    val preRequestScript: Script? = null,
    val method: String = "GET",
    val url: String = "",
    val multilineUrl: Boolean = false,
    val httpVersion: String? = null,
    val headers: List<HttpHeader> = emptyList(),
    val body: String = "",
    val responseHandler: Script? = null,
    /** Raw trailing lines such as `<> 2024-01-01T000000.200.json` or `>> out.json`. */
    val trailer: List<String> = emptyList(),
) {
    val name: String? get() = tags.firstOrNull { it.name == "name" }?.value?.takeIf { it.isNotBlank() }

    fun header(name: String): HttpHeader? = headers.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun tag(name: String): RequestTag? = tags.firstOrNull { it.name == name }

    fun withTag(name: String, value: String?, present: Boolean = true): HttpRequest {
        val rest = tags.filterNot { it.name == name }
        if (!present) return copy(tags = rest)
        val index = tags.indexOfFirst { it.name == name }
        val updated = rest.toMutableList()
        updated.add(if (index < 0) updated.size else index.coerceAtMost(updated.size), RequestTag(name, value))
        return copy(tags = updated)
    }

    fun withHeader(name: String, value: String?): HttpRequest {
        val index = headers.indexOfFirst { it.name.equals(name, ignoreCase = true) }
        val updated = headers.filterNot { it.name.equals(name, ignoreCase = true) }.toMutableList()
        if (value != null) updated.add(if (index < 0) updated.size else index, HttpHeader(name, value))
        return copy(headers = updated)
    }

    fun displayName(): String = name ?: title?.takeIf { it.isNotBlank() } ?: "$method ${url.ifBlank { "<no url>" }}"
}

/** A slice of the file from one `###` separator to the next. */
data class HttpBlock(
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    /** Null when the block contains no request line (only comments/variables). */
    val request: HttpRequest?,
    /** Whitespace after the request's last meaningful line, kept when the block is rewritten. */
    val trailingWhitespace: String,
)

data class FileVariable(val name: String, val value: String)

data class HttpFileModel(val blocks: List<HttpBlock>, val variables: List<FileVariable>) {
    val requests: List<HttpBlock> get() = blocks.filter { it.request != null }
}
