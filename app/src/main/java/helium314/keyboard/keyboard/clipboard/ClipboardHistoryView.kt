// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.clipboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import android.database.ContentObserver
import android.graphics.Outline
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.ViewOutlineProvider
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.core.view.ViewCompat
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.keyboard.MainKeyboardView
import helium314.keyboard.keyboard.PointerTracker
import helium314.keyboard.keyboard.internal.KeyDrawParams
import helium314.keyboard.keyboard.internal.KeyVisualAttributes
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.ClipboardHistoryManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.createToolbarKey
import helium314.keyboard.latin.utils.getEnabledClipboardToolbarKeys
import helium314.keyboard.latin.utils.onClickToolbarKey
import helium314.keyboard.latin.utils.onLongClickToolbarKey
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.setToolbarButtonsActivatedStateOnPrefChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("CustomViewStyleable")
class ClipboardHistoryView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet?,
        defStyle: Int = R.attr.clipboardHistoryViewStyle
) : LinearLayout(context, attrs, defStyle), View.OnClickListener,
    ClipboardDao.Listener, OnKeyEventListener,
    View.OnLongClickListener, SharedPreferences.OnSharedPreferenceChangeListener {

    private val clipboardLayoutParams = ClipboardLayoutParams(context)
    private val pinIconId: Int
    private val keyBackgroundId: Int

    private lateinit var clipboardRecyclerView: ClipboardHistoryRecyclerView
    private lateinit var placeholderView: TextView
    private val toolbarKeys = mutableListOf<ImageButton>()
    private var screenshotJob: Job? = null
    private val screenshotObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            // a new screenshot fires several changes, so refresh once they settle
            removeCallbacks(refreshScreenshots)
            postDelayed(refreshScreenshots, 500)
        }
    }
    private val refreshScreenshots = Runnable { if (isShown) setupScreenshotRow() }
    private lateinit var clipboardAdapter: ClipboardAdapter

    lateinit var keyboardActionListener: KeyboardActionListener
    private lateinit var clipboardHistoryManager: ClipboardHistoryManager

    init {
        val clipboardViewAttr = context.obtainStyledAttributes(attrs,
                R.styleable.ClipboardHistoryView, defStyle, R.style.ClipboardHistoryView)
        pinIconId = clipboardViewAttr.getResourceId(R.styleable.ClipboardHistoryView_iconPinnedClip, 0)
        clipboardViewAttr.recycle()
        @SuppressLint("UseKtx") // suggestion does not work
        val keyboardViewAttr = context.obtainStyledAttributes(attrs, R.styleable.KeyboardView, defStyle, R.style.KeyboardView)
        keyBackgroundId = keyboardViewAttr.getResourceId(R.styleable.KeyboardView_keyBackground, 0)
        keyboardViewAttr.recycle()
        fitsSystemWindows = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // fitsSystemWindows doesn't give this view the nav bar padding on some devices, so set it from the root insets
        ViewCompat.getRootWindowInsets(this)?.systemWindowInsetBottom?.let {
            if (paddingBottom != it) setPadding(paddingLeft, paddingTop, paddingRight, it)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val res = context.resources
        // The main keyboard expands to the entire this {@link KeyboardView}.
        val width = ResourceUtils.getKeyboardWidth(context, Settings.getValues()) + paddingLeft + paddingRight
        val height = ResourceUtils.getSecondaryKeyboardHeight(res, Settings.getValues()) + paddingTop + paddingBottom
        setMeasuredDimension(width, height)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initialize() { // needs to be delayed for access to ClipboardStrip, which is not a child of this view
        if (this::clipboardAdapter.isInitialized) return
        val colors = Settings.getValues().mColors
        clipboardAdapter = ClipboardAdapter(clipboardLayoutParams, this).apply {
            itemBackgroundId = keyBackgroundId
            pinnedIconResId = pinIconId
        }
        placeholderView = findViewById(R.id.clipboard_empty_view)
        clipboardRecyclerView = findViewById<ClipboardHistoryRecyclerView>(R.id.clipboard_list).apply {
            val colCount = resources.getInteger(R.integer.config_clipboard_keyboard_col_count)
            layoutManager = StaggeredGridLayoutManager(colCount, StaggeredGridLayoutManager.VERTICAL)
            @Suppress("deprecation") // "no cache" should be fine according to warning in https://developer.android.com/reference/android/view/ViewGroup#setPersistentDrawingCache(int)
            persistentDrawingCache = PERSISTENT_NO_CACHE
            clipboardLayoutParams.setListProperties(this)
            placeholderView = this@ClipboardHistoryView.placeholderView
        }
        val clipboardStrip = KeyboardSwitcher.getInstance().clipboardStrip!!
        getEnabledClipboardToolbarKeys(context.prefs()).forEach {
            val key = createToolbarKey(context, it)
            clipboardStrip.addView(key)
            key.setOnClickListener(this@ClipboardHistoryView)
            key.setOnLongClickListener(this@ClipboardHistoryView)
            colors.setColor(key, ColorType.TOOL_BAR_KEY)
            colors.setBackground(key, ColorType.STRIP_BACKGROUND)
            toolbarKeys.add(key)
        }
    }

    private fun setupClipKey(params: KeyDrawParams) {
        clipboardAdapter.apply {
            itemBackgroundId = keyBackgroundId
            itemTypeFace = params.mTypeface
            itemTextColor = params.mTextColor
            itemTextSize = params.mLabelSize.toFloat()
        }
    }

    private fun setupToolbarKeys() {
        // set layout params
        val toolbarKeyLayoutParams = LayoutParams(resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_edge_key_width), LayoutParams.MATCH_PARENT)
        toolbarKeys.forEach { it.layoutParams = toolbarKeyLayoutParams }
    }

    private fun setupBottomRowKeyboard(editorInfo: EditorInfo, listener: KeyboardActionListener) {
        val keyboardView = findViewById<MainKeyboardView>(R.id.bottom_row_keyboard)
        keyboardView.setKeyboardActionListener(listener)
        PointerTracker.switchTo(keyboardView)
        val kls = KeyboardLayoutSet.Builder.buildEmojiClipBottomRow(context, editorInfo)
        val keyboard = kls.getKeyboard(KeyboardElement.CLIPBOARD_BOTTOM_ROW)
        keyboardView.setKeyboard(keyboard)
    }

    fun setHardwareAcceleratedDrawingEnabled(enabled: Boolean) {
        if (!enabled) return
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun startClipboardHistory(
            historyManager: ClipboardHistoryManager,
            keyVisualAttr: KeyVisualAttributes?,
            editorInfo: EditorInfo,
            keyboardActionListener: KeyboardActionListener
    ) {
        clipboardHistoryManager = historyManager
        initialize()
        setupToolbarKeys()
        historyManager.prepareClipboardHistory()
        historyManager.setHistoryChangeListener(this)
        clipboardAdapter.clipboardHistoryManager = historyManager

        val params = KeyDrawParams()
        params.updateParams(clipboardLayoutParams.bottomRowKeyboardHeight, keyVisualAttr)
        val settings = Settings.getInstance()
        KeyboardTypeface.customTypeface()?.let { params.mTypeface = it }
        setupClipKey(params)
        setupBottomRowKeyboard(editorInfo, keyboardActionListener)

        placeholderView.apply {
            KeyboardTypeface.applyToTextView(this)
            setTextColor(params.mTextColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, params.mLabelSize.toFloat() * 2)
        }
        clipboardRecyclerView.apply {
            adapter = clipboardAdapter
            val keyboardWidth = ResourceUtils.getKeyboardWidth(context, settings.current)
            layoutParams.width = keyboardWidth
            // new ClipboardLayoutParams means ClipboardAdapter has wrong gaps, but that's ok (only relevant when resizing floating keyboard)
            ClipboardLayoutParams(context).setListProperties(this)

            // set side padding
            val keyboardAttr = context.obtainStyledAttributes(
                null, R.styleable.Keyboard, R.attr.keyboardStyle, R.style.Keyboard)
            val leftPadding = (keyboardAttr.getFraction(R.styleable.Keyboard_keyboardLeftPadding,
                keyboardWidth, keyboardWidth, 0f)
                    * settings.current.mSidePaddingScale).toInt()
            val rightPadding =  (keyboardAttr.getFraction(R.styleable.Keyboard_keyboardRightPadding,
                keyboardWidth, keyboardWidth, 0f)
                    * settings.current.mSidePaddingScale).toInt()
            keyboardAttr.recycle()
            setPadding(leftPadding, paddingTop, rightPadding, paddingBottom)
        }

        setupScreenshotRow()

        // absurd workaround so Android sets the correct color from stateList (depending on "activated")
        toolbarKeys.forEach { it.isEnabled = false; it.isEnabled = true }
    }

    private fun setupScreenshotRow() {
        val row = findViewById<HorizontalScrollView>(R.id.screenshot_row)
        val container = findViewById<LinearLayout>(R.id.screenshot_container)
        screenshotJob?.cancel()
        container.removeAllViews()
        ClipboardLayoutParams(context).setListProperties(clipboardRecyclerView) // reset height before splitting it
        context.contentResolver.unregisterContentObserver(screenshotObserver)
        if (!RecentScreenshots.isEnabled(context)) {
            row.visibility = GONE
            return
        }
        // the row takes its height from the list, so the whole view keeps its size
        val rowHeight = clipboardRecyclerView.layoutParams.height * 3 / 10
        row.layoutParams = row.layoutParams.apply { height = rowHeight }
        clipboardRecyclerView.layoutParams = clipboardRecyclerView.layoutParams.apply { height -= rowHeight }
        row.visibility = VISIBLE
        row.scrollTo(0, 0)
        context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, screenshotObserver)
        val gap = (resources.displayMetrics.density * 4).toInt()
        val corner = resources.displayMetrics.density * 6
        screenshotJob = CoroutineScope(Dispatchers.Main).launch {
            val shots = withContext(Dispatchers.IO) {
                RecentScreenshots.query(context).map { it to RecentScreenshots.loadThumbnail(context, it, rowHeight) }
            }
            shots.forEach { (shot, thumbnail) ->
                if (thumbnail == null) return@forEach
                container.addView(ImageView(context).apply {
                    setImageBitmap(thumbnail)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = context.getString(R.string.clipboard_show_screenshots)
                    clipToOutline = true
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) =
                            outline.setRoundRect(0, 0, view.width, view.height, corner)
                    }
                    setOnClickListener { pasteScreenshot(it, shot) }
                }, LinearLayout.LayoutParams(rowHeight * 3 / 4, LayoutParams.MATCH_PARENT).apply {
                    setMargins(gap, gap, 0, gap)
                })
            }
            if (container.childCount == 0) {
                row.visibility = GONE
                clipboardRecyclerView.layoutParams = clipboardRecyclerView.layoutParams.apply { height += rowHeight }
            }
        }
    }

    private fun pasteScreenshot(view: View, shot: RecentScreenshots.Screenshot) {
        keyboardActionListener.onPressKey(KeyCode.NOT_SPECIFIED, 0, 1, HapticEvent.KEY_PRESS)
        CoroutineScope(Dispatchers.Main).launch {
            val info = withContext(Dispatchers.IO) { RecentScreenshots.contentInfo(context, shot) }
            if (info != null) keyboardActionListener.onContent(info)
            keyboardActionListener.onReleaseKey(KeyCode.NOT_SPECIFIED, false)
        }
    }

    fun stopClipboardHistory() {
        screenshotJob?.cancel()
        removeCallbacks(refreshScreenshots)
        context.contentResolver.unregisterContentObserver(screenshotObserver)
        if (!this::clipboardAdapter.isInitialized) return
        clipboardRecyclerView.adapter = null
        clipboardHistoryManager.setHistoryChangeListener(null)
        clipboardAdapter.clipboardHistoryManager = null
    }

    override fun onClick(view: View) {
        if (view.tag is ToolbarKey) {
            onClickToolbarKey(view) {
                keyboardActionListener.onCodeInput(it, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
            }
        }
    }

    override fun onLongClick(view: View): Boolean {
        if (view.tag is ToolbarKey) {
            onLongClickToolbarKey(view) { code, isRepeat ->
                keyboardActionListener.onCodeInput(code, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, isRepeat)
            }
            return true
        }
        return false
    }

    override fun onKeyDown(clipId: Long) {
        keyboardActionListener.onPressKey(KeyCode.NOT_SPECIFIED, 0, 1, HapticEvent.KEY_PRESS)
    }

    override fun onKeyUp(clipId: Long) {
        val clipContent = clipboardHistoryManager.getHistoryEntryContent(clipId)
        if (clipContent?.filename != null) keyboardActionListener.onContent(clipContent.getContentInfo(context))
        else keyboardActionListener.onTextInput(clipContent?.text)
        keyboardActionListener.onReleaseKey(KeyCode.NOT_SPECIFIED, false)
        if (Settings.getValues().mAlphaAfterClipHistoryEntry)
            keyboardActionListener.onCodeInput(KeyCode.ALPHA, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
    }

    override fun onClipInserted(position: Int) {
        clipboardAdapter.notifyItemInserted(position)
        clipboardRecyclerView.smoothScrollToPosition(position)
    }

    override fun onClipsRemoved(position: Int, count: Int) {
        clipboardAdapter.notifyItemRangeRemoved(position, count)
    }

    override fun onClipMoved(oldPosition: Int, newPosition: Int) {
        clipboardAdapter.notifyItemMoved(oldPosition, newPosition)
        clipboardAdapter.notifyItemChanged(newPosition)
        if (newPosition < oldPosition) clipboardRecyclerView.smoothScrollToPosition(newPosition)
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        setToolbarButtonsActivatedStateOnPrefChange(KeyboardSwitcher.getInstance().clipboardStrip!!, key)

        // The setting can only be changed from a settings screen, but adding it to this listener seems necessary: https://github.com/HeliBorg/HeliBoard/pull/1903#issuecomment-3478424606
        if (::clipboardHistoryManager.isInitialized && key == Settings.PREF_CLIPBOARD_HISTORY_PINNED_FIRST) {
            // Ensure settings are reloaded first
            Settings.getInstance().onSharedPreferenceChanged(prefs, key)
            clipboardHistoryManager.sortHistoryEntries()
            clipboardAdapter.notifyDataSetChanged()
        }
    }
}
