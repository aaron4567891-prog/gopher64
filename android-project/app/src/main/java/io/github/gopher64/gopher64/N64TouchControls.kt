package io.github.gopher64.gopher64

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Multi-touch N64 controls, independent of the user's physical-controller profile. */
class N64TouchControls(
    context: Context,
    private val changed: (Int, Int, Int) -> Unit
) : View(context) {
    private data class Key(val label: String, val bit: Int, val x: Float, val y: Float)
    private val keys = mutableListOf<Key>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var unit = 1f
    private var stickX = 0f
    private var stickY = 0f
    private var stickPointer = -1
    private var axisX = 0
    private var axisY = 0
    private var held = 0

    init {
        isFocusable = false
        contentDescription = "N64 touch controls"
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        release()
        unit = minOf(48f * resources.displayMetrics.density, w / 13f, h / 9f)
        stickX = unit * 2.0f
        stickY = h - unit * 2.0f
        keys.clear()
        fun key(label: String, bit: Int, x: Float, y: Float) {
            keys.add(Key(label, bit, x, y))
        }
        key("L", 13, unit, unit)
        key("Z", 5, unit * 2.5f, unit)
        key("R", 12, w - unit, unit)
        key("Start", 4, w / 2f, h - unit)
        key("↑", 3, unit * 2f, unit * 2.5f)
        key("←", 1, unit, unit * 3.5f)
        key("→", 0, unit * 3f, unit * 3.5f)
        key("↓", 2, unit * 2f, unit * 4.5f)
        key("C↑", 11, w - unit * 2f, unit * 2.5f)
        key("C←", 9, w - unit * 3f, unit * 3.5f)
        key("C→", 8, w - unit, unit * 3.5f)
        key("C↓", 10, w - unit * 2f, unit * 4.5f)
        key("B", 6, w - unit * 3f, h - unit * 2f)
        key("A", 7, w - unit * 1.3f, h - unit * 1.3f)
    }

    private fun keyAt(x: Float, y: Float): Key? =
        keys.firstOrNull { hypot(x - it.x, y - it.y) <= unit * 0.55f }

    private fun inStick(x: Float, y: Float) =
        hypot(x - stickX, y - stickY) <= unit * 1.35f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            release()
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN ||
            event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            val i = event.actionIndex
            val x = event.getX(i)
            val y = event.getY(i)
            if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                !inStick(x, y) && keyAt(x, y) == null) return false
            if (stickPointer == -1 && inStick(x, y)) stickPointer = event.getPointerId(i)
        }
        var buttons = 0
        var xAxis = 0
        var yAxis = 0
        for (i in 0 until event.pointerCount) {
            if ((event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_POINTER_UP) && i == event.actionIndex) {
                if (event.getPointerId(i) == stickPointer) stickPointer = -1
                continue
            }
            val x = event.getX(i)
            val y = event.getY(i)
            if (event.getPointerId(i) == stickPointer) {
                val dx = (x - stickX) / (unit * 1.35f)
                val dy = (stickY - y) / (unit * 1.35f)
                val length = hypot(dx, dy)
                if (length > 0.12f) {
                    val scale = ((length - 0.12f) / 0.88f).coerceAtMost(1f) / length
                    xAxis = (dx * scale * 85).roundToInt()
                    yAxis = (dy * scale * 85).roundToInt()
                }
            } else {
                keyAt(x, y)?.let { buttons = buttons or (1 shl it.bit) }
            }
        }
        update(buttons, xAxis, yAxis)
        return true
    }

    private fun update(buttons: Int, x: Int, y: Int) {
        if (held == buttons && axisX == x && axisY == y) return
        held = buttons
        axisX = x
        axisY = y
        changed(buttons, x, y)
        invalidate()
    }

    fun release() {
        stickPointer = -1
        update(0, 0, 0)
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.style = Paint.Style.FILL
        for (key in keys) {
            paint.color = if (held and (1 shl key.bit) != 0)
                0xAA64B5F6.toInt() else 0x66555555
            canvas.drawCircle(key.x, key.y, unit * 0.55f, paint)
            paint.color = Color.WHITE
            paint.alpha = 190
            paint.textSize = unit * 0.38f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(key.label, key.x, key.y - (paint.ascent() + paint.descent()) / 2, paint)
        }
        paint.color = 0x66555555
        canvas.drawCircle(stickX, stickY, unit * 1.35f, paint)
        paint.color = 0xAABBBBBB.toInt()
        canvas.drawCircle(stickX + axisX / 85f * unit, stickY - axisY / 85f * unit,
            unit * 0.55f, paint)
    }
}
