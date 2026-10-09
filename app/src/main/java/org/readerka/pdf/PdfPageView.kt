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
    var onNavigationKey: (KeyEvent) -> Boolean = { false }
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
            clampPan(); invalidate()
            return true
        }
    })
    init { isClickable = true; isFocusable = true; isFocusableInTouchMode = true; setOnKeyListener { _, _, event -> onNavigationKey(event) } }
    fun clearSelection() { startGlyph = null; endGlyph = null; initialWord = null; cachedSelection = null; selecting = false; invalidate() }
    fun reset() { handler.removeCallbacksAndMessages(null); parent?.requestDisallowInterceptTouchEvent(false); clearSelection(); fitWidth(); bitmap = null; pageText = null; highlights = emptyList() }
    fun fitWidth() { zoom = 1f; panX = 0f; panY = 0f; invalidate() }
    fun setZoom(value: Float) { zoom = value.coerceIn(1f, 5f); clampPan(); invalidate() }
    private fun clampPan() {
        panX = panX.coerceIn(width * (1f - zoom), 0f)
        panY = panY.coerceIn(height * (1f - zoom), 0f)
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw > 0) panX *= w.toFloat() / oldw
        if (oldh > 0) panY *= h.toFloat() / oldh
        clampPan(); invalidate()
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
                if (at >= 0) { initialWord = null; selecting = true; movingStart = at == 0; parent?.requestDisallowInterceptTouchEvent(true) }
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
                    if (zoom > 1f) { panX += event.x - lastX; panY += event.y - lastY; clampPan(); invalidate() }
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
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDetachedFromWindow() { handler.removeCallbacksAndMessages(null); super.onDetachedFromWindow() }
}
