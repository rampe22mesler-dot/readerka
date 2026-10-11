package org.readerka.desktop

import java.awt.*
import kotlin.math.max

/** FlowLayout with a wrapping preferred height, including narrow split panes. */
class WrapFlowLayout : FlowLayout(LEADING, 8, 5) {
    override fun preferredLayoutSize(target: Container): Dimension =
        synchronized(target.treeLock) {
            val insets = target.insets
            val available =
                (target.width.takeIf { it > 0 }
                    ?: target.parent?.width?.takeIf { it > 0 }
                    ?: Int.MAX_VALUE) - insets.left - insets.right - 2 * hgap
            var rowWidth = 0
            var rowHeight = 0
            var width = 0
            var height = 0
            target.components
                .filter { it.isVisible }
                .forEach { c ->
                    val d = c.preferredSize
                    val gap = if (rowWidth > 0) hgap else 0
                    if (rowWidth > 0 && rowWidth + gap + d.width > available) {
                        width = max(width, rowWidth)
                        height += rowHeight + vgap
                        rowWidth = 0
                        rowHeight = 0
                    }
                    if (rowWidth > 0) rowWidth += hgap
                    rowWidth += d.width
                    rowHeight = max(rowHeight, d.height)
                }
            width = max(width, rowWidth)
            height += rowHeight
            Dimension(
                width + insets.left + insets.right + 2 * hgap,
                height + insets.top + insets.bottom + 2 * vgap,
            )
        }
}
