package dev.jcake.httpgui

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
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
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import dev.jcake.httpgui.core.ApiRoute
import dev.jcake.httpgui.core.DYNAMIC_VARIABLES
import dev.jcake.httpgui.core.EnvironmentSet
import dev.jcake.httpgui.core.HttpFileModel
import dev.jcake.httpgui.core.HttpFileParser
import dev.jcake.httpgui.core.HttpHeader
import dev.jcake.httpgui.core.HttpRequest
import dev.jcake.httpgui.core.HttpSerializer
import dev.jcake.httpgui.core.Script
import dev.jcake.httpgui.core.TestScripts
import dev.jcake.httpgui.core.VariableInfo
import dev.jcake.httpgui.core.VariableSource
import dev.jcake.httpgui.services.HttpEnvironments
import dev.jcake.httpgui.services.OpenApiRoutes
import dev.jcake.httpgui.ui.RequestEditorContext
import dev.jcake.httpgui.ui.RequestEditorPanel
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.beans.PropertyChangeListener
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel

/**
 * Graphical editor for an `.http` file. It works on the same [Document] as the text editor:
 * GUI edits rewrite only the affected request block, and document changes from any other source
 * (typing in the text tab, VCS updates, external edits) are re-parsed and shown live.
 */
class HttpGuiFileEditor(
    override val project: Project,
    private val httpFile: VirtualFile,
) : UserDataHolderBase(), FileEditor, RequestEditorContext {

    private data class RequestItem(val blockIndex: Int, val request: HttpRequest)

    private val document: Document = FileDocumentManager.getInstance().getDocument(httpFile)
        ?: error("No document for ${httpFile.path}")
    private var model: HttpFileModel = HttpFileParser.parse(document.text)
    private var environments: EnvironmentSet = EnvironmentSet.EMPTY
    private var selectedEnv: String? = PropertiesComponent.getInstance(project).getValue(ENV_KEY)

    private var ownChange = false
    private var fillingEnvCombo = false
    private var suppressSelection = false
    private var selectedBlock = -1
    private var pendingWrite: Pair<Int, HttpRequest>? = null

    private val reloadAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val writeAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val envAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    private val listModel = CollectionListModel<RequestItem>()
    private val list = JBList(listModel)
    private val envCombo = ComboBox<String>()
    private val form = RequestEditorPanel(this) { request -> scheduleWrite(request) }
    private val root: JComponent

    init {
        list.cellRenderer = object : ColoredListCellRenderer<RequestItem>() {
            override fun customizeCellRenderer(list: JList<out RequestItem>, value: RequestItem?, index: Int, selected: Boolean, hasFocus: Boolean) {
                val r = value?.request ?: return
                append(r.method.padEnd(7), SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, methodColor(r.method)))
                append(" " + r.displayName())
                if (r.name != null || !r.title.isNullOrBlank()) append("  " + r.url, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        list.emptyText.text = "No requests in this file"
        list.addListSelectionListener { e ->
            if (e.valueIsAdjusting || suppressSelection) return@addListSelectionListener
            flushWrite()
            val item = list.selectedValue
            selectedBlock = item?.blockIndex ?: -1
            form.setRequest(item?.request)
        }

        val listPanel = ToolbarDecorator.createDecorator(list)
            .setAddAction { addRequest(newRequest()) }
            .setRemoveAction { removeSelected() }
            .disableUpDownActions()
            .addExtraAction(DumbAwareAction.create("New Request from OpenAPI Route", AllIcons.ToolbarDecorator.Import) { e ->
                val anchor = e.inputEvent?.component as? JComponent ?: list
                pickRoute(anchor) { route -> addRequest(route.toRequest(baseUrlFor(route))) }
            })
            .addExtraAction(DumbAwareAction.create("Duplicate Request", AllIcons.Actions.Copy) {
                list.selectedValue?.let { item -> addRequest(item.request.copy(hasSeparator = true, title = item.request.title?.let { t -> "$t (copy)" })) }
            })
            .createPanel()

        envCombo.renderer = SimpleListCellRenderer.create<String>("") { it.ifEmpty { "No environment" } }
        envCombo.addActionListener {
            if (fillingEnvCombo) return@addActionListener
            val env = (envCombo.selectedItem as? String)?.ifEmpty { null }
            if (env != selectedEnv) {
                selectedEnv = env
                PropertiesComponent.getInstance(project).setValue(ENV_KEY, env)
                form.refreshEnvironment()
            }
        }

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply {
            add(JBLabel("Environment:"))
            add(envCombo)
            add(JButton("Reload", AllIcons.Actions.Refresh).apply {
                toolTipText = "Re-read the file and environment files"
                addActionListener { reloadNow(); reloadEnvironments() }
            })
            add(JButton("Open in Text Editor", AllIcons.Actions.EditSource).apply {
                toolTipText = "Show this request in the text editor, where it can be run"
                addActionListener { openInTextEditor() }
            })
        }

        root = JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(JBSplitter(false, 0.25f).apply {
                firstComponent = listPanel
                secondComponent = form
                splitterProportionKey = "httpgui.splitter"
            }, BorderLayout.CENTER)
        }

        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (!ownChange) scheduleReload()
            }
        }, this)

        // Environment files: react to saved changes and to unsaved edits in open editors.
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { it.path.substringAfterLast('/') in HttpEnvironments.FILE_NAMES }) scheduleEnvReload()
            }
        })
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val name = FileDocumentManager.getInstance().getFile(event.document)?.name ?: return
                if (name in HttpEnvironments.FILE_NAMES) scheduleEnvReload()
            }
        }, this)

        updateList()
        if (listModel.size > 0) list.selectedIndex = 0
        reloadEnvironments()
    }

    // ---- document -> GUI ----------------------------------------------------------------------

    private fun scheduleReload() {
        reloadAlarm.cancelAllRequests()
        reloadAlarm.addRequest({ reloadNow() }, RELOAD_DELAY_MS)
    }

    private fun reloadNow() {
        flushWrite()
        model = HttpFileParser.parse(document.text)
        updateList()
        val item = list.selectedValue
        if (item?.request != form.request()) form.setRequest(item?.request)
    }

    /** Syncs the list with [model], keeping the selected position. */
    private fun updateList() {
        val items = model.blocks.mapIndexedNotNull { i, b -> b.request?.let { RequestItem(i, it) } }
        val selectedPosition = list.selectedIndex
        suppressSelection = true
        try {
            if (items.size == listModel.size) {
                items.forEachIndexed { i, item -> if (listModel.getElementAt(i) != item) listModel.setElementAt(item, i) }
            } else {
                listModel.replaceAll(items)
                if (items.isNotEmpty()) list.selectedIndex = selectedPosition.coerceIn(0, items.lastIndex)
            }
        } finally {
            suppressSelection = false
        }
        selectedBlock = list.selectedValue?.blockIndex ?: -1
        if (list.selectedValue == null) form.setRequest(null)
    }

    // ---- GUI -> document ----------------------------------------------------------------------

    private fun scheduleWrite(request: HttpRequest) {
        if (selectedBlock < 0) return
        pendingWrite = selectedBlock to request
        val position = list.selectedIndex
        if (position >= 0) {
            suppressSelection = true
            try {
                listModel.setElementAt(RequestItem(selectedBlock, request), position)
            } finally {
                suppressSelection = false
            }
        }
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
        updateList()
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

    private fun addRequest(request: HttpRequest) {
        flushWrite()
        val text = document.text
        val prefix = when {
            text.isBlank() -> ""
            text.endsWith("\n\n") -> ""
            text.endsWith("\n") -> "\n"
            else -> "\n\n"
        }
        // A blank document is replaced entirely; otherwise the block is appended.
        val start = if (text.isBlank()) 0 else text.length
        val block = prefix + HttpSerializer.serialize(request.copy(hasSeparator = true)) + "\n"
        if (!writeDocument("Add HTTP Request") { document.replaceString(start, text.length, block) }) return
        model = HttpFileParser.parse(document.text)
        updateList()
        list.selectedIndex = listModel.size - 1
        list.ensureIndexIsVisible(listModel.size - 1)
    }

    private fun removeSelected() {
        flushWrite()
        val item = list.selectedValue ?: return
        val block = model.blocks.getOrNull(item.blockIndex) ?: return
        val request = block.request ?: return
        // A request at the top of the file may share its block with file-level variables; keep those.
        val replacement = if (!request.hasSeparator && request.prelude.isNotEmpty()) {
            request.prelude.joinToString("\n") + block.trailingWhitespace
        } else {
            ""
        }
        val position = list.selectedIndex
        writeDocument("Remove HTTP Request") { document.replaceString(block.startOffset, block.endOffset, replacement) }
        model = HttpFileParser.parse(document.text)
        updateList()
        if (listModel.size > 0) list.selectedIndex = position.coerceIn(0, listModel.size - 1)
    }

    private fun newRequest(): HttpRequest {
        val base = baseVariable()?.let { "{{$it}}" } ?: "http://localhost:8080"
        return HttpRequest(
            title = "New request",
            method = "GET",
            url = "$base/",
            headers = listOf(HttpHeader("Accept", "application/json")),
            responseHandler = Script.Inline(TestScripts.generate(TestScripts.DEFAULT_CONDITIONS, "")),
        )
    }

    private fun openInTextEditor() {
        flushWrite()
        val offset = list.selectedValue?.let { model.blocks.getOrNull(it.blockIndex)?.startOffset } ?: 0
        FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, httpFile, offset), true)
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
                val wanted = selectedEnv?.takeIf { it in loaded.environmentNames }
                fillingEnvCombo = true
                try {
                    envCombo.removeAllItems()
                    (listOf("") + loaded.environmentNames).forEach { envCombo.addItem(it) }
                    envCombo.selectedItem = wanted ?: ""
                } finally {
                    fillingEnvCombo = false
                }
                selectedEnv = wanted
                form.refreshEnvironment()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

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

    override fun pickRoute(anchor: JComponent, onPicked: (ApiRoute) -> Unit) {
        ReadAction.nonBlocking<List<ApiRoute>> { OpenApiRoutes.collect(project) }
            .inSmartMode(project)
            .expireWith(this)
            .finishOnUiThread(ModalityState.any()) { routes ->
                if (routes.isEmpty()) {
                    JBPopupFactory.getInstance()
                        .createHtmlTextBalloonBuilder(
                            "No routes found. Routes come from the Endpoints view and OpenAPI files in the project.",
                            MessageType.INFO, null,
                        )
                        .createBalloon()
                        .show(RelativePoint.getSouthOf(anchor), Balloon.Position.below)
                    return@finishOnUiThread
                }
                JBPopupFactory.getInstance().createPopupChooserBuilder(routes)
                    .setTitle("OpenAPI Routes")
                    .setRenderer(object : ColoredListCellRenderer<ApiRoute>() {
                        override fun customizeCellRenderer(list: JList<out ApiRoute>, value: ApiRoute?, index: Int, selected: Boolean, hasFocus: Boolean) {
                            value ?: return
                            append(value.method.padEnd(7), SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, methodColor(value.method)))
                            append(" " + value.path)
                            value.summary?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                            if (value.source.isNotEmpty()) append("  ${value.source}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                        }
                    })
                    .setNamerForFiltering { "${it.method} ${it.path} ${it.summary ?: ""} ${it.operationId ?: ""}" }
                    .setItemChosenCallback { onPicked(it) }
                    .createPopup()
                    .showUnderneathOf(anchor)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
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

    private fun baseVariable(): String? {
        val names = variables().filter { it.source != VariableSource.DYNAMIC }.map { it.name }
        return BASE_URL_VARIABLES.firstOrNull { candidate -> names.any { it.equals(candidate, ignoreCase = true) } }
            ?.let { candidate -> names.first { it.equals(candidate, ignoreCase = true) } }
    }

    // ---- FileEditor ---------------------------------------------------------------------------

    override fun getComponent(): JComponent = root

    override fun getPreferredFocusedComponent(): JComponent = list

    override fun getName(): String = "Designer"

    override fun setState(state: FileEditorState) {}

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = httpFile.isValid

    override fun getFile(): VirtualFile = httpFile

    override fun selectNotify() {
        reloadNow()
    }

    override fun deselectNotify() {
        flushWrite()
    }

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun dispose() {
        if (!project.isDisposed) runCatching { flushWrite() }
    }

    companion object {
        private const val ENV_KEY = "httpgui.selectedEnvironment"
        private const val COMMAND_GROUP = "HttpRequestDesigner"
        private const val RELOAD_DELAY_MS = 250
        private const val WRITE_DELAY_MS = 200
        private const val ENV_DELAY_MS = 500

        private val BASE_URL_VARIABLES = listOf("baseUrl", "base_url", "baseURL", "host", "url", "apiUrl", "server")

        fun methodColor(method: String): Color = when (method.uppercase()) {
            "GET" -> JBUI.CurrentTheme.Link.Foreground.ENABLED
            "POST" -> JBColor(0x2E7D32, 0x7CC47F)
            "PUT", "PATCH" -> JBColor(0xB26A00, 0xE0A458)
            "DELETE" -> JBColor(0xC62828, 0xE57373)
            else -> JBColor(0x6A1B9A, 0xB39DDB)
        }
    }
}
