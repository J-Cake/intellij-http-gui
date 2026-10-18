package dev.jcake.httpgui.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureCodecsTest {
    @Test
    fun `auth detection and application`() {
        val base = HttpRequest(url = "https://x/a?q=1", headers = listOf(HttpHeader("Accept", "*/*")))
        assertEquals(AuthConfig.None, AuthCodec.detect(base))

        val basic = AuthCodec.apply(base, AuthConfig.Basic("{{user}}", "{{pass}}"))
        assertEquals("Basic {{user}} {{pass}}", basic.header("Authorization")!!.value)
        assertEquals(AuthConfig.Basic("{{user}}", "{{pass}}"), AuthCodec.detect(basic))
        assertEquals(AuthConfig.Basic("user", "pass"), AuthCodec.parseAuthorization("Basic dXNlcjpwYXNz"))

        val oauth = AuthCodec.apply(basic, AuthConfig.OAuth2("my-auth"))
        assertEquals("Bearer {{\$auth.token(\"my-auth\")}}", oauth.header("authorization")!!.value)
        assertEquals(AuthConfig.OAuth2("my-auth"), AuthCodec.detect(oauth))

        val key = AuthCodec.apply(oauth, AuthConfig.ApiKey("api_key", "{{key}}", inQuery = true))
        assertNull(key.header("Authorization"))
        assertEquals("https://x/a?q=1&api_key={{key}}", key.url)
        assertEquals(AuthConfig.ApiKey("api_key", "{{key}}", true), AuthCodec.detect(key))

        val none = AuthCodec.apply(key, AuthConfig.None)
        assertEquals("https://x/a?q=1", none.url)
        assertEquals(listOf(HttpHeader("Accept", "*/*")), none.headers)
    }

    @Test
    fun `environment variables merge shared and private`() {
        val public = EnvironmentFile.parse(
            PUBLIC_ENV_FILE, false,
            """{"${'$'}shared": {"version": "v1"}, "dev": {"host": "http://localhost", "token": "public"}, "prod": {"host": "https://prod"}}""",
        )
        val private = EnvironmentFile.parse(
            PRIVATE_ENV_FILE, true,
            """{"dev": {"token": "secret", "Security": {"Auth": {"auth-id": {"Type": "OAuth2", "Client Secret": "s"}}}}}""",
        )
        val set = EnvironmentSet(listOf(private, public))
        assertEquals(listOf("dev", "prod"), set.environmentNames)
        val vars = set.variables("dev").associate { it.name to it.value }
        assertEquals(mapOf("version" to "v1", "host" to "http://localhost", "token" to "secret"), vars)
        assertEquals(mapOf("Type" to "OAuth2", "Client Secret" to "s"), set.authConfigs("dev")["auth-id"])
        assertEquals("http://localhost/v1/x", Variables.resolve("{{host}}/{{version}}/x", vars))
    }

    @Test
    fun `auth config update keeps other content`() {
        val text = """{"dev": {"host": "h"}}"""
        val updated = updateAuthConfig(text, "dev", "auth", mapOf("Type" to "OAuth2", "Scope" to ""))
        val parsed = Json.parse(updated) as Map<*, *>
        assertEquals(
            mapOf("dev" to mapOf("host" to "h", "Security" to mapOf("Auth" to mapOf("auth" to mapOf("Type" to "OAuth2"))))),
            parsed,
        )
    }

    @Test
    fun `completion prefix`() {
        assertEquals("ho", Variables.completionPrefix("{{host}}/a/{{ho", 15))
        assertNull(Variables.completionPrefix("{{host}}/a", 10))
        assertEquals("", Variables.completionPrefix("x{{", 3))
    }

    @Test
    fun `openapi routes`() {
        val spec = """
            openapi: 3.0.0
            servers:
              - url: https://api.example.com/v1
            paths:
              /pets/{petId}:
                parameters:
                  - name: petId
                    in: path
                get:
                  operationId: getPet
                  summary: Get a pet
                  parameters:
                    - name: verbose
                      in: query
              /pets:
                post:
                  requestBody:
                    content:
                      application/json:
                        schema:
                          ${'$'}ref: '#/components/schemas/Pet'
            components:
              schemas:
                Pet:
                  type: object
                  properties:
                    name: { type: string, example: Rex }
                    age: { type: integer }
        """.trimIndent()
        assertTrue(OpenApi.looksLikeSpec(spec))
        val routes = OpenApi.parse(spec, "api.yaml")
        assertEquals(listOf("GET /pets/{petId}", "POST /pets"), routes.map { "${it.method} ${it.path}" })
        val get = routes[0].toRequest("{{host}}")
        assertEquals("{{host}}/pets/{{petId}}?verbose=", get.url)
        assertEquals("getPet", get.name)
        val post = routes[1]
        assertEquals("application/json", post.contentType)
        assertEquals("{\n  \"name\": \"Rex\",\n  \"age\": 0\n}", post.bodyExample)
    }
}
