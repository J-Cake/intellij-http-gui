package dev.jcake.httpgui.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class SampleFilesTest {
    private fun sample(name: String) = File("../samples/$name").readText()

    @Test
    fun `petstore sample is understood`() {
        val text = sample("petstore.http")
        val model = HttpFileParser.parse(text)
        val requests = model.requests.map { it.request!! }
        assertEquals(listOf("GET", "POST"), requests.map { it.method })
        assertEquals(AuthConfig.OAuth2("petstore"), AuthCodec.detect(requests[0]))
        assertEquals(AuthConfig.ApiKey("X-API-Key", "{{apiKey}}"), AuthCodec.detect(requests[1]))

        val tests = TestScripts.parse((requests[0].responseHandler as Script.Inline).code)
        assertEquals(2, tests.conditions.size)
        assertEquals("    client.global.set(\"firstPetId\", jsonPath(response.body, \"$[0].id\"));", tests.customCode)

        val rebuilt = model.blocks.mapIndexed { i, b ->
            b.request?.let { HttpSerializer.serializeBlock(it, b, i == model.blocks.lastIndex) } ?: b.text
        }.joinToString("")
        assertEquals(text, rebuilt)
    }

    @Test
    fun `sample environment and spec`() {
        val env = EnvironmentSet(listOf(EnvironmentFile.parse(PUBLIC_ENV_FILE, false, sample("http-client.env.json"))))
        assertEquals(listOf("dev", "prod"), env.environmentNames)
        assertEquals("Client Credentials", env.authConfigs("dev")["petstore"]?.get("Grant Type"))
        val routes = OpenApi.parse(sample("openapi.yaml"), "openapi.yaml")
        assertEquals(3, routes.size)
        assertEquals("{{host}}/v1/pets/{{petId}}", routes.first { it.operationId == "showPetById" }.toRequest("{{host}}/v1").url)
    }
}
