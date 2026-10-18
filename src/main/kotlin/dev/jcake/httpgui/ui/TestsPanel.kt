package dev.jcake.httpgui.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
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
        emptyText.text = "No checks yet"
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
            .addExtraAction(templatesGroup())
            .disableUpDownActions()
            .createPanel()

        val content = JPanel(VerticalLayout(JBUI.scale(4))).apply {
            add(externalPanel)
            add(decorated)
            add(JBLabel("Additional response handler script:").apply { border = JBUI.Borders.emptyTop(6) })
            add(customScript)
        }
        add(content, BorderLayout.CENTER)
        updateExternalState()
        rowsChanged()
    }

    /** "Add from Template" dropdown next to the table's + button. */
    private fun templatesGroup(): ActionGroup = DefaultActionGroup("Add from Template", true).apply {
        templatePresentation.icon = AllIcons.Actions.AddList
        templatePresentation.description = "Add a common check"
        TEMPLATES.forEach { (label, condition) -> add(DumbAwareAction.create(label) { addCondition(condition) }) }
    }

    /** The table grows with its rows, so the request section grows instead of scrolling. */
    private fun rowsChanged() {
        table.visibleRowCount = maxOf(conditions.size, MIN_VISIBLE_ROWS)
        revalidate()
    }

    private fun addCondition(condition: TestCondition) {
        if (table.isEditing) table.cellEditor.stopCellEditing()
        conditions += condition
        tableModel.fireTableDataChanged()
        table.selectionModel.setSelectionInterval(conditions.lastIndex, conditions.lastIndex)
        rowsChanged()
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
            rowsChanged()
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

    private fun fire() {
        rowsChanged()
        if (!silent) onChanged(currentHandler())
    }

    companion object {
        private const val MIN_VISIBLE_ROWS = 2

        val TEMPLATES = listOf(
            "Status is 200" to TestCondition(TestSubject.STATUS, "", TestOperator.EQUALS, "200"),
            "Status is 2xx" to TestCondition(TestSubject.STATUS, "", TestOperator.LESS_THAN, "300"),
            "Response is JSON" to TestCondition(TestSubject.CONTENT_TYPE, "", TestOperator.CONTAINS, "json"),
            "JSON field exists" to TestCondition(TestSubject.JSON_PATH, "$.id", TestOperator.EXISTS, ""),
            "JSON field equals" to TestCondition(TestSubject.JSON_PATH, "$.id", TestOperator.EQUALS, ""),
            "Header is present" to TestCondition(TestSubject.HEADER, "Content-Type", TestOperator.EXISTS, ""),
            "Body contains text" to TestCondition(TestSubject.BODY, "", TestOperator.CONTAINS, ""),
        )
    }
}
