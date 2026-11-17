// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import helium314.keyboard.latin.R

/** Exact keycap colors for color presets made for the Mechanical style, lit from the top left. */
class KeycapColors(
    @ColorInt val highlight: Int, // top left corner of the face
    @ColorInt val faceLeft: Int, @ColorInt val faceRight: Int,
    @ColorInt val sideLeft: Int, @ColorInt val sideRight: Int,
    @ColorInt val wallTop: Int, @ColorInt val wallBottom: Int,
)

/** Keycaps with exact colors: one rounded block with a lit top rim, the face in its upper part and a darker front band. */
object MechanicalKeycaps {
    /** Keycap shades for any key color, so user-picked colors get the same 3D look (ratios from the Teal keycaps). */
    fun derive(@ColorInt key: Int) = KeycapColors(
        highlight = ColorUtils.blendARGB(key, Color.WHITE, 0.12f),
        faceLeft = key, faceRight = scale(key, 0.8f),
        sideLeft = scale(key, 0.85f), sideRight = scale(key, 0.6f),
        wallTop = scale(key, 0.72f), wallBottom = scale(key, 0.55f),
    )

    private fun scale(color: Int, factor: Float) = Color.argb(Color.alpha(color),
        (Color.red(color) * factor).toInt(), (Color.green(color) * factor).toInt(), (Color.blue(color) * factor).toInt())

    fun drawable(res: Resources, colors: KeycapColors): Drawable {
        val pressed = KeycapDrawable(res, colors, pressed = true)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_empty, android.R.attr.state_pressed), pressed)
            addState(intArrayOf(android.R.attr.state_empty), ColorDrawable(Color.TRANSPARENT))
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), KeycapDrawable(res, colors, pressed = false))
        }
    }
}

private class KeycapDrawable(res: Resources, private val c: KeycapColors, private val pressed: Boolean) : Drawable() {
    private val corner = res.getDimension(R.dimen.mechanical_keycap_corner)
    private val faceCorner = res.getDimension(R.dimen.mechanical_keycap_face_corner)
    private val side = res.getDimension(R.dimen.mechanical_keycap_side)
    private val top = res.getDimension(R.dimen.mechanical_keycap_top)
    private val bottom = res.getDimension(if (pressed) R.dimen.mechanical_keycap_bottom_pressed else R.dimen.mechanical_keycap_bottom)
    private val travel = if (pressed) res.getDimension(R.dimen.mechanical_keycap_travel) else 0f
    private val shadow = res.getDimension(R.dimen.mechanical_keycap_shadow)
    private val rim = res.getDimension(R.dimen.mechanical_keycap_rim)
    private val gap = res.getDimension(R.dimen.mechanical_keycap_gap)
    private val faceShadow = res.getDimension(R.dimen.mechanical_keycap_face_shadow)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outer = RectF()
    private val face = RectF()
    // pressed keys sink into the plate, so they are a bit darker
    private val shade = if (pressed) 0.88f else 1f

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        outer.set(b.left + gap, b.top + travel, b.right - gap, b.bottom - shadow)
        face.set(outer.left + side, outer.top + top, outer.right - side, outer.bottom - bottom)

        // contact shadow, so the key looks lifted off the plate
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = Color.argb(if (pressed) 60 else 120, 0, 0, 0)
        canvas.drawRoundRect(outer.left, outer.top + shadow, outer.right, outer.bottom + shadow, corner, corner, paint)

        // body: side color around the face, then the darker front band below it
        val bandStart = ((face.bottom - outer.top) / outer.height()).coerceIn(0f, 1f)
        val sideMid = mix(c.sideLeft, c.sideRight, 0.5f)
        paint.color = Color.WHITE
        paint.shader = LinearGradient(0f, outer.top, 0f, outer.bottom,
            intArrayOf(s(sideMid), s(sideMid), s(c.wallTop), s(c.wallBottom)),
            floatArrayOf(0f, bandStart - 0.02f, bandStart + 0.02f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(outer, corner, corner, paint)
        // the body curves away at its sides, the right one more as the light comes from the left
        paint.shader = LinearGradient(outer.left, 0f, outer.right, 0f,
            intArrayOf(Color.argb(60, 0, 0, 0), 0, 0, Color.argb(90, 0, 0, 0)),
            floatArrayOf(0f, 0.12f, 0.85f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(outer, corner, corner, paint)

        // face, raised above the body: it casts a soft shadow down and to the right
        paint.shader = null
        paint.color = s(c.faceRight)
        paint.setShadowLayer(faceShadow, faceShadow * 0.3f, faceShadow * 0.6f, Color.argb(125, 0, 0, 0))
        canvas.drawRoundRect(face, faceCorner, faceCorner, paint)
        paint.clearShadowLayer()
        // darker to the right, with a soft glow in its top left corner
        paint.color = Color.WHITE
        paint.shader = LinearGradient(face.left, 0f, face.right, 0f, s(c.faceLeft), s(c.faceRight), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(face, faceCorner, faceCorner, paint)
        paint.shader = RadialGradient(face.left, face.top, face.width() * 0.75f,
            intArrayOf(s(c.highlight), s(c.highlight) and 0xFFFFFF), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(face, faceCorner, faceCorner, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = rim
        // thin lit rim on the face's top edge, fading out down its sides
        val rimColor = s(mix(c.highlight, Color.WHITE, 0.12f))
        paint.shader = LinearGradient(0f, face.top, 0f, face.top + face.height() * 0.6f,
            rimColor, rimColor and 0xFFFFFF, Shader.TileMode.CLAMP)
        val inset = rim / 2
        canvas.drawRoundRect(face.left + inset, face.top + inset, face.right - inset, face.bottom - inset,
            faceCorner, faceCorner, paint)
        // thin dark outline, so the key edge melts into the plate
        paint.shader = null
        paint.color = Color.argb(110, 0, 0, 0)
        canvas.drawRoundRect(outer.left + inset, outer.top + inset, outer.right - inset, outer.bottom - inset, corner, corner, paint)
        paint.style = Paint.Style.FILL
        paint.shader = null
    }

    private fun s(color: Int): Int = if (shade == 1f) color else Color.argb(Color.alpha(color),
        (Color.red(color) * shade).toInt(), (Color.green(color) * shade).toInt(), (Color.blue(color) * shade).toInt())

    private fun mix(a: Int, b: Int, ratio: Float) = ColorUtils.blendARGB(a, b, ratio)

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
