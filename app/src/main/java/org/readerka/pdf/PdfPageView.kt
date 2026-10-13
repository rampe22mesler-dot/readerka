package org.readerka.pdf

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import org.readerka.data.Anchor
import org.readerka.data.Entry
import org.readerka.data.PdfViewState

/** A page canvas whose image, highlights and selection use the same normalized transform. */
class PdfPageView(context: Context) : View(context) {
    var bitmap: Bitmap? = null
        set(value) { field = value; invalidate() }
    var pageText: PageText? = null
    var highlights: List<Entry> = emptyList()
        set(value) { field = value; invalidate() }
    var onSelection: (String, Anchor) -> Unit = { _, _ -> }
    var onHighlight: (Entry) -> Unit = {}
    var onCenterTap: () -> Unit = {}
    var onPaneFocus: () -> Unit = {}
    var onViewport: (PdfViewState) -> Unit = {}
    private var desiredViewport: PdfViewState? = null
    var onNavigationKey: (KeyEvent) -> Boolean = { false }
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus) onPaneFocus()
    }
    var zoom = 1f
        private set
    private var panX = 0f
    private var panY = 0f
    private var startGlyph: Int? = null
    private var endGlyph: Int? = null
    private var selecting = false
    private var movingStart = false
    private var initialWord: IntRange? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val drawingRect = RectF()
    private var cachedSelection: Pair<String, Anchor>? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastMouseClick = 0L
    private var lastMousePoint = PointF()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPress = Runnable {
        val hit = hit(downX, downY) ?: return@Runnable
        val word = pageText?.wordAt(hit) ?: return@Runnable
        initialWord = word; startGlyph = word.first; endGlyph = word.last; selecting = true; movingStart = false
        parent?.requestDisallowInterceptTouchEvent(true)
        notifySelection()
    }
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            handler.removeCallbacks(longPress)
            selecting = false
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val old = zoom
            zoom = (zoom * detector.scaleFactor).coerceIn(1f, 5f)
            val ratio = zoom / old
            panX = detector.focusX - (detector.focusX - panX) * ratio
            panY = detector.focusY - (detector.focusY - panY) * ratio
            clampPan(); notifyViewport(); invalidate()
            return true
        }
    })
    init { isClickable = true; isFocusable = true; isFocusableInTouchMode = true; setOnKeyListener { _, _, event -> onNavigationKey(event) } }
    fun clearSelection() { handler.removeCallbacks(longPress); startGlyph = null; endGlyph = null; initialWord = null; cachedSelection = null; selecting = false; invalidate() }
    fun restoreSelection(value: Pair<String, Anchor>?) {
        // Compose may update during a drag before ACTION_UP publishes its new anchor.
        // Restoring the previous saved value must not cancel the active gesture.
        if (selecting) return
        if (value == cachedSelection) return
        if (value == null) { clearSelection(); return }
        val text = pageText ?: return
        val anchor = value.second
        if (text.page != anchor.page) return
        val first = text.glyphs.indexOfFirst { it.end > anchor.start }
        val last = text.glyphs.indexOfLast { it.start < anchor.end }
        if (first < 0 || last < first) return
        startGlyph = first; endGlyph = last; initialWord = null; cachedSelection = value; invalidate()
    }
    fun reset() { onViewport = {}; desiredViewport = null; handler.removeCallbacksAndMessages(null); parent?.requestDisallowInterceptTouchEvent(false); clearSelection(); fitWidth(); desiredViewport = null; bitmap = null; pageText = null; highlights = emptyList() }
    fun fitWidth() { zoom = 1f; panX = 0f; panY = 0f; notifyViewport(); invalidate() }
    fun setZoom(value: Float) { zoom = value.coerceIn(1f, 5f); clampPan(); notifyViewport(); invalidate() }
    private fun notifyViewport() {
        if (width > 0 && height > 0) PdfViewState(zoom, panX / width, panY / height).let { desiredViewport = it; onViewport(it) }
    }
    fun restoreViewport(value: PdfViewState) {
        if (!value.zoom.isFinite() || !value.panX.isFinite() || !value.panY.isFinite()) return
        desiredViewport = value
        if (width > 0 && height > 0) {
            zoom = value.zoom.coerceIn(1f, 5f); panX = value.panX * width; panY = value.panY * height
            clampPan(); invalidate()
        }
    }
    private fun clampPan() {
        panX = panX.coerceIn(width * (1f - zoom), 0f)
        panY = panY.coerceIn(height * (1f - zoom), 0f)
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val wanted = desiredViewport
        if (wanted != null) restoreViewport(wanted)
        else {
            if (oldw > 0) panX *= w.toFloat() / oldw
            if (oldh > 0) panY *= h.toFloat() / oldh
            clampPan(); invalidate()
        }
        notifyViewport()
    }
    fun screenRect(rect: org.readerka.data.PageRect) = RectF(
        panX + rect.left * width * zoom, panY + rect.top * height * zoom,
        panX + rect.right * width * zoom, panY + rect.bottom * height * zoom)
    private fun hit(x: Float, y: Float) = pageText?.hit((x - panX) / (width * zoom), (y - panY) / (height * zoom))
    private fun selection() = cachedSelection
    private fun notifySelection(finished: Boolean = false) {
        cachedSelection = startGlyph?.let { a -> endGlyph?.let { b -> pageText?.selection(a, b) } }
        if (finished) cachedSelection?.let { onSelection(it.first, it.second) }
        invalidate()
    }
    private fun drawHighlight(canvas: Canvas, r: org.readerka.data.PageRect) {
        drawingRect.set(panX + r.left * width * zoom, panY + r.top * height * zoom, panX + r.right * width * zoom, panY + r.bottom * height * zoom)
        canvas.drawRect(drawingRect, paint)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        paint.alpha = 255
        bitmap?.let { drawingRect.set(panX, panY, panX + width * zoom, panY + height * zoom); canvas.drawBitmap(it, null, drawingRect, paint) }
        for (entry in highlights) {
            paint.color = entry.color.argb.toInt(); paint.alpha = 95
            entry.anchor?.rects?.forEach { drawHighlight(canvas, it) }
        }
        selection()?.second?.rects?.let { rects ->
            paint.color = 0xFF4C789F.toInt(); paint.alpha = 85
            rects.forEach { drawHighlight(canvas, it) }
            paint.alpha = 255
            for (r in listOfNotNull(rects.firstOrNull(), rects.lastOrNull())) {
                val sr = screenRect(r)
                canvas.drawCircle(sr.centerX(), sr.bottom + 10 * resources.displayMetrics.density, 7 * resources.displayMetrics.density, paint)
            }
        }
        paint.alpha = 255
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onPaneFocus()
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                downX = event.x; downY = event.y; lastX = event.x; lastY = event.y
                val rects = selection()?.second?.rects
                val handles = listOfNotNull(rects?.firstOrNull(), rects?.lastOrNull())
                val at = handles.indexOfFirst { r ->
                    val sr = screenRect(r)
                    kotlin.math.hypot(event.x - sr.centerX(), event.y - (sr.bottom + 10 * resources.displayMetrics.density)) < 28 * resources.displayMetrics.density
                }
                val mouse = event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
                if (mouse) {
                    val hit = hit(event.x, event.y)
                    val double = event.eventTime - lastMouseClick in 1..ViewConfiguration.getDoubleTapTimeout().toLong() && kotlin.math.hypot(event.x - lastMousePoint.x, event.y - lastMousePoint.y) < slop * 2
                    lastMouseClick = event.eventTime; lastMousePoint = PointF(event.x, event.y)
                    if (hit != null) {
                        val word = if (double || event.buttonState and MotionEvent.BUTTON_SECONDARY != 0) pageText?.wordAt(hit) else null
                        startGlyph = word?.first ?: hit; endGlyph = word?.last ?: hit; initialWord = word
                        selecting = true; movingStart = false; parent?.requestDisallowInterceptTouchEvent(true)
                        notifySelection()
                    }
                } else if (at >= 0) { initialWord = null; selecting = true; movingStart = at == 0; parent?.requestDisallowInterceptTouchEvent(true) }
                else { selecting = false; handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong()) }
                if (zoom > 1f) parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1 || scaleDetector.isInProgress) return true
                if (selecting) {
                    hit(event.x, event.y)?.let { at ->
                        val word = initialWord
                        if (word != null) { startGlyph = minOf(at, word.first); endGlyph = maxOf(at, word.last) }
                        else if (movingStart) startGlyph = at else endGlyph = at
                    }
                    notifySelection()
                } else {
                    if (kotlin.math.hypot(event.x - downX, event.y - downY) > slop) handler.removeCallbacks(longPress)
                    if (zoom > 1f) { panX += event.x - lastX; panY += event.y - lastY; clampPan(); notifyViewport(); invalidate() }
                }
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                if (selecting) notifySelection(finished = true)
                else if (kotlin.math.hypot(event.x - downX, event.y - downY) < slop) {
                    val nx = (event.x - panX) / (width * zoom)
                    val ny = (event.y - panY) / (height * zoom)
                    val highlighted = highlights.lastOrNull { e -> e.anchor?.rects?.any { it.contains(nx, ny) } == true }
                    if (highlighted != null) onHighlight(highlighted)
                    else if (nx in .25f.. .75f) onCenterTap()
                    performClick()
                }
                selecting = false; parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> { handler.removeCallbacks(longPress); selecting = false; parent?.requestDisallowInterceptTouchEvent(false) }
        }
        return true
    }
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val amount = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if ((event.metaState and android.view.KeyEvent.META_CTRL_ON != 0)) { setZoom((zoom * Math.pow(1.15, amount.toDouble()).toFloat()).coerceIn(1f, 5f)); return true }
            if ((event.metaState and android.view.KeyEvent.META_SHIFT_ON != 0) || event.getAxisValue(MotionEvent.AXIS_HSCROLL) != 0f) {
                panX += (if ((event.metaState and android.view.KeyEvent.META_SHIFT_ON != 0)) amount else event.getAxisValue(MotionEvent.AXIS_HSCROLL)) * 48 * resources.displayMetrics.density
                clampPan(); notifyViewport(); invalidate(); return true
            }
        }
        return super.onGenericMotionEvent(event)
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDetachedFromWindow() { handler.removeCallbacksAndMessages(null); super.onDetachedFromWindow() }
}
