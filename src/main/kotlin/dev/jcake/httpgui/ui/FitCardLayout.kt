package dev.jcake.httpgui.ui

import java.awt.CardLayout
import java.awt.Container
import java.awt.Dimension

/** A [CardLayout] whose preferred size is that of the visible card rather than the largest one. */
class FitCardLayout : CardLayout() {
    override fun preferredLayoutSize(parent: Container): Dimension {
        val visible = parent.components.firstOrNull { it.isVisible } ?: return Dimension(0, 0)
        val size = visible.preferredSize
        val insets = parent.insets
        return Dimension(size.width + insets.left + insets.right, size.height + insets.top + insets.bottom)
    }
}
