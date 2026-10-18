package dev.jcake.httpgui.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import dev.jcake.httpgui.core.HttpRequest
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.border.Border

/** What a request section can ask of the designer that holds it. */
interface RequestCardHost {
    val context: RequestEditorContext
    val disposable: Disposable
    fun requestChanged(card: RequestCard, request: HttpRequest)
    fun insertBelow(card: RequestCard)
    fun showInText(card: RequestCard)
    fun duplicate(card: RequestCard)
    fun remove(card: RequestCard)
}

/**
 * One request in the designer: a collapsible titled separator (as in Settings pages) with the
 * request's actions in a toolbar on the right, and the request form below it. The form is created
 * the first time the section is expanded.
 */
class RequestCard(
    private val host: RequestCardHost,
    blockIndex: Int,
    request: HttpRequest,
    expanded: Boolean,
) : JPanel(BorderLayout()) {

    /** Index of the request's block in the parsed file; updated whenever the file is re-parsed. */
    var blockIndex: Int = blockIndex
        private set

    var request: HttpRequest = request
        private set

    var isExpanded: Boolean = false
        private set

    /** Owns the form's tabs; disposed when the request disappears from the file. */
    val disposable: Disposable = Disposer.newDisposable(host.disposable, "HTTP request section")

    private var form: RequestEditorPanel? = null
    private lateinit var header: JPanel

    /** The separator line above the title; hidden for the first request, which sits under the toolbar. */
    var showSeparator: Boolean = true
        set(value) {
            field = value
            header.border = headerBorder()
        }
    private val title = SimpleColoredComponent().apply {
        isOpaque = false
        ipad = JBUI.insets(0)
        iconTextGap = JBUI.scale(4)
    }

    init {
        isOpaque = false
        val actions = DefaultActionGroup(
            DumbAwareAction.create("Add Request Below", AllIcons.General.Add) { host.insertBelow(this) },
            DumbAwareAction.create("Duplicate Request", AllIcons.Actions.Copy) { host.duplicate(this) },
            DumbAwareAction.create("Show in Text Editor", AllIcons.Actions.EditSource) { host.showInText(this) },
            DumbAwareAction.create("Delete Request", AllIcons.Actions.GC) { host.remove(this) },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, actions, true).apply {
            targetComponent = this@RequestCard
            component.isOpaque = false
            component.border = JBUI.Borders.empty()
        }
        header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = headerBorder()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            add(title, BorderLayout.CENTER)
            add(toolbar.component, BorderLayout.EAST)
        }
        val toggle = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = setExpanded(!isExpanded)
        }
        header.addMouseListener(toggle)
        title.addMouseListener(toggle)
        add(header, BorderLayout.NORTH)
        updateHeader()
        setExpanded(expanded)
    }

    // A separator line above each request, like the groups of a Settings page.
    private fun headerBorder(): Border? = JBUI.Borders.compound(
        if (showSeparator) JBUI.Borders.customLineTop(JBColor.border()) else JBUI.Borders.empty(),
        JBUI.Borders.empty(4, 0, 2, 0),
    )

    fun setExpanded(value: Boolean) {
        isExpanded = value
        if (value && form == null) {
            form = RequestEditorPanel(host.context, disposable, { pre -> host.context.scriptContext(this.blockIndex, pre) }) { changed ->
                request = changed
                updateHeader()
                host.requestChanged(this, changed)
            }.also {
                // Indented to line up with the title text after the chevron.
                it.border = JBUI.Borders.empty(4, INDENT, 16, 0)
                it.setRequest(request)
                add(it, BorderLayout.CENTER)
            }
        }
        form?.isVisible = value
        updateHeader()
        revalidate()
        repaint()
    }

    /**
     * Points the section at a (re-)parsed request. With [pushToForm] the form shows [request] too; that
     * is skipped for the section's own writes, so normalization by the serializer never moves the caret.
     */
    fun update(blockIndex: Int, request: HttpRequest, pushToForm: Boolean) {
        this.blockIndex = blockIndex
        val formRequest = form?.request()
        this.request = if (pushToForm || formRequest == null) request else formRequest
        updateHeader()
        if (pushToForm && formRequest != request) form?.setRequest(request) else form?.refreshScriptContext()
    }

    fun refreshScriptContext() {
        form?.refreshScriptContext()
    }

    fun refreshEnvironment() {
        form?.refreshEnvironment()
    }

    private fun updateHeader() {
        val r = request
        title.clear()
        title.icon = if (isExpanded) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
        title.append(r.method, SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, methodColor(r.method)))
        val name = r.name ?: r.title?.takeIf { it.isNotBlank() }
        if (name != null) title.append("  $name", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        val url = r.url.ifBlank { "<no url>" }.let { if (it.length > 100) it.take(100) + "…" else it }
        title.append("  $url", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        title.toolTipText = if (isExpanded) "Click to collapse" else "Click to expand"
        title.repaint()
    }

    companion object {
        private const val TOOLBAR_PLACE = "HttpRequestDesigner.Request"
        private val INDENT = JBUI.scale(20)

        /** Method colors in the spirit of the HTTP Client and Endpoints views. */
        fun methodColor(method: String): Color = when (method.uppercase()) {
            "GET" -> JBColor(0x2F6FDB, 0x5C9DFF)
            "POST" -> JBColor(0x1E8A3E, 0x5FB865)
            "PUT", "PATCH" -> JBColor(0xA6610A, 0xD9A343)
            "DELETE" -> JBColor(0xC2302C, 0xE0625E)
            else -> JBColor(0x7A3E9D, 0xB48EAD)
        }
    }
}
