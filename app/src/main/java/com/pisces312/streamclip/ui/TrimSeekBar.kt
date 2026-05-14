package com.pisces312.streamclip.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.pisces312.streamclip.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 自定义截取进度条
 * 显示 [ 和 ] 两个可拖动标记，支持点击跳转
 */
class TrimSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface OnRangeChangeListener {
        fun onRangeChanged(startSec: Int, endSec: Int, fromUser: Boolean, draggingEnd: Boolean)
    }

    private var listener: OnRangeChangeListener? = null
    private var isDragging = false

    // 视频总时长（秒）
    var durationSec: Int = 0
        set(value) {
            field = max(0, value)
            if (endSec > field) endSec = field
            if (startSec > endSec) startSec = endSec
            invalidate()
        }

    // 开始/结束时间（秒）
    var startSec: Int = 0
        private set
    var endSec: Int = 0
        private set

    // 标记宽度（dp转px）
    private val markerWidth = dpToPx(12f)
    private val markerHeight = dpToPx(24f)
    private val trackHeight = dpToPx(4f)
    private val touchSlop = dpToPx(20f)

    // 颜色（与布局中开始/结束时间标签文字颜色一致）
    private val trackColor = ContextCompat.getColor(context, R.color.purple_500)
    private val trackBackgroundColor = ContextCompat.getColor(context, R.color.gray_600)
    private val markerColor = ContextCompat.getColor(context, R.color.white)
    private val selectedTrackColor = ContextCompat.getColor(context, R.color.teal_200)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackRect = RectF()
    private val selectedRect = RectF()

    // 当前拖动的标记：0=无, 1=开始, 2=结束
    private var draggingMarker = 0

    fun setOnRangeChangeListener(l: OnRangeChangeListener?) {
        listener = l
    }

    fun setRange(start: Int, end: Int) {
        startSec = max(0, min(start, durationSec))
        endSec = max(startSec, min(end, durationSec))
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateRects()
    }

    private fun updateRects() {
        val centerY = height / 2f
        val trackTop = centerY - trackHeight / 2
        val trackBottom = centerY + trackHeight / 2

        trackRect.set(
            paddingLeft.toFloat(),
            trackTop,
            (width - paddingRight).toFloat(),
            trackBottom
        )

        if (durationSec > 0) {
            val startX = secToX(startSec)
            val endX = secToX(endSec)
            selectedRect.set(startX, trackTop, endX, trackBottom)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (durationSec <= 0) return

        updateRects()

        // 画背景轨道
        paint.color = trackBackgroundColor
        canvas.drawRoundRect(trackRect, trackHeight / 2, trackHeight / 2, paint)

        // 画选中区域
        paint.color = selectedTrackColor
        canvas.drawRoundRect(selectedRect, trackHeight / 2, trackHeight / 2, paint)

        // 画开始标记 [
        val startX = secToX(startSec)
        drawMarker(canvas, startX, true)

        // 画结束标记 ]
        val endX = secToX(endSec)
        drawMarker(canvas, endX, false)
    }

    private fun drawMarker(canvas: Canvas, x: Float, isStart: Boolean) {
        val centerY = height / 2f
        val halfHeight = markerHeight / 2
        val halfWidth = markerWidth / 2

        paint.color = markerColor
        paint.strokeWidth = dpToPx(2f)

        if (isStart) {
            // 画 [ 形状：左边竖线 + 上下横线
            val left = x - halfWidth
            val right = x + halfWidth
            val top = centerY - halfHeight
            val bottom = centerY + halfHeight

            // 左边竖线
            canvas.drawLine(left, top, left, bottom, paint)
            // 上横线
            canvas.drawLine(left, top, right, top, paint)
            // 下横线
            canvas.drawLine(left, bottom, right, bottom, paint)
        } else {
            // 画 ] 形状：右边竖线 + 上下横线
            val left = x - halfWidth
            val right = x + halfWidth
            val top = centerY - halfHeight
            val bottom = centerY + halfHeight

            // 右边竖线
            canvas.drawLine(right, top, right, bottom, paint)
            // 上横线
            canvas.drawLine(left, top, right, top, paint)
            // 下横线
            canvas.drawLine(left, bottom, right, bottom, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val startX = secToX(startSec)
                val endX = secToX(endSec)

                // 判断是否点击了标记
                draggingMarker = when {
                    abs(x - startX) < touchSlop -> 1
                    abs(x - endX) < touchSlop -> 2
                    else -> 0
                }

                if (draggingMarker == 0) {
                    // 点击轨道，seek 到该位置
                    val sec = xToSec(x)
                    // 判断离哪个标记更近，移动那个标记
                    if (abs(sec - startSec) < abs(sec - endSec)) {
                        startSec = max(0, min(sec, endSec - 1))
                        draggingMarker = 1
                    } else {
                        endSec = max(startSec + 1, min(sec, durationSec))
                        draggingMarker = 2
                    }
                    notifyListener(true)
                    invalidate()
                }
                isDragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingMarker == 0) return false
                val sec = xToSec(event.x)
                when (draggingMarker) {
                    1 -> startSec = max(0, min(sec, endSec - 1))
                    2 -> endSec = max(startSec + 1, min(sec, durationSec))
                }
                notifyListener(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingMarker = 0
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun secToX(sec: Int): Float {
        if (durationSec <= 0) return paddingLeft.toFloat()
        val availableWidth = width - paddingLeft - paddingRight
        val ratio = sec.toFloat() / durationSec
        return paddingLeft + ratio * availableWidth
    }

    private fun xToSec(x: Float): Int {
        if (durationSec <= 0) return 0
        val availableWidth = width - paddingLeft - paddingRight
        val ratio = (x - paddingLeft) / availableWidth
        return max(0, min((ratio * durationSec).toInt(), durationSec))
    }

    private fun notifyListener(fromUser: Boolean) {
        listener?.onRangeChanged(startSec, endSec, fromUser, draggingMarker == 2)
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }
}
