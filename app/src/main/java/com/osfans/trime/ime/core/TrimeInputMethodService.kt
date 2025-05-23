// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.core

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.AlarmManager
import android.app.Dialog
import android.app.PendingIntent
import android.content.Intent
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.widget.FrameLayout
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.BuildConfig
import com.osfans.trime.R
import com.osfans.trime.core.KeyModifiers
import com.osfans.trime.core.KeyValue
import com.osfans.trime.core.Rime
import com.osfans.trime.core.RimeApi
import com.osfans.trime.core.RimeKeyMapping
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.core.RimeProto
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.db.DraftHelper
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.broadcast.IntentReceiver
import com.osfans.trime.ime.composition.ComposingPopupWindow
import com.osfans.trime.ime.keyboard.InitializationUi
import com.osfans.trime.ime.keyboard.InputFeedbackManager
import com.osfans.trime.util.findSectionFrom
import com.osfans.trime.util.forceShowSelf
import com.osfans.trime.util.isNightMode
import com.osfans.trime.util.monitorCursorAnchor
import com.osfans.trime.util.styledFloat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import splitties.bitflags.hasFlag
import splitties.systemservices.clipboardManager
import splitties.systemservices.inputMethodManager
import timber.log.Timber
import java.util.Locale

/** [輸入法][InputMethodService]主程序  */

open class TrimeInputMethodService : LifecycleInputMethodService() {
    private lateinit var rime: RimeSession
    private val jobs = Channel<Job>(capacity = Channel.UNLIMITED)

    private var normalTextEditor = false
    private val prefs: AppPrefs
        get() = AppPrefs.defaultInstance()
    private lateinit var decorView: View
    private lateinit var contentView: FrameLayout
    var inputView: InputView? = null
    private var composingPopupWindow: ComposingPopupWindow? = null
    private val inputDeviceManager = InputDeviceManager()
    private var initializationUi: InitializationUi? = null
    private var mIntentReceiver: IntentReceiver? = null
    private val locales = Array(2) { Locale.getDefault() }

    var lastCommittedText: CharSequence = ""
        private set

    @Keep
    private val onThemeChangeListener =
        ThemeManager.OnThemeChangeListener {
            recreateInputView(it)
        }

    @Keep
    private val onColorChangeListener =
        ColorManager.OnColorChangeListener {
            lifecycleScope.launch(Dispatchers.Main) {
                recreateInputView(it)
            }
        }

    private fun postJob(
        scope: CoroutineScope,
        block: suspend () -> Unit,
    ): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        jobs.trySend(job)
        return job
    }

    /**
     * Post a rime operation to [jobs] to be executed
     *
     * Unlike `rime.runOnReady` or `rime.launchOnReady` where
     * subsequent operations can start if the prior operation is not finished (suspended),
     * [postRimeJob] ensures that operations are executed sequentially.
     */
    fun postRimeJob(block: suspend RimeApi.() -> Unit) = postJob(rime.lifecycleScope) { rime.runOnReady(block) }

    private suspend fun updateRimeOption(api: RimeApi) {
        try {
            api.setRuntimeOption("soft_cursor", prefs.keyboard.softCursorEnabled) // 軟光標
            api.setRuntimeOption("_horizontal", ThemeManager.activeTheme.generalStyle.horizontal) // 水平模式
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    /** 防止重启系统 强行停止应用时alarm任务丢失 */
    @SuppressLint("ScheduleExactAlarm")
    fun restartSystemStartTimingSync() {
        if (prefs.profile.timingBackgroundSyncEnabled) {
            val triggerTime = prefs.profile.timingBackgroundSyncSetTime
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager

            /** 设置待发送的同步事件 */
            val pendingIntent =
                PendingIntent.getBroadcast(
                    this,
                    0,
                    Intent("com.osfans.trime.timing.sync"),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    },
                )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // 根据SDK设置alarm任务
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }
        }
    }

    override fun onCreate() {
        rime = RimeDaemon.createSession(javaClass.name)
        lifecycleScope.launch {
            jobs.consumeEach { it.join() }
        }
        lifecycleScope.launch {
            rime.run { messageFlow }.collect {
                handleRimeMessage(it)
            }
        }
        ThemeManager.addOnChangedListener(onThemeChangeListener)
        ColorManager.addOnChangedListener(onColorChangeListener)
        super.onCreate()
        decorView = window.window!!.decorView
        contentView = decorView.findViewById(android.R.id.content)
        // MUST WRAP all code within Service onCreate() in try..catch to prevent any crash loops
        try {
            // Additional try..catch wrapper as the event listeners chain or the super.onCreate() method
            // could crash
            //  and lead to a crash loop
            Timber.d("onCreate")
            mIntentReceiver =
                IntentReceiver().also {
                    it.registerReceiver(this)
                }
            postRimeJob {
                ColorManager.init(resources.configuration)
                ThemeManager.init()
                InputFeedbackManager.init()
                restartSystemStartTimingSync()
                val theme = ThemeManager.activeTheme
                val defaultLocale = theme.generalStyle.locale.split(DELIMITER_SPLITTER)
                locales[0] =
                    when (defaultLocale.size) {
                        3 -> Locale(defaultLocale[0], defaultLocale[1], defaultLocale[2])
                        2 -> Locale(defaultLocale[0], defaultLocale[1])
                        else -> Locale.getDefault()
                    }

                val latinLocale = theme.generalStyle.latinLocale.split(DELIMITER_SPLITTER)
                locales[1] =
                    when (latinLocale.size) {
                        3 -> Locale(latinLocale[0], latinLocale[1], latinLocale[2])
                        2 -> Locale(latinLocale[0], latinLocale[1])
                        else -> Locale.US
                    }
                Timber.d("Trime.onCreate  completed")
            }
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    private fun handleRimeMessage(it: RimeMessage<*>) {
        when (it) {
            is RimeMessage.OptionMessage -> {
                val (option, value) = it.data
                when (option) {
                    "ascii_mode" -> {
                        InputFeedbackManager.ttsLanguage =
                            locales[if (value) 1 else 0]
                    }
                }
            }
            is RimeMessage.ResponseMessage ->
                it.data.let event@{
                    val (commit, ctx) = it
                    if (commit.text?.isNotEmpty() == true) {
                        commitText(commit.text)
                    }
                    updateComposingText(ctx)
                }
            is RimeMessage.KeyMessage ->
                it.data.let event@{
                    val keyCode = it.value.keyCode
                    if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                        val eventTime = SystemClock.uptimeMillis()
                        if (it.modifiers.release) {
                            sendUpKeyEvent(eventTime, keyCode, it.modifiers.metaState)
                        } else {
                            // TODO: look for better workaround for this
                            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                                handleReturnKey()
                                return
                            }
                            sendDownKeyEvent(eventTime, keyCode, it.modifiers.metaState)
                        }
                    } else {
                        if (!it.modifiers.release && it.unicode > 0) {
                            commitText(Char(it.unicode).toString())
                        } else {
                            Timber.w("Unhandled Rime KeyEvent: $it")
                        }
                    }
                }
            else -> {}
        }
    }

    private fun setupInputViews(theme: Theme): InputView {
        composingPopupWindow?.cancelJob()
        val newInputView = InputView(this, rime, theme)
        val newComposingWindow = ComposingPopupWindow(this, rime, theme, contentView)
        inputView = newInputView
        composingPopupWindow = newComposingWindow
        inputDeviceManager.setComponents(newInputView, newComposingWindow)
        return newInputView
    }

    /** Must be called on the UI thread
     *
     * 重置鍵盤、候選條、狀態欄等 !!注意，如果其中調用Rime.setOption，切換方案會卡住  */
    fun recreateInputView(theme: Theme) {
        setInputView(setupInputViews(theme))
        initializationUi = null
    }

    override fun onDestroy() {
        mIntentReceiver?.unregisterReceiver(this)
        mIntentReceiver = null
        InputFeedbackManager.destroy()
        inputView = null
        ThemeManager.removeOnChangedListener(onThemeChangeListener)
        ColorManager.removeOnChangedListener(onColorChangeListener)
        super.onDestroy()
        RimeDaemon.destroySession(javaClass.name)
    }

    private fun handleReturnKey() {
        currentInputEditorInfo.run {
            if (inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                return
            }
            if (imeOptions.hasFlag(EditorInfo.IME_FLAG_NO_ENTER_ACTION)) {
                val ic = currentInputConnection
                ic?.commitText("\n", 1)
                return
            }
            if (!actionLabel.isNullOrEmpty() && actionId != EditorInfo.IME_ACTION_UNSPECIFIED
            ) {
                currentInputConnection.performEditorAction(actionId)
                return
            }
            when (val action = imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_UNSPECIFIED, EditorInfo.IME_ACTION_NONE -> currentInputConnection.commitText("\n", 1)
                else -> currentInputConnection.performEditorAction(action)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        postRimeJob { clearComposition() }
        ColorManager.onSystemNightModeChange(newConfig.isNightMode())
    }

    override fun onUpdateCursorAnchorInfo(cursorAnchorInfo: CursorAnchorInfo) {
        composingPopupWindow?.updateCursorAnchorInfo(cursorAnchorInfo, decorView)
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
        )
        if (candidatesEnd != -1 && (newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            // 移動光標時，更新候選區
            if (newSelEnd in candidatesStart..<candidatesEnd) {
                val n = newSelEnd - candidatesStart
                Rime.setCaretPos(n)
            }
        }
        if (candidatesStart == -1 && candidatesEnd == -1 && newSelStart == 0 && newSelEnd == 0) {
            // 上屏後，清除候選區
            postRimeJob { clearComposition() }
        }
        inputView?.updateSelection(newSelStart, newSelEnd)
    }

    override fun onComputeInsets(outInsets: Insets) {
        if (inputDeviceManager.isVirtualKeyboard) {
            val (_, y) =
                intArrayOf(0, 0).also {
                    if (inputView?.keyboardView?.isVisible == true) {
                        inputView?.keyboardView?.getLocationInWindow(it)
                    } else {
                        initializationUi?.initial?.getLocationInWindow(it)
                    }
                }
            outInsets.apply {
                contentTopInsets = y
                visibleTopInsets = y
                touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
            }
        } else {
            val h = decorView.height
            outInsets.apply {
                contentTopInsets = h
                visibleTopInsets = h
                touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
            }
        }
    }

    // always show InputView since we delegate CandidatesView's visibility to it
    @SuppressLint("MissingSuperCall")
    override fun onEvaluateInputViewShown() = true

    fun superEvaluateInputViewShown() = super.onEvaluateInputViewShown()

    override fun onCreateInputView(): View {
        Timber.d("onCreateInputView")
        postRimeJob {
            ContextCompat.getMainExecutor(this@TrimeInputMethodService).execute {
                recreateInputView(ThemeManager.activeTheme)
            }
        }
        initializationUi = InitializationUi(this)
        return initializationUi!!.root
    }

    override fun setInputView(view: View) {
        super.setInputView(view)
        val inputArea = contentView.findViewById<FrameLayout>(android.R.id.inputArea)
        inputArea.updateLayoutParams<ViewGroup.LayoutParams> {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        view.updateLayoutParams<ViewGroup.LayoutParams> {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    override fun onConfigureWindow(
        win: Window,
        isFullscreen: Boolean,
        isCandidatesOnly: Boolean,
    ) {
        win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onStartInput(
        attribute: EditorInfo,
        restarting: Boolean,
    ) {
        Timber.d("onStartInput: restarting=$restarting")
        postRimeJob {
            if (restarting) {
                // when input restarts in the same editor, clear previous composition
                clearComposition()
            }
        }
    }

    override fun onStartInputView(
        attribute: EditorInfo,
        restarting: Boolean,
    ) {
        Timber.d("onStartInputView: restarting=$restarting")
        if (!restarting) {
            currentInputConnection?.monitorCursorAnchor()
        }
        postRimeJob {
            updateRimeOption(this)
            InputFeedbackManager.loadSoundEffects(this@TrimeInputMethodService)
            InputFeedbackManager.resetPlayProgress()
            if (prefs.other.showStatusBarIcon) {
                showStatusIcon(R.drawable.ic_trime_status) // 狀態欄圖標
            }
            ContextCompat.getMainExecutor(this@TrimeInputMethodService).execute {
                if (inputDeviceManager.evaluateOnStartInputView(attribute, this@TrimeInputMethodService)) {
                    inputView?.startInput(attribute, restarting)
                }
            }
            when (attribute.inputType and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                -> {
                    Timber.d(
                        "EditorInfo: private;" +
                            " packageName=" +
                            attribute.packageName +
                            "; fieldName=" +
                            attribute.fieldName +
                            "; actionLabel=" +
                            attribute.actionLabel +
                            "; inputType=" +
                            attribute.inputType +
                            "; VARIATION=" +
                            (attribute.inputType and InputType.TYPE_MASK_VARIATION) +
                            "; CLASS=" +
                            (attribute.inputType and InputType.TYPE_MASK_CLASS) +
                            "; ACTION=" +
                            (attribute.imeOptions and EditorInfo.IME_MASK_ACTION),
                    )
                    normalTextEditor = false
                }

                else -> {
                    Timber.d(
                        "EditorInfo: normal;" +
                            " packageName=" +
                            attribute.packageName +
                            "; fieldName=" +
                            attribute.fieldName +
                            "; actionLabel=" +
                            attribute.actionLabel +
                            "; inputType=" +
                            attribute.inputType +
                            "; VARIATION=" +
                            (attribute.inputType and InputType.TYPE_MASK_VARIATION) +
                            "; CLASS=" +
                            (attribute.inputType and InputType.TYPE_MASK_CLASS) +
                            "; ACTION=" +
                            (attribute.imeOptions and EditorInfo.IME_MASK_ACTION),
                    )
                    if (attribute.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                        == EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    ) {
                        //  应用程求以隐身模式打开键盘应用程序
                        normalTextEditor = false
                        Timber.d("EditorInfo: normal -> private, IME_FLAG_NO_PERSONALIZED_LEARNING")
                    } else if (attribute.packageName == BuildConfig.APPLICATION_ID ||
                        prefs
                            .clipboard
                            .draftExcludeApp
                            .trim()
                            .split('\n')
                            .contains(attribute.packageName)
                    ) {
                        normalTextEditor = false
                        Timber.d("EditorInfo: normal -> exclude, packageName=%s", attribute.packageName)
                    } else {
                        normalTextEditor = true
                        currentInputConnection?.let { DraftHelper.onExtractedTextChanged(it) }
                    }
                }
            }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        Timber.d("onFinishInputView: finishingInput=$finishingInput")
        inputDeviceManager.onFinishInputView()
        currentInputConnection?.apply {
            if (normalTextEditor) {
                DraftHelper.onExtractedTextChanged(this)
            }
            finishComposingText()
            monitorCursorAnchor(false)
        }
        postRimeJob {
            clearComposition()
        }
        InputFeedbackManager.finishInput()
    }

    // 直接commit不做任何处理
    fun commitText(
        text: CharSequence,
        clearMeatKeyState: Boolean = false,
    ) {
        val ic = currentInputConnection ?: return
        if (ic.commitText(text, 1)) {
            lastCommittedText = text
        }
        if (clearMeatKeyState) {
            ic.clearMetaKeyStates(KeyEvent.getModifierMetaStateMask())
            DraftHelper.onExtractedTextChanged(ic)
        }
    }

    /**
     * Constructs a meta state integer flag which can be used for setting the `metaState` field when sending a KeyEvent
     * to the input connection. If this method is called without a meta modifier set to true, the default value `0` is
     * returned.
     *
     * @param ctrl Set to true to enable the CTRL meta modifier. Defaults to false.
     * @param alt Set to true to enable the ALT meta modifier. Defaults to false.
     * @param shift Set to true to enable the SHIFT meta modifier. Defaults to false.
     *
     * @return An integer containing all meta flags passed and formatted for use in a [KeyEvent].
     */
    fun meta(
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        meta: Boolean = false,
        sym: Boolean = false,
    ): Int {
        var metaState = 0
        if (ctrl) {
            metaState = metaState or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        }
        if (alt) {
            metaState = metaState or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        }
        if (shift) {
            metaState = metaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        }
        if (meta) {
            metaState = metaState or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        }
        if (sym) {
            metaState = metaState or KeyEvent.META_SYM_ON
        }

        return metaState
    }

    private fun sendDownKeyEvent(
        eventTime: Long,
        keyEventCode: Int,
        metaState: Int = 0,
    ): Boolean {
        val ic = currentInputConnection ?: return false
        return ic.sendKeyEvent(
            KeyEvent(
                eventTime,
                eventTime,
                KeyEvent.ACTION_DOWN,
                keyEventCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
                InputDevice.SOURCE_KEYBOARD,
            ),
        )
    }

    private fun sendUpKeyEvent(
        eventTime: Long,
        keyEventCode: Int,
        metaState: Int = 0,
    ): Boolean {
        val ic = currentInputConnection ?: return false
        return ic.sendKeyEvent(
            KeyEvent(
                eventTime,
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                keyEventCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
                InputDevice.SOURCE_KEYBOARD,
            ),
        )
    }

    /**
     * Same as [InputMethodService.sendDownUpKeyEvents] but also allows to set meta state.
     *
     * @param keyEventCode The key code to send, use a key code defined in Android's [KeyEvent].
     * @param metaState Flags indicating which meta keys are currently pressed.
     * @param count How often the key is pressed while the meta keys passed are down. Must be greater than or equal to
     *  `1`, else this method will immediately return false.
     *
     * @return True on success, false if an error occurred or the input connection is invalid.
     */
    fun sendDownUpKeyEvent(
        keyEventCode: Int,
        metaState: Int = meta(),
        count: Int = 1,
    ): Boolean {
        if (count < 1) return false
        val ic = currentInputConnection ?: return false
        ic.clearMetaKeyStates(
            KeyEvent.META_FUNCTION_ON
                or KeyEvent.META_SHIFT_MASK
                or KeyEvent.META_ALT_MASK
                or KeyEvent.META_CTRL_MASK
                or KeyEvent.META_META_MASK
                or KeyEvent.META_SYM_ON,
        )
        ic.beginBatchEdit()
        val eventTime = SystemClock.uptimeMillis()
        if (metaState and KeyEvent.META_CTRL_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        }
        if (metaState and KeyEvent.META_ALT_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
        }
        if (metaState and KeyEvent.META_SHIFT_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        }
        if (metaState and KeyEvent.META_META_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_META_LEFT)
        }

        if (metaState and KeyEvent.META_SYM_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_SYM)
        }

        for (n in 0 until count) {
            sendDownKeyEvent(eventTime, keyEventCode, metaState)
            sendUpKeyEvent(eventTime, keyEventCode, metaState)
        }
        if (metaState and KeyEvent.META_SHIFT_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        }
        if (metaState and KeyEvent.META_ALT_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
        }
        if (metaState and KeyEvent.META_CTRL_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        }

        if (metaState and KeyEvent.META_META_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_META_LEFT)
        }

        if (metaState and KeyEvent.META_SYM_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_SYM)
        }

        ic.endBatchEdit()
        return true
    }

    fun getActiveText(type: Int): String {
        if (type == 2) return Rime.getRimeRawInput() ?: "" // 當前編碼
        var s = Rime.composingText // 當前候選
        if (s.isEmpty()) {
            val ic = currentInputConnection
            var cs = ic?.getSelectedText(0) // 選中字
            if (type == 1 && cs.isNullOrEmpty()) cs = lastCommittedText // 剛上屏字
            if (cs.isNullOrEmpty() && ic != null) {
                cs = ic.getTextBeforeCursor(if (type == 4) 1024 else 1, 0) // 光標前字
            }
            if (cs.isNullOrEmpty() && ic != null) cs = ic.getTextAfterCursor(1024, 0) // 光標後面所有字
            if (cs != null) s = cs.toString()
        }
        return s
    }

    private fun forwardKeyEvent(event: KeyEvent): Boolean {
        val modifiers = KeyModifiers.fromKeyEvent(event)
        val charCode = event.unicodeChar
        if (charCode > 0 && charCode != '\t'.code && charCode != '\n'.code) {
            postRimeJob {
                processKey(charCode, modifiers.modifiers)
            }
            return true
        }
        val keyVal = KeyValue.fromKeyEvent(event)
        if (keyVal.value != RimeKeyMapping.RimeKey_VoidSymbol) {
            postRimeJob {
                processKey(keyVal, modifiers)
            }
            return true
        }
        Timber.d("Skipped KeyEvent: $event")
        return false
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (inputDeviceManager.evaluateOnKeyDown(event, this)) {
            forceShowSelf()
        }
        return forwardKeyEvent(event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean = forwardKeyEvent(event) || super.onKeyUp(keyCode, event)

    // Added in API level 14, deprecated in 29
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onViewClicked(focusChanged: Boolean) {
        super.onViewClicked(focusChanged)
        if (Build.VERSION.SDK_INT < 34) {
            inputDeviceManager.evaluateOnViewClicked(this)
        }
    }

    @TargetApi(34)
    override fun onUpdateEditorToolType(toolType: Int) {
        super.onUpdateEditorToolType(toolType)
        inputDeviceManager.evaluateOnUpdateEditorToolType(toolType, this)
    }

    fun switchToPrevIme() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                switchToPreviousInputMethod()
            } else {
                @Suppress("DEPRECATION")
                inputMethodManager.switchToLastInputMethod(window.window!!.attributes.token)
            }
        } catch (e: Exception) {
            Timber.e(e, "Unable to switch to the previous IME.")
            inputMethodManager.showInputMethodPicker()
        }
    }

    fun switchToNextIme() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                switchToNextInputMethod(false)
            } else {
                @Suppress("DEPRECATION")
                inputMethodManager.switchToNextInputMethod(window.window!!.attributes.token, false)
            }
        } catch (e: Exception) {
            Timber.e(e, "Unable to switch to the next IME.")
            inputMethodManager.showInputMethodPicker()
        }
    }

    fun shareText(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val ic = currentInputConnection ?: return false
            val cs = ic.getSelectedText(0)
            if (cs == null) ic.performContextMenuAction(android.R.id.selectAll)
            return ic.performContextMenuAction(android.R.id.shareText)
        }
        return false
    }

    /** 編輯操作 */
    fun hookKeyboard(
        code: Int,
        mask: Int,
    ): Boolean {
        val ic = currentInputConnection ?: return false
        // 没按下 Ctrl 键
        if (mask != KeyEvent.META_CTRL_ON) {
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (prefs.keyboard.hookCtrlZY) {
                when (code) {
                    KeyEvent.KEYCODE_Y -> return ic.performContextMenuAction(android.R.id.redo)
                    KeyEvent.KEYCODE_Z -> return ic.performContextMenuAction(android.R.id.undo)
                }
            }
        }

        when (code) {
            KeyEvent.KEYCODE_A -> {
                // 全选
                return if (prefs.keyboard.hookCtrlA) ic.performContextMenuAction(android.R.id.selectAll) else false
            }

            KeyEvent.KEYCODE_X -> {
                // 剪切
                if (prefs.keyboard.hookCtrlCV) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et != null) {
                        if (et.selectionEnd - et.selectionStart > 0) return ic.performContextMenuAction(android.R.id.cut)
                    }
                }
                Timber.w("hookKeyboard cut fail")
                return false
            }

            KeyEvent.KEYCODE_C -> {
                // 复制
                if (prefs.keyboard.hookCtrlCV) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et != null) {
                        if (et.selectionEnd - et.selectionStart > 0) return ic.performContextMenuAction(android.R.id.copy)
                    }
                }
                Timber.w("hookKeyboard copy fail")
                return false
            }

            KeyEvent.KEYCODE_V -> {
                // 粘贴
                if (prefs.keyboard.hookCtrlCV) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et == null) {
                        Timber.d("hookKeyboard paste, et == null, try commitText")
                        val clipboardText = clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(this)
                        if (ic.commitText(clipboardText, 1)) {
                            return true
                        }
                    } else if (ic.performContextMenuAction(android.R.id.paste)) {
                        return true
                    }
                    Timber.w("hookKeyboard paste fail")
                }
                return false
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (prefs.keyboard.hookCtrlLR) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et != null) {
                        val moveTo = et.text.findSectionFrom(et.startOffset + et.selectionEnd)
                        ic.setSelection(moveTo, moveTo)
                        return true
                    }
                }
            }

            KeyEvent.KEYCODE_DPAD_LEFT ->
                if (prefs.keyboard.hookCtrlLR) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et != null) {
                        val moveTo = et.text.findSectionFrom(et.startOffset + et.selectionStart, true)
                        ic.setSelection(moveTo, moveTo)
                        return true
                    }
                }
        }
        return false
    }

    private val composingTextMode by prefs.general.composingTextMode

    private fun updateComposingText(ctx: RimeProto.Context) {
        val ic = currentInputConnection ?: return
        val text =
            when (composingTextMode) {
                ComposingTextMode.DISABLE -> ""
                ComposingTextMode.PREEDIT -> ctx.composition.preedit ?: ""
                ComposingTextMode.COMMIT_TEXT_PREVIEW -> ctx.composition.commitTextPreview ?: ""
                ComposingTextMode.RAW_INPUT -> ctx.input
            }
        if (ic.getSelectedText(0).isNullOrEmpty() || text.isNotEmpty()) {
            ic.setComposingText(text, 1)
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    private var showingDialog: Dialog? = null

    fun showDialog(dialog: Dialog) {
        showingDialog?.dismiss()
        dialog.window?.also {
            it.attributes.apply {
                token = decorView.windowToken
                type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            }
            it.addFlags(
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            )
            it.setDimAmount(styledFloat(android.R.attr.backgroundDimAmount))
        }
        dialog.setOnDismissListener {
            showingDialog = null
        }
        dialog.show()
        showingDialog = dialog
    }

    companion object {
        /** Delimiter regex to split language/locale tags. */
        private val DELIMITER_SPLITTER = """[-_]""".toRegex()
    }
}
