package dev.jcake.httpgui

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.jcake.httpgui.core.ApiRoute
import dev.jcake.httpgui.core.DYNAMIC_VARIABLES
import dev.jcake.httpgui.core.EnvironmentSet
import dev.jcake.httpgui.core.HttpFileModel
import dev.jcake.httpgui.core.HttpFileParser
import dev.jcake.httpgui.core.HttpHeader
import dev.jcake.httpgui.core.HttpRequest
import dev.jcake.httpgui.core.HttpSerializer
import dev.jcake.httpgui.core.Script
import dev.jcake.httpgui.core.ScriptTemplates
import dev.jcake.httpgui.core.VariableInfo
import dev.jcake.httpgui.core.VariableSource
import dev.jcake.httpgui.services.HttpEnvironments
import dev.jcake.httpgui.services.OpenApiRoutes
import dev.jcake.httpgui.ui.RequestCard
import dev.jcake.httpgui.ui.RequestCardHost
import dev.jcake.httpgui.ui.RequestEditorContext
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.LayoutFocusTraversalPolicy
import javax.swing.Scrollable
import javax.swing.SwingUtilities

/**
 * Graphical editor for an `.http` file, laid out like a notebook: every request is a collapsible
 * section in one scrolling column. It works on the same [Document] as the text editor: GUI edits rewrite only the
 * affected request block, and document changes from any other source (the text editor, VCS,
 * external tools) are re-parsed and shown live.
 */
class HttpGuiFileEditor(
    override val project: Project,
    private val httpFile: VirtualFile,
) : UserDataHolderBase(), FileEditor, RequestEditorContext, RequestCardHost {

    private val document: Document = FileDocumentManager.getInstance().getDocument(httpFile)
        ?: error("No document for ${httpFile.path}")
    private var model: HttpFileModel = HttpFileParser.parse(document.text)
    private var environments: EnvironmentSet = EnvironmentSet.EMPTY
    private var selectedEnv: String? = PropertiesComponent.getInstance(project).getValue(ENV_KEY)
    @Volatile
    private var routes: List<ApiRoute> = emptyList()

    private var ownChange = false
    private var pendingWrite: Pair<Int, HttpRequest>? = null

    private val reloadAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val writeAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val envAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val routesAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    private val cards = mutableListOf<RequestCard>()
    private val column = ColumnPanel()
    private val root: JComponent

    override val context: RequestEditorContext get() = this
    override val disposable: Disposable get() = this

    init {
        val actions = DefaultActionGroup(
            EnvironmentAction(),
            Separator.getInstance(),
            DumbAwareAction.create("Add Request", AllIcons.General.Add) { insertRequest(cards.size, newRequest()) },
            DumbAwareAction.create("Expand All", AllIcons.Actions.Expandall) { cards.forEach { it.setExpanded(true) } },
            DumbAwareAction.create("Collapse All", AllIcons.Actions.Collapseall) { cards.forEach { it.setExpanded(false) } },
            Separator.getInstance(),
            DumbAwareAction.create("Reload File, Environments and Routes", AllIcons.Actions.Refresh) {
                reloadNow(pushToForms = true)
                reloadEnvironments()
                refreshRoutes()
            },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, actions, true).apply {
            targetComponent = column
            component.border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(1, 4))
        }

        val scrollPane = JBScrollPane(column).apply {
            border = JBUI.Borders.empty()
            verticalScrollBar.unitIncrement = JBUI.scale(16)
        }
        root = JPanel(BorderLayout()).apply {
            // Let the split editor's divider decide the width instead of the toolbar's preferred size.
            minimumSize = Dimension(JBUI.scale(200), 0)
            // Tab / Shift+Tab cycle through the designer's fields instead of escaping into the text editor.
            isFocusCycleRoot = true
            focusTraversalPolicy = LayoutFocusTraversalPolicy()
            add(toolbar.component, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
        }

        // Keep the focused field visible while tabbing through a long file.
        val focusListener = PropertyChangeListener { event ->
            val owner = event.newValue as? Component ?: return@PropertyChangeListener
            if (!SwingUtilities.isDescendingFrom(owner, column)) return@PropertyChangeListener
            val bounds = SwingUtilities.convertRectangle(owner.parent, owner.bounds, column)
            column.scrollRectToVisible(bounds)
        }
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        focusManager.addPropertyChangeListener("focusOwner", focusListener)
        Disposer.register(this) { focusManager.removePropertyChangeListener("focusOwner", focusListener) }

        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (!ownChange) scheduleReload()
            }
        }, this)

        // Environment and OpenAPI files: react to saved changes and to unsaved edits in open editors.
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val names = events.map { it.path.substringAfterLast('/') }
                if (names.any { it in HttpEnvironments.FILE_NAMES }) scheduleEnvReload()
                if (names.any { it.substringAfterLast('.').lowercase() in SPEC_EXTENSIONS }) scheduleRoutesRefresh()
            }
        })
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val name = FileDocumentManager.getInstance().getFile(event.document)?.name ?: return
                if (name in HttpEnvironments.FILE_NAMES) scheduleEnvReload()
                else if (name.substringAfterLast('.').lowercase() in SPEC_EXTENSIONS) scheduleRoutesRefresh()
            }
        }, this)

        syncCards(pushToForms = true)
        reloadEnvironments()
        refreshRoutes()
    }

    // ---- document -> GUI ----------------------------------------------------------------------

    private fun scheduleReload() {
        reloadAlarm.cancelAllRequests()
        reloadAlarm.addRequest({ reloadNow(pushToForms = true) }, RELOAD_DELAY_MS)
    }

    private fun reloadNow(pushToForms: Boolean) {
        flushWrite()
        model = HttpFileParser.parse(document.text)
        syncCards(pushToForms)
    }

    /**
     * Makes the cards match [model]. Existing cards are reused by position, so focus and expansion
     * survive edits; cards are only added or removed when the number of requests changes.
     */
    private fun syncCards(pushToForms: Boolean) {
        val requests = model.blocks.mapIndexedNotNull { i, b -> b.request?.let { i to it } }
        var structureChanged = false
        while (cards.size > requests.size) {
            Disposer.dispose(cards.removeAt(cards.lastIndex).disposable)
            structureChanged = true
        }
        requests.forEachIndexed { position, (blockIndex, request) ->
            if (position < cards.size) {
                cards[position].update(blockIndex, request, pushToForms)
            } else {
                val expanded = requests.size <= EXPAND_ALL_LIMIT || position == 0
                cards += RequestCard(this, blockIndex, request, expanded)
                structureChanged = true
            }
        }
        if (structureChanged || column.componentCount == 0) rebuildColumn()
    }

    private fun rebuildColumn() {
        column.removeAll()
        if (cards.isEmpty()) {
            column.add(JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(4), 0)).apply {
                isOpaque = false
                border = JBUI.Borders.empty(24)
                add(JBLabel("No requests yet.").apply { foreground = UIUtil.getContextHelpForeground() })
                add(ActionLink("Add request") { insertRequest(0, newRequest()) })
            })
        }
        cards.forEachIndexed { index, card ->
            card.showSeparator = index > 0
            column.add(card)
        }
        column.revalidate()
        column.repaint()
    }

    // ---- GUI -> document ----------------------------------------------------------------------

    override fun requestChanged(card: RequestCard, request: HttpRequest) {
        pendingWrite?.let { (index, _) -> if (index != card.blockIndex) flushWrite() }
        pendingWrite = card.blockIndex to request
        writeAlarm.cancelAllRequests()
        writeAlarm.addRequest({ flushWrite() }, WRITE_DELAY_MS)
    }

    private fun flushWrite() {
        val (blockIndex, request) = pendingWrite ?: return
        pendingWrite = null
        writeAlarm.cancelAllRequests()
        val fresh = HttpFileParser.parse(document.text)
        val block = fresh.blocks.getOrNull(blockIndex)
        if (block?.request != null) {
            val text = HttpSerializer.serializeBlock(request, block, blockIndex == fresh.blocks.lastIndex)
            if (text != block.text) writeDocument("Edit HTTP Request") { document.replaceString(block.startOffset, block.endOffset, text) }
        }
        model = HttpFileParser.parse(document.text)
        syncCards(pushToForms = false)
    }

    private fun writeDocument(name: String, action: () -> Unit): Boolean {
        if (!FileDocumentManager.getInstance().requestWriting(document, project)) return false
        ownChange = true
        try {
            WriteCommandAction.runWriteCommandAction(project, name, COMMAND_GROUP, Runnable { action() })
        } finally {
            ownChange = false
        }
        return true
    }

    /** Inserts [request] so that it becomes the request at [position] (or the last one). */
    private fun insertRequest(position: Int, request: HttpRequest) {
        flushWrite()
        val serialized = HttpSerializer.serialize(request.copy(hasSeparator = true))
        val target = cards.getOrNull(position)?.let { model.blocks.getOrNull(it.blockIndex) }
        val existing = target?.request
        val ok = if (target == null || existing == null) {
            val text = document.text
            val prefix = when {
                text.isBlank() -> ""
                text.endsWith("\n\n") -> ""
                text.endsWith("\n") -> "\n"
                else -> "\n\n"
            }
            // A blank document is replaced entirely; otherwise the request is appended.
            val start = if (text.isBlank()) 0 else text.length
            writeDocument("Add HTTP Request") { document.replaceString(start, text.length, prefix + serialized + "\n") }
        } else {
            // A request at the very top may lack a ### separator; it needs one once something precedes it.
            val following = if (existing.hasSeparator) target.text else {
                HttpSerializer.serializeBlock(existing.copy(hasSeparator = true), target, target == model.blocks.last())
            }
            writeDocument("Add HTTP Request") { document.replaceString(target.startOffset, target.endOffset, serialized + "\n\n" + following) }
        }
        if (!ok) return
        model = HttpFileParser.parse(document.text)
        // Give the new request its own card so the neighbours keep their forms and expansion state.
        val at = position.coerceIn(0, cards.size)
        cards.add(at, RequestCard(this, 0, request, expanded = true))
        syncCards(pushToForms = true)
        rebuildColumn()
        val card = cards[at.coerceAtMost(cards.lastIndex)]
        column.validate()
        column.scrollRectToVisible(card.bounds)
    }

    override fun insertBelow(card: RequestCard) {
        insertRequest(cards.indexOf(card) + 1, newRequest())
    }

    override fun duplicate(card: RequestCard) {
        val position = cards.indexOf(card)
        val r = card.request
        insertRequest(position + 1, r.copy(hasSeparator = true, title = r.title?.let { "$it (copy)" }, tags = r.tags.filterNot { it.name == "name" }))
    }

    override fun remove(card: RequestCard) {
        flushWrite()
        val block = model.blocks.getOrNull(card.blockIndex) ?: return
        val request = block.request ?: return
        // A request at the top of the file may share its block with file-level variables; keep those.
        val replacement = if (!request.hasSeparator && request.prelude.isNotEmpty()) {
            request.prelude.joinToString("\n") + block.trailingWhitespace
        } else {
            ""
        }
        if (!writeDocument("Remove HTTP Request") { document.replaceString(block.startOffset, block.endOffset, replacement) }) return
        model = HttpFileParser.parse(document.text)
        // Drop exactly this section so the others keep their forms and expansion state.
        cards.remove(card)
        Disposer.dispose(card.disposable)
        syncCards(pushToForms = true)
        rebuildColumn()
    }

    override fun showInText(card: RequestCard) {
        flushWrite()
        val offset = model.blocks.getOrNull(card.blockIndex)?.startOffset ?: 0
        FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, httpFile, offset), true)
    }

    private fun newRequest(): HttpRequest {
        val base = baseVariable()?.let { "{{$it}}" } ?: "http://localhost:8080"
        return HttpRequest(
            title = "New request",
            method = "GET",
            url = "$base/",
            headers = listOf(HttpHeader("Accept", "application/json")),
            responseHandler = Script.Inline(ScriptTemplates.RESPONSE_HANDLER),
        )
    }

    // ---- environments and routes --------------------------------------------------------------

    private fun scheduleEnvReload() {
        envAlarm.cancelAllRequests()
        envAlarm.addRequest({ reloadEnvironments() }, ENV_DELAY_MS)
    }

    private fun reloadEnvironments() {
        ReadAction.nonBlocking<EnvironmentSet> { HttpEnvironments.load(project, httpFile) }
            .expireWith(this)
            .finishOnUiThread(ModalityState.any()) { loaded ->
                environments = loaded
                selectedEnv = selectedEnv?.takeIf { it in loaded.environmentNames }
                cards.forEach { it.refreshEnvironment() }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun scheduleRoutesRefresh() {
        routesAlarm.cancelAllRequests()
        routesAlarm.addRequest({ refreshRoutes() }, ROUTES_DELAY_MS)
    }

    /** Collects routes in the background; URL completion always uses the latest result. */
    private fun refreshRoutes() {
        ReadAction.nonBlocking<List<ApiRoute>> { OpenApiRoutes.collect(project) }
            .inSmartMode(project)
            .expireWith(this)
            .finishOnUiThread(ModalityState.any()) { routes = it }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    override fun routes(): List<ApiRoute> = routes

    override fun environments(): EnvironmentSet = environments

    override fun selectedEnvironment(): String? = selectedEnv

    override fun variables(): List<VariableInfo> {
        val env = selectedEnv?.let { environments.variables(it) } ?: environments.allVariables()
        val fileVars = model.variables.map { VariableInfo(it.name, it.value, VariableSource.FILE) }
        return (fileVars + env).distinctBy { it.name } + DYNAMIC_VARIABLES
    }

    override fun resolvedValues(): Map<String, String?> {
        val values = LinkedHashMap<String, String?>()
        selectedEnv?.let { env -> environments.variables(env).forEach { values[it.name] = it.value } }
        model.variables.forEach { values[it.name] = it.value }
        return values
    }

    override fun saveOAuthConfig(environment: String, id: String, publicValues: Map<String, Any?>, privateValues: Map<String, Any?>) {
        HttpEnvironments.saveAuthConfig(project, httpFile, environment, id, publicValues, privateValues)
        reloadEnvironments()
    }

    override fun baseUrlFor(route: ApiRoute): String {
        val server = route.servers.firstOrNull()?.trimEnd('/')
        val absolute = server?.contains("://") == true
        val variable = baseVariable()
        return when {
            variable != null && !absolute -> "{{$variable}}" + (server ?: "")
            variable != null -> "{{$variable}}" + server!!.substringAfter("://").let { s -> s.indexOf('/').let { if (it < 0) "" else s.substring(it) } }
            absolute -> server!!
            else -> "http://localhost:8080" + (server ?: "")
        }
    }

    override fun scriptContext(blockIndex: Int, preRequest: Boolean): PsiElement? {
        val block = model.blocks.getOrNull(blockIndex) ?: return null
        val start = (if (preRequest) PRE_REQUEST_SCRIPT else RESPONSE_HANDLER_SCRIPT).find(block.text) ?: return null
        val offset = block.startOffset + start.range.last + 1
        val documents = PsiDocumentManager.getInstance(project)
        if (!documents.isCommitted(document)) {
            // Committing here may be write-unsafe (e.g. while the editor is created); look again once the PSI is current.
            documents.performForCommittedDocument(document) { cards.forEach { it.refreshScriptContext() } }
            return null
        }
        val file = documents.getPsiFile(document) ?: return null
        val leaf = file.findElementAt(offset) ?: return null
        // The JavaScript is injected into this host; anything under the handler would do, the host is most precise.
        return PsiTreeUtil.getParentOfType(leaf, PsiLanguageInjectionHost::class.java, false) ?: leaf
    }

    private fun baseVariable(): String? {
        val names = variables().filter { it.source != VariableSource.DYNAMIC }.map { it.name }
        return BASE_URL_VARIABLES.firstNotNullOfOrNull { candidate -> names.firstOrNull { it.equals(candidate, ignoreCase = true) } }
    }

    // ---- FileEditor ---------------------------------------------------------------------------

    override fun getComponent(): JComponent = root

    override fun getPreferredFocusedComponent(): JComponent? = null

    override fun getName(): String = "Designer"

    override fun setState(state: FileEditorState) {}

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = httpFile.isValid

    override fun getFile(): VirtualFile = httpFile

    override fun deselectNotify() {
        flushWrite()
    }

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun dispose() {
        if (!project.isDisposed) runCatching { flushWrite() }
    }

    private fun selectEnvironment(env: String?) {
        if (env == selectedEnv) return
        selectedEnv = env
        PropertiesComponent.getInstance(project).setValue(ENV_KEY, env)
        cards.forEach { it.refreshEnvironment() }
    }

    /** Environment picker in the toolbar, like the HTTP Client's own "Run with" selector. */
    private inner class EnvironmentAction : ComboBoxAction(), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.text = selectedEnv ?: "No Environment"
            e.presentation.description = "Environment used for variable completion and URL previews"
        }

        override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup = DefaultActionGroup().apply {
            add(choice(null))
            if (environments.environmentNames.isNotEmpty()) addSeparator()
            environments.environmentNames.forEach { add(choice(it)) }
        }

        private fun choice(env: String?) = object : ToggleAction(env ?: "No Environment"), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = selectedEnv == env
            override fun setSelected(e: AnActionEvent, state: Boolean) = selectEnvironment(env)
        }
    }

    /** Vertical stack that always matches the viewport width, so cards never scroll sideways. */
    private class ColumnPanel : JPanel(VerticalLayout(0)), Scrollable {
        init {
            border = JBUI.Borders.empty(4, 12, 12, 8)
        }

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = visibleRect.height
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }

    companion object {
        private const val ENV_KEY = "httpgui.selectedEnvironment"
        private const val TOOLBAR_PLACE = "HttpRequestDesigner.Toolbar"
        private const val COMMAND_GROUP = "HttpRequestDesigner"
        private const val RELOAD_DELAY_MS = 250
        private const val WRITE_DELAY_MS = 200
        private const val ENV_DELAY_MS = 500
        private const val ROUTES_DELAY_MS = 1500

        /** Files with at most this many requests open with every card expanded. */
        private const val EXPAND_ALL_LIMIT = 8

        private val RESPONSE_HANDLER_SCRIPT = Regex("""(?m)^>(?!>)\s*\{%""")
        private val PRE_REQUEST_SCRIPT = Regex("""(?m)^<(?!>)\s*\{%""")
        private val SPEC_EXTENSIONS = setOf("yaml", "yml", "json")
        private val BASE_URL_VARIABLES = listOf("baseUrl", "base_url", "baseURL", "host", "url", "apiUrl", "server")
    }
}
