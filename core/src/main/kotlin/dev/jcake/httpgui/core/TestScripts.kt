package dev.jcake.httpgui.core

enum class TestSubject(val label: String, val needsTarget: Boolean) {
    STATUS("Status code", false),
    HEADER("Header", true),
    JSON_PATH("JSON path", true),
    BODY("Body text", false),
    CONTENT_TYPE("Content type", false),
}

enum class TestOperator(val label: String, val needsExpected: Boolean) {
    EQUALS("equals", true),
    NOT_EQUALS("does not equal", true),
    CONTAINS("contains", true),
    NOT_CONTAINS("does not contain", true),
    MATCHES("matches regex", true),
    EXISTS("exists", false),
    NOT_EXISTS("does not exist", false),
    LESS_THAN("<", true),
    LESS_OR_EQUAL("<=", true),
    GREATER_THAN(">", true),
    GREATER_OR_EQUAL(">=", true),
}

/** A single assertion that is rendered as a `client.test(...)` block in the response handler. */
data class TestCondition(
    val subject: TestSubject = TestSubject.STATUS,
    val target: String = "",
    val operator: TestOperator = TestOperator.EQUALS,
    val expected: String = "200",
    val name: String = "",
) {
    fun effectiveName(): String = name.ifBlank { describe() }

    fun describe(): String {
        val what = when (subject) {
            TestSubject.STATUS -> "Status"
            TestSubject.HEADER -> "Header '$target'"
            TestSubject.JSON_PATH -> "Body $target"
            TestSubject.BODY -> "Body"
            TestSubject.CONTENT_TYPE -> "Content type"
        }
        return if (operator.needsExpected) "$what ${operator.label} $expected" else "$what ${operator.label}"
    }
}

/**
 * Converts between [TestCondition]s and the JavaScript of an HTTP Client response handler.
 *
 * Generated tests have a fixed one-line shape so they can be recognized again when the file is
 * re-read; any other code in the handler is preserved as free-form script.
 */
object TestScripts {
    val DEFAULT_CONDITIONS = listOf(TestCondition(TestSubject.STATUS, "", TestOperator.EQUALS, "200"))

    private val TEST_LINE = Regex(
        """^\s*client\.test\(("(?:[^"\\]|\\.)*"), function \(\) \{ client\.assert\((.+), ("(?:[^"\\]|\\.)*")\); \}\);\s*$""",
    )

    fun actual(c: TestCondition): String = when (c.subject) {
        TestSubject.STATUS -> "response.status"
        TestSubject.HEADER -> "response.headers.valueOf(${js(c.target)})"
        TestSubject.JSON_PATH -> "jsonPath(response.body, ${js(c.target.ifBlank { "$" })})"
        TestSubject.BODY -> "(typeof response.body === \"string\" ? response.body : JSON.stringify(response.body))"
        TestSubject.CONTENT_TYPE -> "response.contentType.mimeType"
    }

    private fun expectedLiteral(c: TestCondition): String {
        val numeric = c.expected.trim().toDoubleOrNull() != null
        return if (c.subject == TestSubject.STATUS && numeric) c.expected.trim() else js(c.expected)
    }

    fun expression(c: TestCondition): String {
        val a = actual(c)
        val e = expectedLiteral(c)
        return when (c.operator) {
            TestOperator.EQUALS -> "$a == $e"
            TestOperator.NOT_EQUALS -> "$a != $e"
            TestOperator.CONTAINS -> "String($a).includes($e)"
            TestOperator.NOT_CONTAINS -> "!String($a).includes($e)"
            TestOperator.MATCHES -> "new RegExp($e).test(String($a))"
            TestOperator.EXISTS -> "$a != null"
            TestOperator.NOT_EXISTS -> "$a == null"
            TestOperator.LESS_THAN -> "Number($a) < Number($e)"
            TestOperator.LESS_OR_EQUAL -> "Number($a) <= Number($e)"
            TestOperator.GREATER_THAN -> "Number($a) > Number($e)"
            TestOperator.GREATER_OR_EQUAL -> "Number($a) >= Number($e)"
        }
    }

    fun testLine(c: TestCondition): String =
        "client.test(${js(c.effectiveName())}, function () { client.assert(${expression(c)}, ${js("Failed: " + c.describe())}); });"

    /** Builds handler code: one generated line per condition followed by the free-form [customCode]. */
    fun generate(conditions: List<TestCondition>, customCode: String): String {
        val lines = conditions.map { "    " + testLine(it) }
        val custom = customCode.trimEnd().trim('\n')
        return when {
            lines.isEmpty() -> custom
            custom.isBlank() -> lines.joinToString("\n")
            else -> lines.joinToString("\n") + "\n" + custom
        }
    }

    data class Parsed(val conditions: List<TestCondition>, val customCode: String)

    fun parse(code: String): Parsed {
        val conditions = mutableListOf<TestCondition>()
        val rest = mutableListOf<String>()
        for (line in code.split('\n')) {
            val condition = TEST_LINE.matchEntire(line)?.let { m ->
                val name = unjs(m.groupValues[1]) ?: return@let null
                parseExpression(m.groupValues[2])?.let { c -> if (name == c.describe()) c else c.copy(name = name) }
            }
            if (condition != null) conditions += condition else rest += line
        }
        return Parsed(conditions, rest.joinToString("\n").trim('\n'))
    }

    private fun parseExpression(expr: String): TestCondition? {
        for (subject in TestSubject.entries) {
            for (operator in TestOperator.entries) {
                val candidate = matchOperator(expr, subject, operator)
                if (candidate != null) return candidate
            }
        }
        return null
    }

    private val STRING = """("(?:[^"\\]|\\.)*")"""
    private val NUMBER_OR_STRING = """("(?:[^"\\]|\\.)*"|-?\d+(?:\.\d+)?)"""

    private fun matchOperator(expr: String, subject: TestSubject, operator: TestOperator): TestCondition? {
        val actualPattern = when (subject) {
            TestSubject.STATUS -> Regex.escape("response.status")
            TestSubject.HEADER -> Regex.escape("response.headers.valueOf(") + STRING + Regex.escape(")")
            TestSubject.JSON_PATH -> Regex.escape("jsonPath(response.body, ") + STRING + Regex.escape(")")
            TestSubject.BODY -> Regex.escape(actual(TestCondition(TestSubject.BODY)))
            TestSubject.CONTENT_TYPE -> Regex.escape("response.contentType.mimeType")
        }
        val a = "($actualPattern)"
        val e = NUMBER_OR_STRING
        val pattern = when (operator) {
            TestOperator.EQUALS -> "$a == $e"
            TestOperator.NOT_EQUALS -> "$a != $e"
            TestOperator.CONTAINS -> "String\\($a\\)\\.includes\\($e\\)"
            TestOperator.NOT_CONTAINS -> "!String\\($a\\)\\.includes\\($e\\)"
            TestOperator.MATCHES -> "new RegExp\\($e\\)\\.test\\(String\\($a\\)\\)"
            TestOperator.EXISTS -> "$a != null"
            TestOperator.NOT_EXISTS -> "$a == null"
            TestOperator.LESS_THAN -> "Number\\($a\\) < Number\\($e\\)"
            TestOperator.LESS_OR_EQUAL -> "Number\\($a\\) <= Number\\($e\\)"
            TestOperator.GREATER_THAN -> "Number\\($a\\) > Number\\($e\\)"
            TestOperator.GREATER_OR_EQUAL -> "Number\\($a\\) >= Number\\($e\\)"
        }
        val m = Regex(pattern).matchEntire(expr) ?: return null
        // Group layout depends on whether the actual expression captures a target string.
        val groups = m.groupValues.drop(1)
        val expectedFirst = operator == TestOperator.MATCHES
        val actualIndex = if (expectedFirst) 1 else 0
        val target = if (subject.needsTarget) unjs(groups[actualIndex + 1]) ?: return null else ""
        val expectedRaw = when {
            !operator.needsExpected -> null
            expectedFirst -> groups[0]
            else -> groups.last()
        }
        val expected = expectedRaw?.let { if (it.startsWith("\"")) unjs(it) ?: return null else it } ?: ""
        return TestCondition(subject, target, operator, expected)
    }

    fun js(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }

    fun unjs(literal: String): String? {
        if (literal.length < 2 || !literal.startsWith("\"") || !literal.endsWith("\"")) return null
        val s = literal.substring(1, literal.length - 1)
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                when (val next = s[i + 1]) {
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    else -> out.append(next)
                }
                i += 2
            } else {
                out.append(ch)
                i++
            }
        }
        return out.toString()
    }
}
