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
    fun `auth config editing keeps other content`() {
        val text = """{"dev": {"host": "h"}}"""
        val added = setAuthConfig(text, "dev", "auth", mapOf("Type" to "OAuth2", "Client ID" to "app"))
        assertEquals(
            mapOf("dev" to mapOf("host" to "h", "Security" to mapOf("Auth" to mapOf("auth" to mapOf("Type" to "OAuth2", "Client ID" to "app"))))),
            Json.parse(added),
        )
        val removed = setAuthConfig(added, "dev", "auth", null)
        assertEquals(mapOf("dev" to mapOf("host" to "h")), Json.parse(removed))
        // Removing from an environment the file doesn't have leaves it untouched.
        assertEquals(text, setAuthConfig(text, "prod", "auth", null))
    }

    @Test
    fun `oauth settings are trimmed to the grant type`() {
        val config = mapOf(
            OAuthKeys.TYPE to "OAuth2",
            OAuthKeys.GRANT_TYPE to OAuthKeys.CLIENT_CREDENTIALS,
            OAuthKeys.AUTH_URL to "https://idp/authorize",
            OAuthKeys.TOKEN_URL to "https://idp/token",
            OAuthKeys.USERNAME to "jane",
            OAuthKeys.CLIENT_ID to "app",
            OAuthKeys.SCOPE to "",
            OAuthKeys.PKCE to true,
            OAuthKeys.ACQUIRE_AUTOMATICALLY to true,
        )
        assertEquals(
            mapOf(
                OAuthKeys.TYPE to "OAuth2",
                OAuthKeys.GRANT_TYPE to OAuthKeys.CLIENT_CREDENTIALS,
                OAuthKeys.TOKEN_URL to "https://idp/token",
                OAuthKeys.CLIENT_ID to "app",
                OAuthKeys.ACQUIRE_AUTOMATICALLY to true,
            ),
            OAuthKeys.normalize(config),
        )
        val (public, private) = splitSecrets(mapOf(OAuthKeys.CLIENT_ID to "app", OAuthKeys.CLIENT_SECRET to "s"))
        assertEquals(mapOf(OAuthKeys.CLIENT_ID to "app"), public)
        assertEquals(mapOf(OAuthKeys.CLIENT_SECRET to "s"), private)
    }

    @Test
    fun `oidc discovery`() {
        assertEquals("https://idp.example/realms/x/.well-known/openid-configuration", OidcDiscovery.documentUrl("https://idp.example/realms/x/"))
        assertEquals(
            "https://idp.example/.well-known/openid-configuration",
            OidcDiscovery.documentUrl("https://idp.example/.well-known/openid-configuration"),
        )
        val document = """{"issuer": "https://idp.example", "authorization_endpoint": "https://idp.example/auth",
            "token_endpoint": "https://idp.example/token", "device_authorization_endpoint": "https://idp.example/device"}"""
        assertEquals(
            mapOf(OAuthKeys.AUTH_URL to "https://idp.example/auth", OAuthKeys.TOKEN_URL to "https://idp.example/token", OAuthKeys.DEVICE_AUTH_URL to "https://idp.example/device"),
            OidcDiscovery.endpoints(document),
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
