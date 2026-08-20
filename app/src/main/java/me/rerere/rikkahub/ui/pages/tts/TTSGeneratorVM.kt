package me.rerere.rikkahub.ui.pages.tts

import android.app.Application
import android.content.ContentValues
import android.media.MediaPlayer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.tts.controller.TtsChunk
import me.rerere.tts.controller.TtsSynthesizer
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.provider.TTSProviderSetting
import java.io.File

private const val TAG = "TTSGeneratorVM"

class TTSGeneratorVM(
    private val application: Application,
    val settingsStore: SettingsStore,
    private val ttsSynthesizer: TtsSynthesizer,
) : AndroidViewModel(application) {
    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text

    private val _selectedProviderId = MutableStateFlow<String?>(null)
    val selectedProviderId: StateFlow<String?> = _selectedProviderId

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    private val _generatedAudioFile = MutableStateFlow<File?>(null)
    val generatedAudioFile: StateFlow<File?> = _generatedAudioFile

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _savedUri = MutableStateFlow<Uri?>(null)
    val savedUri: StateFlow<Uri?> = _savedUri

    private var mediaPlayer: MediaPlayer? = null

    init {
        viewModelScope.launch {
            settingsStore.settingsFlow.collect { settings ->
                if (_selectedProviderId.value == null) {
                    _selectedProviderId.value = settings.getSelectedTTSProvider()?.id?.toString()
                }
            }
        }
    }

    fun updateText(text: String) {
        _text.value = text
    }

    fun selectProvider(providerId: String) {
        _selectedProviderId.value = providerId
    }

    fun getSelectedProvider(): TTSProviderSetting? {
        val settings = settingsStore.settingsFlow.value
        val providerId = _selectedProviderId.value ?: return settings.ttsProviders.firstOrNull()
        return settings.ttsProviders.find { it.id.toString() == providerId }
    }

    fun generate() {
        val text = _text.value.trim()
        if (text.isBlank()) {
            _error.value = "Please enter text"
            return
        }

        val provider = getSelectedProvider()
        if (provider == null) {
            _error.value = "No TTS provider selected"
            return
        }

        viewModelScope.launch {
            _isGenerating.value = true
            _error.value = null
            _generatedAudioFile.value = null

            try {
                val response = ttsSynthesizer.synthesize(provider, TtsChunk(index = 0, text = text))
                val file = saveToCache(response)
                _generatedAudioFile.value = file
                Log.i(TAG, "Generated audio: ${file.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Generation failed", e)
                _error.value = "Generation failed: ${e.message}"
            } finally {
                _isGenerating.value = false
            }
        }
    }

    private suspend fun saveToCache(response: TTSResponse): File = withContext(Dispatchers.IO) {
        val cacheDir = application.cacheDir
        val ttsDir = File(cacheDir, "tts_generator")
        ttsDir.mkdirs()

        val extension = when (response.format) {
            AudioFormat.MP3 -> "mp3"
            AudioFormat.WAV -> "wav"
            AudioFormat.OGG -> "ogg"
            AudioFormat.AAC -> "aac"
            AudioFormat.OPUS -> "opus"
            AudioFormat.PCM -> "wav"
        }

        val file = File(ttsDir, "tts_${System.currentTimeMillis()}.$extension")
        file.writeBytes(response.audioData)
        file
    }

    fun saveToDownloads() {
        val file = _generatedAudioFile.value ?: return
        val provider = getSelectedProvider() ?: return

        viewModelScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "tts_${System.currentTimeMillis()}.${file.extension}")
                        put(MediaStore.MediaColumns.MIME_TYPE, getMimeType(file.extension))
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }

                    val resolver = application.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    uri?.let {
                        resolver.openOutputStream(it)?.use { outputStream ->
                            file.inputStream().use { inputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                    }
                    uri
                }

                if (uri != null) {
                    _savedUri.value = uri
                    Log.i(TAG, "Saved to Downloads: $uri")
                } else {
                    _error.value = "Failed to save file"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Save failed", e)
                _error.value = "Save failed: ${e.message}"
            }
        }
    }

    fun share() {
        val file = _generatedAudioFile.value ?: return

        viewModelScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    FileProvider.getUriForFile(
                        application,
                        "${application.packageName}.fileprovider",
                        file
                    )
                }
                _savedUri.value = uri
            } catch (e: Exception) {
                Log.e(TAG, "Share failed", e)
                _error.value = "Share failed: ${e.message}"
            }
        }
    }

    fun play() {
        val file = _generatedAudioFile.value ?: return

        try {
            stop()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    stop()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Playback failed", e)
            _error.value = "Playback failed: ${e.message}"
        }
    }

    fun stop() {
        mediaPlayer?.apply {
            if (isPlaying) {
                stop()
            }
            release()
        }
        mediaPlayer = null
    }

    fun clearError() {
        _error.value = null
    }

    fun clearSavedUri() {
        _savedUri.value = null
    }

    private fun getMimeType(extension: String): String {
        return when (extension.lowercase()) {
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "aac" -> "audio/aac"
            "opus" -> "audio/opus"
            else -> "audio/*"
        }
    }

    override fun onCleared() {
        super.onCleared()
        stop()
    }
}
