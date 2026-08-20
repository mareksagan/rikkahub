package me.rerere.rikkahub.ui.pages.tts

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.FloppyDisk
import me.rerere.hugeicons.stroke.Pause
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Share01
import me.rerere.hugeicons.stroke.Stop
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.tts.provider.TTSProviderSetting
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TTSGeneratorPage(
    vm: TTSGeneratorVM = koinViewModel()
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val text by vm.text.collectAsStateWithLifecycle()
    val selectedProviderId by vm.selectedProviderId.collectAsStateWithLifecycle()
    val isGenerating by vm.isGenerating.collectAsStateWithLifecycle()
    val generatedAudioFile by vm.generatedAudioFile.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val savedUri by vm.savedUri.collectAsStateWithLifecycle()

    val settings by vm.settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val providers = settings.ttsProviders

    var isPlaying by remember { mutableStateOf(false) }

    LaunchedEffect(error) {
        error?.let {
            toaster.show(it, type = ToastType.Error)
            vm.clearError()
        }
    }

    LaunchedEffect(savedUri) {
        savedUri?.let { uri ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Audio"))
            vm.clearSavedUri()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tts_generator_page_title)) },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Text input
            OutlinedTextField(
                value = text,
                onValueChange = { vm.updateText(it) },
                label = { Text(stringResource(R.string.tts_generator_text_hint)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                maxLines = 10,
            )

            // Provider selector
            var providerExpanded by remember { mutableStateOf(false) }
            val selectedProvider = providers.find { it.id.toString() == selectedProviderId }

            ExposedDropdownMenuBox(
                expanded = providerExpanded,
                onExpandedChange = { providerExpanded = it },
            ) {
                OutlinedTextField(
                    value = selectedProvider?.name ?: "Select Provider",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.tts_generator_provider)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(
                    expanded = providerExpanded,
                    onDismissRequest = { providerExpanded = false },
                ) {
                    providers.forEach { provider ->
                        DropdownMenuItem(
                            text = { Text(provider.name) },
                            onClick = {
                                vm.selectProvider(provider.id.toString())
                                providerExpanded = false
                            },
                        )
                    }
                }
            }

            // Voice info
            if (selectedProvider != null) {
                val voiceInfo = getVoiceInfo(selectedProvider)
                if (voiceInfo.isNotBlank()) {
                    Text(
                        text = "Voice: $voiceInfo",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Generate button
            Button(
                onClick = { vm.generate() },
                enabled = text.isNotBlank() && !isGenerating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowUp02,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = if (isGenerating) stringResource(R.string.tts_generator_generating)
                    else stringResource(R.string.tts_generator_generate),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            // Audio player and actions
            if (generatedAudioFile != null) {
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.tts_generator_audio_ready),
                    style = MaterialTheme.typography.titleMedium,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Play/Stop button
                    IconButton(
                        onClick = {
                            if (isPlaying) {
                                vm.stop()
                                isPlaying = false
                            } else {
                                vm.play()
                                isPlaying = true
                            }
                        },
                    ) {
                        Icon(
                            imageVector = if (isPlaying) HugeIcons.Stop else HugeIcons.Play,
                            contentDescription = if (isPlaying) "Stop" else "Play",
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Save button
                    OutlinedButton(
                        onClick = { vm.saveToDownloads() },
                    ) {
                        Icon(
                            imageVector = HugeIcons.FloppyDisk,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(R.string.tts_generator_save),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }

                    // Share button
                    OutlinedButton(
                        onClick = { vm.share() },
                    ) {
                        Icon(
                            imageVector = HugeIcons.Share01,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(R.string.tts_generator_share),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun getVoiceInfo(provider: TTSProviderSetting): String {
    return when (provider) {
        is TTSProviderSetting.OpenAI -> provider.voice
        is TTSProviderSetting.Gemini -> provider.voiceName
        is TTSProviderSetting.SystemTTS -> "System"
        is TTSProviderSetting.MiniMax -> provider.voiceId
        is TTSProviderSetting.Qwen -> provider.voice
        is TTSProviderSetting.Groq -> provider.voice
        is TTSProviderSetting.XAI -> provider.voiceId
        is TTSProviderSetting.MiMo -> provider.voice
        is TTSProviderSetting.ElevenLabs -> provider.voiceId
        is TTSProviderSetting.FishAudio -> provider.referenceId
        is TTSProviderSetting.Step -> provider.voice
    }
}
