package dev.jcake.httpgui.core

/** Keys of `Security.Auth.<id>` entries in `http-client.env.json` (see the HTTP Client's env file schema). */
object OAuthKeys {
    const val TYPE = "Type"
    const val OAUTH2 = "OAuth2"
    const val GRANT_TYPE = "Grant Type"
    const val AUTH_URL = "Auth URL"
    const val TOKEN_URL = "Token URL"
    const val DEVICE_AUTH_URL = "Device Auth URL"
    const val REDIRECT_URL = "Redirect URL"
    const val CLIENT_ID = "Client ID"
    const val CLIENT_SECRET = "Client Secret"
    const val USERNAME = "Username"
    const val PASSWORD = "Password"
    const val SCOPE = "Scope"
    const val PKCE = "PKCE"
    const val ACQUIRE_AUTOMATICALLY = "Acquire Automatically"
    const val USE_ID_TOKEN = "Use ID Token"
    const val CUSTOM_REQUEST_PARAMETERS = "Custom Request Parameters"
    const val AUDIENCE = "audience"

    const val AUTHORIZATION_CODE = "Authorization Code"
    const val CLIENT_CREDENTIALS = "Client Credentials"
    const val PASSWORD_GRANT = "Password"
    const val IMPLICIT = "Implicit"
    const val DEVICE_AUTHORIZATION = "Device Authorization"

    val GRANT_TYPES = listOf(AUTHORIZATION_CODE, CLIENT_CREDENTIALS, PASSWORD_GRANT, DEVICE_AUTHORIZATION, IMPLICIT)

    /** Text fields in display order. */
    val TEXT_FIELDS = listOf(AUTH_URL, DEVICE_AUTH_URL, TOKEN_URL, REDIRECT_URL, CLIENT_ID, CLIENT_SECRET, USERNAME, PASSWORD, SCOPE)

    /** Values that belong in `http-client.private.env.json`. */
    val SECRET_FIELDS = setOf(CLIENT_SECRET, PASSWORD)

    /** The text fields a grant type uses; the others are irrelevant and hidden. */
    fun fieldsFor(grantType: String?): Set<String> = when (grantType) {
        CLIENT_CREDENTIALS -> setOf(TOKEN_URL, CLIENT_ID, CLIENT_SECRET, SCOPE)
        PASSWORD_GRANT -> setOf(TOKEN_URL, CLIENT_ID, CLIENT_SECRET, USERNAME, PASSWORD, SCOPE)
        DEVICE_AUTHORIZATION -> setOf(DEVICE_AUTH_URL, TOKEN_URL, CLIENT_ID, CLIENT_SECRET, SCOPE)
        IMPLICIT -> setOf(AUTH_URL, REDIRECT_URL, CLIENT_ID, SCOPE)
        else -> setOf(AUTH_URL, TOKEN_URL, REDIRECT_URL, CLIENT_ID, CLIENT_SECRET, SCOPE)
    }

    /** PKCE only applies to the authorization code flow. */
    fun supportsPkce(grantType: String?) = grantType == null || grantType == AUTHORIZATION_CODE

    /**
     * Drops values the grant type doesn't use, so switching grant types doesn't leave stale settings
     * behind. Keys this editor doesn't know about are kept.
     */
    fun normalize(config: Map<String, Any?>): Map<String, Any?> {
        val grant = config[GRANT_TYPE] as? String
        val used = fieldsFor(grant)
        return config.filter { (key, value) ->
            when {
                value == null || (value is String && value.isEmpty()) -> false
                key in TEXT_FIELDS -> key in used
                key == PKCE -> supportsPkce(grant)
                else -> true
            }
        }
    }
}

/** OpenID Connect discovery (`/.well-known/openid-configuration`). */
object OidcDiscovery {
    const val WELL_KNOWN_PATH = "/.well-known/openid-configuration"

    /** Accepts an issuer URL or the full discovery document URL. */
    fun documentUrl(input: String): String {
        val url = input.trim().trimEnd('/')
        return if (url.endsWith(WELL_KNOWN_PATH)) url else url + WELL_KNOWN_PATH
    }

    /** Maps a discovery document to the env file keys it provides. */
    fun endpoints(document: String): Map<String, String> {
        val json = Json.parse(document) as? Map<*, *> ?: throw Json.ParseException("Not a discovery document")
        val result = LinkedHashMap<String, String>()
        (json["authorization_endpoint"] as? String)?.let { result[OAuthKeys.AUTH_URL] = it }
        (json["token_endpoint"] as? String)?.let { result[OAuthKeys.TOKEN_URL] = it }
        (json["device_authorization_endpoint"] as? String)?.let { result[OAuthKeys.DEVICE_AUTH_URL] = it }
        if (result.isEmpty()) throw Json.ParseException("The document has no OAuth endpoints")
        return result
    }
}

/**
 * Sets `Security.Auth.<id>` of [environment] in the JSON text of an environment file to [config], or
 * removes it when [config] is null or empty. Emptied `Auth`/`Security` objects are removed too.
 */
fun setAuthConfig(fileText: String, environment: String, id: String, config: Map<String, Any?>?): String {
    @Suppress("UNCHECKED_CAST")
    val root = (runCatching { Json.parse(fileText) }.getOrNull() as? Map<String, Any?>)?.let { LinkedHashMap(it) }
        ?: LinkedHashMap()
    @Suppress("UNCHECKED_CAST")
    val env = LinkedHashMap((root[environment] as? Map<String, Any?>) ?: emptyMap())
    @Suppress("UNCHECKED_CAST")
    val security = LinkedHashMap((env["Security"] as? Map<String, Any?>) ?: emptyMap())
    @Suppress("UNCHECKED_CAST")
    val auth = LinkedHashMap((security["Auth"] as? Map<String, Any?>) ?: emptyMap())
    if (config.isNullOrEmpty()) auth.remove(id) else auth[id] = LinkedHashMap(config)
    if (auth.isEmpty()) security.remove("Auth") else security["Auth"] = auth
    if (security.isEmpty()) env.remove("Security") else env["Security"] = security
    if (env.isEmpty() && !root.containsKey(environment)) return fileText
    root[environment] = env
    return Json.write(root) + "\n"
}

/** Splits a configuration into what goes to the public and to the private environment file. */
fun splitSecrets(config: Map<String, Any?>): Pair<Map<String, Any?>, Map<String, Any?>> {
    val public = config.filterKeys { it !in OAuthKeys.SECRET_FIELDS }
    val private = config.filterKeys { it in OAuthKeys.SECRET_FIELDS }
    return public to private
}
