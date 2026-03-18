package net.jacoblo.notesoutloud

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class TtsManager(
    private val lifecycleScope: LifecycleCoroutineScope,
    private val getActiveWebView: () -> WebView?
) : TextToSpeech.OnInitListener {

    var tts: TextToSpeech? = null
        private set
    private var isTtsReady = false

    val isTtsPlaying = mutableStateOf(false)
    val isTtsRandom = mutableStateOf(false)
    val ttsDelaySeconds = mutableStateOf("2")

    private var currentParaIndex = 0
    private var ttsParagraphCount = 0

    private var toastCallback: ((String) -> Unit)? = null

    fun init(context: android.content.Context, onToast: (String) -> Unit) {
        toastCallback = onToast
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                toastCallback?.invoke("TTS Language not supported")
            } else {
                isTtsReady = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        lifecycleScope.launch(Dispatchers.Main) {
                            val delayMs = (ttsDelaySeconds.value.toLongOrNull() ?: 2L) * 1000
                            delay(delayMs)
                            if (isTtsPlaying.value) {
                                playNextParagraph()
                            }
                        }
                    }
                    override fun onError(utteranceId: String?) {
                        isTtsPlaying.value = false
                    }
                })
            }
        }
    }

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
    }

    fun start() {
        if (!isTtsReady) {
            toastCallback?.invoke("TTS not ready")
            return
        }
        isTtsPlaying.value = true
        val webView = getActiveWebView() ?: return

        webView.evaluateJavascript("window.AndroidTtsHelper.getCount()") { countStr ->
            ttsParagraphCount = countStr?.toIntOrNull() ?: 0
            if (ttsParagraphCount > 0) {
                if (currentParaIndex >= ttsParagraphCount) currentParaIndex = 0
                playNextParagraph(speakCurrent = true)
            } else {
                toastCallback?.invoke("No paragraphs found")
                isTtsPlaying.value = false
            }
        }
    }

    fun stop() {
        isTtsPlaying.value = false
        tts?.stop()
        val webView = getActiveWebView()
        webView?.evaluateJavascript("window.AndroidTtsHelper.highlight(-1)", null)
    }

    fun handleTocClick(id: String) {
        if (!isTtsReady) {
            toastCallback?.invoke("TTS not ready")
            return
        }
        val idx = id.toIntOrNull() ?: return
        val webView = getActiveWebView() ?: return

        webView.evaluateJavascript("window.AndroidTtsHelper.getCount()") { countStr ->
            ttsParagraphCount = countStr?.toIntOrNull() ?: 0
            if (idx in 0 until ttsParagraphCount) {
                currentParaIndex = idx
                isTtsPlaying.value = true
                playNextParagraph(speakCurrent = true)
            }
        }
    }

    private fun playNextParagraph(speakCurrent: Boolean = false) {
        if (!isTtsPlaying.value) return

        if (!speakCurrent) {
            if (isTtsRandom.value) {
                currentParaIndex = (0 until ttsParagraphCount).random()
            } else {
                currentParaIndex++
                if (currentParaIndex >= ttsParagraphCount) {
                    stop()
                    return
                }
            }
        }

        val webView = getActiveWebView() ?: return

        val script = """
            (function() {
                window.AndroidTtsHelper.highlight($currentParaIndex);
                return window.AndroidTtsHelper.getParaText($currentParaIndex);
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { text ->
            val cleanText = text?.trim()?.removeSurrounding("\"")
                ?.replace("\\n", " ")
                ?.replace("\\\"", "\"") ?: ""

            if (cleanText.isNotEmpty() && cleanText != "null") {
                val params = Bundle()
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "TTS_ID")
                tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params, "TTS_ID")
            } else {
                playNextParagraph()
            }
        }
    }
}
