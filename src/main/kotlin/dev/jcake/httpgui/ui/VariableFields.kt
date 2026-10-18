package dev.jcake.httpgui.ui

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.lookup.CharFilter
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorTextField
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.ui.TextFieldWithAutoCompletionListProvider
import com.intellij.util.ui.JBUI
import dev.jcake.httpgui.core.ApiRoute
import dev.jcake.httpgui.core.VariableInfo
import dev.jcake.httpgui.core.Variables
import java.awt.Dimension

/** Completes `{{variable}}` references from environments, in-file variables and dynamic variables. */
class VariableCompletionProvider(private val variables: () -> List<VariableInfo>) :
    TextFieldWithAutoCompletionListProvider<VariableInfo>(null) {

    override fun getLookupString(item: VariableInfo): String = item.name

    override fun getTypeText(item: VariableInfo): String = item.environment?.let { "${item.source.label} · $it" } ?: item.source.label

    override fun getTailText(item: VariableInfo): String? = item.value?.let { " = " + if (it.length > 40) it.take(40) + "…" else it }

    override fun getPrefix(text: String, offset: Int): String? = Variables.completionPrefix(text, offset)

    // Everything is in memory, so all items are offered in the "cached" pass and none in the background pass.
    override fun getItems(prefix: String?, cached: Boolean, parameters: CompletionParameters?): Collection<VariableInfo> =
        if (prefix == null || !cached) emptyList() else variables()

    override fun acceptChar(c: Char): CharFilter.Result? =
        if (c.isLetterOrDigit() || c in "_\$.-") CharFilter.Result.ADD_TO_PREFIX else null

    override fun createInsertHandler(item: VariableInfo): InsertHandler<LookupElement> = InsertHandler { context, _ ->
        val document = context.document
        val tail = context.tailOffset
        if (!document.charsSequence.startsWith("}}", tail)) document.insertString(tail, "}}")
        context.editor.caretModel.moveToOffset(tail + 2)
    }

    override fun compare(item1: VariableInfo, item2: VariableInfo): Int = item1.name.compareTo(item2.name, ignoreCase = true)
}

/** A one-line text field with `{{variable}}` completion that reports text changes. */
class VariableTextField(
    private val project: Project,
    variables: () -> List<VariableInfo>,
    text: String = "",
    private val onTextChanged: (String) -> Unit = {},
) : TextFieldWithAutoCompletion<VariableInfo>(project, VariableCompletionProvider(variables), false, text) {
    private var silent = false

    init {
        addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (silent) return
                val ed = editor
                val caret = ed?.caretModel?.offset ?: event.offset + event.newLength
                if (ed != null && Variables.completionPrefix(event.document.text, caret) == "") {
                    AutoPopupController.getInstance(project).scheduleAutoPopup(ed)
                }
                onTextChanged(event.document.text)
            }
        })
    }

    /** Replaces the text without firing [onTextChanged]; no-op when unchanged so the caret stays put. */
    fun setTextSilently(value: String) {
        if (text == value) return
        silent = true
        try {
            text = value
        } finally {
            silent = false
        }
    }
}

/** Multi-line code editor used for bodies and scripts. */
class CodeEditorField(
    project: Project,
    fileType: FileType,
    private val onTextChanged: (String) -> Unit = {},
    private val viewer: Boolean = false,
) : EditorTextField(EditorFactory.getInstance().createDocument(""), project, fileType, viewer, false) {
    private var silent = false

    init {
        addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                // Let the enclosing request section grow or shrink with the text.
                revalidate()
                if (!silent) onTextChanged(event.document.text)
            }
        })
    }

    /** Grows with its content between [MIN_LINES] and [MAX_LINES] lines, then scrolls. */
    override fun getPreferredSize(): Dimension {
        val size = super.getPreferredSize()
        val lineHeight = editor?.lineHeight ?: JBUI.scale(18)
        val lines = document.lineCount.coerceIn(MIN_LINES, MAX_LINES) + 1
        val insets = insets.top + insets.bottom
        return Dimension(size.width, lines * lineHeight + insets + JBUI.scale(12))
    }

    override fun createEditor(): EditorEx {
        val editor = super.createEditor()
        editor.setVerticalScrollbarVisible(true)
        editor.setHorizontalScrollbarVisible(true)
        editor.settings.isLineNumbersShown = true
        editor.settings.isFoldingOutlineShown = true
        editor.settings.isUseSoftWraps = false
        return editor
    }

    fun setTextSilently(value: String) {
        if (text == value) return
        silent = true
        try {
            text = value
        } finally {
            silent = false
        }
    }

    companion object {
        private const val MIN_LINES = 5
        private const val MAX_LINES = 30

        fun fileTypeFor(extension: String): FileType =
            FileTypeManager.getInstance().getFileTypeByExtension(extension).takeUnless { it.isBinary } ?: PlainTextFileType.INSTANCE

        fun fileTypeForContentType(contentType: String?): FileType {
            val ct = contentType?.lowercase() ?: return PlainTextFileType.INSTANCE
            return when {
                ct.contains("json") -> fileTypeFor("json")
                ct.contains("xml") -> fileTypeFor("xml")
                ct.contains("html") -> fileTypeFor("html")
                ct.contains("graphql") -> fileTypeFor("graphql")
                ct.contains("yaml") -> fileTypeFor("yaml")
                else -> PlainTextFileType.INSTANCE
            }
        }
    }
}

/** An entry offered by the URL field: an environment variable or an OpenAPI / Endpoints route. */
sealed class UrlSuggestion {
    data class Variable(val info: VariableInfo) : UrlSuggestion()

    /** [url] is the route's path with path parameters as `{{variables}}`, prefixed by the base URL. */
    data class Route(val route: ApiRoute, val url: String) : UrlSuggestion()
}

/**
 * Inside `{{` the URL field completes variables; anywhere else it completes routes, matched against
 * the full URL, the path, or "METHOD path".
 */
class UrlCompletionProvider(
    private val variables: () -> List<VariableInfo>,
    private val routes: () -> List<UrlSuggestion.Route>,
    private val onRoutePicked: (ApiRoute) -> Unit,
) : TextFieldWithAutoCompletionListProvider<UrlSuggestion>(null) {

    override fun getLookupString(item: UrlSuggestion): String = when (item) {
        is UrlSuggestion.Variable -> item.info.name
        is UrlSuggestion.Route -> item.url
    }

    override fun getTypeText(item: UrlSuggestion): String? = when (item) {
        is UrlSuggestion.Variable -> item.info.environment?.let { "${item.info.source.label} · $it" } ?: item.info.source.label
        is UrlSuggestion.Route -> item.route.source.ifEmpty { null }
    }

    override fun getTailText(item: UrlSuggestion): String? = when (item) {
        is UrlSuggestion.Variable -> item.info.value?.let { " = " + if (it.length > 40) it.take(40) + "…" else it }
        is UrlSuggestion.Route -> item.route.summary?.let { "  $it" }
    }

    override fun createLookupBuilder(item: UrlSuggestion): LookupElementBuilder {
        val builder = super.createLookupBuilder(item)
        if (item !is UrlSuggestion.Route) return builder
        val method = item.route.method
        return builder
            .withLookupStrings(setOf(item.route.path, "$method ${item.route.path}"))
            .withPresentableText("${method.padEnd(7)} ${item.url}")
            .withBoldness(false)
    }

    override fun getPrefix(text: String, offset: Int): String =
        Variables.completionPrefix(text, offset) ?: text.substring(0, offset.coerceIn(0, text.length)).trimStart()

    override fun getItems(prefix: String?, cached: Boolean, parameters: CompletionParameters?): Collection<UrlSuggestion> {
        if (prefix == null || !cached) return emptyList()
        val text = parameters?.editor?.document?.text ?: ""
        val offset = parameters?.offset ?: text.length
        return if (Variables.completionPrefix(text, offset) != null) variables().map { UrlSuggestion.Variable(it) } else routes()
    }

    override fun acceptChar(c: Char): CharFilter.Result? = when {
        c.isWhitespace() || c == '}' -> CharFilter.Result.HIDE_LOOKUP
        else -> CharFilter.Result.ADD_TO_PREFIX
    }

    override fun createInsertHandler(item: UrlSuggestion): InsertHandler<LookupElement> = when (item) {
        is UrlSuggestion.Variable -> InsertHandler { context, _ ->
            val document = context.document
            val tail = context.tailOffset
            if (!document.charsSequence.startsWith("}}", tail)) document.insertString(tail, "}}")
            context.editor.caretModel.moveToOffset(tail + 2)
        }
        // Method, query parameters, headers and body are applied once the completion has finished.
        is UrlSuggestion.Route -> InsertHandler { _, _ -> ApplicationManager.getApplication().invokeLater { onRoutePicked(item.route) } }
    }

    override fun compare(item1: UrlSuggestion, item2: UrlSuggestion): Int = getLookupString(item1).compareTo(getLookupString(item2), ignoreCase = true)
}

/** URL field: routes and `{{variables}}` completion. */
class UrlTextField(
    private val project: Project,
    variables: () -> List<VariableInfo>,
    routes: () -> List<UrlSuggestion.Route>,
    onRoutePicked: (ApiRoute) -> Unit,
    private val onTextChanged: (String) -> Unit,
) : TextFieldWithAutoCompletion<UrlSuggestion>(project, UrlCompletionProvider(variables, routes, onRoutePicked), false, "") {
    private var silent = false

    init {
        addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (silent) return
                val ed = editor
                val text = event.document.text
                val caret = ed?.caretModel?.offset ?: (event.offset + event.newLength)
                val typed = event.newFragment.toString()
                val startsVariable = Variables.completionPrefix(text, caret) == ""
                if (ed != null && (startsVariable || typed == "/")) AutoPopupController.getInstance(project).scheduleAutoPopup(ed)
                onTextChanged(text)
            }
        })
    }

    fun setTextSilently(value: String) {
        if (text == value) return
        silent = true
        try {
            text = value
        } finally {
            silent = false
        }
    }
}
