// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import kotlin.random.Random

/** Key sounds and vibrations for the Mechanical style. */
class MechanicalFeedback(context: Context) {
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build())
        .build()
    private val keySounds = listOf(R.raw.mechanical_key_1, R.raw.mechanical_key_2, R.raw.mechanical_key_3)
        .map { soundPool.load(context, it, 1) }
    private val deleteSound = soundPool.load(context, R.raw.mechanical_delete, 1)
    private val enterSound = soundPool.load(context, R.raw.mechanical_enter, 1)
    private val spaceSound = soundPool.load(context, R.raw.mechanical_space, 1)

    /** [volume] is the user's key sound volume, negative means system default. */
    fun playSound(code: Int, volume: Float) {
        val sound = when (code) {
            KeyCode.DELETE -> deleteSound
            Constants.CODE_ENTER -> enterSound
            Constants.CODE_SPACE -> spaceSound
            else -> keySounds.random()
        }
        val v = if (volume < 0) DEFAULT_VOLUME else volume
        // slight pitch variation so repeated keys don't sound identical
        soundPool.play(sound, v, v, 1, 0, Random.nextDouble(0.95, 1.05).toFloat())
    }

    /** Returns false if the device can't do predefined click effects, so the caller can fall back. */
    fun vibrate(vibrator: Vibrator, code: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val effect = when (code) {
            KeyCode.DELETE, Constants.CODE_ENTER, Constants.CODE_SPACE -> VibrationEffect.EFFECT_HEAVY_CLICK
            else -> VibrationEffect.EFFECT_CLICK
        }
        vibrator.vibrate(VibrationEffect.createPredefined(effect))
        return true
    }

    fun release() = soundPool.release()

    companion object {
        private const val DEFAULT_VOLUME = 0.6f
    }
}
