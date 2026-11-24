package com.voiceflow.ime

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Bundle
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

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    var listenState by mutableStateOf(ListenState.IDLE)
    var audioLevel by mutableFloatStateOf(0f)
    var showQwertyDrawer by mutableStateOf(false)
    var statusLabel by mutableStateOf("Tap or hold pill to speak")
    var lastCommittedPhrase by mutableStateOf("")

    private var speechRecognizer: SpeechRecognizer? = null
    private var accumulatedText = StringBuilder()

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
                    onStartListening = { startVoiceRecognition() },
                    onStopListening = { stopVoiceRecognition() },
                    onToggleQwerty = {
                        if (listenState == ListenState.LISTENING) stopVoiceRecognition()
                        showQwertyDrawer = !showQwertyDrawer
                    },
                    onSwitchSystemKeyboard = { switchToPreviousOrNextIme() },
                    onCommitText = { text ->
                        currentInputConnection?.commitText(text, 1)
                    },
                    onBackspace = {
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
                        sendDefaultEditorAction(true)
                    }
                )
            }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        stopVoiceRecognition()
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        super.onDestroy()
    }

    private fun startVoiceRecognition() {
        if (listenState == ListenState.LISTENING) return
        accumulatedText.clear()
        listenState = ListenState.LISTENING
        statusLabel = "Listening..."

        if (speechRecognizer == null) {
            speechRecognizer = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
            ) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            } else {
                SpeechRecognizer.createSpeechRecognizer(this)
            }
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                statusLabel = "Speak now..."
            }

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {
                audioLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                if (listenState == ListenState.LISTENING &&
                    (error == SpeechRecognizer.ERROR_NO_SPEECH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                ) {
                    launchRecognizerIntent()
                } else {
                    finalizeAndCommit()
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val best = matches?.firstOrNull().orEmpty()
                if (best.isNotBlank()) {
                    if (accumulatedText.isNotEmpty()) accumulatedText.append(" ")
                    accumulatedText.append(best)
                }

                if (listenState == ListenState.LISTENING) {
                    launchRecognizerIntent()
                } else {
                    finalizeAndCommit()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
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
        })

        launchRecognizerIntent()
    }

    private fun launchRecognizerIntent() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {
            finalizeAndCommit()
        }
    }

    private fun stopVoiceRecognition() {
        if (listenState != ListenState.LISTENING) return
        listenState = ListenState.PROCESSING
        audioLevel = 0f
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {
            finalizeAndCommit()
        }
    }

    private fun finalizeAndCommit() {
        val cleaned = cleanTranscript(accumulatedText.toString())
        currentInputConnection?.finishComposingText()

        if (cleaned.isNotEmpty()) {
            currentInputConnection?.setComposingText("", 1)
            val toInsert = "$cleaned "
            currentInputConnection?.commitText(toInsert, 1)
            lastCommittedPhrase = toInsert
            statusLabel = "✦ Inserted"
        } else {
            statusLabel = "Tap or hold pill to speak"
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            switchToNextInputMethod(false)
        } else {
            @Suppress("DEPRECATION")
            imm.switchToNextInputMethod(window?.window?.attributes?.token, false)
        }
    }
}
