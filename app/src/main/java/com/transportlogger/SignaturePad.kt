package com.transportlogger

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A strip of paper to sign on with a finger. The ink is the colour it prints
 * in; [toPng] crops to it on a transparent background, so the picture sits on
 * the form's line without a white box around it.
 *
 * A saved signature can be shown with [showSaved] until the first new stroke
 * replaces it.
 */
class SignaturePad @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val strokeWidth = 3f * density

    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.paper_ink)
        style = Paint.Style.STROKE
        strokeWidth = this@SignaturePad.strokeWidth
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.paper_line)
        strokeWidth = 1f * density
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val path = Path()
    private val inkBounds = RectF()
    private var lastX = 0f
    private var lastY = 0f
    private var saved: Bitmap? = null

    /** Called when the pad goes from empty to inked or back. */
    var onChange: (() -> Unit)? = null

    /** Something new has been drawn. */
    var hasInk = false
        private set

    /** Shows [bitmap] as what is on file, until the first new stroke. */
    fun showSaved(bitmap: Bitmap?) {
        saved = bitmap
        invalidate()
    }

    val showsSaved: Boolean get() = saved != null

    fun clear() {
        path.reset()
        inkBounds.setEmpty()
        hasInk = false
        saved = null
        invalidate()
        onChange?.invoke()
    }

    /** Where the signature line runs: the lower part, like on the form. */
    private val baseline get() = height * 0.72f

    override fun onDraw(canvas: Canvas) {
        val margin = 20f * density
        canvas.drawLine(margin, baseline, width - margin, baseline, line)
        saved?.let { bmp ->
            // Fit it above the line, as it will stand on the form.
            val room = RectF(margin, 12f * density, width - margin, baseline)
            val scale = min(room.width() / bmp.width, room.height() / bmp.height)
            val w = bmp.width * scale
            val h = bmp.height * scale
            val left = room.centerX() - w / 2
            canvas.drawBitmap(bmp, null, RectF(left, room.bottom - h, left + w, room.bottom), bitmapPaint)
        }
        canvas.drawPath(path, ink)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Signing must not scroll the page.
                parent?.requestDisallowInterceptTouchEvent(true)
                saved = null
                path.moveTo(x, y)
                // A dot is a stroke too (the i's).
                path.lineTo(x + 0.1f, y)
                grow(x, y)
                lastX = x
                lastY = y
                if (!hasInk) {
                    hasInk = true
                    onChange?.invoke()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.historySize) to(e.getHistoricalX(i), e.getHistoricalY(i))
                to(x, y)
            }
            MotionEvent.ACTION_UP -> {
                path.lineTo(x, y)
                grow(x, y)
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
            else -> return false
        }
        invalidate()
        return true
    }

    /** Smooth the stroke through the midpoints of the samples. */
    private fun to(x: Float, y: Float) {
        path.quadTo(lastX, lastY, (x + lastX) / 2, (y + lastY) / 2)
        grow(x, y)
        lastX = x
        lastY = y
    }

    private fun grow(x: Float, y: Float) {
        if (hasInk) inkBounds.union(x, y) else inkBounds.set(x, y, x, y)
    }

    /**
     * The new strokes as a PNG cropped to the ink, at most [maxHeight] pixels
     * tall (it prints at most about 2.5 cm high, so this is ample).
     * Null when nothing new was drawn.
     */
    fun toPng(maxHeight: Int = 400): ByteArray? {
        if (!hasInk) return null
        // Cropped to the ink, not to the view: after turning the phone, strokes
        // made on the wider screen may lie past the edge but still count.
        val pad = strokeWidth
        val left = inkBounds.left - pad
        val top = inkBounds.top - pad
        val right = inkBounds.right + pad
        val bottom = inkBounds.bottom + pad
        val w = max(1, (right - left).roundToInt())
        val h = max(1, (bottom - top).roundToInt())

        val scale = min(1f, maxHeight.toFloat() / h)
        val bmp = Bitmap.createBitmap(
            max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()), Bitmap.Config.ARGB_8888
        )
        Canvas(bmp).apply {
            scale(scale, scale)
            translate(-left, -top)
            drawPath(path, ink)
        }
        return ByteArrayOutputStream().use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            bmp.recycle()
            it.toByteArray()
        }
    }
}
