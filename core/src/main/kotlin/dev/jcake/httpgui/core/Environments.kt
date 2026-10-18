package dev.jcake.httpgui.core

const val PUBLIC_ENV_FILE = "http-client.env.json"
const val PRIVATE_ENV_FILE = "http-client.private.env.json"
const val SHARED_ENV = "\$shared"

/** Where a completion variable comes from; shown as the type text in completion popups. */
enum class VariableSource(val label: String) {
    ENVIRONMENT("env"),
    PRIVATE_ENVIRONMENT("private env"),
    FILE("file"),
    DYNAMIC("dynamic"),
}

data class VariableInfo(val name: String, val value: String?, val source: VariableSource, val environment: String? = null)

/** Parsed content of one environment file: environment name -> raw JSON object. */
data class EnvironmentFile(val path: String, val isPrivate: Boolean, val environments: Map<String, Map<String, Any?>>) {
    companion object {
        fun parse(path: String, isPrivate: Boolean, text: String): EnvironmentFile {
            val root = runCatching { Json.parse(text) }.getOrNull() as? Map<*, *> ?: emptyMap<String, Any?>()
            val envs = LinkedHashMap<String, Map<String, Any?>>()
            root.forEach { (k, v) ->
                @Suppress("UNCHECKED_CAST")
                if (k is String && v is Map<*, *>) envs[k] = v as Map<String, Any?>
            }
            return EnvironmentFile(path, isPrivate, envs)
        }
    }
}

/** All environment files visible to an `.http` file. */
class EnvironmentSet(val files: List<EnvironmentFile>) {
    val environmentNames: List<String>
        get() = files.flatMap { it.environments.keys }.filter { it != SHARED_ENV }.distinct()

    /** Variables of [environment] (with `$shared` merged in); private files override public ones. */
    fun variables(environment: String?): List<VariableInfo> {
        val result = LinkedHashMap<String, VariableInfo>()
        val ordered = files.sortedBy { it.isPrivate }
        for (envName in listOfNotNull(SHARED_ENV, environment)) {
            for (file in ordered) {
                val env = file.environments[envName] ?: continue
                val source = if (file.isPrivate) VariableSource.PRIVATE_ENVIRONMENT else VariableSource.ENVIRONMENT
                env.forEach { (name, value) ->
                    if (name == "Security" || value is Map<*, *> || value is List<*>) return@forEach
                    result[name] = VariableInfo(name, value?.toString(), source, envName)
                }
            }
        }
        return result.values.toList()
    }

    /** Variable names from any environment, for completion when no environment is selected. */
    fun allVariables(): List<VariableInfo> {
        val names = LinkedHashMap<String, VariableInfo>()
        for (env in listOf(SHARED_ENV) + environmentNames) {
            variables(env).forEach { names.putIfAbsent(it.name, it) }
        }
        return names.values.toList()
    }

    /** `Security.Auth` configurations across files for [environment] (or all environments when null). */
    fun authConfigs(environment: String?): Map<String, Map<String, Any?>> {
        val result = LinkedHashMap<String, MutableMap<String, Any?>>()
        val envs = if (environment == null) listOf(SHARED_ENV) + environmentNames else listOf(SHARED_ENV, environment)
        for (envName in envs) {
            for (file in files.sortedBy { it.isPrivate }) {
                val auth = ((file.environments[envName]?.get("Security") as? Map<*, *>)?.get("Auth") as? Map<*, *>) ?: continue
                auth.forEach { (id, cfg) ->
                    if (id is String && cfg is Map<*, *>) {
                        val merged = result.getOrPut(id) { LinkedHashMap() }
                        cfg.forEach { (k, v) -> if (k is String) merged[k] = v }
                    }
                }
            }
        }
        return result
    }

    companion object {
        val EMPTY = EnvironmentSet(emptyList())
    }
}

/**
 * Updates `Security.Auth.<id>` of [environment] inside the JSON text of an environment file and
 * returns the new text. Values that are null are removed.
 */
fun updateAuthConfig(fileText: String, environment: String, id: String, values: Map<String, Any?>): String {
    @Suppress("UNCHECKED_CAST")
    val root = (runCatching { Json.parse(fileText) }.getOrNull() as? Map<String, Any?>)?.let { LinkedHashMap(it) }
        ?: LinkedHashMap()
    val env = LinkedHashMap((root[environment] as? Map<String, Any?>) ?: emptyMap())
    val security = LinkedHashMap((env["Security"] as? Map<String, Any?>) ?: emptyMap())
    val auth = LinkedHashMap((security["Auth"] as? Map<String, Any?>) ?: emptyMap())
    val config = LinkedHashMap((auth[id] as? Map<String, Any?>) ?: emptyMap())
    values.forEach { (k, v) -> if (v == null || (v is String && v.isEmpty())) config.remove(k) else config[k] = v }
    auth[id] = config
    security["Auth"] = auth
    env["Security"] = security
    root[environment] = env
    return Json.write(root) + "\n"
}

/** Built-in dynamic variables of the HTTP Client. */
val DYNAMIC_VARIABLES: List<VariableInfo> = listOf(
    "\$uuid", "\$random.uuid", "\$timestamp", "\$isoTimestamp", "\$randomInt",
    "\$random.integer()", "\$random.float()", "\$random.alphabetic()", "\$random.alphanumeric()",
    "\$random.hexadecimal()", "\$random.email", "\$exampleServer",
    "\$processEnv.", "\$dotenv.", "\$env.",
    "\$auth.token(\"\")", "\$auth.idToken(\"\")",
).map { VariableInfo(it, null, VariableSource.DYNAMIC) }

object Variables {
    private val REFERENCE = Regex("""\{\{\s*([^{}]+?)\s*}}""")

    fun references(text: String): List<String> = REFERENCE.findAll(text).map { it.groupValues[1] }.toList()

    /** Substitutes known variables; unknown ones are left as `{{name}}`. */
    fun resolve(text: String, values: Map<String, String?>, depth: Int = 0): String {
        val replaced = REFERENCE.replace(text) { m -> values[m.groupValues[1]] ?: m.value }
        return if (replaced != text && depth < 5 && REFERENCE.containsMatchIn(replaced)) resolve(replaced, values, depth + 1) else replaced
    }

    /** The partially typed variable name if [offset] is inside an unclosed `{{`, else null. */
    fun completionPrefix(text: String, offset: Int): String? {
        val before = text.substring(0, offset.coerceIn(0, text.length))
        val open = before.lastIndexOf("{{")
        if (open < 0 || before.indexOf("}}", open) >= 0) return null
        return before.substring(open + 2).trimStart()
    }
}
