package dev.jcake.httpgui.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpFileParserTest {
    private val sample = """
        @host = https://example.com

        ### List users
        # @name listUsers
        # @no-redirect
        GET {{host}}/users
            ?page=1
            &size=20
        Accept: application/json

        > {%
            client.test("Status equals 200", function () { client.assert(response.status == 200, "Failed: Status equals 200"); });
        %}

        ### Create user
        < {%
            request.variables.set("now", Date.now())
        %}
        POST {{host}}/users HTTP/1.1
        Content-Type: application/json
        Authorization: Bearer {{token}}

        {
          "name": "Jane"
        }

        > handlers/create.js
        <> 2024-01-01T000000.200.json

        ###
        PURGE https://cache.local/item
    """.trimIndent() + "\n"

    @Test
    fun `parses blocks and requests`() {
        val model = HttpFileParser.parse(sample)
        assertEquals(4, model.blocks.size)
        assertNull(model.blocks[0].request)
        assertEquals(listOf(FileVariable("host", "https://example.com")), model.variables)

        val list = model.blocks[1].request!!
        assertEquals("List users", list.title)
        assertEquals("listUsers", list.name)
        assertEquals(listOf(RequestTag("name", "listUsers"), RequestTag("no-redirect")), list.tags)
        assertEquals("GET", list.method)
        assertEquals("{{host}}/users?page=1&size=20", list.url)
        assertTrue(list.multilineUrl)
        assertEquals(listOf(HttpHeader("Accept", "application/json")), list.headers)
        assertIs<Script.Inline>(list.responseHandler)

        val create = model.blocks[2].request!!
        assertEquals("POST", create.method)
        assertEquals("HTTP/1.1", create.httpVersion)
        assertEquals("{\n  \"name\": \"Jane\"\n}", create.body)
        assertEquals(Script.FileRef("handlers/create.js"), create.responseHandler)
        assertEquals(listOf("<> 2024-01-01T000000.200.json"), create.trailer)
        assertEquals("    request.variables.set(\"now\", Date.now())", (create.preRequestScript as Script.Inline).code)

        val purge = model.blocks[3].request!!
        assertEquals("PURGE", purge.method)
        assertNull(purge.title)
    }

    @Test
    fun `blocks cover the whole file`() {
        val model = HttpFileParser.parse(sample)
        assertEquals(sample, model.blocks.joinToString("") { it.text })
        model.blocks.zipWithNext().forEach { (a, b) -> assertEquals(a.endOffset, b.startOffset) }
    }

    @Test
    fun `serializing parsed requests reproduces the file`() {
        val model = HttpFileParser.parse(sample)
        val rebuilt = model.blocks.mapIndexed { i, block ->
            val request = block.request ?: return@mapIndexed block.text
            HttpSerializer.serializeBlock(request, block, i == model.blocks.lastIndex)
        }.joinToString("")
        assertEquals(sample, rebuilt)
    }

    @Test
    fun `url without method defaults to GET`() {
        val request = HttpFileParser.parse("https://example.com/a\n").blocks.single().request!!
        assertEquals("GET", request.method)
        assertEquals("https://example.com/a", request.url)
        assertEquals(false, request.hasSeparator)
    }

    @Test
    fun `body without headers`() {
        val request = HttpFileParser.parse("### x\nPOST /a\n\nhello\nworld\n").blocks.single().request!!
        assertEquals(emptyList(), request.headers)
        assertEquals("hello\nworld", request.body)
    }

    @Test
    fun `query params split and join`() {
        val (base, params) = Urls.split("{{host}}/a?x=1&flag&y={{v}}")
        assertEquals("{{host}}/a", base)
        assertEquals(listOf(QueryParam("x", "1"), QueryParam("flag", null), QueryParam("y", "{{v}}")), params)
        assertEquals("{{host}}/a?x=1&flag&y={{v}}", Urls.join(base, params))
    }

    @Test
    fun `single line scripts`() {
        val request = HttpFileParser.parse("GET /a\n\n> {% client.global.set(\"a\", 1) %}\n").blocks.single().request!!
        assertEquals(Script.Inline("client.global.set(\"a\", 1)"), request.responseHandler)
        assertEquals("", request.body)
    }
}
