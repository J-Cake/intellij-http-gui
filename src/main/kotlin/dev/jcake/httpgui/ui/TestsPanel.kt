package dev.jcake.httpgui.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.jcake.httpgui.core.Script
import dev.jcake.httpgui.core.TestCondition
import dev.jcake.httpgui.core.TestOperator
import dev.jcake.httpgui.core.TestScripts
import dev.jcake.httpgui.core.TestSubject
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.DefaultCellEditor
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Editor for the response handler: assertions are edited as rows and generated as
 * `client.test(...)` blocks; anything else in the handler is kept as free-form JavaScript.
 */
class TestsPanel(
    project: Project,
    private val onChanged: (Script?) -> Unit,
) : JPanel(BorderLayout()) {

    private var silent = false
    private var externalHandler: Script.FileRef? = null
    private val conditions = mutableListOf<TestCondition>()

    private val tableModel = object : AbstractTableModel() {
        val columns = listOf("Check", "Target (header / JSON path)", "Operator", "Expected", "Test name")

        override fun getRowCount() = conditions.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(column: Int) = columns[column]

        override fun isCellEditable(row: Int, column: Int): Boolean {
            val c = conditions[row]
            return when (column) {
                1 -> c.subject.needsTarget
                3 -> c.operator.needsExpected
                else -> true
            }
        }

        override fun getValueAt(row: Int, column: Int): Any? {
            val c = conditions[row]
            return when (column) {
                0 -> c.subject
                1 -> if (c.subject.needsTarget) c.target else ""
                2 -> c.operator
                3 -> if (c.operator.needsExpected) c.expected else ""
                else -> c.name
            }
        }

        override fun setValueAt(value: Any?, row: Int, column: Int) {
            val c = conditions[row]
            conditions[row] = when (column) {
                0 -> (value as? TestSubject ?: c.subject).let { s ->
                    c.copy(subject = s, target = if (s.needsTarget) c.target.ifBlank { defaultTarget(s) } else "")
                }
                1 -> c.copy(target = value?.toString() ?: "")
                2 -> c.copy(operator = value as? TestOperator ?: c.operator)
                3 -> c.copy(expected = value?.toString() ?: "")
                else -> c.copy(name = value?.toString() ?: "")
            }
            fireTableRowsUpdated(row, row)
            fire()
        }
    }

    private val table = JBTable(tableModel).apply {
        setShowGrid(false)
        emptyText.text = "No checks yet — add one to generate a test case"
        val subjectCombo = ComboBox(TestSubject.entries.toTypedArray()).apply {
            renderer = SimpleListCellRenderer.create<TestSubject>("") { it.label }
        }
        val operatorCombo = ComboBox(TestOperator.entries.toTypedArray()).apply {
            renderer = SimpleListCellRenderer.create<TestOperator>("") { it.label }
        }
        columnModel.getColumn(0).cellEditor = DefaultCellEditor(subjectCombo)
        columnModel.getColumn(2).cellEditor = DefaultCellEditor(operatorCombo)
        val labelRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(t: JTable, value: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int): Component {
                val text = when (value) {
                    is TestSubject -> value.label
                    is TestOperator -> value.label
                    else -> value?.toString() ?: ""
                }
                return super.getTableCellRendererComponent(t, text, sel, focus, row, col)
            }
        }
        columnModel.getColumn(0).cellRenderer = labelRenderer
        columnModel.getColumn(2).cellRenderer = labelRenderer
        putClientProperty("terminateEditOnFocusLost", true)
    }

    private val customScript = CodeEditorField(project, CodeEditorField.fileTypeFor("js"), { fire() })
    private val preview = CodeEditorField(project, CodeEditorField.fileTypeFor("js"), viewer = true)
    private val externalLabel = JBLabel()
    private val externalPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(4)
        add(externalLabel, BorderLayout.CENTER)
        add(JButton("Replace with Inline Tests").apply {
            addActionListener {
                externalHandler = null
                if (conditions.isEmpty()) conditions += TestScripts.DEFAULT_CONDITIONS
                tableModel.fireTableDataChanged()
                updateExternalState()
                fire()
            }
        }, BorderLayout.EAST)
    }

    init {
        val decorated = ToolbarDecorator.createDecorator(table)
            .setAddAction { addCondition(TestCondition()) }
            .setRemoveAction {
                val rows = table.selectedRows.sortedDescending()
                if (table.isEditing) table.cellEditor.stopCellEditing()
                rows.forEach { conditions.removeAt(it) }
                tableModel.fireTableDataChanged()
                fire()
            }
            .disableUpDownActions()
            .createPanel()

        val presets = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            add(JBLabel("Quick add:"))
            add(presetButton("Status 200", TestCondition(TestSubject.STATUS, "", TestOperator.EQUALS, "200")))
            add(presetButton("Status 2xx", TestCondition(TestSubject.STATUS, "", TestOperator.LESS_THAN, "300")))
            add(presetButton("JSON response", TestCondition(TestSubject.CONTENT_TYPE, "", TestOperator.CONTAINS, "json")))
            add(presetButton("Field exists", TestCondition(TestSubject.JSON_PATH, "$.id", TestOperator.EXISTS, "")))
            add(presetButton("Header present", TestCondition(TestSubject.HEADER, "Content-Type", TestOperator.EXISTS, "")))
        }

        val top = JPanel(BorderLayout()).apply {
            add(externalPanel, BorderLayout.NORTH)
            add(decorated, BorderLayout.CENTER)
            add(presets, BorderLayout.SOUTH)
        }

        val scripts = JBSplitter(false, 0.5f).apply {
            firstComponent = titled("Additional script (response handler JavaScript)", customScript)
            secondComponent = titled("Generated handler", preview)
        }

        val split = JBSplitter(true, 0.45f).apply {
            firstComponent = top
            secondComponent = scripts
        }
        add(split, BorderLayout.CENTER)
        updateExternalState()
    }

    private fun titled(title: String, component: Component) = JPanel(BorderLayout()).apply {
        add(JBLabel(title).apply { foreground = UIUtil.getContextHelpForeground(); border = JBUI.Borders.empty(4, 0) }, BorderLayout.NORTH)
        add(component, BorderLayout.CENTER)
    }

    private fun presetButton(text: String, condition: TestCondition) = JButton(text).apply {
        addActionListener { addCondition(condition) }
    }

    private fun addCondition(condition: TestCondition) {
        if (table.isEditing) table.cellEditor.stopCellEditing()
        conditions += condition
        tableModel.fireTableDataChanged()
        table.selectionModel.setSelectionInterval(conditions.lastIndex, conditions.lastIndex)
        fire()
    }

    private fun defaultTarget(subject: TestSubject) = when (subject) {
        TestSubject.HEADER -> "Content-Type"
        TestSubject.JSON_PATH -> "$."
        else -> ""
    }

    fun setHandler(handler: Script?) {
        if (handler == currentHandler()) return
        silent = true
        try {
            if (table.isEditing) table.cellEditor.cancelCellEditing()
            conditions.clear()
            when (handler) {
                is Script.FileRef -> {
                    externalHandler = handler
                    customScript.setTextSilently("")
                }
                is Script.Inline -> {
                    externalHandler = null
                    val parsed = TestScripts.parse(handler.code)
                    conditions += parsed.conditions
                    customScript.setTextSilently(parsed.customCode)
                }
                null -> {
                    externalHandler = null
                    customScript.setTextSilently("")
                }
            }
            tableModel.fireTableDataChanged()
            updateExternalState()
            updatePreview()
        } finally {
            silent = false
        }
    }

    private fun updateExternalState() {
        val external = externalHandler
        externalPanel.isVisible = external != null
        externalLabel.text = external?.let { "Response handler is the external file '${it.path}'." } ?: ""
        table.isEnabled = external == null
        customScript.isEnabled = external == null
    }

    private fun currentHandler(): Script? {
        externalHandler?.let { return it }
        val code = TestScripts.generate(conditions, customScript.text)
        return if (code.isBlank()) null else Script.Inline(code)
    }

    private fun updatePreview() {
        val handler = currentHandler()
        preview.setTextSilently(
            when (handler) {
                is Script.Inline -> "> {%\n${handler.code}\n%}"
                is Script.FileRef -> "> ${handler.path}"
                null -> "// No response handler"
            },
        )
    }

    private fun fire() {
        updatePreview()
        if (!silent) onChanged(currentHandler())
    }
}
