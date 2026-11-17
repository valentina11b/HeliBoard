// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.MechanicalFeedback
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.getStringResourceOrName
import helium314.keyboard.latin.utils.prefs

/** Grid of shortcuts to common settings, shown over the keyboard from the quick settings toolbar key. */
@SuppressLint("ViewConstructor")
class QuickSettingsView(context: Context, private val listener: KeyboardActionListener) : FrameLayout(context) {
    private val prefs = context.prefs()
    private val themeColors = Settings.getValues().mColors
    private val mechanical = themeColors.themeStyle == KeyboardTheme.STYLE_MECHANICAL
    private val density = resources.displayMetrics.density
    private val scroll = ScrollView(context)

    /** Sub-panel to show, or null for the main grid. */
    var page: String? = null
        set(value) { field = value; render() }

    init {
        themeColors.setBackground(this, ColorType.MAIN_BACKGROUND)
        isClickable = true // don't let touches through to the keyboard below
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        render()
    }

    private class Tile(val label: String, val icon: Int?, val active: Boolean = false, val action: () -> Unit)

    private fun render() {
        scroll.removeAllViews()
        val tiles = when (page) {
            PAGE_COLORS -> colorTiles()
            PAGE_STYLE -> styleTiles()
            PAGE_SOUND -> packTiles(Settings.PREF_SOUND_ON, Settings.PREF_MECHANICAL_SOUND_PACK, Defaults.PREF_SOUND_ON)
            PAGE_VIBRATION -> packTiles(Settings.PREF_VIBRATE_ON, Settings.PREF_MECHANICAL_VIBRATION_PACK, Defaults.PREF_VIBRATE_ON)
            PAGE_TRANSPARENCY -> transparencyTiles()
            else -> mainTiles()
        }
        // narrower keyboards get fewer columns, so the labels fit
        val narrow = Settings.getValues().mIsFloatingKeyboard || Settings.getValues().mOneHandedModeEnabled
        val grid = GridLayout(context).apply { columnCount = if (narrow) COLUMNS - 1 else COLUMNS }
        val gap = (4 * density).toInt()
        tiles.forEach { grid.addView(tileView(it), GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)
        ).apply { width = 0; height = (64 * density).toInt(); setMargins(gap, gap, gap, gap) }) }
        val content = if (page == PAGE_TRANSPARENCY) LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(grid)
            addView(transparencySlider())
        } else grid
        scroll.addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** Applied live while dragging, saved when released. */
    private fun transparencySlider(): View {
        val current = prefs.getInt(Settings.PREF_KEYBOARD_TRANSPARENCY, Defaults.PREF_KEYBOARD_TRANSPARENCY)
        val label = TextView(context).apply {
            text = context.getString(R.string.quick_transparency_value, current)
            setTextColor(themeColors.get(ColorType.KEY_TEXT))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        }
        val accent = android.content.res.ColorStateList.valueOf(themeColors.get(ColorType.ACTION_KEY_BACKGROUND))
        val slider = android.widget.SeekBar(context).apply {
            max = MAX_TRANSPARENCY
            progress = current.coerceAtMost(MAX_TRANSPARENCY)
            progressTintList = accent
            thumbTintList = accent
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: android.widget.SeekBar, value: Int, fromUser: Boolean) {
                    label.text = context.getString(R.string.quick_transparency_value, value)
                    KeyboardSwitcher.getInstance().previewTransparency(value)
                }
                override fun onStartTrackingTouch(bar: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(bar: android.widget.SeekBar) {
                    prefs.edit { putInt(Settings.PREF_KEYBOARD_TRANSPARENCY, bar.progress) }
                }
            })
        }
        val pad = (16 * density).toInt()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(label)
            addView(slider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (48 * density).toInt()))
        }
    }

    private fun tileView(tile: Tile): View {
        val textColor = themeColors.get(if (tile.active) ColorType.ACTION_KEY_BACKGROUND else ColorType.KEY_TEXT)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = if (mechanical) MechanicalKeycaps.drawable(resources, themeColors.mechanicalKeycaps?.get(ColorType.KEY_BACKGROUND)
                    ?: MechanicalKeycaps.derive(themeColors.get(ColorType.KEY_BACKGROUND)))
                else GradientDrawable().apply { cornerRadius = 8 * density; setColor(themeColors.get(ColorType.KEY_BACKGROUND)) }
            if (mechanical) // keep the content on the keycap face
                setPadding(0, resources.getDimensionPixelSize(R.dimen.mechanical_keycap_top), 0,
                    resources.getDimensionPixelSize(R.dimen.mechanical_keycap_bottom))
            tile.icon?.let { icon ->
                addView(ImageView(context).apply {
                    setImageDrawable(ContextCompat.getDrawable(context, icon)?.mutate()?.apply { setTint(textColor) })
                }, LinearLayout.LayoutParams((20 * density).toInt(), (20 * density).toInt()))
            }
            addView(TextView(context).apply {
                text = tile.label
                setTextColor(textColor)
                gravity = Gravity.CENTER
                maxLines = 1
                // long labels shrink to fit narrow tiles
                androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 7, 12, 1, TypedValue.COMPLEX_UNIT_SP)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (18 * density).toInt()).apply {
                val margin = (2 * density).toInt()
                setMargins(margin, 0, margin, 0)
            })
            setOnClickListener {
                AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, it, HapticEvent.KEY_PRESS)
                tile.action()
            }
        }
    }

    private fun mainTiles(): List<Tile> = listOfNotNull(
        Tile(context.getString(R.string.quick_close), R.drawable.tabler_close) { KeyboardSwitcher.getInstance().hideQuickSettings() },
        Tile(context.getString(R.string.quick_colors), R.drawable.tabler_palette) { page = PAGE_COLORS },
        Tile(context.getString(R.string.quick_style), R.drawable.tabler_style) { page = PAGE_STYLE },
        Tile(context.getString(R.string.quick_resize), R.drawable.tabler_resize) { KeyboardSwitcher.getInstance().startResizeMode() },
        Tile(context.getString(R.string.quick_transparency), R.drawable.tabler_transparency) { page = PAGE_TRANSPARENCY },
        Tile(context.getString(R.string.quick_one_handed), R.drawable.tabler_onehanded, Settings.getValues().mOneHandedModeEnabled) { code(KeyCode.TOGGLE_ONE_HANDED_MODE) },
        Tile(context.getString(R.string.quick_floating), R.drawable.tabler_floating, Settings.getValues().mIsFloatingKeyboard) { code(KeyCode.TOGGLE_FLOATING_WINDOW) },
        Tile(context.getString(R.string.quick_split), R.drawable.tabler_split, Settings.getValues().mIsSplitKeyboardEnabled) { code(KeyCode.SPLIT_LAYOUT) },
        toggle(R.string.quick_number_row, R.drawable.tabler_numpad, Settings.PREF_SHOW_NUMBER_ROW, Defaults.PREF_SHOW_NUMBER_ROW, reload = true),
        toggle(R.string.quick_hints, R.drawable.tabler_hints, Settings.PREF_SHOW_HINTS, Defaults.PREF_SHOW_HINTS, reload = true),
        toggle(R.string.quick_incognito, R.drawable.tabler_incognito, Settings.PREF_ALWAYS_INCOGNITO_MODE, Defaults.PREF_ALWAYS_INCOGNITO_MODE),
        toggle(R.string.quick_autocorrect, R.drawable.tabler_autocorrect, Settings.PREF_AUTO_CORRECTION, Defaults.PREF_AUTO_CORRECTION),
        Tile(context.getString(R.string.quick_sound), R.drawable.tabler_sound,
            prefs.getBoolean(Settings.PREF_SOUND_ON, Defaults.PREF_SOUND_ON)) { page = PAGE_SOUND },
        Tile(context.getString(R.string.quick_vibration), R.drawable.tabler_vibration,
            prefs.getBoolean(Settings.PREF_VIBRATE_ON, Defaults.PREF_VIBRATE_ON)) { page = PAGE_VIBRATION },
        Tile(context.getString(R.string.settings), R.drawable.tabler_settings) { code(KeyCode.SETTINGS) },
    )

    private fun toggle(label: Int, icon: Int, key: String, default: Boolean, reload: Boolean = false): Tile {
        val on = prefs.getBoolean(key, default)
        return Tile(context.getString(label), icon, on) {
            prefs.edit { putBoolean(key, !on) }
            if (reload) reloadAndReturn(null) else render()
        }
    }

    private fun back() = Tile(context.getString(R.string.quick_back), R.drawable.tabler_arrow_left) { page = null }

    private fun colorTiles(): List<Tile> {
        val isNight = ResourceUtils.isNight(resources) && prefs.getBoolean(Settings.PREF_THEME_DAY_NIGHT, Defaults.PREF_THEME_DAY_NIGHT)
        val key = if (isNight) Settings.PREF_THEME_COLORS_NIGHT else Settings.PREF_THEME_COLORS
        val current = prefs.getString(key, if (isNight) Defaults.PREF_THEME_COLORS_NIGHT else Defaults.PREF_THEME_COLORS)
        return listOf(back()) + KeyboardTheme.getAvailableDefaultColors(prefs, isNight).map { name ->
            Tile(name.getStringResourceOrName("theme_name_", context), null, name == current) {
                prefs.edit { putString(key, name) }
                reloadAndReturn(PAGE_COLORS)
            }
        }
    }

    private fun styleTiles(): List<Tile> {
        val current = prefs.getString(Settings.PREF_THEME_STYLE, Defaults.PREF_THEME_STYLE)
        return listOf(back()) + KeyboardTheme.STYLES.map { style ->
            Tile(style.getStringResourceOrName("style_name_", context), null, style == current) {
                // icons follow the style, like when choosing it in settings for the first time
                prefs.edit { putString(Settings.PREF_THEME_STYLE, style); putString(Settings.PREF_ICON_STYLE, style) }
                reloadAndReturn(PAGE_STYLE)
            }
        }
    }

    private fun transparencyTiles(): List<Tile> = listOf(back())

    /** Off, plus the Mechanical packs when that style is active. */
    private fun packTiles(onKey: String, packKey: String, onDefault: Boolean): List<Tile> {
        val on = prefs.getBoolean(onKey, onDefault)
        val currentPack = prefs.getString(packKey, MechanicalFeedback.DEFAULT_PACK)
        val off = Tile(context.getString(R.string.quick_off), null, !on) {
            prefs.edit { putBoolean(onKey, false) }
            render()
        }
        if (!mechanical) return listOf(back(), off, Tile(context.getString(R.string.quick_on), null, on) {
            prefs.edit { putBoolean(onKey, true) }
            render()
        })
        return listOf(back(), off) + MechanicalFeedback.PACK_NAMES.map { pack ->
            Tile(pack.getStringResourceOrName("sound_pack_", context), null, on && pack == currentPack) {
                prefs.edit { putBoolean(onKey, true); putString(packKey, pack) }
                render()
            }
        }
    }

    private fun code(code: Int) {
        KeyboardSwitcher.getInstance().hideQuickSettings()
        listener.onCodeInput(code, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
    }

    /** Settings that change the keyboard layout need a reload, the panel comes back afterwards. */
    private fun reloadAndReturn(returnPage: String?) {
        KeyboardSwitcher.getInstance().reloadWithQuickSettings(returnPage)
    }

    companion object {
        private const val COLUMNS = 4
        private const val MAX_TRANSPARENCY = 60
        const val PAGE_MAIN = ""
        private const val PAGE_COLORS = "colors"
        private const val PAGE_STYLE = "style"
        private const val PAGE_SOUND = "sound"
        private const val PAGE_VIBRATION = "vibration"
        private const val PAGE_TRANSPARENCY = "transparency"
    }
}
