package com.tverona.scpanywhere.utils

import android.content.Context
import android.content.res.Resources
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.tverona.scpanywhere.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.*

/**
 * Wrapper around android's TextToSpeech library to provide speech functionality
 */
class TextToSpeechProvider constructor(
    @ApplicationContext val context: Context
) {
    /**
     * Callback interface to notify on speech state
     */
    interface SpeechProgress {
        fun onStart(totalUtterances: Int)
        fun onUtteranceDone(utteranceId: Int)
        fun onDone()
        fun onError(utteranceId: Int)
    }

    /**
     * Callback interface to notify on initialization status
     */
    interface OnInitStatus {
        fun onSuccess()
        fun onError(errorMessage: String)
    }

    private val _isInitialized = MutableLiveData<Boolean>(false)
    val isInitialized: LiveData<Boolean> = _isInitialized

    private var tts: TextToSpeech? = null

    private val regExSentenceDelimiter = Regex("(?:\\n|(?<=[!.?]\\s))")
    private val regExWhitespaceDelimiter = Regex("(?<=[,\\s])")

    private var currentUtteranceId = 0
    private val utteranceIdAsString: String
        get() {
            return "$currentUtteranceId"
        }

    private val currentSpeechText: String? = null

    private fun getUtteranceIdFromString(utteranceId: String?): Int {
        return utteranceId?.toIntOrNull() ?: 0
    }

    val voices: Set<Voice?>
        get() {
            return try {
                tts?.voices ?: setOf()
            } catch (error: NullPointerException) {
                return setOf()
            }
        }

    var voice: Voice?
        get() {
            return try {
                tts?.voice
            } catch (error: NullPointerException) {
                null
            }
        }
        set(value) {
            try {
                tts?.voice = value
            } catch (error: NullPointerException) {
            }
        }

    val defaultVoice: Voice?
        get() {
            return try {
                tts?.defaultVoice
            } catch (error: NullPointerException) {
                null
            }
        }

    fun setPitch(pitch: Float) {
        tts?.setPitch(pitch)
    }

    fun setSpeechRate(speechRate: Float) {
        tts?.setSpeechRate(speechRate)
    }

    val engines: List<TextToSpeech.EngineInfo>
        get() {
            return try {
                tts?.engines ?: listOf()
            } catch (error: NullPointerException) {
                return listOf()
            }
        }

    val defaultEngine: String?
        get() {
            return try {
                tts?.defaultEngine
            } catch (error: NullPointerException) {
                null
            }
        }

    var currentEngineName: String? = null
        private set

    /**
     * Initialize text to speech provider given optional [enginePackageName] and [onInitStatus] callback
     */
    fun initialize(enginePackageName: String?, onInitStatus: OnInitStatus? = null) {
        if (null != tts) {
            shutdown()
        }

        currentEngineName = enginePackageName
        tts = TextToSpeech(context, object : TextToSpeech.OnInitListener {
            override fun onInit(status: Int) {
                if (status == TextToSpeech.ERROR) {
                    var errorMessage = context.getString(R.string.speech_error_generic)
                    if (tts?.engines.isNullOrEmpty()) {
                        errorMessage = context.getString(R.string.speech_error_no_engines)
                    }
                    shutdown()
                    onInitStatus?.onError(errorMessage)
                    loge("Error initializing TextToSpeech provider. Status: $status: $errorMessage")
                    return
                }

                if (currentEngineName == null) {
                    currentEngineName = tts!!.defaultEngine
                }
                val systemLocale = currentSystemLocale

                @Suppress("deprecation")
                val language = tts!!.voice?.locale ?: tts!!.language ?: systemLocale
                when (tts!!.isLanguageAvailable(language)) {
                    // Set the language if it is available and current voice is not set
                    TextToSpeech.LANG_AVAILABLE, TextToSpeech.LANG_COUNTRY_AVAILABLE,
                    TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> {
                        if (tts!!.voice == null)
                            tts!!.language = language
                        logv("Setting language to $language")
                    }

                    TextToSpeech.LANG_MISSING_DATA -> {
                        loge("Missing language data")
                    }

                    // Fall back on system language
                    TextToSpeech.LANG_NOT_SUPPORTED -> {
                        when (tts!!.isLanguageAvailable(systemLocale)) {
                            TextToSpeech.LANG_AVAILABLE,
                            TextToSpeech.LANG_COUNTRY_AVAILABLE,
                            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> {
                                tts!!.language = systemLocale
                                logv("Language not supported. Setting language to system locale: $systemLocale")
                            }

                            else -> {
                                loge("Neither selected or default language is available")
                            }
                        }
                    }
                }

                _isInitialized.postValue(true)
                onInitStatus?.onSuccess()
                logv("Successfully initialized text to speech provider")
            }
        }, enginePackageName)
    }

    fun shutdown() {
        if (null != tts) {
            stop()
            tts!!.shutdown()
            tts = null
            _isInitialized.postValue(false)
            currentEngineName = null
        }
    }

    /**
     * Speak given [string]. Optional [utteranceId] will skip ahead until given utterance id is reached (in other words, resuming from previous speech output.
     * Also accepts optional [speechProgress] callback to monitor speech state.
     */
    fun speak(string: String, utteranceId: Int? = null, speechProgress: SpeechProgress? = null) {
        // Split according to sentence / newline delimeters
        val lines =
            string.split(regExSentenceDelimiter).mapNotNull { it.trim() }.filter { !it.isBlank() }
        speak(lines, utteranceId, speechProgress)
    }

    /**
     * Stop current speech output
     */
    fun stop() {
        if (tts?.isSpeaking == true) {
            logv("Stopping speak")
            tts?.stop()
        }
    }

    /**
     * Splt given string into equally pieces by [size]
     */
    private fun splitEqually(text: String, size: Int): List<String> {
        val ret = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            ret.add(text.substring(start, Math.min(text.length, start + size)))
            start += size
        }
        return ret
    }

    /**
     * Split up given strings into shorter strings, based on text-to-speech provider limits
     */
    private fun splitLines(lines: List<String>): List<String> {
        val maxLength = TextToSpeech.getMaxSpeechInputLength()
        val result = mutableListOf<String>()

        for (line in lines) {
            if (line.length < maxLength) {
                // If string is small enough, add it
                result.add(line)
                continue
            }

            // Split into shorter strings
            val shorterLines = line.split(regExWhitespaceDelimiter)
            var tempString = ""
            for (shorterLine in shorterLines) {
                if (shorterLine.length >= maxLength) {
                    if (!tempString.isBlank()) {
                        result.add(tempString)
                        tempString = ""
                    }

                    result.addAll(splitEqually(shorterLine, maxLength))
                    continue
                }

                if (tempString.length + shorterLine.length < maxLength) {
                    tempString += shorterLine
                } else {
                    result.add(tempString)
                    result.add(shorterLine)
                    tempString = ""
                }
            }
            if (!tempString.isBlank()) {
                result.add(tempString)
            }
        }

        return result
    }

    /**
     * Internal function to speak given set of [lines] strings, optionally skipping heads to given [utteranceId] and notifying on progress with [speechProgress]
     */
    private fun speak(lines: List<String>, utteranceId: Int?, speechProgress: SpeechProgress?) {
        logv("Speak starting")
        var isError = false

        if (isInitialized.value == false || null == tts) {
            speechProgress?.onError(0)
            return
        }

        if (tts!!.isSpeaking) {
            stop()
        }

        // Split lines into shorter strings as needed
        val inputLines = splitLines(lines)

        // Reset utterance id
        currentUtteranceId = 0
        val totalUtterances = inputLines.size

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceIdString: String?) {
                logv("UtteranceProgressListener: onStart $utteranceIdString")
            }

            override fun onDone(utteranceIdString: String?) {
                if (!utteranceIdString.isNullOrEmpty()) {
                    logv("UtteranceProgressListener: onUtteranceDone $utteranceIdString")
                    val currentUtteranceId = getUtteranceIdFromString(utteranceIdString)

                    speechProgress?.onUtteranceDone(currentUtteranceId)
                    if (currentUtteranceId == totalUtterances) {
                        // We're done when we've reached total utterances
                        logv("UtteranceProgressListener: onDone $utteranceIdString")
                        speechProgress?.onDone()
                    }
                }
            }

            override fun onError(utteranceIdString: String?) {
                isError = true
                if (!utteranceIdString.isNullOrEmpty()) {
                    val currentUtteranceId = getUtteranceIdFromString(utteranceIdString)
                    logv("UtteranceProgressListener: onError $utteranceIdString")
                    speechProgress?.onError(currentUtteranceId)
                }
            }
        })

        speechProgress?.onStart(totalUtterances)
        if (inputLines.size == 0 || (utteranceId != null && utteranceId + 1 >= inputLines.size)) {
            // If nothing to do, say we're done
            speechProgress?.onDone()
        } else {
            for (inputLine in inputLines) {
                if (isError) {
                    break
                }

                // If utterance id specified, skip ahead
                if (utteranceId != null && currentUtteranceId <= utteranceId) {
                    currentUtteranceId++
                    continue
                }

                // Queue up utterance
                val bundle = Bundle()
                bundle.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                tts?.speak(inputLine, TextToSpeech.QUEUE_ADD, bundle, utteranceIdAsString)

                // Add a pause after each utterance
                tts?.playSilentUtterance(100, TextToSpeech.QUEUE_ADD, "")
                currentUtteranceId++
            }
        }
        logv("Speak started")
    }

    /**
     * Get current system locale
     */
    private val currentSystemLocale: Locale
        get() {
            val systemConfig = Resources.getSystem().configuration
            val systemLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                systemConfig?.locales?.get(0)
            } else {
                @Suppress("deprecation")
                systemConfig?.locale
            }

            // Return the system locale
            return systemLocale ?: Locale.getDefault()
        }
}
