package dev.jcake.httpgui.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.jcake.httpgui.core.HttpRequest
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel

/** What a card can ask of the notebook that holds it. */
interface RequestCardHost {
    val context: RequestEditorContext
    fun requestChanged(card: RequestCard, request: HttpRequest)
    fun showInText(card: RequestCard)
    fun duplicate(card: RequestCard)
    fun remove(card: RequestCard)
}

/**
 * One request in the designer's notebook: a header that summarizes the request and a form that is
 * created the first time the card is expanded.
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

    private var form: RequestEditorPanel? = null

    private val chevron = JBLabel()
    private val methodLabel = JBLabel().apply { font = font.deriveFont(Font.BOLD) }
    private val nameLabel = JBLabel()
    private val urlLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }

    init {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(2),
        )
        val summary = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply {
            isOpaque = false
            add(chevron)
            add(methodLabel)
            add(nameLabel)
            add(urlLabel)
        }
        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), JBUI.scale(4))).apply {
            isOpaque = false
            add(InplaceButton("Show in text editor", AllIcons.Actions.EditSource) { host.showInText(this@RequestCard) })
            add(InplaceButton("Duplicate request", AllIcons.Actions.Copy) { host.duplicate(this@RequestCard) })
            add(InplaceButton("Delete request", AllIcons.Actions.GC) { host.remove(this@RequestCard) })
        }
        val header = JPanel(BorderLayout()).apply {
            background = JBColor.namedColor("EditorTabs.background", UIUtil.getPanelBackground())
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Click to expand or collapse"
            add(summary, BorderLayout.CENTER)
            add(actions, BorderLayout.EAST)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = setExpanded(!isExpanded)
            })
        }
        add(header, BorderLayout.NORTH)
        updateHeader()
        setExpanded(expanded)
    }

    fun setExpanded(value: Boolean) {
        isExpanded = value
        chevron.icon = if (value) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
        if (value && form == null) {
            form = RequestEditorPanel(host.context) { changed ->
                request = changed
                updateHeader()
                host.requestChanged(this, changed)
            }.also {
                it.setRequest(request)
                add(it, BorderLayout.CENTER)
            }
        }
        form?.isVisible = value
        revalidate()
        repaint()
    }

    /**
     * Points the card at a (re-)parsed request. With [pushToForm] the form shows [request] too; that
     * is skipped for the card's own writes, so normalization by the serializer never moves the caret.
     */
    fun update(blockIndex: Int, request: HttpRequest, pushToForm: Boolean) {
        this.blockIndex = blockIndex
        val formRequest = form?.request()
        this.request = if (pushToForm || formRequest == null) request else formRequest
        updateHeader()
        if (pushToForm && formRequest != request) form?.setRequest(request)
    }

    fun refreshEnvironment() {
        form?.refreshEnvironment()
    }

    private fun updateHeader() {
        val r = request
        methodLabel.text = r.method
        methodLabel.foreground = methodColor(r.method)
        nameLabel.text = r.name ?: r.title?.takeIf { it.isNotBlank() } ?: ""
        nameLabel.isVisible = nameLabel.text.isNotEmpty()
        urlLabel.text = r.url.ifBlank { "<no url>" }.let { if (it.length > 90) it.take(90) + "…" else it }
    }

    companion object {
        fun methodColor(method: String): Color = when (method.uppercase()) {
            "GET" -> JBUI.CurrentTheme.Link.Foreground.ENABLED
            "POST" -> JBColor(0x2E7D32, 0x7CC47F)
            "PUT", "PATCH" -> JBColor(0xB26A00, 0xE0A458)
            "DELETE" -> JBColor(0xC62828, 0xE57373)
            else -> JBColor(0x6A1B9A, 0xB39DDB)
        }
    }
}
