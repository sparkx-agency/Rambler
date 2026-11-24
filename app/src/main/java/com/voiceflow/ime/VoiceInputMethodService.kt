package com.voiceflow.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import java.util.Locale

enum class ListenState { IDLE, LISTENING, PROCESSING }

class VoiceInputMethodService : InputMethodService(),
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val mainHandler = Handler(Looper.getMainLooper())

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    var listenState by mutableStateOf(ListenState.IDLE)
    var audioLevel by mutableFloatStateOf(0f)
    var showQwertyDrawer by mutableStateOf(false)
    var statusLabel by mutableStateOf("Tap or hold to speak")
    var lastCommittedPhrase by mutableStateOf("")

    private var speechRecognizer: SpeechRecognizer? = null
    private var usingOnDeviceRecognizer = false
    private var triedCloudFallback = false
    private var accumulatedText = StringBuilder()

    private val processingTimeoutRunnable = Runnable {
        if (listenState == ListenState.PROCESSING) {
            runCatching { speechRecognizer?.cancel() }
            finalizeAndCommit()
        }
    }

    private val relaunchRunnable = Runnable {
        if (listenState == ListenState.LISTENING) {
            launchRecognizerIntent()
        }
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onCreateInputView(): View {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        val decorView = window?.window?.decorView
        decorView?.setViewTreeLifecycleOwner(this)
        decorView?.setViewTreeViewModelStoreOwner(this)
        decorView?.setViewTreeSavedStateRegistryOwner(this)

        return ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@VoiceInputMethodService)
            setViewTreeViewModelStoreOwner(this@VoiceInputMethodService)
            setViewTreeSavedStateRegistryOwner(this@VoiceInputMethodService)

            setContent {
                VoiceKeyboardScreen(
                    listenState = listenState,
                    audioLevel = audioLevel,
                    showQwertyDrawer = showQwertyDrawer,
                    statusLabel = statusLabel,
                    canUndoLastPhrase = lastCommittedPhrase.isNotEmpty(),
                    onStartListening = { startVoiceRecognition() },
                    onStopListening = { stopVoiceRecognition() },
                    onCancelOrReset = { cancelVoiceRecognition() },
                    onToggleQwerty = {
                        if (listenState != ListenState.IDLE) cancelVoiceRecognition()
                        showQwertyDrawer = !showQwertyDrawer
                    },
                    onSwitchSystemKeyboard = { switchToPreviousOrNextIme() },
                    onCommitText = { text ->
                        lastCommittedPhrase = ""
                        currentInputConnection?.commitText(text, 1)
                    },
                    onBackspace = {
                        lastCommittedPhrase = ""
                        currentInputConnection?.deleteSurroundingText(1, 0)
                    },
                    onUndoLastPhrase = {
                        if (lastCommittedPhrase.isNotEmpty()) {
                            currentInputConnection?.deleteSurroundingText(lastCommittedPhrase.length, 0)
                            lastCommittedPhrase = ""
                            statusLabel = "Undid last phrase"
                        }
                    },
                    onEnterKey = {
                        lastCommittedPhrase = ""
                        sendDefaultEditorAction(true)
                    }
                )
            }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        if (!restarting) {
            lastCommittedPhrase = ""
        }
        if (listenState == ListenState.IDLE &&
            statusLabel.startsWith("Grant mic") &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            statusLabel = "Tap or hold to speak"
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        cancelVoiceRecognition()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        speechRecognizer = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        super.onDestroy()
    }

    private fun startVoiceRecognition() {
        if (listenState != ListenState.IDLE) return

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            statusLabel = "Grant mic permission in VoiceFlow app"
            val setupIntent = Intent(this, SetupActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { startActivity(setupIntent) }
            return
        }

        mainHandler.removeCallbacks(processingTimeoutRunnable)
        mainHandler.removeCallbacks(relaunchRunnable)
        accumulatedText.clear()
        triedCloudFallback = false
        listenState = ListenState.LISTENING
        statusLabel = "Listening..."

        if (speechRecognizer == null) {
            if (!initSpeechRecognizer(preferOnDevice = true)) {
                statusLabel = "Speech recognition unavailable"
                listenState = ListenState.IDLE
                return
            }
        }

        launchRecognizerIntent()
    }

    private fun initSpeechRecognizer(preferOnDevice: Boolean): Boolean {
        speechRecognizer?.destroy()
        speechRecognizer = null

        val created = if (
            preferOnDevice &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        ) {
            usingOnDeviceRecognizer = true
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else if (SpeechRecognizer.isRecognitionAvailable(this)) {
            usingOnDeviceRecognizer = false
            SpeechRecognizer.createSpeechRecognizer(this)
        } else {
            null
        } ?: return false

        created.setRecognitionListener(recognitionListener)
        speechRecognizer = created
        return true
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (listenState == ListenState.LISTENING) {
                statusLabel = "Speak now..."
            }
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {
            if (listenState == ListenState.LISTENING) {
                audioLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            if (listenState == ListenState.IDLE) return

            if (listenState == ListenState.LISTENING &&
                (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
            ) {
                mainHandler.postDelayed(relaunchRunnable, 150L)
                return
            }

            if (listenState == ListenState.LISTENING &&
                usingOnDeviceRecognizer &&
                !triedCloudFallback &&
                SpeechRecognizer.isRecognitionAvailable(this@VoiceInputMethodService)
            ) {
                triedCloudFallback = true
                if (initSpeechRecognizer(preferOnDevice = false)) {
                    launchRecognizerIntent()
                    return
                }
            }

            val errorMessage = when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission needed in VoiceFlow app"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech service busy — tap to retry"
                SpeechRecognizer.ERROR_AUDIO -> "Microphone unavailable"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network speech error"
                else -> null
            }

            finalizeAndCommit(errorStatus = errorMessage)
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val best = matches?.firstOrNull().orEmpty()
            if (best.isNotBlank()) {
                if (accumulatedText.isNotEmpty()) accumulatedText.append(" ")
                accumulatedText.append(best)
            }

            if (listenState == ListenState.LISTENING) {
                mainHandler.postDelayed(relaunchRunnable, 80L)
            } else {
                finalizeAndCommit()
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (listenState != ListenState.LISTENING) return
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (partial.isNotBlank()) {
                val preview = (accumulatedText.toString() + " " + partial).trim()
                currentInputConnection?.setComposingText(preview, 1)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun launchRecognizerIntent() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            if (usingOnDeviceRecognizer) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {
            finalizeAndCommit(errorStatus = "Could not start voice input")
        }
    }

    private fun stopVoiceRecognition() {
        if (listenState != ListenState.LISTENING) return
        mainHandler.removeCallbacks(relaunchRunnable)
        listenState = ListenState.PROCESSING
        statusLabel = "Finishing..."
        audioLevel = 0f
        mainHandler.postDelayed(processingTimeoutRunnable, 2500L)
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {
            finalizeAndCommit()
        }
    }

    private fun cancelVoiceRecognition() {
        mainHandler.removeCallbacks(relaunchRunnable)
        mainHandler.removeCallbacks(processingTimeoutRunnable)
        if (listenState != ListenState.IDLE) {
            runCatching { speechRecognizer?.cancel() }
            finalizeAndCommit()
        }
    }

    private fun finalizeAndCommit(errorStatus: String? = null) {
        mainHandler.removeCallbacks(relaunchRunnable)
        mainHandler.removeCallbacks(processingTimeoutRunnable)

        val cleaned = cleanTranscript(accumulatedText.toString())

        if (cleaned.isNotEmpty()) {
            val toInsert = "$cleaned "
            currentInputConnection?.commitText(toInsert, 1)
            lastCommittedPhrase = toInsert
            statusLabel = "Inserted"
        } else {
            currentInputConnection?.setComposingText("", 1)
            currentInputConnection?.finishComposingText()
            statusLabel = errorStatus ?: "Tap or hold to speak"
        }

        accumulatedText.clear()
        audioLevel = 0f
        listenState = ListenState.IDLE
    }

    private fun cleanTranscript(raw: String): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""

        text = text.replace(Regex("\\b(um+|uh+|er+|ah+)\\b[,\\s]*", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("\\s{2,}"), " ").trim()
        if (text.isEmpty()) return ""
        text = text.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

        if (!text.endsWith(".") && !text.endsWith("?") && !text.endsWith("!")) {
            val isQuestion = Regex("^(are|is|can|could|would|will|do|does|did|what|when|where|who|why|how)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(text)
            text += if (isQuestion) "?" else "."
        }
        return text
    }

    private fun switchToPreviousOrNextIme() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToNextInputMethod(false)
        } else {
            @Suppress("DEPRECATION")
            imm.switchToNextInputMethod(window?.window?.attributes?.token, false)
        }
    }
}
