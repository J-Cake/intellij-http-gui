package dev.jcake.httpgui.core

import java.util.Base64

/** Authentication of a request, expressed through its headers or query string. */
sealed class AuthConfig {
    object None : AuthConfig()
    data class Basic(val username: String = "", val password: String = "") : AuthConfig()
    data class Digest(val username: String = "", val password: String = "") : AuthConfig()
    data class Bearer(val token: String = "") : AuthConfig()
    data class ApiKey(val name: String = "X-API-Key", val value: String = "", val inQuery: Boolean = false) : AuthConfig()

    /** Token obtained by the HTTP Client from a `Security.Auth` configuration in an environment file. */
    data class OAuth2(val configId: String = "", val idToken: Boolean = false) : AuthConfig()

    /** An `Authorization` header the dashboard cannot represent; left untouched. */
    data class Custom(val header: String) : AuthConfig()
}

object AuthCodec {
    const val AUTHORIZATION = "Authorization"
    val API_KEY_NAMES = listOf("X-API-Key", "X-Api-Key", "Api-Key", "apikey", "api_key", "X-Auth-Token")

    private val AUTH_TOKEN = Regex("""^\{\{\s*\${'$'}auth\.(token|idToken)\(\s*"([^"]*)"\s*\)\s*}}$""")

    fun detect(request: HttpRequest): AuthConfig {
        val header = request.header(AUTHORIZATION)?.value?.trim()
        if (header != null) return parseAuthorization(header)
        request.headers.firstOrNull { h -> API_KEY_NAMES.any { it.equals(h.name, ignoreCase = true) } }?.let {
            return AuthConfig.ApiKey(it.name, it.value, inQuery = false)
        }
        Urls.split(request.url).second.firstOrNull { p -> API_KEY_NAMES.any { it.equals(p.name, ignoreCase = true) } }?.let {
            return AuthConfig.ApiKey(it.name, it.value ?: "", inQuery = true)
        }
        return AuthConfig.None
    }

    fun parseAuthorization(value: String): AuthConfig {
        val scheme = value.substringBefore(' ')
        val rest = value.substringAfter(' ', "").trim()
        return when (scheme.lowercase()) {
            "basic" -> credentials(rest)?.let { AuthConfig.Basic(it.first, it.second) } ?: AuthConfig.Custom(value)
            "digest" -> credentials(rest)?.let { AuthConfig.Digest(it.first, it.second) } ?: AuthConfig.Custom(value)
            "bearer" -> AUTH_TOKEN.matchEntire(rest)?.let { AuthConfig.OAuth2(it.groupValues[2], it.groupValues[1] == "idToken") }
                ?: AuthConfig.Bearer(rest)
            else -> AUTH_TOKEN.matchEntire(value)?.let { AuthConfig.OAuth2(it.groupValues[2], it.groupValues[1] == "idToken") }
                ?: AuthConfig.Custom(value)
        }
    }

    /** `user password` (HTTP Client shorthand), `user:password` or base64 of `user:password`. */
    private fun credentials(text: String): Pair<String, String>? {
        if (text.isEmpty()) return "" to ""
        splitOutsideBraces(text)?.let { return it }
        if (!text.contains("{{")) {
            val decoded = runCatching { String(Base64.getDecoder().decode(text)) }.getOrNull()
            if (decoded != null && decoded.contains(':')) return decoded.substringBefore(':') to decoded.substringAfter(':')
        }
        if (text.contains(':') && !text.contains("{{")) return text.substringBefore(':') to text.substringAfter(':')
        // A lone username (password not typed yet) or a single variable.
        return text to ""
    }

    private fun splitOutsideBraces(text: String): Pair<String, String>? {
        var depth = 0
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("{{", i) -> { depth++; i += 2; continue }
                text.startsWith("}}", i) -> { depth--; i += 2; continue }
                text[i] == ' ' && depth == 0 -> return text.substring(0, i) to text.substring(i + 1).trim()
            }
            i++
        }
        return null
    }

    /** Removes whatever auth [request] currently carries and applies [auth]. */
    fun apply(request: HttpRequest, auth: AuthConfig): HttpRequest {
        var r = request.withHeader(AUTHORIZATION, null)
        val previous = detect(request)
        if (previous is AuthConfig.ApiKey) {
            r = if (previous.inQuery) {
                val (base, params) = Urls.split(r.url)
                r.copy(url = Urls.join(base, params.filterNot { it.name == previous.name }))
            } else {
                r.withHeader(previous.name, null)
            }
        }
        return when (auth) {
            AuthConfig.None -> r
            is AuthConfig.Basic -> r.withHeader(AUTHORIZATION, "Basic ${auth.username} ${auth.password}".trimEnd())
            is AuthConfig.Digest -> r.withHeader(AUTHORIZATION, "Digest ${auth.username} ${auth.password}".trimEnd())
            is AuthConfig.Bearer -> r.withHeader(AUTHORIZATION, "Bearer ${auth.token}".trimEnd())
            is AuthConfig.OAuth2 -> r.withHeader(
                AUTHORIZATION,
                "Bearer {{\$auth.${if (auth.idToken) "idToken" else "token"}(\"${auth.configId}\")}}",
            )
            is AuthConfig.ApiKey -> if (auth.name.isBlank()) r else if (auth.inQuery) {
                val (base, params) = Urls.split(r.url)
                r.copy(url = Urls.join(base, params.filterNot { it.name == auth.name } + QueryParam(auth.name, auth.value)))
            } else {
                r.withHeader(auth.name, auth.value)
            }
            is AuthConfig.Custom -> r.withHeader(AUTHORIZATION, auth.header)
        }
    }
}

/** Keys used by the HTTP Client for `Security.Auth` entries in `http-client.env.json`. */
object OAuthKeys {
    const val TYPE = "Type"
    const val GRANT_TYPE = "Grant Type"
    const val AUTH_URL = "Auth URL"
    const val TOKEN_URL = "Token URL"
    const val CLIENT_ID = "Client ID"
    const val CLIENT_SECRET = "Client Secret"
    const val SCOPE = "Scope"
    const val REDIRECT_URL = "Redirect URL"
    const val USERNAME = "Username"
    const val PASSWORD = "Password"
    const val PKCE = "PKCE"

    val GRANT_TYPES = listOf("Authorization Code", "Client Credentials", "Password", "Implicit", "Device Authorization")
    val FIELDS = listOf(GRANT_TYPE, AUTH_URL, TOKEN_URL, CLIENT_ID, CLIENT_SECRET, SCOPE, REDIRECT_URL, USERNAME, PASSWORD)
    val SECRET_FIELDS = setOf(CLIENT_SECRET, PASSWORD)
}
