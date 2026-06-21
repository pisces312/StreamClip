package com.pisces312.streamclip.audio

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.pisces312.streamclip.R

/**
 * 波形可视化 View，参考 Ringdroid 的 WaveformView。
 * 支持多级缩放、拖拽滚动、选区高亮、播放进度指示。
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface WaveformListener {
        fun waveformTouchStart(x: Float)
        fun waveformTouchMove(x: Float)
        fun waveformTouchEnd()
        fun waveformFling(vx: Float)
        fun waveformDraw()
        fun waveformZoomIn()
        fun waveformZoomOut()
        fun waveformLongPress(pos: Int)
    }

    // Paints
    private val gridPaint = Paint().apply {
        isAntiAlias = false
        color = context.getColor(R.color.waveform_grid)
    }
    private val selectedLinePaint = Paint().apply {
        isAntiAlias = false
        color = context.getColor(R.color.waveform_selected)
    }
    private val unselectedLinePaint = Paint().apply {
        isAntiAlias = false
        color = context.getColor(R.color.waveform_unselected)
    }
    private val unselectedBkgndLinePaint = Paint().apply {
        isAntiAlias = false
        color = context.getColor(R.color.waveform_unselected_bkgnd)
    }
    private val borderLinePaint = Paint().apply {
        isAntiAlias = true
        strokeWidth = 1.5f
        pathEffect = DashPathEffect(floatArrayOf(3f, 2f), 0f)
        color = context.getColor(R.color.waveform_border)
    }
    private val playbackLinePaint = Paint().apply {
        isAntiAlias = true
        strokeWidth = 2.5f
        color = context.getColor(R.color.waveform_playback)
    }
    private val timecodePaint = Paint().apply {
        textSize = 12f
        isAntiAlias = true
        color = context.getColor(R.color.waveform_timecode)
        setShadowLayer(2f, 1f, 1f, context.getColor(R.color.waveform_timecode_shadow))
    }

    // Scrollbar paints (Change 2)
    private val scrollbarTrackPaint = Paint().apply {
        isAntiAlias = true
        color = context.getColor(R.color.waveform_scrollbar_track)
    }
    private val scrollbarThumbPaint = Paint().apply {
        isAntiAlias = true
        color = context.getColor(R.color.waveform_scrollbar_thumb)
    }

    // Highlight paints (Change 3)
    private val highlightPaint = Paint().apply {
        isAntiAlias = false
        color = context.getColor(R.color.waveform_highlight)
        alpha = 80
    }
    private val highlightBorderPaint = Paint().apply {
        isAntiAlias = true
        strokeWidth = 2f
        color = context.getColor(R.color.waveform_highlight_border)
    }

    // Data
    private var waveformData: WaveformProcessor.WaveformData? = null
    private var sampleRate = 0
    private var samplesPerFrame = WaveformProcessor.SAMPLES_PER_FRAME

    private var lenByZoomLevel: IntArray = IntArray(0)
    private var valuesByZoomLevel: Array<DoubleArray> = emptyArray()
    private var zoomFactorByZoomLevel: DoubleArray = DoubleArray(0)
    private var heightsAtThisZoomLevel: IntArray? = null

    private var zoomLevel = 0
    private var numZoomLevels = 0
    private var offset = 0
    private var audioStart = 0   // overall audio range start (pixels)
    private var audioEnd = 0     // overall audio range end (pixels)
    private var selStart = -1    // user selection start (-1 = no selection)
    private var selEnd = -1      // user selection end
    private var playbackPos = -1
    private var density = 1.0f
    private var initialized = false
    private var initialScaleSpan = 0f

    // Scrollbar state (Change 2)
    private var scrollbarHeight = 24f
    private var scrollbarPadding = 2f
    private var scrollbarTouchSlop = 12f
    private var isDraggingScrollbar = false

    private var listener: WaveformListener? = null

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            listener?.waveformFling(vx)
            return true
        }
    })

    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            initialScaleSpan = Math.abs(d.currentSpanX)
            return true
        }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val scale = Math.abs(d.currentSpanX)
            if (scale - initialScaleSpan > 40) {
                listener?.waveformZoomIn()
                initialScaleSpan = scale
            }
            if (scale - initialScaleSpan < -40) {
                listener?.waveformZoomOut()
                initialScaleSpan = scale
            }
            return true
        }
    })

    private val longPressDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            val pos = offset + e.x.toInt()
            listener?.waveformLongPress(pos)
        }
    })

    init {
        isFocusable = false
    }

    fun setListener(l: WaveformListener) {
        listener = l
    }

    fun hasData(): Boolean = waveformData != null

    fun setData(data: WaveformProcessor.WaveformData, sampleRate: Int) {
        waveformData = data
        this.sampleRate = sampleRate
        this.samplesPerFrame = WaveformProcessor.SAMPLES_PER_FRAME

        lenByZoomLevel = data.lenByZoomLevel
        valuesByZoomLevel = data.valuesByZoomLevel
        zoomFactorByZoomLevel = data.zoomFactorByZoomLevel
        numZoomLevels = data.numZoomLevels
        zoomLevel = data.initialZoomLevel

        heightsAtThisZoomLevel = null
        initialized = true
        invalidate()
    }

    fun clearData() {
        waveformData = null
        initialized = false
        offset = 0
        audioStart = 0
        audioEnd = 0
        selStart = -1
        selEnd = -1
        playbackPos = -1
        invalidate()
    }

    fun isInitialized(): Boolean = initialized

    fun getZoomLevel(): Int = zoomLevel

    fun canZoomIn(): Boolean = zoomLevel > 0

    fun zoomIn() {
        if (canZoomIn()) {
            zoomLevel--
            audioStart *= 2
            audioEnd *= 2
            heightsAtThisZoomLevel = null
            val offsetCenter = offset + measuredWidth / 2
            offset = offsetCenter * 2 - measuredWidth / 2
            if (offset < 0) offset = 0
            invalidate()
        }
    }

    fun canZoomOut(): Boolean = zoomLevel < numZoomLevels - 1

    fun zoomOut() {
        if (canZoomOut()) {
            zoomLevel++
            audioStart /= 2
            audioEnd /= 2
            val offsetCenter = offset + measuredWidth / 2
            offset = offsetCenter / 2 - measuredWidth / 2
            if (offset < 0) offset = 0
            heightsAtThisZoomLevel = null
            invalidate()
        }
    }

    fun maxPos(): Int = if (numZoomLevels > 0) lenByZoomLevel[zoomLevel] else 0

    fun secondsToPixels(seconds: Double): Int {
        if (zoomLevel >= zoomFactorByZoomLevel.size) return 0
        val z = zoomFactorByZoomLevel[zoomLevel]
        return (z * seconds * sampleRate / samplesPerFrame + 0.5).toInt()
    }

    fun pixelsToSeconds(pixels: Int): Double {
        if (zoomLevel >= zoomFactorByZoomLevel.size) return 0.0
        val z = zoomFactorByZoomLevel[zoomLevel]
        return pixels * samplesPerFrame.toDouble() / (sampleRate * z)
    }

    fun millisecsToPixels(msecs: Int): Int {
        if (zoomLevel >= zoomFactorByZoomLevel.size) return 0
        val z = zoomFactorByZoomLevel[zoomLevel]
        return (msecs * sampleRate.toDouble() * z / (1000.0 * samplesPerFrame) + 0.5).toInt()
    }

    fun pixelsToMillisecs(pixels: Int): Int {
        if (zoomLevel >= zoomFactorByZoomLevel.size) return 0
        val z = zoomFactorByZoomLevel[zoomLevel]
        return (pixels * (1000.0 * samplesPerFrame) / (sampleRate * z) + 0.5).toInt()
    }

    fun setParameters(start: Int, end: Int, offset: Int) {
        audioStart = start
        audioEnd = end
        this.offset = offset
    }

    fun getOffset(): Int = offset

    fun setPlayback(pos: Int) {
        playbackPos = pos
    }

    /** Set user selection range (pixels). Controls waveform coloring. */
    fun setSelection(start: Int, end: Int) {
        selStart = minOf(start, end)
        selEnd = maxOf(start, end)
        invalidate()
    }

    /** Clear user selection — full waveform shown in normal color. */
    fun clearSelection() {
        selStart = -1
        selEnd = -1
        invalidate()
    }

    fun recomputeHeights(density: Float) {
        heightsAtThisZoomLevel = null
        this.density = density
        scrollbarHeight = 24 * density
        scrollbarPadding = 2 * density
        scrollbarTouchSlop = 12 * density
        timecodePaint.textSize = (12 * density).toInt().toFloat()
        playbackLinePaint.strokeWidth = 2.5f * density
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val totalWidth = maxPos().toFloat()
        val viewWidth = measuredWidth.toFloat()
        val scrollbarRegionTop = measuredHeight - scrollbarHeight - scrollbarPadding - scrollbarTouchSlop

        // Scrollbar touch handling (highest priority)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (event.y >= scrollbarRegionTop && totalWidth > viewWidth) {
                    isDraggingScrollbar = true
                    handleScrollbarDrag(event.x)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDraggingScrollbar) {
                    handleScrollbarDrag(event.x)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDraggingScrollbar) {
                    isDraggingScrollbar = false
                    return true
                }
            }
        }

        scaleGestureDetector.onTouchEvent(event)
        longPressDetector.onTouchEvent(event)
        if (gestureDetector.onTouchEvent(event)) return true

        when (event.action) {
            MotionEvent.ACTION_DOWN -> listener?.waveformTouchStart(event.x)
            MotionEvent.ACTION_MOVE -> listener?.waveformTouchMove(event.x)
            MotionEvent.ACTION_UP -> listener?.waveformTouchEnd()
        }
        return true
    }

    private fun handleScrollbarDrag(x: Float) {
        val totalWidth = maxPos().toFloat()
        val viewWidth = measuredWidth.toFloat()
        val scrollRange = totalWidth - viewWidth
        if (scrollRange <= 0) return

        val ratio = (x / viewWidth).coerceIn(0f, 1f)
        offset = (ratio * scrollRange).toInt()
        invalidate()
        listener?.waveformDraw()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (waveformData == null || !initialized) return

        if (heightsAtThisZoomLevel == null) {
            computeIntsForThisZoomLevel()
        }

        val measuredWidth = measuredWidth
        val measuredHeight = measuredHeight
        val start = offset
        var width = heightsAtThisZoomLevel!!.size - start
        val ctr = measuredHeight / 2

        if (width > measuredWidth) width = measuredWidth

        // 限制波形绘制区域：滚动条以上
        val waveformBottom = measuredHeight - scrollbarHeight - scrollbarPadding
        canvas.save()
        canvas.clipRect(0f, 0f, measuredWidth.toFloat(), waveformBottom)

        // 网格
        val onePixelInSecs = pixelsToSeconds(1)
        val onlyEveryFiveSecs = onePixelInSecs > 1.0 / 50.0
        var fractionalSecs = offset * onePixelInSecs
        var integerSecs = fractionalSecs.toInt()
        var i = 0
        while (i < width) {
            i++
            fractionalSecs += onePixelInSecs
            val integerSecsNew = fractionalSecs.toInt()
            if (integerSecsNew != integerSecs) {
                integerSecs = integerSecsNew
                if (!onlyEveryFiveSecs || integerSecs % 5 == 0) {
                    canvas.drawLine(i.toFloat(), 0f, i.toFloat(), measuredHeight.toFloat(), gridPaint)
                }
            }
        }

        // 波形 — 选区内亮色，选区外灰色
        for (j in 0 until width) {
            val paint: Paint
            if (selStart >= 0 && j + start in selStart until selEnd) {
                paint = selectedLinePaint
            } else {
                canvas.drawLine(j.toFloat(), 0f, j.toFloat(), measuredHeight.toFloat(), unselectedBkgndLinePaint)
                paint = unselectedLinePaint
            }
            val h = heightsAtThisZoomLevel!![start + j]
            canvas.drawLine(j.toFloat(), (ctr - h).toFloat(), j.toFloat(), (ctr + 1 + h).toFloat(), paint)

            if (j + start == playbackPos) {
                canvas.drawLine(j.toFloat(), 0f, j.toFloat(), measuredHeight.toFloat(), playbackLinePaint)
            }
        }

        // 非波形区域
        for (j in width until measuredWidth) {
            canvas.drawLine(j.toFloat(), 0f, j.toFloat(), measuredHeight.toFloat(), unselectedBkgndLinePaint)
        }

        // 选区高亮半透明覆盖 + 边界线
        if (selStart >= 0 && selEnd > selStart) {
            val x1 = (selStart - offset).coerceIn(0, measuredWidth).toFloat()
            val x2 = (selEnd - offset).coerceIn(0, measuredWidth).toFloat()
            if (x2 > x1) {
                canvas.drawRect(x1, 0f, x2, measuredHeight.toFloat(), highlightPaint)
                canvas.drawLine(x1, 0f, x1, measuredHeight.toFloat(), highlightBorderPaint)
                canvas.drawLine(x2, 0f, x2, measuredHeight.toFloat(), highlightBorderPaint)
            }
        }

        // 时间码
        var timecodeIntervalSecs = 1.0
        if (timecodeIntervalSecs / onePixelInSecs < 50) timecodeIntervalSecs = 5.0
        if (timecodeIntervalSecs / onePixelInSecs < 50) timecodeIntervalSecs = 15.0

        fractionalSecs = offset * onePixelInSecs
        var integerTimecode = (fractionalSecs / timecodeIntervalSecs).toInt()
        i = 0
        while (i < width) {
            i++
            fractionalSecs += onePixelInSecs
            integerSecs = fractionalSecs.toInt()
            val integerTimecodeNew = (fractionalSecs / timecodeIntervalSecs).toInt()
            if (integerTimecodeNew != integerTimecode) {
                integerTimecode = integerTimecodeNew
                val minutes = integerSecs / 60
                val seconds = integerSecs % 60
                val timecodeStr = "$minutes:${if (seconds < 10) "0$seconds" else "$seconds"}"
                val textOffset = 0.5f * timecodePaint.measureText(timecodeStr)
                canvas.drawText(timecodeStr, i - textOffset, (12 * density), timecodePaint)
            }
        }

        canvas.restore()

        drawScrollbar(canvas)

        listener?.waveformDraw()
    }

    private fun drawScrollbar(canvas: Canvas) {
        val totalWidth = maxPos().toFloat()
        val viewWidth = measuredWidth.toFloat()
        if (totalWidth <= viewWidth) return  // 不需要滚动条

        val trackTop = measuredHeight - scrollbarHeight - scrollbarPadding
        val trackBottom = measuredHeight - scrollbarPadding
        val cornerRadius = scrollbarHeight / 2

        // track
        canvas.drawRoundRect(
            0f, trackTop, viewWidth, trackBottom,
            cornerRadius, cornerRadius,
            scrollbarTrackPaint
        )

        // thumb
        val thumbWidth = (viewWidth / totalWidth) * viewWidth
        val scrollRange = totalWidth - viewWidth
        val thumbLeft = if (scrollRange > 0) (offset / scrollRange) * (viewWidth - thumbWidth) else 0f
        canvas.drawRoundRect(
            thumbLeft.coerceIn(0f, viewWidth - thumbWidth), trackTop,
            (thumbLeft + thumbWidth).coerceIn(0f, viewWidth), trackBottom,
            cornerRadius, cornerRadius,
            scrollbarThumbPaint
        )
    }

    private fun computeIntsForThisZoomLevel() {
        if (zoomLevel >= lenByZoomLevel.size) return
        val halfHeight = (measuredHeight / 2) - 1
        val len = lenByZoomLevel[zoomLevel]
        val heights = IntArray(len)
        val values = valuesByZoomLevel[zoomLevel]
        for (i in 0 until len) {
            heights[i] = (values[i] * halfHeight).toInt()
        }
        heightsAtThisZoomLevel = heights
    }
}
