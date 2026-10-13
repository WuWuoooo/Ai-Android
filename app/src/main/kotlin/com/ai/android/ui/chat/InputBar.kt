package com.ai.android.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.android.R
import com.ai.android.model.SessionStats
import com.ai.android.ui.theme.LocalThemeStyleConfig
import com.ai.android.ui.theme.themeColorOf
import java.io.File

@Composable
fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    isRunning: Boolean,
    stateText: String = "",
    onPickFile: () -> Unit = {},
    onPickImage: () -> Unit = {},
    attachedFiles: List<String> = emptyList(),
    attachedImages: List<String> = emptyList(),
    onRemoveAttachment: (String) -> Unit = {},
    onRemoveImage: (String) -> Unit = {},
    quotedContent: String = "",
    onClearQuote: () -> Unit = {},
    onStartVoiceCall: () -> Unit = {},
    onStartVideoCall: () -> Unit = {},
    voiceController: VoiceInputController? = null,
    onVoiceAutoSend: (String) -> Unit = {},
    modelLabel: String = "",
    onSwitchModel: () -> Unit = {},
    onToggleAddMenu: () -> Unit = {},
    modelMenuOpen: Boolean = false,
    sessionStats: SessionStats = SessionStats(),
    modifier: Modifier = Modifier,
) {
    val theme = LocalThemeStyleConfig.current
    var voiceActive by remember { mutableStateOf(false) }

    val modelArrowAngle by animateFloatAsState(
        targetValue = if (modelMenuOpen) 180f else 0f,
        animationSpec = tween(220),
        label = "model_arrow",
    )

    LaunchedEffect(voiceController) {
        voiceController?.apply {
            onResult = { text ->
                voiceActive = false
                if (text.isNotBlank()) onVoiceAutoSend(text)
            }
            onError = { voiceActive = false }
        }
    }
    DisposableEffect(Unit) {
        onDispose { runCatching { voiceController?.cancel() } }
    }

    val hasText = value.isNotBlank()
    val inputShape = RoundedCornerShape(theme.inputRadius.dp)
    val inputBorderColor = themeColorOf(theme.inputBorderRole)
    val inputBorder = if (theme.inputBorderWidth > 0)
        BorderStroke(theme.inputBorderWidth.dp, inputBorderColor) else null

    Surface(
        modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Column {
            if (isRunning) {
                Row(
                    Modifier.padding(start = 18.dp, end = 18.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stateText.ifBlank { stringResource(R.string.chat_working) },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (quotedContent.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                ) {
                    Row(
                        Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Reply, null, Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.bubble_quoted),
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                            Text(
                                quotedContent.take(80).replace("\n", " ") +
                                    if (quotedContent.length > 80) "…" else "",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        IconButton(onClick = onClearQuote, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, stringResource(R.string.cancel), Modifier.size(14.dp))
                        }
                    }
                }
            }

            if (attachedFiles.isNotEmpty() || attachedImages.isNotEmpty()) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    attachedFiles.forEach { path ->
                        val name = runCatching { File(path).name }.getOrDefault(path).take(16)
                        AssistChip(
                            onClick = { onRemoveAttachment(path) },
                            label = { Text(name, fontSize = 11.sp) },
                            leadingIcon = { Icon(Icons.Default.InsertDriveFile, null, Modifier.size(14.dp)) },
                            trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(14.dp)) },
                        )
                    }
                    attachedImages.forEachIndexed { idx, img ->
                        AssistChip(
                            onClick = { onRemoveImage(img) },
                            label = { Text("${stringResource(R.string.image_label)} ${idx + 1}", fontSize = 11.sp) },
                            leadingIcon = { Icon(Icons.Default.Image, null, Modifier.size(14.dp)) },
                            trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(14.dp)) },
                        )
                    }
                }
            }

            if (voiceActive && !isRunning) {
                val p = voiceController?.phase
                Row(
                    Modifier.padding(start = 18.dp, end = 18.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val label = when {
                        p == VoiceInputController.Phase.RECORDING -> stringResource(R.string.input_speaking_record)
                        p == VoiceInputController.Phase.LIVE -> stringResource(R.string.input_speaking_live)
                        p == VoiceInputController.Phase.TRANSCRIBING -> stringResource(R.string.input_speaking_transcribe)
                        else -> ""
                    }
                    if (label.isNotEmpty()) {
                        CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onToggleAddMenu) {
                    Icon(Icons.Default.Add, stringResource(R.string.chat_add), Modifier.size(22.dp))
                }

                Box(
                    Modifier
                        .weight(1f)
                        .clip(inputShape)
                        .then(
                            if (inputBorder != null)
                                Modifier.border(inputBorder, inputShape)
                            else Modifier
                        )
                        .background(
                            when (theme.inputBorderWidth) {
                                0 -> MaterialTheme.colorScheme.surfaceContainerHigh
                                else -> MaterialTheme.colorScheme.surface
                            }
                        )
                        .padding(horizontal = 4.dp),
                ) {
                    TextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                stringResource(R.string.chat_input_placeholder),
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        maxLines = 5,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                    )
                }

                Spacer(Modifier.width(6.dp))

                if (isRunning) {
                    FilledIconButton(
                        onClick = onStop,
                        modifier = Modifier.size(46.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error),
                    ) { Icon(Icons.Default.Stop, stringResource(R.string.chat_stop),
                        tint = MaterialTheme.colorScheme.onError) }
                } else if (hasText) {
                    FilledIconButton(
                        onClick = { onSend(value) },
                        modifier = Modifier.size(46.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary),
                    ) { Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.chat_send)) }
                } else {
                    Box(
                        Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(
                                if (voiceActive) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary
                            )
                            .then(
                                if (voiceActive)
                                    Modifier.border(2.dp, MaterialTheme.colorScheme.error, CircleShape)
                                else Modifier
                            )
                            .pointerInput(voiceController) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    val c = voiceController
                                    if (c != null) {
                                        val started = c.begin()
                                        if (started) voiceActive = true
                                        waitForUpOrCancellation()
                                        if (voiceActive) c.finish()
                                    } else {
                                        waitForUpOrCancellation()
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = stringResource(R.string.chat_input_placeholder),
                            tint = if (voiceActive) MaterialTheme.colorScheme.onError
                            else MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }

            val statsText = buildStatsText(sessionStats)
            if (statsText.isNotBlank() || modelLabel.isNotBlank()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (statsText.isNotBlank()) {
                        Text(
                            text = statsText,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (modelLabel.isNotBlank()) {
                        Row(
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onSwitchModel,
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                modelLabel,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.input_switch_model),
                                modifier = Modifier
                                    .graphicsLayer { rotationZ = modelArrowAngle }
                                    .size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun buildStatsText(s: SessionStats): String {
    val parts = mutableListOf<String>()
    if (s.round > 0) {
        val roundPart = if (s.steps > 0) "${s.round}轮 ${s.steps}步" else "${s.round}轮"
        parts += roundPart
    }
    if (s.tokensPerSec > 0) parts += "${"%.0f".format(s.tokensPerSec)} tok/s"
    if (s.cacheHitRate > 0) parts += "命中 ${"%.0f".format(s.cacheHitRate)}%"
    if (s.totalTokens > 0) parts += formatTokens(s.totalTokens) + " tok"
    return parts.joinToString(" · ")
}

private fun formatTokens(n: Int): String = when {
    n < 1000 -> "$n"
    n < 1_000_000 -> "${"%.1f".format(n / 1000.0)}K"
    else -> "${"%.1f".format(n / 1_000_000.0)}M"
}