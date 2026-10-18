package dev.jcake.httpgui.core

/** Minimal JSON reader/writer for environment files; objects keep their key order. */
object Json {
    class ParseException(message: String) : RuntimeException(message)

    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.ws()
        val value = reader.value()
        reader.ws()
        if (!reader.atEnd()) throw ParseException("Unexpected trailing content at ${reader.pos}")
        return value
    }

    fun write(value: Any?, indent: String = "  "): String = StringBuilder().also { writeTo(it, value, indent, 0) }.toString()

    private fun writeTo(out: StringBuilder, value: Any?, indent: String, level: Int) {
        when (value) {
            null -> out.append("null")
            is String -> out.append(TestScripts.js(value))
            is Boolean, is Int, is Long -> out.append(value.toString())
            is Number -> {
                val d = value.toDouble()
                out.append(if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) d.toLong().toString() else d.toString())
            }
            is Map<*, *> -> {
                if (value.isEmpty()) { out.append("{}"); return }
                out.append("{\n")
                value.entries.forEachIndexed { i, (k, v) ->
                    out.append(indent.repeat(level + 1)).append(TestScripts.js(k.toString())).append(": ")
                    writeTo(out, v, indent, level + 1)
                    if (i < value.size - 1) out.append(',')
                    out.append('\n')
                }
                out.append(indent.repeat(level)).append('}')
            }
            is List<*> -> {
                if (value.isEmpty()) { out.append("[]"); return }
                out.append("[\n")
                value.forEachIndexed { i, v ->
                    out.append(indent.repeat(level + 1))
                    writeTo(out, v, indent, level + 1)
                    if (i < value.size - 1) out.append(',')
                    out.append('\n')
                }
                out.append(indent.repeat(level)).append(']')
            }
            else -> out.append(TestScripts.js(value.toString()))
        }
    }

    private class Reader(val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun ws() {
            while (pos < s.length) {
                when {
                    s[pos].isWhitespace() -> pos++
                    // Tolerate comments, which some people put in env files.
                    s.startsWith("//", pos) -> pos = s.indexOf('\n', pos).let { if (it < 0) s.length else it }
                    s.startsWith("/*", pos) -> pos = s.indexOf("*/", pos).let { if (it < 0) s.length else it + 2 }
                    else -> return
                }
            }
        }

        fun value(): Any? {
            if (atEnd()) throw ParseException("Unexpected end of input")
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw ParseException("Unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) throw ParseException("Expected $word at $pos")
            pos += word.length
            return v
        }

        private fun num(): Number {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] in "+-.eE")) pos++
            val text = s.substring(start, pos)
            return text.toLongOrNull() ?: text.toDoubleOrNull() ?: throw ParseException("Bad number '$text'")
        }

        private fun str(): String {
            pos++
            val out = StringBuilder()
            while (pos < s.length) {
                val c = s[pos++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (pos >= s.length) break
                        when (val e = s[pos++]) {
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            'r' -> out.append('\r')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'u' -> {
                                out.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> out.append(e)
                        }
                    }
                    else -> out.append(c)
                }
            }
            throw ParseException("Unterminated string")
        }

        private fun obj(): Map<String, Any?> {
            pos++
            val map = LinkedHashMap<String, Any?>()
            ws()
            if (s.getOrNull(pos) == '}') { pos++; return map }
            while (true) {
                ws()
                if (s.getOrNull(pos) != '"') throw ParseException("Expected key at $pos")
                val key = str()
                ws()
                if (s.getOrNull(pos) != ':') throw ParseException("Expected ':' at $pos")
                pos++
                ws()
                map[key] = value()
                ws()
                when (s.getOrNull(pos)) {
                    ',' -> { pos++; ws(); if (s.getOrNull(pos) == '}') { pos++; return map } }
                    '}' -> { pos++; return map }
                    else -> throw ParseException("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun arr(): List<Any?> {
            pos++
            val list = ArrayList<Any?>()
            ws()
            if (s.getOrNull(pos) == ']') { pos++; return list }
            while (true) {
                ws()
                list += value()
                ws()
                when (s.getOrNull(pos)) {
                    ',' -> { pos++; ws(); if (s.getOrNull(pos) == ']') { pos++; return list } }
                    ']' -> { pos++; return list }
                    else -> throw ParseException("Expected ',' or ']' at $pos")
                }
            }
        }
    }
}
