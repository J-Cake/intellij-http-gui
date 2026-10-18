package dev.jcake.httpgui.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.FixedSizeButton
import com.intellij.ui.EditorTextField
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.jcake.httpgui.core.VariableInfo
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Editable list of name/value rows (query parameters, headers). Values get `{{variable}}`
 * completion; names can be completed from [nameSuggestions].
 */
class KeyValueEditor(
    private val project: Project,
    private val variables: () -> List<VariableInfo>,
    private val nameSuggestions: List<String> = emptyList(),
    private val nameLabel: String = "Name",
    private val valueLabel: String = "Value",
    private val onChanged: (List<Pair<String, String>>) -> Unit,
) : JPanel(BorderLayout()) {

    private inner class Row(name: String, value: String) {
        val nameField: EditorTextField = if (nameSuggestions.isEmpty()) {
            VariableTextField(project, variables, name) { fireChanged() }
        } else {
            TextFieldWithAutoCompletion.create(project, nameSuggestions, false, name).also { field ->
                field.addDocumentListener(object : DocumentListener {
                    override fun documentChanged(event: DocumentEvent) = fireChanged()
                })
            }
        }
        val valueField = VariableTextField(project, variables, value) { fireChanged() }
        val remove = FixedSizeButton().apply {
            icon = AllIcons.General.Remove
            toolTipText = "Remove"
            addActionListener {
                rows.remove(this@Row)
                layoutRows()
                fireChanged()
            }
        }

        fun entry() = nameField.text to valueField.text
    }

    private val rows = mutableListOf<Row>()
    private val rowsPanel = JPanel(GridBagLayout())
    private var silent = false

    init {
        val add = JButton("Add", AllIcons.General.Add).apply {
            addActionListener {
                rows += Row("", "")
                layoutRows()
                rows.last().nameField.requestFocusInWindow()
            }
        }
        val holder = JPanel(BorderLayout()).apply {
            add(rowsPanel, BorderLayout.NORTH)
        }
        add(JBScrollPane(holder).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply { add(add, BorderLayout.WEST); border = JBUI.Borders.emptyTop(4) }, BorderLayout.SOUTH)
        layoutRows()
    }

    fun entries(): List<Pair<String, String>> = rows.map { it.entry() }

    /** Replaces the rows unless they already show [entries] (keeps focus and caret while typing). */
    fun setEntries(entries: List<Pair<String, String>>) {
        if (entries == entries()) return
        silent = true
        try {
            if (entries.size == rows.size) {
                rows.zip(entries).forEach { (row, e) ->
                    if (row.nameField.text != e.first) {
                        (row.nameField as? VariableTextField)?.setTextSilently(e.first) ?: run { row.nameField.text = e.first }
                    }
                    row.valueField.setTextSilently(e.second)
                }
            } else {
                rows.clear()
                entries.forEach { rows += Row(it.first, it.second) }
                layoutRows()
            }
        } finally {
            silent = false
        }
    }

    private fun fireChanged() {
        if (!silent) onChanged(entries())
    }

    private fun layoutRows() {
        rowsPanel.removeAll()
        val c = GridBagConstraints().apply {
            gridy = 0
            insets = JBUI.insets(2)
            fill = GridBagConstraints.HORIZONTAL
        }
        c.gridx = 0; c.weightx = 0.4
        rowsPanel.add(JBLabel(nameLabel), c)
        c.gridx = 1; c.weightx = 0.6
        rowsPanel.add(JBLabel(valueLabel), c)
        rows.forEachIndexed { index, row ->
            c.gridy = index + 1
            c.gridx = 0; c.weightx = 0.4
            rowsPanel.add(row.nameField, c)
            c.gridx = 1; c.weightx = 0.6
            rowsPanel.add(row.valueField, c)
            c.gridx = 2; c.weightx = 0.0; c.fill = GridBagConstraints.NONE
            rowsPanel.add(row.remove, c)
            c.fill = GridBagConstraints.HORIZONTAL
        }
        rowsPanel.revalidate()
        rowsPanel.repaint()
    }
}
