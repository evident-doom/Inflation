package inflation.ui

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import kotlin.math.max

/**
 * A [FlowLayout] that asks for the height of all its rows, not one: a plain FlowLayout wraps its
 * components onto more rows but still asks for a single row's height, so in a window's border the rows
 * past the first are cut off. The rows are laid out to the width the container is given, or, before it
 * has one, to the width of the window around it. Whoever holds it revalidates it when its width changes,
 * so the rows are counted again.
 */
class WrapLayout(align: Int = LEFT, hgap: Int = 5, vgap: Int = 5) : FlowLayout(align, hgap, vgap) {
    override fun preferredLayoutSize(target: Container) = size(target, preferred = true)

    override fun minimumLayoutSize(target: Container) = size(target, preferred = false).apply { width -= hgap + 1 }

    private fun size(target: Container, preferred: Boolean): Dimension = synchronized(target.treeLock) {
        // The width to wrap at: the container's own, else its parent's, else (not yet shown) no limit.
        var width = target.size.width
        var parent: Container? = target
        while (width == 0 && parent?.parent != null) { parent = parent.parent; width = parent!!.size.width }
        if (width == 0) width = Int.MAX_VALUE
        val insets = target.insets
        val maxWidth = width - (insets.left + insets.right + hgap * 2)
        val whole = Dimension(0, 0)
        var rowWidth = 0
        var rowHeight = 0
        for (c in target.components) {
            if (!c.isVisible) continue
            val d = if (preferred) c.preferredSize else c.minimumSize
            if (rowWidth > 0 && rowWidth + hgap + d.width > maxWidth) {
                whole.width = max(whole.width, rowWidth); whole.height += rowHeight + vgap
                rowWidth = 0; rowHeight = 0
            }
            if (rowWidth > 0) rowWidth += hgap
            rowWidth += d.width
            rowHeight = max(rowHeight, d.height)
        }
        whole.width = max(whole.width, rowWidth); whole.height += rowHeight
        whole.width += insets.left + insets.right + hgap * 2
        whole.height += insets.top + insets.bottom + vgap * 2
        whole
    }
}
