package dev.jcake.httpgui.ui

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.ShortcutSet
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorTextField
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.AbstractTableCellEditor
import com.intellij.util.ui.EditableModel
import com.intellij.util.ui.JBUI
import dev.jcake.httpgui.core.VariableInfo
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel

/**
 * Editable name/value table (query parameters, headers) in the platform's usual style: a [JBTable]
 * with an add / remove / move toolbar. Cells get `{{variable}}` completion, names can be completed
 * from [nameSuggestions], and edits are reported while typing.
 */
class KeyValueEditor(
    private val project: Project,
    private val variables: () -> List<VariableInfo>,
    private val nameSuggestions: List<String> = emptyList(),
    nameLabel: String = "Name",
    valueLabel: String = "Value",
    private val onChanged: (List<Pair<String, String>>) -> Unit,
) : JPanel(BorderLayout()) {

    private val rows = mutableListOf<Array<String>>()
    private var silent = false

    private val model = object : AbstractTableModel(), EditableModel {
        private val columns = listOf(nameLabel, valueLabel)

        override fun getRowCount() = rows.size
        override fun getColumnCount() = 2
        override fun getColumnName(column: Int) = columns[column]
        override fun isCellEditable(rowIndex: Int, columnIndex: Int) = true
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = rows[rowIndex][columnIndex]

        override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
            if (rowIndex !in rows.indices) return
            rows[rowIndex][columnIndex] = value?.toString() ?: ""
            fireTableCellUpdated(rowIndex, columnIndex)
            fireChanged()
        }

        override fun addRow() {
            rows += arrayOf("", "")
            fireTableRowsInserted(rows.lastIndex, rows.lastIndex)
            rowsChanged()
            // Start typing the name of the new row straight away.
            SwingUtilities.invokeLater { if (table.editCellAt(rows.lastIndex, 0)) table.editorComponent?.requestFocusInWindow() }
        }

        override fun removeRow(idx: Int) {
            rows.removeAt(idx)
            fireTableRowsDeleted(idx, idx)
            rowsChanged()
            fireChanged()
        }

        override fun exchangeRows(oldIndex: Int, newIndex: Int) {
            rows.add(newIndex, rows.removeAt(oldIndex))
            fireTableRowsUpdated(minOf(oldIndex, newIndex), maxOf(oldIndex, newIndex))
            fireChanged()
        }

        override fun canExchangeRows(oldIndex: Int, newIndex: Int) = true
    }

    private val table: JBTable = JBTable(model).apply {
        setShowGrid(false)
        emptyText.text = "Nothing here yet"
        putClientProperty("terminateEditOnFocusLost", true)
        surrendersFocusOnKeystroke = true
        rowHeight = maxOf(rowHeight, JBUI.scale(26))
        columnModel.getColumn(0).cellEditor = CompletionCellEditor(0) { text -> nameField(text) }
        columnModel.getColumn(1).cellEditor = CompletionCellEditor(1) { text -> VariableTextField(project, variables, text) }
    }

    init {
        add(ToolbarDecorator.createDecorator(table).createPanel(), BorderLayout.CENTER)
        rowsChanged()
    }

    fun entries(): List<Pair<String, String>> = rows.map { it[0] to it[1] }

    /** Replaces the rows unless they already show [entries] (keeps an in-progress edit alive). */
    fun setEntries(entries: List<Pair<String, String>>) {
        if (entries == entries()) return
        silent = true
        try {
            if (table.isEditing) table.cellEditor?.cancelCellEditing()
            rows.clear()
            entries.forEach { rows += arrayOf(it.first, it.second) }
            model.fireTableDataChanged()
            rowsChanged()
        } finally {
            silent = false
        }
    }

    private fun nameField(text: String): EditorTextField =
        if (nameSuggestions.isEmpty()) VariableTextField(project, variables, text)
        else TextFieldWithAutoCompletion.create(project, nameSuggestions, false, text)

    /** The table grows with its rows, so the request section grows instead of scrolling. */
    private fun rowsChanged() {
        table.visibleRowCount = maxOf(rows.size, MIN_VISIBLE_ROWS)
        revalidate()
    }

    private fun fireChanged() {
        if (!silent) onChanged(entries())
    }

    /** Edits a cell with a completion field; the row is updated on every keystroke. */
    private inner class CompletionCellEditor(
        private val column: Int,
        private val factory: (String) -> EditorTextField,
    ) : AbstractTableCellEditor() {
        private var field: EditorTextField? = null

        override fun getTableCellEditorComponent(table: JTable, value: Any?, isSelected: Boolean, row: Int, column: Int): Component {
            val editorField = factory(value?.toString() ?: "")
            editorField.border = JBUI.Borders.empty()
            editorField.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    if (row in rows.indices) {
                        rows[row][this@CompletionCellEditor.column] = event.document.text
                        fireChanged()
                    }
                }
            })
            // Enter commits and Escape cancels, unless the completion popup is open and wants the key.
            cellKey(editorField, CommonShortcuts.ENTER) { stopCellEditing() }
            cellKey(editorField, CommonShortcuts.ESCAPE) { cancelCellEditing() }
            field = editorField
            SwingUtilities.invokeLater { editorField.requestFocusInWindow() }
            return editorField
        }

        private fun cellKey(field: EditorTextField, shortcut: ShortcutSet, action: () -> Unit) {
            object : DumbAwareAction() {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = field.editor?.let { LookupManager.getActiveLookup(it) } == null
                }

                override fun actionPerformed(e: AnActionEvent) = action()
            }.registerCustomShortcutSet(shortcut, field)
        }

        override fun getCellEditorValue(): Any = field?.text ?: ""
    }

    companion object {
        private const val MIN_VISIBLE_ROWS = 3
    }
}
