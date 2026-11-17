// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import kotlin.math.abs

/**
 * Resize mode like SwiftKey's: a frame around the keyboard whose top edge is dragged to change the height.
 * [keyboardTop] and [keyboardBottom] are the key area in this view (without the navigation bar padding), [onDone] gets the new height factor (1 = unchanged), null to reset.
 */
@SuppressLint("ViewConstructor")
class ResizeOverlayView(
    context: Context,
    private val keyboardTop: () -> Int,
    private val keyboardBottom: () -> Int,
    private val currentScale: Float, // the height setting the keyboard currently has
    private val onDone: (Float?) -> Unit,
) : View(context) {
    private val colors = Settings.getValues().mColors
    private val accent = colors.get(ColorType.ACTION_KEY_BACKGROUND)
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frame = RectF()
    private val resetButton = RectF()
    private val doneButton = RectF()
    private var top = -1f
    private var dragging = false

    private fun bottom() = keyboardBottom().toFloat()
    private fun originalHeight() = bottom() - keyboardTop()

    override fun onDraw(canvas: Canvas) {
        val origTop = keyboardTop().toFloat()
        if (top < 0) top = origTop
        // dim everything outside the new keyboard area
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(140, 0, 0, 0)
        canvas.drawRect(0f, 0f, width.toFloat(), top, paint)

        // darker keys inside the frame, so the label and buttons are readable
        paint.color = Color.argb(110, 0, 0, 0)
        canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), paint)

        val stroke = 2 * density
        frame.set(stroke, top + stroke, width - stroke, bottom() - stroke)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = accent
        canvas.drawRoundRect(frame, 8 * density, 8 * density, paint)

        // handle on the top edge
        paint.style = Paint.Style.FILL
        val handleW = 56 * density
        val handleH = 8 * density
        canvas.drawRoundRect(width / 2f - handleW / 2, top - handleH / 2, width / 2f + handleW / 2, top + handleH / 2,
            handleH, handleH, paint)

        // percentage and buttons in the middle of the frame
        val centerY = (top + bottom()) / 2f
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 16 * density * resources.configuration.fontScale
        paint.color = Color.WHITE
        val factor = (bottom() - top) / originalHeight()
        canvas.drawText("${Math.round(currentScale * factor * 100)}%", width / 2f, centerY - 28 * density, paint)
        val buttonW = 110 * density
        val buttonH = 40 * density
        resetButton.set(width / 2f - buttonW - 8 * density, centerY - buttonH / 2, width / 2f - 8 * density, centerY + buttonH / 2)
        doneButton.set(width / 2f + 8 * density, centerY - buttonH / 2, width / 2f + buttonW + 8 * density, centerY + buttonH / 2)
        button(canvas, resetButton, context.getString(R.string.quick_reset), Color.argb(200, 60, 60, 60))
        button(canvas, doneButton, context.getString(R.string.quick_done), accent)
    }

    private fun button(canvas: Canvas, rect: RectF, label: String, color: Int) {
        paint.color = color
        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, paint)
        paint.color = Color.WHITE
        canvas.drawText(label, rect.centerX(), rect.centerY() - (paint.descent() + paint.ascent()) / 2, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val origHeight = originalHeight()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> dragging = abs(event.y - top) < 32 * density
            MotionEvent.ACTION_MOVE -> if (dragging) {
                // same range as the height setting, 50% to 150%
                top = event.y.coerceIn(bottom() - origHeight * MAX_SCALE / currentScale,
                    bottom() - origHeight * MIN_SCALE / currentScale).coerceAtLeast(0f)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging && resetButton.contains(event.x, event.y)) onDone(null)
                else if (!dragging && doneButton.contains(event.x, event.y)) onDone((bottom() - top) / origHeight)
                dragging = false
            }
        }
        return true // resize mode is modal, nothing below gets touches
    }

    companion object {
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 1.5f
    }
}
