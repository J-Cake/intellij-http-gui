package dev.jcake.httpgui.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.ActionLink
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
import dev.jcake.httpgui.core.Urls
import dev.jcake.httpgui.core.VariableInfo
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Per-request authentication. Either the request uses the environment's authentication (an OAuth
 * configuration with the same name in every environment, see [EnvironmentAuthDialog]), or it picks
 * its own scheme.
 */
class AuthPanel(
    private val project: Project,
    private val variables: () -> List<VariableInfo>,
    private val environments: () -> EnvironmentSet,
    private val selectedEnvironment: () -> String?,
    /** Name of the OAuth configuration used by requests with environment authentication. */
    private val environmentAuthId: () -> String,
    /** Opens the environment authentication settings, optionally at a configuration. */
    private val openAuthSettings: (configId: String?) -> Unit,
    private val onChanged: (AuthConfig) -> Unit,
) : JPanel(BorderLayout()) {

    private enum class Kind(val label: String) {
        NONE("No auth"), BASIC("Basic"), DIGEST("Digest"), BEARER("Bearer token"),
        API_KEY("API key"), OAUTH2("OAuth 2.0"), CUSTOM("Custom header"),
    }

    private var silent = false
    private var kind = Kind.NONE
    private var checkedWithEnvironments = false
    private val cards = FitCardLayout()
    private val cardPanel = JPanel(cards)
    private val kindButtons = Kind.entries.associateWith { JBRadioButton(it.label) }

    private val useEnvironment = JBCheckBox("Use environment authentication").apply {
        addActionListener {
            if (silent) return@addActionListener
            updateVisibility()
            if (!isSelected) {
                // Continue from the equivalent per-request setting.
                kind = Kind.OAUTH2
                kindButtons.getValue(kind).isSelected = true
                cards.show(cardPanel, kind.name)
                setOAuthId(environmentAuthId())
            }
            refreshStatus()
            fire()
        }
    }
    private val environmentStatus = hint("")
    private val perRequest = JPanel(BorderLayout())

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
        addActionListener { if (!silent) { refreshStatus(); fire() } }
    }
    private val oauthIdToken = JBCheckBox("Use the ID token instead of the access token").apply { addActionListener { fire() } }
    private val oauthStatus = hint("")

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
        cardPanel.add(form {
            addLabeledComponent("Configuration:", JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                add(oauthId)
                add(ActionLink("Edit configurations…") { openAuthSettings(currentOAuthId().ifEmpty { null }) })
            })
            addComponent(oauthIdToken)
            addComponent(oauthStatus)
        }, Kind.OAUTH2.name)
        cardPanel.add(form {
            addLabeledComponent("Authorization:", customHeader)
            addComponent(hint("Raw value of the Authorization header."))
        }, Kind.CUSTOM.name)

        perRequest.add(kindRow, BorderLayout.NORTH)
        perRequest.add(cardPanel.apply { border = JBUI.Borders.emptyTop(8) }, BorderLayout.CENTER)

        val environmentRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            add(useEnvironment)
            add(ActionLink("Configure…") { openAuthSettings(environmentAuthId()) })
            add(environmentStatus)
        }
        add(environmentRow, BorderLayout.NORTH)
        add(perRequest.apply { border = JBUI.Borders.emptyTop(8) }, BorderLayout.CENTER)
        kindButtons.getValue(Kind.NONE).isSelected = true
        updateVisibility()
    }

    private fun form(build: FormBuilder.() -> Unit): JComponent {
        val panel = FormBuilder.createFormBuilder().apply(build).panel
        return JPanel(BorderLayout()).apply { add(panel, BorderLayout.NORTH) }
    }

    private fun hint(text: String) = JBLabel(text).apply { foreground = UIUtil.getContextHelpForeground() }

    private fun updateVisibility() {
        perRequest.isVisible = !useEnvironment.isSelected
        revalidate()
        repaint()
    }

    /** Re-reads configuration names and status after environment files or the selection changed. */
    fun refreshOAuthChoices() {
        // The environment default is only known once the env files are read; recognize it then, once.
        if (!checkedWithEnvironments && environments().files.isNotEmpty()) {
            checkedWithEnvironments = true
            val auth = currentAuth()
            if (!useEnvironment.isSelected && auth is AuthConfig.OAuth2 && !auth.idToken && auth.configId == environmentAuthId()) {
                silent = true
                try {
                    useEnvironment.isSelected = true
                } finally {
                    silent = false
                }
                updateVisibility()
            }
        }
        silent = true
        try {
            val currentId = currentOAuthId()
            oauthId.removeAllItems()
            environments().authConfigs(null).keys.forEach { oauthId.addItem(it) }
            oauthId.selectedItem = currentId.ifEmpty { oauthId.getItemAt(0) ?: "" }
        } finally {
            silent = false
        }
        refreshStatus()
    }

    private fun setOAuthId(id: String) {
        silent = true
        try {
            oauthId.selectedItem = id
        } finally {
            silent = false
        }
    }

    /** Points out a configuration that is missing in the selected environment. */
    private fun refreshStatus() {
        val env = selectedEnvironment()
        fun status(id: String): String = when {
            id.isEmpty() -> "Choose or type a configuration name."
            env == null -> "Select an environment in the toolbar to use '$id'."
            id !in environments().authConfigs(env) -> "'$id' isn't configured for the '$env' environment yet."
            else -> ""
        }
        environmentStatus.text = if (useEnvironment.isSelected) status(environmentAuthId()) else ""
        oauthStatus.text = if (kind == Kind.OAUTH2) status(currentOAuthId()) else ""
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
        if (auth == currentAuth()) {
            refreshStatus()
            return
        }
        silent = true
        try {
            useEnvironment.isSelected = auth is AuthConfig.OAuth2 && !auth.idToken && auth.configId == environmentAuthId()
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
        updateVisibility()
        if (kind == Kind.OAUTH2) refreshOAuthChoices() else refreshStatus()
    }

    private fun currentAuth(): AuthConfig {
        if (useEnvironment.isSelected) return AuthConfig.OAuth2(environmentAuthId())
        return when (kind) {
            Kind.NONE -> AuthConfig.None
            Kind.BASIC -> AuthConfig.Basic(basicUser.text, basicPassword.text)
            Kind.DIGEST -> AuthConfig.Digest(digestUser.text, digestPassword.text)
            Kind.BEARER -> AuthConfig.Bearer(bearerToken.text)
            Kind.API_KEY -> AuthConfig.ApiKey(apiKeyName.text.ifBlank { "X-API-Key" }, apiKeyValue.text, apiKeyInQuery.isSelected)
            Kind.OAUTH2 -> AuthConfig.OAuth2(currentOAuthId(), oauthIdToken.isSelected)
            Kind.CUSTOM -> AuthConfig.Custom(customHeader.text)
        }
    }

    private fun fire() {
        if (!silent) onChanged(currentAuth())
    }
}
