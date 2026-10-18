package dev.jcake.httpgui.ui

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.WrapLayout
import dev.jcake.httpgui.core.ApiRoute
import dev.jcake.httpgui.core.AuthCodec
import dev.jcake.httpgui.core.EnvironmentSet
import dev.jcake.httpgui.core.HttpHeader
import dev.jcake.httpgui.core.HttpRequest
import dev.jcake.httpgui.core.QueryParam
import dev.jcake.httpgui.core.STANDARD_METHODS
import dev.jcake.httpgui.core.Script
import dev.jcake.httpgui.core.Urls
import dev.jcake.httpgui.core.VariableInfo
import dev.jcake.httpgui.core.Variables
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ButtonGroup
import javax.swing.JToggleButton
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** What the request form needs from its host editor. */
interface RequestEditorContext {
    val project: Project
    fun variables(): List<VariableInfo>
    fun environments(): EnvironmentSet
    fun selectedEnvironment(): String?
    fun resolvedValues(): Map<String, String?>
    fun saveOAuthConfig(environment: String, id: String, publicValues: Map<String, Any?>, privateValues: Map<String, Any?>)
    /** Routes from the Endpoints view and OpenAPI files, as last collected. */
    fun routes(): List<ApiRoute>
    fun baseUrlFor(route: ApiRoute): String
}

/** Form editing a single [HttpRequest]; every change is reported through [onChanged]. */
class RequestEditorPanel(
    private val ctx: RequestEditorContext,
    private val onChanged: (HttpRequest) -> Unit,
) : JPanel(CardLayout()) {

    private val project: Project = ctx.project
    private var current: HttpRequest? = null
    private var updating = false

    // ---- request line -------------------------------------------------------------------------

    private val methodCombo = ComboBox(STANDARD_METHODS.toTypedArray()).apply {
        isEditable = true
        toolTipText = "HTTP method — pick one or type your own"
        prototypeDisplayValue = "OPTIONS__"
        addActionListener { onMethodEdited() }
        (editor.editorComponent as? JTextField)?.document?.addDocumentListener(swingListener { onMethodEdited() })
    }
    private val urlField = UrlTextField(project, ctx::variables, ::routeSuggestions, ::applyRoute) { text ->
        update(URL) { it.copy(url = text.replace("\n", "")) }
    }.apply {
        toolTipText = "Type a path or press Ctrl+Space for OpenAPI routes; type {{ for variables"
    }
    private val resolvedLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }

    // ---- identity -----------------------------------------------------------------------------

    private val titleField = JBTextField().apply {
        emptyText.text = "Shown after ### in the file"
        document.addDocumentListener(swingListener { update(TITLE) { it.copy(title = text.ifBlank { null }) } })
    }
    private val nameField = ExtendableTextField().apply {
        toolTipText = "# @name — lets other requests reference this one's response"
        document.addDocumentListener(swingListener {
            val value = text.trim()
            update(NAME) { it.withTag("name", value, present = value.isNotEmpty()) }
        })
    }

    // ---- tabs ---------------------------------------------------------------------------------

    private val paramsEditor = KeyValueEditor(project, ctx::variables) { entries ->
        update(PARAMS) { r ->
            val (base, old) = Urls.split(r.url)
            // Keep value-less parameters (`?flag`) value-less unless a value is typed.
            val params = entries.map { (k, v) -> QueryParam(k, if (v.isEmpty() && old.any { it.name == k && it.value == null }) null else v) }
            r.copy(url = Urls.join(base, params))
        }
    }
    private val headersEditor = KeyValueEditor(project, ctx::variables, COMMON_HEADERS) { entries ->
        update(HEADERS) { it.copy(headers = entries.map { (k, v) -> HttpHeader(k, v) }) }
    }

    private val contentType = ComboBox(CONTENT_TYPES.toTypedArray()).apply {
        isEditable = true
        renderer = SimpleListCellRenderer.create<String>("") { it.ifEmpty { "(none)" } }
        addActionListener { onContentTypeEdited() }
    }
    private var bodyFileType = CodeEditorField.fileTypeFor("json")
    private val bodyEditor = CodeEditorField(project, bodyFileType, { text -> update(BODY) { it.copy(body = text) } })

    private val authPanel = AuthPanel(
        project, ctx::variables, ctx::environments, ctx::selectedEnvironment, ctx::saveOAuthConfig,
    ) { auth -> update(AUTH) { AuthCodec.apply(it, auth) } }

    private val testsPanel = TestsPanel(project) { handler -> update(TESTS) { it.copy(responseHandler = handler) } }

    private val preScriptLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val preScriptEditor = CodeEditorField(project, CodeEditorField.fileTypeFor("js"), { text ->
        update(PRE_SCRIPT) { it.copy(preRequestScript = if (text.isBlank()) null else Script.Inline(text)) }
    })

    private val flagBoxes = FLAG_TAGS.associateWith { (tag, label) ->
        JBCheckBox("$label  (@$tag)").apply { addActionListener { update(OPTIONS) { r -> r.withTag(tag, null, present = isSelected) } } }
    }.mapKeys { it.key.first }
    private val valueTags = VALUE_TAGS.associate { (tag, _) ->
        tag to JBTextField(8).apply {
            document.addDocumentListener(swingListener {
                val v = text.trim()
                update(OPTIONS) { r -> r.withTag(tag, v, present = v.isNotEmpty()) }
            })
        }
    }
    private val httpVersion = ComboBox(arrayOf("", "HTTP/1.1", "HTTP/2")).apply {
        renderer = SimpleListCellRenderer.create<String>("") { it.ifEmpty { "Default" } }
        addActionListener { update(OPTIONS) { r -> r.copy(httpVersion = (selectedItem as? String)?.ifEmpty { null }) } }
    }

    /** Section switcher: a wrapping strip of toggle buttons (stays readable when the designer is narrow). */
    private inner class Sections : JPanel(BorderLayout()) {
        private val cards = CardLayout()
        private val content = JPanel(cards)
        private val strip = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(2), JBUI.scale(2)))
        private val group = ButtonGroup()

        init {
            add(strip, BorderLayout.NORTH)
            add(content, BorderLayout.CENTER)
        }

        fun addTab(title: String, component: JComponent) {
            content.add(component, title)
            val button = JToggleButton(title).apply {
                isSelected = group.buttonCount == 0
                addActionListener {
                    cards.show(content, title)
                    updateStyles()
                }
            }
            group.add(button)
            strip.add(button)
            updateStyles()
        }

        /** Selected toggle buttons barely differ in the IntelliJ themes; bold makes the current section obvious. */
        private fun updateStyles() {
            group.elements.asSequence().forEach { it.font = it.font.deriveFont(if (it.isSelected) Font.BOLD else Font.PLAIN) }
        }
    }

    private val tabs = Sections()

    init {
        add(JBLabel("Select a request on the left, or add a new one.", UIUtil.ComponentStyle.LARGE).apply {
            horizontalAlignment = JBLabel.CENTER
            foreground = UIUtil.getContextHelpForeground()
        }, EMPTY_CARD)
        add(buildEditor(), EDITOR_CARD)
        showCard(EMPTY_CARD)
    }

    private fun buildEditor(): JComponent {
        val requestLine = JPanel(GridBagLayout()).apply {
            val c = GridBagConstraints().apply { insets = JBUI.insets(2); fill = GridBagConstraints.HORIZONTAL; gridy = 0 }
            c.gridx = 0; c.weightx = 0.0
            add(methodCombo, c)
            c.gridx = 1; c.weightx = 1.0
            add(urlField, c)
            c.gridy = 1; c.gridx = 1
            add(resolvedLabel, c)
        }
        val identity = JPanel(GridBagLayout()).apply {
            val c = GridBagConstraints().apply { insets = JBUI.insets(2); fill = GridBagConstraints.HORIZONTAL; gridy = 0 }
            c.gridx = 0; add(JBLabel("Title:"), c)
            c.gridx = 1; c.weightx = 0.6; add(titleField, c)
            c.gridx = 2; c.weightx = 0.0; add(JBLabel("Name:"), c)
            c.gridx = 3; c.weightx = 0.4; add(nameField, c)
        }

        val bodyTab = JPanel(BorderLayout()).apply {
            val bar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
                add(JBLabel("Content-Type:"))
                add(contentType)
                add(JButton("Insert Variable…").apply { addActionListener { insertVariable(this) } })
            }
            add(bar, BorderLayout.NORTH)
            add(bodyEditor, BorderLayout.CENTER)
        }

        val preTab = JPanel(BorderLayout()).apply {
            add(JBLabel("Runs before the request is sent (< {% … %}). Use request.variables.set(name, value).").apply {
                foreground = UIUtil.getContextHelpForeground(); border = JBUI.Borders.empty(4, 0)
            }, BorderLayout.NORTH)
            add(preScriptEditor, BorderLayout.CENTER)
            add(preScriptLabel, BorderLayout.SOUTH)
        }

        val options = FormBuilder.createFormBuilder().apply {
            flagBoxes.values.forEach { addComponent(it) }
            VALUE_TAGS.forEach { (tag, label) -> addLabeledComponent("$label (@$tag):", valueTags.getValue(tag)) }
            addLabeledComponent("HTTP version:", httpVersion)
        }.panel
        val optionsTab = JPanel(BorderLayout()).apply { add(options, BorderLayout.NORTH); border = JBUI.Borders.empty(8) }

        tabs.addTab("Params", paramsEditor.withBorder())
        tabs.addTab("Headers", headersEditor.withBorder())
        tabs.addTab("Body", bodyTab)
        tabs.addTab("Auth", authPanel.withBorder())
        tabs.addTab("Tests", testsPanel)
        tabs.addTab("Pre-request", preTab)
        tabs.addTab("Options", optionsTab)

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(JPanel(BorderLayout()).apply {
                add(identity, BorderLayout.NORTH)
                add(requestLine, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            // Fixed height so the form can live in the designer's scrolling column of requests.
            add(object : JPanel(BorderLayout()) {
                override fun getPreferredSize(): Dimension = Dimension(super.getPreferredSize().width, JBUI.scale(TABS_HEIGHT))
            }.apply { add(tabs, BorderLayout.CENTER) }, BorderLayout.CENTER)
        }
    }

    private fun JComponent.withBorder(): JComponent = apply { border = JBUI.Borders.empty(8) }

    private fun showCard(name: String) = (layout as CardLayout).show(this, name)

    // ---- state --------------------------------------------------------------------------------

    fun request(): HttpRequest? = current

    /** Shows [request]; components already showing the same values are left alone. */
    fun setRequest(request: HttpRequest?) {
        current = request
        if (request == null) {
            showCard(EMPTY_CARD)
            return
        }
        showCard(EDITOR_CARD)
        refresh(request, null)
    }

    /** Re-evaluates variable-dependent parts (environment switched, env files changed). */
    fun refreshEnvironment() {
        current?.let { updateResolved(it) }
        authPanel.refreshOAuthChoices()
    }

    private fun update(source: String, transform: (HttpRequest) -> HttpRequest) {
        if (updating) return
        val before = current ?: return
        val after = transform(before)
        if (after == before) return
        current = after
        refresh(after, source)
        onChanged(after)
    }

    private fun refresh(r: HttpRequest, source: String?) {
        updating = true
        try {
            if (source != METHOD && methodText() != r.method) methodCombo.selectedItem = r.method
            if (source != URL) urlField.setTextSilently(r.url)
            if (source != TITLE && titleField.text != (r.title ?: "")) titleField.text = r.title ?: ""
            if (source != NAME && nameField.text.trim() != (r.name ?: "")) nameField.text = r.name ?: ""
            if (source != PARAMS) {
                val params = Urls.split(r.url).second.map { it.name to (it.value ?: "") }
                val shown = paramsEditor.entries().filter { it.first.isNotEmpty() }
                if (shown != params) paramsEditor.setEntries(params)
            }
            if (source != HEADERS) {
                val headers = r.headers.map { it.name to it.value }
                val shown = headersEditor.entries().filter { it.first.isNotBlank() }
                if (shown != headers) headersEditor.setEntries(headers)
            }
            val ct = r.header("Content-Type")?.value ?: ""
            if (source != CONTENT_TYPE && (contentType.editor.item?.toString() ?: "") != ct) contentType.selectedItem = ct
            val bodyType = CodeEditorField.fileTypeForContentType(ct.ifEmpty { null } ?: guessContentType(r.body))
            if (bodyFileType != bodyType) {
                bodyFileType = bodyType
                bodyEditor.setFileType(bodyType)
            }
            if (source != BODY) bodyEditor.setTextSilently(r.body)
            if (source != AUTH) authPanel.setRequest(r)
            if (source != TESTS) testsPanel.setHandler(r.responseHandler)
            if (source != PRE_SCRIPT) {
                when (val pre = r.preRequestScript) {
                    is Script.FileRef -> {
                        preScriptEditor.setTextSilently("")
                        preScriptEditor.isEnabled = false
                        preScriptLabel.text = "Pre-request script is the external file '${pre.path}'."
                    }
                    else -> {
                        preScriptEditor.isEnabled = true
                        preScriptLabel.text = ""
                        preScriptEditor.setTextSilently((pre as? Script.Inline)?.code ?: "")
                    }
                }
            }
            if (source != OPTIONS) {
                flagBoxes.forEach { (tag, box) -> box.isSelected = r.tag(tag) != null }
                valueTags.forEach { (tag, field) ->
                    val v = r.tag(tag)?.value ?: ""
                    if (field.text.trim() != v) field.text = v
                }
                httpVersion.selectedItem = r.httpVersion ?: ""
            }
            updateResolved(r)
        } finally {
            updating = false
        }
    }

    private fun updateResolved(r: HttpRequest) {
        val refs = Variables.references(r.url)
        if (refs.isEmpty()) {
            resolvedLabel.text = " "
            return
        }
        val values = ctx.resolvedValues()
        val resolved = Variables.resolve(r.url, values)
        val missing = refs.filter { it !in values && !it.startsWith("$") }
        val env = ctx.selectedEnvironment()?.let { "[$it] " } ?: "[no environment] "
        resolvedLabel.text = env + resolved + if (missing.isNotEmpty()) "   — undefined: ${missing.joinToString()}" else ""
    }

    private fun guessContentType(body: String): String? = when {
        body.trimStart().startsWith("{") || body.trimStart().startsWith("[") -> "application/json"
        body.trimStart().startsWith("<") -> "application/xml"
        else -> null
    }

    private fun methodText(): String =
        ((methodCombo.editor.editorComponent as? JTextField)?.text ?: methodCombo.selectedItem?.toString() ?: "").trim()

    private fun onMethodEdited() {
        val method = methodText().uppercase().filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        if (method.isEmpty()) return
        update(METHOD) { it.copy(method = method) }
    }

    private fun onContentTypeEdited() {
        val value = (contentType.editor.item ?: contentType.selectedItem)?.toString()?.trim() ?: ""
        update(CONTENT_TYPE) { it.withHeader("Content-Type", value.ifEmpty { null }) }
    }

    /**
     * The same route often comes from both an OpenAPI file and the Endpoints view (with the server
     * path folded into the path); keep one per method and URL, preferring the one with a summary.
     */
    private fun routeSuggestions(): List<UrlSuggestion.Route> = ctx.routes()
        .map { route -> UrlSuggestion.Route(route, Urls.split(route.toRequest(ctx.baseUrlFor(route)).url).first) }
        .sortedBy { it.route.summary == null }
        .distinctBy { it.route.method + " " + it.url }

    private fun applyRoute(route: ApiRoute) {
        val base = ctx.baseUrlFor(route)
        val prefilled = route.toRequest(base)
        update(ROUTE) { r ->
            val headers = prefilled.headers.fold(r) { acc, h -> if (acc.header(h.name) == null) acc.withHeader(h.name, h.value) else acc }
            headers.copy(
                method = prefilled.method,
                url = prefilled.url,
                body = r.body.ifBlank { prefilled.body },
                title = r.title ?: prefilled.title,
            ).let { if (it.name == null && prefilled.name != null) it.withTag("name", prefilled.name) else it }
        }
    }

    private fun insertVariable(anchor: JComponent) {
        val items = ctx.variables()
        if (items.isEmpty()) return
        JBPopupFactory.getInstance().createPopupChooserBuilder(items)
            .setTitle("Insert Variable")
            .setRenderer(SimpleListCellRenderer.create<VariableInfo>("") { v -> v.name + (v.value?.let { "  = $it" } ?: "") })
            .setNamerForFiltering { it.name }
            .setItemChosenCallback { v ->
                val editor = bodyEditor.editor ?: return@setItemChosenCallback
                WriteCommandAction.runWriteCommandAction(project, "Insert Variable", null, Runnable {
                    editor.document.insertString(editor.caretModel.offset, "{{${v.name}}}")
                })
            }
            .createPopup()
            .showUnderneathOf(anchor)
    }

    private fun swingListener(action: () -> Unit) = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = action()
        override fun removeUpdate(e: DocumentEvent) = action()
        override fun changedUpdate(e: DocumentEvent) = action()
    }

    companion object {
        private const val TABS_HEIGHT = 420
        private const val EMPTY_CARD = "empty"
        private const val EDITOR_CARD = "editor"

        private const val METHOD = "method"
        private const val URL = "url"
        private const val TITLE = "title"
        private const val NAME = "name"
        private const val PARAMS = "params"
        private const val HEADERS = "headers"
        private const val CONTENT_TYPE = "contentType"
        private const val BODY = "body"
        private const val AUTH = "auth"
        private const val TESTS = "tests"
        private const val PRE_SCRIPT = "preScript"
        private const val OPTIONS = "options"
        private const val ROUTE = "route"

        val CONTENT_TYPES = listOf(
            "", "application/json", "application/x-www-form-urlencoded", "multipart/form-data; boundary=WebAppBoundary",
            "application/xml", "text/plain", "text/html", "application/graphql",
        )

        val COMMON_HEADERS = listOf(
            "Accept", "Accept-Encoding", "Accept-Language", "Authorization", "Cache-Control", "Connection", "Content-Type",
            "Content-Length", "Cookie", "Host", "If-Match", "If-None-Match", "If-Modified-Since", "Origin", "Referer",
            "User-Agent", "X-API-Key", "X-Request-ID", "X-Correlation-ID", "X-Forwarded-For",
        )

        /** Boolean request tags supported by the HTTP Client. */
        val FLAG_TAGS = listOf(
            "no-redirect" to "Don't follow redirects",
            "no-cookie-jar" to "Don't use the cookie jar",
            "no-log" to "Don't save to request history",
            "no-auto-encoding" to "Don't URL-encode automatically",
        )

        val VALUE_TAGS = listOf(
            "timeout" to "Read timeout",
            "connection-timeout" to "Connection timeout",
        )
    }
}
