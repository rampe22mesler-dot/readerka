package org.readerka.desktop

import java.awt.*
import java.awt.event.HierarchyEvent
import javax.swing.*

/** One clock for visible decorative transitions. No timer runs while the interface is idle. */
object ChromeMotion {
    private val active = linkedSetOf<ChromeTransition>()
    private val timer: Timer = Timer(16) {
        active.toList().forEach { it.tick() }
        if (active.isEmpty()) stopClock()
    }
    var optimized = false
        private set

    fun configure(optimized: Boolean) {
        this.optimized = optimized
        System.setProperty("flatlaf.animation", (!optimized).toString())
        if (optimized) {
            active.toList().forEach { it.finish() }
            timer.stop()
        }
    }

    internal fun start(transition: ChromeTransition) {
        active += transition
        if (!timer.isRunning) timer.start()
    }
    internal fun remove(transition: ChromeTransition) {
        active -= transition
        if (active.isEmpty()) timer.stop()
    }
    private fun stopClock() = timer.stop()
}

/** Only paint changes. Actions, focus, selection and component bounds remain immediate. */
internal class ChromeTransition(private val owner: JComponent, initial: Float = 0f) {
    var value = initial
        private set
    private var from = initial
    private var target = initial
    private var started = 0L

    init {
        owner.addHierarchyListener {
            if (it.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && !owner.isShowing) finish()
        }
    }
    fun to(next: Float) {
        if (target == next) return
        from = value
        target = next
        started = System.nanoTime()
        if (ChromeMotion.optimized || !owner.isShowing) finish() else ChromeMotion.start(this)
    }
    fun tick() {
        if (!owner.isShowing || ChromeMotion.optimized) { finish(); return }
        val progress = ((System.nanoTime() - started) / 150_000_000.0).coerceIn(0.0, 1.0).toFloat()
        val eased = 1f - (1f - progress) * (1f - progress) * (1f - progress)
        value = from + (target - from) * eased
        owner.repaint()
        if (progress >= 1f) finish()
    }
    fun finish() {
        value = target
        ChromeMotion.remove(this)
        owner.repaint()
    }
}

private class ButtonMotion(private val button: AbstractButton) {
    private val transition = ChromeTransition(button)
    init {
        button.isContentAreaFilled = false
        button.isRolloverEnabled = true
        button.model.addChangeListener { update() }
        button.addPropertyChangeListener("enabled") { update() }
    }
    private fun update() = transition.to(when {
        !button.isEnabled -> 0f
        button.model.isPressed && button.model.isArmed -> 1f
        button.isSelected -> .85f
        button.model.isRollover -> .6f
        else -> 0f
    })
    fun paint(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val toolbar = button.getClientProperty("JButton.buttonType") == "toolBarButton"
        val surface = generateSequence(button.parent as? JComponent) { it.parent as? JComponent }
            .firstOrNull { it.isOpaque }?.background ?: sidebarColor()
        val base = if (toolbar) surface else UIManager.getColor("Button.background") ?: documentColor()
        val tint = selectionColor()
        val amount = transition.value
        g.color = Color(
            (base.red + (tint.red - base.red) * amount).toInt(),
            (base.green + (tint.green - base.green) * amount).toInt(),
            (base.blue + (tint.blue - base.blue) * amount).toInt())
        if (!toolbar || amount > 0f) g.fillRoundRect(1, 1, (button.width - 2).coerceAtLeast(0), (button.height - 2).coerceAtLeast(0), 10, 10)
        g.dispose()
    }
}

class MotionButton(text: String = "", icon: Icon? = null) : JButton(DesktopPlatform.shortcutText(text), icon) {
    override fun setToolTipText(text: String?) { super.setToolTipText(text?.let(DesktopPlatform::shortcutText)) }
    private val motion = ButtonMotion(this)
    override fun paintComponent(g: Graphics) { motion.paint(g); super.paintComponent(g) }
}

class MotionToggleButton(text: String) : JToggleButton(DesktopPlatform.shortcutText(text)) {
    override fun setToolTipText(text: String?) { super.setToolTipText(text?.let(DesktopPlatform::shortcutText)) }
    private val motion = ButtonMotion(this)
    override fun paintComponent(g: Graphics) { motion.paint(g); super.paintComponent(g) }
}

/** A short accent reveal on the selected tab, without repainting or fading document contents. */
class MotionTabbedPane : JTabbedPane() {
    private val reveal = ChromeTransition(this, 1f)
    init {
        putClientProperty("FlatLaf.style", "tabSelectionHeight: 0; cardTabSelectionHeight: 0")
        addChangeListener {
            reveal.to(0f)
            reveal.finish()
            reveal.to(1f)
        }
    }
    override fun paintChildren(g: Graphics) {
        super.paintChildren(g)
        if (selectedIndex < 0 || tabPlacement != TOP) return
        val bounds = getBoundsAt(selectedIndex) ?: return
        val inset = com.formdev.flatlaf.util.UIScale.scale(10)
        val thickness = com.formdev.flatlaf.util.UIScale.scale(2)
        val paint = g.create() as Graphics2D
        components.filterIsInstance<JViewport>().firstOrNull()?.bounds?.let {
            paint.clipRect(it.x, it.y, it.width, it.height)
        }
        paint.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        paint.color = accentColor()
        paint.composite = AlphaComposite.SrcOver.derive(.25f + .75f * reveal.value)
        val width = ((bounds.width - inset * 2).coerceAtLeast(0) * (.65f + .35f * reveal.value)).toInt()
        paint.fillRoundRect(bounds.x + (bounds.width - width) / 2, bounds.y + bounds.height - thickness - 1, width, thickness, thickness, thickness)
        paint.dispose()
    }
}
