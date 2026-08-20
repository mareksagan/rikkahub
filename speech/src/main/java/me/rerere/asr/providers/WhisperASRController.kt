package me.rerere.asr.providers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.asr.ASRController
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.asr.appendAmplitude
import me.rerere.asr.calculateRmsAmplitude
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException

private const val TAG = "WhisperASR"

/**
 * OpenAI Whisper ASR Controller.
 *
 * Uses standard OpenAI [POST /v1/audio/transcriptions] endpoint.
 * Records audio in segments, converts each segment to WAV, and uploads via multipart/form-data.
 * Compatible with all OpenAI-compatible APIs (OpenAI, OVH AI, local servers, etc.).
 */
class WhisperASRController(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val provider: ASRProviderSetting.Whisper
) : ASRController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ASRState(isAvailable = true))
    override val state: StateFlow<ASRState> = _state.asStateFlow()

    private var recorderJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var onTranscriptChange: ((String) -> Unit)? = null

    private val bufferLock = Any()
    private var currentBuffer = ByteArrayOutputStream()
    private var segmentStartElapsedMs = 0L
    private val completedTranscripts = mutableListOf<String>()

    override fun start(onTranscriptChange: (String) -> Unit) {
        if (state.value.isRecording) return
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            setError("Microphone permission is required")
            return
        }

        this.onTranscriptChange = onTranscriptChange
        synchronized(bufferLock) {
            currentBuffer = ByteArrayOutputStream()
            segmentStartElapsedMs = SystemClock.elapsedRealtime()
        }
        completedTranscripts.clear()

        _state.update {
            it.copy(
                status = ASRStatus.Listening,
                transcript = "",
                errorMessage = null
            )
        }

        recorderJob = scope.launch(Dispatchers.IO) {
            val sampleRate = provider.sampleRate
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                .coerceAtLeast(4096)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                setError("Failed to initialize AudioRecord")
                return@launch
            }

            audioRecord?.startRecording()

            val buffer = ByteArray(bufferSize)
            val segmentMs = provider.segmentDurationSec * 1000L
            while (isActive) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) {
                    val amplitude = calculateRmsAmplitude(buffer, read)
                    _state.update { it.copy(amplitudes = it.amplitudes.appendAmplitude(amplitude)) }

                    val shouldFlush = synchronized(bufferLock) {
                        currentBuffer.write(buffer, 0, read)
                        segmentMs > 0 && (SystemClock.elapsedRealtime() - segmentStartElapsedMs >= segmentMs)
                    }

                    if (shouldFlush) {
                        scope.launch(Dispatchers.IO) { flushBuffer() }
                    }
                } else if (read < 0) {
                    break
                }
            }
        }
    }

    override fun stop() {
        recorderJob?.cancel()
        recorderJob = null
        stopRecording()

        _state.update {
            it.copy(status = ASRStatus.Stopping)
        }

        // Flush remaining buffer
        scope.launch(Dispatchers.IO) {
            flushBuffer()
            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(
                        status = ASRStatus.Idle,
                        isAvailable = true
                    )
                }
            }
        }
    }

    override fun dispose() {
        scope.cancel()
        stopRecording()
    }

    private fun stopRecording() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping recording", e)
        }
        audioRecord = null
    }

    private suspend fun flushBuffer() {
        val audioData: ByteArray
        synchronized(bufferLock) {
            if (currentBuffer.size() == 0) return
            audioData = currentBuffer.toByteArray()
            currentBuffer.reset()
            segmentStartElapsedMs = SystemClock.elapsedRealtime()
        }

        try {
            val transcription = transcribe(audioData)
            if (transcription.isNotBlank()) {
                synchronized(completedTranscripts) {
                    completedTranscripts.add(transcription)
                    val fullText = completedTranscripts.joinToString(" ")
                    onTranscriptChange?.invoke(fullText)
                    _state.update {
                        it.copy(transcript = fullText)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            setError("Transcription failed: ${e.message}")
        }
    }

    private suspend fun transcribe(audioData: ByteArray): String = withContext(Dispatchers.IO) {
        val wavData = pcmToWav(audioData, provider.sampleRate, 1, 16)

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "audio.wav",
                wavData.toRequestBody("audio/wav".toMediaType())
            )
            .addFormDataPart("model", provider.model)
            .apply {
                if (provider.language.isNotBlank()) {
                    addFormDataPart("language", provider.language)
                }
            }
            .build()

        val url = "${provider.baseUrl.trimEnd('/')}/audio/transcriptions"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${provider.apiKey}")
            .post(requestBody)
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            throw IOException("Transcription failed: ${response.code} $errorBody")
        }

        val body = response.body?.string() ?: ""
        val json = JSONObject(body)
        json.optString("text", "")
    }

    private fun setError(message: String) {
        _state.update {
            it.copy(
                status = ASRStatus.Error,
                errorMessage = message,
                isAvailable = false
            )
        }
    }

    companion object {
        /**
         * Transcribe an audio/video file using the Whisper API.
         *
         * Accepts arbitrary audio formats (MP3, WAV, M4A, WEBM, etc.) — no PCM conversion needed.
         * The Whisper API handles format detection automatically.
         *
         * @param httpClient OkHttp client for making the request
         * @param baseUrl API base URL (e.g., "https://api.openai.com/v1")
         * @param apiKey API key for authentication
         * @param model Whisper model to use (e.g., "whisper-1", "whisper-large-v3-turbo")
         * @param language Optional language code (e.g., "en", "zh"). Empty string = auto-detect
         * @param audioBytes Raw audio/video file bytes
         * @param fileName Original file name (used for MIME type hint in the request)
         * @return Transcribed text
         */
        suspend fun transcribeFile(
            httpClient: OkHttpClient,
            baseUrl: String,
            apiKey: String,
            model: String,
            language: String,
            audioBytes: ByteArray,
            fileName: String,
        ): String = withContext(Dispatchers.IO) {
            val mimeType = when {
                fileName.endsWith(".mp3", true) -> "audio/mpeg"
                fileName.endsWith(".wav", true) -> "audio/wav"
                fileName.endsWith(".m4a", true) -> "audio/mp4"
                fileName.endsWith(".ogg", true) -> "audio/ogg"
                fileName.endsWith(".flac", true) -> "audio/flac"
                fileName.endsWith(".webm", true) -> "audio/webm"
                fileName.endsWith(".mp4", true) -> "video/mp4"
                fileName.endsWith(".mov", true) -> "video/quicktime"
                fileName.endsWith(".avi", true) -> "video/x-msvideo"
                fileName.endsWith(".mkv", true) -> "video/x-matroska"
                else -> "audio/wav"
            }

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    fileName,
                    audioBytes.toRequestBody(mimeType.toMediaType())
                )
                .addFormDataPart("model", model)
                .apply {
                    if (language.isNotBlank()) {
                        addFormDataPart("language", language)
                    }
                }
                .build()

            val url = "${baseUrl.trimEnd('/')}/audio/transcriptions"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                throw IOException("Transcription failed: ${response.code} $errorBody")
            }

            val body = response.body?.string() ?: ""
            val json = JSONObject(body)
            json.optString("text", "")
        }

        /**
         * Convert raw PCM to WAV format.
         */
        fun pcmToWav(
            pcmData: ByteArray,
            sampleRate: Int,
            channels: Int,
            bitsPerSample: Int
        ): ByteArray {
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8
            val dataSize = pcmData.size
            val totalSize = 44 + dataSize

            val header = ByteArray(44)
            // RIFF header
            header[0] = 'R'.code.toByte()
            header[1] = 'I'.code.toByte()
            header[2] = 'F'.code.toByte()
            header[3] = 'F'.code.toByte()
            writeInt(header, 4, totalSize - 8)
            header[8] = 'W'.code.toByte()
            header[9] = 'A'.code.toByte()
            header[10] = 'V'.code.toByte()
            header[11] = 'E'.code.toByte()
            // fmt chunk
            header[12] = 'f'.code.toByte()
            header[13] = 'm'.code.toByte()
            header[14] = 't'.code.toByte()
            header[15] = ' '.code.toByte()
            writeInt(header, 16, 16) // chunk size
            writeShort(header, 20, 1) // PCM format
            writeShort(header, 22, channels)
            writeInt(header, 24, sampleRate)
            writeInt(header, 28, byteRate)
            writeShort(header, 32, blockAlign)
            writeShort(header, 34, bitsPerSample)
            // data chunk
            header[36] = 'd'.code.toByte()
            header[37] = 'a'.code.toByte()
            header[38] = 't'.code.toByte()
            header[39] = 'a'.code.toByte()
            writeInt(header, 40, dataSize)

            return header + pcmData
        }

        private fun writeInt(data: ByteArray, offset: Int, value: Int) {
            data[offset] = (value and 0xFF).toByte()
            data[offset + 1] = (value shr 8 and 0xFF).toByte()
            data[offset + 2] = (value shr 16 and 0xFF).toByte()
            data[offset + 3] = (value shr 24 and 0xFF).toByte()
        }

        private fun writeShort(data: ByteArray, offset: Int, value: Int) {
            data[offset] = (value and 0xFF).toByte()
            data[offset + 1] = (value shr 8 and 0xFF).toByte()
        }
    }
}
