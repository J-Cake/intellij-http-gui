package dev.jcake.httpgui.ui

import com.intellij.lang.Language
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.impl.source.resolve.FileContextUtil
import com.intellij.ui.EditorTextField
import com.intellij.util.ui.JBUI
import java.awt.Dimension

/**
 * JavaScript editor for a pre-request or response handler script.
 *
 * Like the IDE's own "Edit fragment" editor, the script lives in a JavaScript PSI file that is
 * marked as injected into the request's `{% %}` block of the `.http` file ([setContext]). The HTTP
 * Client resolves `client`, `response`, `request` etc. from that context, so completion,
 * documentation and inspections behave as in the text editor.
 */
class ScriptEditorField private constructor(
    project: Project,
    private val psiFile: PsiFile?,
    document: Document,
    placeholderText: String,
    private val onTextChanged: (String) -> Unit,
) : EditorTextField(document, project, psiFile?.fileType ?: PlainTextFileType.INSTANCE, false, false) {

    private val pointers = SmartPointerManager.getInstance(project)
    private var silent = false
    private var context: PsiElement? = null

    init {
        setPlaceholder(placeholderText)
        setShowPlaceholderWhenFocused(true)
        addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                // Let the enclosing request section grow or shrink with the script.
                revalidate()
                if (!silent) onTextChanged(event.document.text)
            }
        })
    }

    /** Ties the script to [element] (inside the handler block in the `.http` file), or detaches it. */
    fun setContext(element: PsiElement?) {
        val file = psiFile ?: return
        if (element == context) return
        context = element
        file.putUserData(FileContextUtil.INJECTED_IN_ELEMENT, element?.let { pointers.createSmartPsiElementPointer(it) })
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

    /** Grows with its content between [MIN_LINES] and [MAX_LINES] lines, then scrolls. */
    override fun getPreferredSize(): Dimension {
        val size = super.getPreferredSize()
        val lineHeight = editor?.lineHeight ?: JBUI.scale(18)
        val lines = document.lineCount.coerceIn(MIN_LINES, MAX_LINES) + 1
        return Dimension(size.width, lines * lineHeight + insets.top + insets.bottom + JBUI.scale(12))
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

    companion object {
        private const val MIN_LINES = 5
        private const val MAX_LINES = 30

        fun create(project: Project, fileName: String, placeholder: String, onTextChanged: (String) -> Unit): ScriptEditorField {
            val javaScript = Language.findLanguageByID("JavaScript")
            val psiFile = javaScript?.let {
                PsiFileFactory.getInstance(project).createFileFromText(fileName, it, "", true, false)
            }
            // Without the JavaScript plugin this is a plain text editor.
            val document = psiFile?.let { PsiDocumentManager.getInstance(project).getDocument(it) }
                ?: EditorFactory.getInstance().createDocument("")
            return ScriptEditorField(project, psiFile, document, placeholder, onTextChanged)
        }
    }
}
