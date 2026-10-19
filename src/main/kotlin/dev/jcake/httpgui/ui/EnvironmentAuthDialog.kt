package dev.jcake.httpgui.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TitledSeparator
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.util.io.HttpRequests
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.jcake.httpgui.core.EnvironmentSet
import dev.jcake.httpgui.core.OAuthKeys
import dev.jcake.httpgui.core.OidcDiscovery
import dev.jcake.httpgui.core.SHARED_ENV
import dev.jcake.httpgui.services.HttpEnvironments
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel

/** The OAuth configuration name used by requests that tick "Use environment authentication". */
object EnvironmentAuth {
    private const val KEY = "httpgui.environmentAuthId"
    private const val FALLBACK_ID = "auth"

    /** The stored name; otherwise the first configuration found, remembered so it stays stable. */
    fun id(project: Project, environments: EnvironmentSet): String {
        PropertiesComponent.getInstance(project).getValue(KEY)?.let { return it }
        val first = environments.authConfigs(null).keys.firstOrNull() ?: return FALLBACK_ID
        setId(project, first)
        return first
    }

    fun setId(project: Project, id: String) = PropertiesComponent.getInstance(project).setValue(KEY, id)
}

/**
 * Edits the OAuth configurations (`Security.Auth`) of each environment: one list per environment, a
 * form that only shows what the selected grant type needs, and OpenID Connect discovery for the
 * endpoints. Changes are written when the dialog is confirmed.
 */
class EnvironmentAuthDialog(
    private val project: Project,
    private val httpFile: VirtualFile,
    environments: EnvironmentSet,
    initialEnvironment: String?,
    initialConfig: String?,
) : DialogWrapper(project) {

    /** Working copy: environment -> configuration name -> settings. */
    private val configs = LinkedHashMap<String, LinkedHashMap<String, MutableMap<String, Any?>>>()
    private val original = HashMap<String, Map<String, Map<String, Any?>>>()
    private var defaultId = EnvironmentAuth.id(project, environments)

    private val envNames = listOf(SHARED_ENV) + environments.environmentNames
    private val envCombo = ComboBox(envNames.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create<String>("") { if (it == SHARED_ENV) "$SHARED_ENV (all environments)" else it }
    }
    private val listModel = CollectionListModel<String>()
    private val list = JBList(listModel)
    private var loading = false
    private var shownEnv: String? = null
    private var shownId: String? = null

    private val useAsDefault = JBCheckBox()
    private val grantType = ComboBox(OAuthKeys.GRANT_TYPES.toTypedArray())
    private val discoveryUrl = JBTextField().apply { emptyText.text = "https://issuer.example.com" }
    private val discoveryStatus = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val textFields = OAuthKeys.TEXT_FIELDS.associateWith { JBTextField() }
    private val textLabels = OAuthKeys.TEXT_FIELDS.associateWith { JBLabel("$it:") }
    private val audience = JBTextField().apply { emptyText.text = "Optional, e.g. for Auth0" }
    private val pkce = JBCheckBox("Use PKCE")
    private val useIdToken = JBCheckBox("Use the ID token instead of the access token")
    private val acquireAutomatically = JBCheckBox("Acquire the token automatically before a request needs it")
    private val formPanel = JPanel(BorderLayout())
    private val emptyLabel = JBLabel("Add a configuration with +.", UIUtil.ComponentStyle.REGULAR).apply {
        foreground = UIUtil.getContextHelpForeground()
        horizontalAlignment = JBLabel.CENTER
    }
    private val missingLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }

    init {
        title = "Environment Authentication"
        for (env in envNames) {
            val inEnv = environments.authConfigsIn(env)
            original[env] = inEnv
            configs[env] = LinkedHashMap(inEnv.mapValues { (_, v) -> LinkedHashMap(v) })
        }
        envCombo.selectedItem = initialEnvironment?.takeIf { it in envNames } ?: environments.environmentNames.firstOrNull() ?: SHARED_ENV
        envCombo.addActionListener { storeForm(); showEnvironment(initialConfig = null) }
        list.cellRenderer = object : ColoredListCellRenderer<String>() {
            override fun customizeCellRenderer(list: JList<out String>, value: String?, index: Int, selected: Boolean, hasFocus: Boolean) {
                value ?: return
                icon = AllIcons.Nodes.SecurityRole
                append(value)
                if (value == defaultId) append("  environment default", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        list.addListSelectionListener { if (!it.valueIsAdjusting && !loading) { storeForm(); showConfig(list.selectedValue) } }
        grantType.addActionListener { if (!loading) updateFieldVisibility() }
        useAsDefault.addActionListener { if (!loading) storeForm() }
        init()
        showEnvironment(initialConfig)
    }

    private val environment: String get() = envCombo.selectedItem as? String ?: SHARED_ENV

    override fun createCenterPanel(): JComponent {
        val listPanel = ToolbarDecorator.createDecorator(list)
            .setAddAction { addConfig() }
            .setRemoveAction { removeConfig() }
            .addExtraAction(DumbAwareAction.create("Rename", AllIcons.Actions.Edit) { renameConfig() })
            .disableUpDownActions()
            .createPanel()

        val form = JPanel(GridBagLayout())
        val c = GridBagConstraints().apply { insets = JBUI.insets(3); fill = GridBagConstraints.HORIZONTAL; anchor = GridBagConstraints.WEST }
        var row = 0
        fun full(component: JComponent) {
            c.gridx = 0; c.gridy = row++; c.gridwidth = 2; c.weightx = 1.0
            form.add(component, c)
            c.gridwidth = 1
        }
        fun labeled(label: JComponent, component: JComponent) {
            c.gridx = 0; c.gridy = row; c.weightx = 0.0
            form.add(label, c)
            c.gridx = 1; c.weightx = 1.0
            form.add(component, c)
            row++
        }
        full(useAsDefault)
        labeled(JBLabel("Grant type:"), grantType)
        full(TitledSeparator("Endpoints"))
        labeled(JBLabel("OpenID discovery:"), JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            add(discoveryUrl, BorderLayout.CENTER)
            add(JButton("Discover").apply { addActionListener { discover() } }, BorderLayout.EAST)
        })
        labeled(JBLabel(""), discoveryStatus)
        for (key in listOf(OAuthKeys.AUTH_URL, OAuthKeys.DEVICE_AUTH_URL, OAuthKeys.TOKEN_URL, OAuthKeys.REDIRECT_URL)) {
            labeled(textLabels.getValue(key), textFields.getValue(key))
        }
        full(TitledSeparator("Client"))
        for (key in listOf(OAuthKeys.CLIENT_ID, OAuthKeys.CLIENT_SECRET, OAuthKeys.USERNAME, OAuthKeys.PASSWORD, OAuthKeys.SCOPE)) {
            labeled(textLabels.getValue(key), textFields.getValue(key))
        }
        labeled(JBLabel("Audience:"), audience)
        full(pkce)
        full(useIdToken)
        full(acquireAutomatically)
        full(JBLabel("Client secret and password are stored in http-client.private.env.json.").apply {
            foreground = UIUtil.getContextHelpForeground()
        })
        c.gridy = row; c.weighty = 1.0
        form.add(JPanel(), c)

        formPanel.add(form, BorderLayout.CENTER)

        val split = JBSplitter(false, 0.3f).apply {
            firstComponent = listPanel
            secondComponent = JPanel(CardLayout()).apply {
                add(emptyLabel, "empty")
                add(formPanel, "form")
            }
        }
        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            preferredSize = Dimension(JBUI.scale(820), JBUI.scale(560))
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                add(JBLabel("Environment:"))
                add(envCombo)
            }, BorderLayout.NORTH)
            add(split, BorderLayout.CENTER)
            add(missingLabel, BorderLayout.SOUTH)
        }
    }

    // ---- list ---------------------------------------------------------------------------------

    private fun showEnvironment(initialConfig: String?) {
        loading = true
        try {
            shownEnv = environment
            listModel.replaceAll(configs[environment].orEmpty().keys.toList())
        } finally {
            loading = false
        }
        val select = initialConfig?.takeIf { it in listModel.items } ?: listModel.items.firstOrNull()
        if (select != null) list.setSelectedValue(select, true) else showConfig(null)
        updateMissing()
    }

    private fun addConfig() {
        val id = Messages.showInputDialog(project, "Configuration name:", "New Auth Configuration", null,
            if (configs.values.none { defaultId in it }) defaultId else "", null)?.trim()?.takeIf { it.isNotEmpty() } ?: return
        storeForm()
        val envConfigs = configs.getOrPut(environment) { LinkedHashMap() }
        if (id !in envConfigs) {
            // Start from the same configuration in another environment, if there is one.
            val template = configs.values.firstNotNullOfOrNull { it[id] }?.filterKeys { it !in OAuthKeys.SECRET_FIELDS }
            envConfigs[id] = LinkedHashMap(template ?: mapOf(OAuthKeys.TYPE to OAuthKeys.OAUTH2, OAuthKeys.GRANT_TYPE to OAuthKeys.AUTHORIZATION_CODE))
        }
        showEnvironment(id)
    }

    private fun removeConfig() {
        val id = list.selectedValue ?: return
        configs[environment]?.remove(id)
        shownId = null
        showEnvironment(null)
    }

    private fun renameConfig() {
        val old = list.selectedValue ?: return
        val new = Messages.showInputDialog(project, "New name:", "Rename Auth Configuration", null, old, null)?.trim()
            ?.takeIf { it.isNotEmpty() && it != old } ?: return
        storeForm()
        val envConfigs = configs[environment] ?: return
        val renamed = LinkedHashMap<String, MutableMap<String, Any?>>()
        envConfigs.forEach { (k, v) -> renamed[if (k == old) new else k] = v }
        configs[environment] = renamed
        if (defaultId == old) defaultId = new
        shownId = null
        showEnvironment(new)
    }

    // ---- form ---------------------------------------------------------------------------------

    private fun showConfig(id: String?) {
        shownEnv = environment
        shownId = id
        val cards = (formPanel.parent?.layout as? CardLayout)
        cards?.show(formPanel.parent, if (id == null) "empty" else "form")
        val config = id?.let { configs[environment]?.get(it) } ?: return
        loading = true
        try {
            useAsDefault.text = "Use for requests with \"Use environment authentication\" (in every environment)"
            useAsDefault.isSelected = id == defaultId
            grantType.selectedItem = config[OAuthKeys.GRANT_TYPE] as? String ?: OAuthKeys.AUTHORIZATION_CODE
            textFields.forEach { (key, field) -> field.text = config[key]?.toString() ?: "" }
            audience.text = ((config[OAuthKeys.CUSTOM_REQUEST_PARAMETERS] as? Map<*, *>)?.get(OAuthKeys.AUDIENCE))?.toString() ?: ""
            pkce.isSelected = config[OAuthKeys.PKCE].let { it != null && it != false }
            useIdToken.isSelected = config[OAuthKeys.USE_ID_TOKEN] == true
            acquireAutomatically.isSelected = config[OAuthKeys.ACQUIRE_AUTOMATICALLY] == true
            discoveryStatus.text = ""
        } finally {
            loading = false
        }
        updateFieldVisibility()
    }

    private fun updateFieldVisibility() {
        val grant = grantType.selectedItem as? String
        val used = OAuthKeys.fieldsFor(grant)
        OAuthKeys.TEXT_FIELDS.forEach { key ->
            val visible = key in used
            textFields.getValue(key).isVisible = visible
            textLabels.getValue(key).isVisible = visible
        }
        pkce.isVisible = OAuthKeys.supportsPkce(grant)
        formPanel.revalidate()
        formPanel.repaint()
    }

    /** Copies the form back into the working copy of the configuration that is shown. */
    private fun storeForm() {
        val env = shownEnv ?: return
        val id = shownId ?: return
        val config = configs[env]?.get(id) ?: return
        config[OAuthKeys.TYPE] = OAuthKeys.OAUTH2
        config[OAuthKeys.GRANT_TYPE] = grantType.selectedItem as? String
        textFields.forEach { (key, field) -> config[key] = field.text.trim() }
        val params = LinkedHashMap((config[OAuthKeys.CUSTOM_REQUEST_PARAMETERS] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap())
        if (audience.text.isBlank()) params.remove(OAuthKeys.AUDIENCE) else params[OAuthKeys.AUDIENCE] = audience.text.trim()
        config[OAuthKeys.CUSTOM_REQUEST_PARAMETERS] = params.ifEmpty { null }
        // Keep PKCE options someone wrote by hand; just switch it on or off.
        val existingPkce = config[OAuthKeys.PKCE]
        config[OAuthKeys.PKCE] = if (!pkce.isSelected) null else existingPkce.takeIf { it is Map<*, *> } ?: true
        config[OAuthKeys.USE_ID_TOKEN] = if (useIdToken.isSelected) true else null
        config[OAuthKeys.ACQUIRE_AUTOMATICALLY] = if (acquireAutomatically.isSelected) true else null
        if (useAsDefault.isSelected) defaultId = id
        configs[env]!![id] = LinkedHashMap(OAuthKeys.normalize(config))
        list.repaint()
        updateMissing()
    }

    private fun updateMissing() {
        val missing = envNames.filter { it != SHARED_ENV && configs[it]?.containsKey(defaultId) != true && configs[SHARED_ENV]?.containsKey(defaultId) != true }
        missingLabel.text = if (missing.isEmpty()) " " else
            "'$defaultId' (used by requests with environment authentication) is not configured for: ${missing.joinToString()}"
    }

    // ---- OpenID Connect discovery ---------------------------------------------------------------

    private fun discover() {
        val input = discoveryUrl.text.trim()
        if (input.isEmpty()) {
            discoveryStatus.text = "Enter the issuer URL, e.g. https://login.example.com/realms/main"
            return
        }
        val url = OidcDiscovery.documentUrl(input)
        val result = runCatching {
            ProgressManager.getInstance().runProcessWithProgressSynchronously<Map<String, String>, Exception>(
                { OidcDiscovery.endpoints(HttpRequests.request(url).accept("application/json").readString()) },
                "Reading OpenID Configuration", true, project,
            )
        }
        result.onSuccess { endpoints ->
            endpoints.forEach { (key, value) -> textFields[key]?.text = value }
            discoveryStatus.text = "Filled in from $url"
        }.onFailure {
            discoveryStatus.text = "Could not read $url: ${it.message ?: it.javaClass.simpleName}"
        }
    }

    // ---- saving -------------------------------------------------------------------------------

    override fun doOKAction() {
        storeForm()
        val changes = mutableListOf<HttpEnvironments.AuthChange>()
        for (env in envNames) {
            val before = original[env].orEmpty()
            val after = configs[env].orEmpty()
            (before.keys - after.keys).forEach { changes += HttpEnvironments.AuthChange(env, it, null) }
            after.forEach { (id, config) -> if (before[id] != config) changes += HttpEnvironments.AuthChange(env, id, config) }
        }
        HttpEnvironments.writeAuthConfigs(project, httpFile, changes)
        EnvironmentAuth.setId(project, defaultId)
        super.doOKAction()
    }
}
