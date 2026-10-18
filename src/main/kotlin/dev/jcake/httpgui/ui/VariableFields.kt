package dev.jcake.httpgui.ui

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.lookup.CharFilter
import com.intellij.codeInsight.lookup.LookupElement
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
import dev.jcake.httpgui.core.VariableInfo
import dev.jcake.httpgui.core.Variables

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
                if (!silent) onTextChanged(event.document.text)
            }
        })
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
