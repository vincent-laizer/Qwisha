package com.tanzaniaprogrammers.qwisha

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.media.MediaPlayer
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun MessageBubble(
    message: Message,
    allMessages: List<Message>,
    onReply: (Message) -> Unit,
    onEdit: (Message) -> Unit,
    onDelete: (Message) -> Unit,
    onCopy: (Message) -> Unit = {}
) {
    var showMenu by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var playbackPosition by remember { mutableStateOf(0) }
    var audioDuration by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Load audio duration when message is a voice note
    LaunchedEffect(message.id, message.messageType, message.audioFilePath) {
        if (message.messageType == "voice" && message.audioFilePath != null) {
            audioDuration = AudioUtils.getAudioDuration(message.audioFilePath!!)
        }
    }

    // Cleanup media player when component is disposed
    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    fun togglePlayback() {
        if (message.messageType != "voice" || message.audioFilePath == null) return

        scope.launch {
            if (isPlaying) {
                // Stop playback
                mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = null
                isPlaying = false
                playbackPosition = 0
            } else {
                // Start playback
                try {
                    val player = AudioUtils.playAudio(message.audioFilePath!!) {
                        // On completion
                        isPlaying = false
                        playbackPosition = 0
                    }
                    mediaPlayer = player
                    isPlaying = true
                } catch (e: Exception) {
                    e.printStackTrace()
                    isPlaying = false
                }
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (message.outgoing) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .combinedClickable(
                    onClick = { },
                    onLongClick = { showMenu = true }
                )
        ) {
            // Reply indicator
            message.replyTo?.let { replyId ->
                val repliedMsg = allMessages.find { it.id == replyId }
                repliedMsg?.let {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (message.outgoing)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(bottom = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = if (message.outgoing) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                it.content,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (message.outgoing) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Message bubble
            Surface(
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (message.outgoing) 16.dp else 4.dp,
                    bottomEnd = if (message.outgoing) 4.dp else 16.dp
                ),
                color = if (message.outgoing)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 1.dp
            ) {
                Column(Modifier.padding(12.dp)) {
                    // Voice message UI
                    if (message.messageType == "voice" && message.audioFilePath != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { togglePlayback() },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    if (isPlaying) Icons.Default.Close else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = if (message.outgoing) Color.White else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "🎤 Voice message",
                                    color = if (message.outgoing) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium
                                )
                                if (audioDuration > 0) {
                                    Text(
                                        AudioUtils.formatDuration(audioDuration / 1000),
                                        fontSize = 12.sp,
                                        color = if (message.outgoing) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    } else {
                        // Regular text message
                        Text(
                            message.content,
                            color = if (message.outgoing) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Show delivery status for outgoing messages
                        if (message.outgoing) {
                            val statusIcon = when (message.status) {
                                "delivered" -> Icons.Default.CheckCircle
                                "sent" -> Icons.Default.Done
                                "failed" -> Icons.Default.Close
                                else -> Icons.AutoMirrored.Filled.Send
                            }
                            val statusColor = when (message.status) {
                                "delivered" -> Color.White.copy(alpha = 0.7f)
                                "sent" -> Color.White.copy(alpha = 0.5f)
                                "failed" -> Color(0xFFFF5252)
                                else -> Color.White.copy(alpha = 0.5f)
                            }
                            Icon(
                                statusIcon,
                                contentDescription = message.status,
                                modifier = Modifier.size(12.dp),
                                tint = statusColor
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            formatTime(message.timestamp),
                            fontSize = 10.sp,
                            color = if (message.outgoing) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            // Context menu
            // Only show overlay features (reply, edit, delete) for messages with overlay header
            val supportsOverlay = message.hasOverlayHeader

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Copy") },
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Message", message.content)
                        clipboard.setPrimaryClip(clip)
                        showMenu = false
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = null
                        )
                    }
                )
                DropdownMenuItem(
                    text = { Text("Reply") },
                    onClick = {
                        if (supportsOverlay) {
                            onReply(message)
                        }
                        showMenu = false
                    },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                    enabled = supportsOverlay
                )
                // Edit is only available for outgoing messages with overlay header (messages you sent)
                if (message.outgoing && supportsOverlay) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = {
                            onEdit(message)
                            showMenu = false
                        },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = {
                        if (supportsOverlay) {
                            onDelete(message)
                        }
                        showMenu = false
                    },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    enabled = supportsOverlay
                )
            }
        }
    }
}

