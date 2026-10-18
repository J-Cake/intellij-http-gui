package dev.jcake.httpgui.core

/**
 * Parser for the JetBrains HTTP Client file format (`.http` / `.rest`).
 *
 * The file is split into blocks at lines starting with `###`. Each block is parsed independently
 * and remembers its offsets so a single request can be rewritten without touching the rest of
 * the file.
 */
object HttpFileParser {
    private val TAG = Regex("""^(?:#|//)\s*@([\w.-]+)(?:\s+(.*))?$""")
    private val VARIABLE = Regex("""^@([\w.-]+)\s*=\s?(.*)$""")
    private val REQUEST_LINE = Regex("""^([A-Z][A-Z0-9_-]*)\s+(\S.*)$""")
    private val HTTP_VERSION = Regex("""^(.*?)\s+(HTTP/\d(?:\.\d)?)$""")
    private val HEADER = Regex("""^([^\s:][^:]*?)\s*:\s?(.*)$""")

    fun parse(text: String): HttpFileModel {
        val blocks = mutableListOf<HttpBlock>()
        val variables = mutableListOf<FileVariable>()
        var blockStart = 0
        var offset = 0
        while (offset < text.length) {
            val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it + 1 }
            if (offset != blockStart && text.startsWith("###", offset)) {
                blocks += parseBlock(text, blockStart, offset, variables)
                blockStart = offset
            }
            offset = lineEnd
        }
        if (blockStart < text.length || blocks.isEmpty()) {
            blocks += parseBlock(text, blockStart, text.length, variables)
        }
        return HttpFileModel(blocks, variables)
    }

    private fun parseBlock(text: String, start: Int, end: Int, variables: MutableList<FileVariable>): HttpBlock {
        val blockText = text.substring(start, end)
        val content = blockText.trimEnd()
        val trailing = blockText.substring(content.length)
        val lines = if (content.isEmpty()) emptyList() else content.split('\n').map { it.removeSuffix("\r") }
        lines.forEach { line -> VARIABLE.matchEntire(line.trim())?.let { variables += FileVariable(it.groupValues[1], it.groupValues[2].trim()) } }
        return HttpBlock(start, end, blockText, parseRequest(lines), trailing)
    }

    /** Parses the lines of one block; returns null if the block contains no request line. */
    fun parseRequest(lines: List<String>): HttpRequest? {
        var i = 0
        var title: String? = null
        var hasSeparator = false
        if (lines.isNotEmpty() && lines[0].startsWith("###")) {
            hasSeparator = true
            title = lines[0].removePrefix("###").trim().ifEmpty { null }
            i++
        }

        val prelude = mutableListOf<String>()
        val tags = mutableListOf<RequestTag>()
        var preRequest: Script? = null
        var requestLine: String? = null
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            when {
                t.isEmpty() -> prelude += line
                TAG.matches(t) -> TAG.matchEntire(t)!!.let { tags += RequestTag(it.groupValues[1], it.groupValues[2].trim().ifEmpty { null }) }
                t.startsWith("#") || t.startsWith("//") || VARIABLE.matches(t) -> prelude += line
                t.startsWith("<") && t.removePrefix("<").trimStart().startsWith("{%") -> {
                    val (script, next) = readScript(lines, i, "<")
                    preRequest = script
                    i = next
                    continue
                }
                t.startsWith("< ") -> preRequest = Script.FileRef(t.removePrefix("<").trim())
                else -> {
                    requestLine = t
                    i++
                    break
                }
            }
            i++
        }
        val line = requestLine ?: return null

        var method = "GET"
        var url: String = line
        REQUEST_LINE.matchEntire(line)?.let {
            method = it.groupValues[1]
            url = it.groupValues[2]
        }
        // Indented lines directly after the request line continue the URL (e.g. `    &page=2`).
        var multiline = false
        while (i < lines.size && lines[i].isNotEmpty() && lines[i][0].isWhitespace() && lines[i].isNotBlank()) {
            url += lines[i].trim()
            multiline = true
            i++
        }
        var httpVersion: String? = null
        HTTP_VERSION.matchEntire(url)?.let {
            url = it.groupValues[1]
            httpVersion = it.groupValues[2]
        }

        val headers = mutableListOf<HttpHeader>()
        while (i < lines.size && lines[i].isNotBlank() && !isHandlerStart(lines[i]) && !isTrailer(lines[i])) {
            val t = lines[i].trim()
            if (t.startsWith("#") || t.startsWith("//")) {
                prelude += lines[i]
                i++
                continue
            }
            val match = HEADER.matchEntire(t) ?: break
            headers += HttpHeader(match.groupValues[1], match.groupValues[2])
            i++
        }
        if (i < lines.size && lines[i].isBlank()) i++

        val bodyLines = mutableListOf<String>()
        while (i < lines.size && !isHandlerStart(lines[i]) && !isTrailer(lines[i])) {
            bodyLines += lines[i]
            i++
        }

        var handler: Script? = null
        val trailer = mutableListOf<String>()
        while (i < lines.size) {
            val line = lines[i]
            if (handler == null && isHandlerStart(line)) {
                val t = line.trim()
                if (t.removePrefix(">").trimStart().startsWith("{%")) {
                    val (script, next) = readScript(lines, i, ">")
                    handler = script
                    i = next
                    continue
                }
                handler = Script.FileRef(t.removePrefix(">").trim())
            } else if (line.isNotBlank() || trailer.isNotEmpty()) {
                trailer += line
            }
            i++
        }

        return HttpRequest(
            title = title,
            hasSeparator = hasSeparator,
            prelude = prelude.dropLastWhile { it.isBlank() },
            tags = tags,
            preRequestScript = preRequest,
            method = method,
            url = url,
            multilineUrl = multiline,
            httpVersion = httpVersion,
            headers = headers,
            body = bodyLines.dropLastWhile { it.isBlank() }.joinToString("\n"),
            responseHandler = handler,
            trailer = trailer.dropLastWhile { it.isBlank() },
        )
    }

    private fun isHandlerStart(line: String): Boolean = line.startsWith(">") && !line.startsWith(">>")

    private fun isTrailer(line: String): Boolean = line.startsWith("<>") || line.startsWith(">>")

    /** Reads a `{% ... %}` script beginning at [start]; returns the script and the index after it. */
    private fun readScript(lines: List<String>, start: Int, marker: String): Pair<Script, Int> {
        val first = lines[start].trim().removePrefix(marker).trimStart().removePrefix("{%")
        if (first.contains("%}")) return Script.Inline(first.substringBefore("%}").trim()) to start + 1
        val code = mutableListOf<String>()
        if (first.isNotBlank()) code += first.trim()
        var i = start + 1
        while (i < lines.size) {
            val line = lines[i]
            if (line.contains("%}")) {
                line.substringBefore("%}").takeIf { it.isNotBlank() }?.let { code += it.trimEnd() }
                return Script.Inline(code.joinToString("\n")) to i + 1
            }
            code += line
            i++
        }
        // Unterminated script: keep what was read.
        return Script.Inline(code.joinToString("\n")) to i
    }
}
