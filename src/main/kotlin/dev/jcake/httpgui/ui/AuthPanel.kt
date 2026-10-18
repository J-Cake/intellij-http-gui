package dev.jcake.httpgui.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.jcake.httpgui.core.AuthCodec
import dev.jcake.httpgui.core.AuthConfig
import dev.jcake.httpgui.core.EnvironmentSet
import dev.jcake.httpgui.core.HttpRequest
import dev.jcake.httpgui.core.OAuthKeys
import dev.jcake.httpgui.core.SHARED_ENV
import dev.jcake.httpgui.core.Urls
import dev.jcake.httpgui.core.VariableInfo
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Authentication dashboard: picks an auth scheme and edits its parameters. OAuth 2.0 is backed by
 * the HTTP Client's `Security.Auth` environment configuration, which can be edited here too.
 */
class AuthPanel(
    private val project: Project,
    private val variables: () -> List<VariableInfo>,
    private val environments: () -> EnvironmentSet,
    private val selectedEnvironment: () -> String?,
    /** Saves an OAuth configuration: environment, id, public values, private (secret) values. */
    private val saveOAuthConfig: (String, String, Map<String, Any?>, Map<String, Any?>) -> Unit,
    private val onChanged: (AuthConfig) -> Unit,
) : JPanel(BorderLayout()) {

    private enum class Kind(val label: String) {
        NONE("No auth"), BASIC("Basic"), DIGEST("Digest"), BEARER("Bearer token"),
        API_KEY("API key"), OAUTH2("OAuth 2.0"), CUSTOM("Custom header"),
    }

    private var silent = false
    private var kind = Kind.NONE
    private val cards = FitCardLayout()
    private val cardPanel = JPanel(cards)
    private val kindButtons = Kind.entries.associateWith { JBRadioButton(it.label) }

    private fun field() = VariableTextField(project, variables) { fire() }

    private val basicUser = field()
    private val basicPassword = field()
    private val digestUser = field()
    private val digestPassword = field()
    private val bearerToken = field()
    private val apiKeyName = field()
    private val apiKeyValue = field()
    private val apiKeyInQuery = JBCheckBox("Send as query parameter instead of header").apply { addActionListener { fire() } }
    private val customHeader = field()

    private val oauthId = ComboBox<String>().apply {
        isEditable = true
        addActionListener { if (!silent) { loadOAuthConfig(); fire() } }
    }
    private val oauthIdToken = JBCheckBox("Use ID token (\$auth.idToken) instead of access token").apply { addActionListener { fire() } }
    private val oauthEnv = ComboBox<String>().apply { addActionListener { if (!silent) loadOAuthConfig() } }
    private val grantType = ComboBox(OAuthKeys.GRANT_TYPES.toTypedArray()).apply { isEditable = true }
    private val oauthFields = (OAuthKeys.FIELDS - OAuthKeys.GRANT_TYPE).associateWith { VariableTextField(project, variables) }
    private val pkce = JBCheckBox("Use PKCE")
    private val oauthStatus = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }

    init {
        val group = ButtonGroup()
        val kindRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0))
        kindButtons.forEach { (k, button) ->
            group.add(button)
            kindRow.add(button)
            button.addActionListener {
                if (silent) return@addActionListener
                kind = k
                cards.show(cardPanel, k.name)
                cardPanel.revalidate()
                if (k == Kind.OAUTH2) refreshOAuthChoices()
                fire()
            }
        }

        cardPanel.add(hint("The request is sent without authentication."), Kind.NONE.name)
        cardPanel.add(form {
            addLabeledComponent("Username:", basicUser)
            addLabeledComponent("Password:", basicPassword)
            addComponent(hint("Written as 'Authorization: Basic <user> <password>'; the HTTP Client encodes it."))
        }, Kind.BASIC.name)
        cardPanel.add(form {
            addLabeledComponent("Username:", digestUser)
            addLabeledComponent("Password:", digestPassword)
        }, Kind.DIGEST.name)
        cardPanel.add(form {
            addLabeledComponent("Token:", bearerToken)
            addComponent(hint("Tip: store the token in an environment variable and type {{ to reference it."))
        }, Kind.BEARER.name)
        cardPanel.add(form {
            addLabeledComponent("Key name:", apiKeyName)
            addLabeledComponent("Value:", apiKeyValue)
            addComponent(apiKeyInQuery)
        }, Kind.API_KEY.name)
        cardPanel.add(oauthCard(), Kind.OAUTH2.name)
        cardPanel.add(form {
            addLabeledComponent("Authorization:", customHeader)
            addComponent(hint("Raw value of the Authorization header."))
        }, Kind.CUSTOM.name)

        add(kindRow, BorderLayout.NORTH)
        add(cardPanel.apply { border = JBUI.Borders.emptyTop(8) }, BorderLayout.CENTER)
        kindButtons.getValue(Kind.NONE).isSelected = true
    }

    private fun oauthCard(): JComponent {
        val save = JButton("Save to Environment File").apply {
            addActionListener { saveCurrentOAuthConfig() }
        }
        return form {
            addLabeledComponent("Auth configuration:", oauthId)
            addComponent(oauthIdToken)
            addComponent(TitledSeparator("Configuration (Security.Auth in http-client.env.json)"))
            addLabeledComponent("Environment:", oauthEnv)
            addLabeledComponent("Grant type:", grantType)
            oauthFields.forEach { (key, f) -> addLabeledComponent("$key:", f) }
            addComponent(pkce)
            addComponent(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(save); add(oauthStatus) })
            addComponent(hint("Secrets (client secret, password) are saved to http-client.private.env.json."))
        }
    }

    private fun form(build: FormBuilder.() -> Unit): JComponent {
        val panel = FormBuilder.createFormBuilder().apply(build).panel
        return JPanel(BorderLayout()).apply { add(panel, BorderLayout.NORTH) }
    }

    private fun hint(text: String) = JBLabel(text).apply { foreground = UIUtil.getContextHelpForeground() }

    fun refreshOAuthChoices() {
        silent = true
        try {
            val envs = environments()
            val currentId = currentOAuthId()
            oauthId.removeAllItems()
            envs.authConfigs(null).keys.forEach { oauthId.addItem(it) }
            oauthId.selectedItem = currentId.ifEmpty { oauthId.getItemAt(0) ?: "" }

            val currentEnv = oauthEnv.selectedItem as? String
            oauthEnv.removeAllItems()
            (listOf(SHARED_ENV) + envs.environmentNames).forEach { oauthEnv.addItem(it) }
            oauthEnv.selectedItem = currentEnv ?: selectedEnvironment() ?: envs.environmentNames.firstOrNull() ?: SHARED_ENV
        } finally {
            silent = false
        }
        loadOAuthConfig()
    }

    private fun loadOAuthConfig() {
        val id = currentOAuthId()
        val env = oauthEnv.selectedItem as? String
        val config = environments().authConfigs(env)[id].orEmpty()
        grantType.selectedItem = config[OAuthKeys.GRANT_TYPE]?.toString() ?: OAuthKeys.GRANT_TYPES.first()
        oauthFields.forEach { (key, f) -> f.setTextSilently(config[key]?.toString() ?: "") }
        pkce.isSelected = config[OAuthKeys.PKCE] != null && config[OAuthKeys.PKCE] != false
        oauthStatus.text = if (id.isNotEmpty() && config.isEmpty()) "  Not defined for this environment yet" else ""
    }

    private fun saveCurrentOAuthConfig() {
        val id = currentOAuthId()
        val env = oauthEnv.selectedItem as? String ?: return
        if (id.isEmpty()) {
            oauthStatus.text = "  Enter a configuration name first"
            return
        }
        val publicValues = linkedMapOf<String, Any?>(
            OAuthKeys.TYPE to "OAuth2",
            OAuthKeys.GRANT_TYPE to grantType.editor.item?.toString(),
        )
        val privateValues = linkedMapOf<String, Any?>()
        oauthFields.forEach { (key, f) ->
            if (key in OAuthKeys.SECRET_FIELDS) {
                privateValues[key] = f.text
                publicValues[key] = null
            } else {
                publicValues[key] = f.text
            }
        }
        publicValues[OAuthKeys.PKCE] = if (pkce.isSelected) true else null
        saveOAuthConfig(env, id, publicValues, privateValues)
        oauthStatus.text = "  Saved"
        fire()
    }

    private fun currentOAuthId(): String = (oauthId.editor.item ?: oauthId.selectedItem)?.toString()?.trim() ?: ""

    /** Shows the auth of [request]; an API key with a non-standard name stays selected while still present. */
    fun setRequest(request: HttpRequest) {
        val current = currentAuth()
        val detected = AuthCodec.detect(request)
        if (detected == AuthConfig.None && current is AuthConfig.ApiKey && current.name.isNotBlank()) {
            val present = if (current.inQuery) {
                Urls.split(request.url).second.any { it.name == current.name && (it.value ?: "") == current.value }
            } else {
                request.header(current.name)?.value == current.value
            }
            if (present) return
        }
        setAuth(detected)
    }

    private fun setAuth(auth: AuthConfig) {
        if (auth == currentAuth()) return
        silent = true
        try {
            kind = when (auth) {
                AuthConfig.None -> Kind.NONE
                is AuthConfig.Basic -> Kind.BASIC.also { basicUser.setTextSilently(auth.username); basicPassword.setTextSilently(auth.password) }
                is AuthConfig.Digest -> Kind.DIGEST.also { digestUser.setTextSilently(auth.username); digestPassword.setTextSilently(auth.password) }
                is AuthConfig.Bearer -> Kind.BEARER.also { bearerToken.setTextSilently(auth.token) }
                is AuthConfig.ApiKey -> Kind.API_KEY.also {
                    apiKeyName.setTextSilently(auth.name)
                    apiKeyValue.setTextSilently(auth.value)
                    apiKeyInQuery.isSelected = auth.inQuery
                }
                is AuthConfig.OAuth2 -> Kind.OAUTH2.also {
                    oauthId.selectedItem = auth.configId
                    oauthIdToken.isSelected = auth.idToken
                }
                is AuthConfig.Custom -> Kind.CUSTOM.also { customHeader.setTextSilently(auth.header) }
            }
            kindButtons.getValue(kind).isSelected = true
            cards.show(cardPanel, kind.name)
            cardPanel.revalidate()
        } finally {
            silent = false
        }
        if (kind == Kind.OAUTH2) refreshOAuthChoices()
    }

    private fun currentAuth(): AuthConfig = when (kind) {
        Kind.NONE -> AuthConfig.None
        Kind.BASIC -> AuthConfig.Basic(basicUser.text, basicPassword.text)
        Kind.DIGEST -> AuthConfig.Digest(digestUser.text, digestPassword.text)
        Kind.BEARER -> AuthConfig.Bearer(bearerToken.text)
        Kind.API_KEY -> AuthConfig.ApiKey(apiKeyName.text.ifBlank { "X-API-Key" }, apiKeyValue.text, apiKeyInQuery.isSelected)
        Kind.OAUTH2 -> AuthConfig.OAuth2(currentOAuthId(), oauthIdToken.isSelected)
        Kind.CUSTOM -> AuthConfig.Custom(customHeader.text)
    }

    private fun fire() {
        if (!silent) onChanged(currentAuth())
    }
}
