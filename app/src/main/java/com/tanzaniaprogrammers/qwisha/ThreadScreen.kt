package com.tanzaniaprogrammers.qwisha

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.*
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(threadId: String, db: AppDatabase, onBack: () -> Unit) {
    // Constants
    val MAX_RECORDING_DURATION_MS = 20000 // 20 seconds

    var inputText by remember { mutableStateOf("") }
    var editingMsgId by remember { mutableStateOf<String?>(null) }
    var replyToMsg by remember { mutableStateOf<Message?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    var selectedMsg by remember { mutableStateOf<Message?>(null) }
    var sendingMessage by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingTime by remember { mutableStateOf(0) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var shouldSendRecording by remember { mutableStateOf(true) } // Flag to indicate if recording should be sent
    var currentRecordingFilePath by remember { mutableStateOf<String?>(null) } // Track current recording file for cleanup
    var showVoiceNoteConfirmation by remember { mutableStateOf(false) } // Show confirmation dialog
    var pendingVoiceNoteData by remember { mutableStateOf<Pair<String, String>?>(null) } // Store base64Audio and audioFilePath for confirmation
    var pendingVoiceNoteParts by remember { mutableStateOf(0) } // Number of SMS parts needed

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // Load and persist overlay header preference
    val prefs = remember {
        context.getSharedPreferences("qwisha_prefs", Context.MODE_PRIVATE)
    }
    var useOverlayHeader by remember {
        mutableStateOf(prefs.getBoolean("use_overlay_header_$threadId", true))
    }

    // Save preference when it changes
    LaunchedEffect(useOverlayHeader) {
        prefs.edit().putBoolean("use_overlay_header_$threadId", useOverlayHeader).apply()
    }

    // Load contact name
    var contactName by remember { mutableStateOf(threadId) }
    LaunchedEffect(threadId) {
        contactName = getContactName(context, threadId)
    }

    // Observe Room messages in real-time
    // Use flowOn to ensure proper threading and stateIn for better performance
    val messages by remember(threadId) {
        db.messageDao()
            .getMessagesFlow(threadId)
    }.collectAsState(initial = emptyList())

    // Mark messages as read when thread is viewed and when new messages arrive
    LaunchedEffect(threadId, messages.size) {
        scope.launch {
            db.messageDao().markThreadAsRead(threadId)

            // Cancel notification for this thread when messages are marked as read
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(threadId.hashCode())
        }
    }

    // Close menu when clicking outside
    LaunchedEffect(messages.size) {
        showMenu = false
    }

    fun sendMessage() {
        if (inputText.isBlank() || sendingMessage) return

        sendingMessage = true
        val msgId = generateShortId()
        val smsBody: String
        val hasOverlay: Boolean

        if (useOverlayHeader) {
            // Build compact header: @i=<MSGID>;c=<CMD>;r=<REFID> content
            // Commands: s=send, r=reply, e=edit, d=delete
            val cmd = when {
                editingMsgId != null -> "e"
                replyToMsg != null -> "r"
                else -> "s"
            }
            val refId = when {
                editingMsgId != null -> editingMsgId  // Original message ID being edited
                replyToMsg != null -> replyToMsg?.id  // Message being replied to
                else -> null
            }

            val header = buildString {
                append("@i=$msgId;c=$cmd")
                if (refId != null) {
                    append(";r=$refId")
                }
            }
            smsBody = "$header $inputText"
            hasOverlay = true
        } else {
            // Send as regular SMS without overlay header
            smsBody = inputText
            hasOverlay = false
        }

        // Send SMS with delivery report
        scope.launch {
            try {
                // Create explicit intents for app-local broadcasts
                val sentIntent = PendingIntent.getBroadcast(
                    context,
                    msgId.hashCode(),
                    Intent("SMS_SENT").apply {
                        setPackage(context.packageName)
                        putExtra("msgId", msgId)
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val deliveryIntent = PendingIntent.getBroadcast(
                    context,
                    msgId.hashCode(),
                    Intent("SMS_DELIVERED").apply {
                        setPackage(context.packageName)
                        putExtra("msgId", msgId)
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )

                withContext(Dispatchers.IO) {
                    SmsManager.getDefault().sendTextMessage(threadId, null, smsBody, sentIntent, deliveryIntent)
                }

                if (editingMsgId != null) {
                    // Update existing message
                    db.messageDao().updateContent(editingMsgId!!, inputText)
                } else {
                    // Insert new message
                    val newMsg = Message(
                        msgId,
                        threadId,
                        inputText,
                        true,
                        replyToMsg?.id,
                        "sent",
                        System.currentTimeMillis(),
                        hasOverlayHeader = hasOverlay
                    )
                    db.messageDao().insert(newMsg)
                }

                // Clear input
                inputText = ""
                replyToMsg = null
                editingMsgId = null
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                sendingMessage = false
            }
        }
        focusManager.clearFocus()
    }

    // Define sendVoiceNote first so it can be called from startRecording
    fun sendVoiceNote(base64Audio: String, audioFilePath: String) {
        if (sendingMessage) {
            Log.w("ThreadScreen", "Already sending a message, ignoring sendVoiceNote request")
            return
        }

        if (!useOverlayHeader) {
            // Can't send voice notes without overlay header
            Log.e("ThreadScreen", "Cannot send voice note: overlay header is disabled")
            isRecording = false
            return
        }

        // Calculate number of parts
        val chunks = AudioUtils.splitBase64ForSms(base64Audio)
        val totalParts = chunks.size

        // Show confirmation dialog
        pendingVoiceNoteData = Pair(base64Audio, audioFilePath)
        pendingVoiceNoteParts = totalParts
        showVoiceNoteConfirmation = true
    }

    fun confirmSendVoiceNote() {
        val data = pendingVoiceNoteData
        if (data == null || sendingMessage) {
            Log.w("ThreadScreen", "No pending voice note or already sending")
            showVoiceNoteConfirmation = false
            pendingVoiceNoteData = null
            return
        }

        val (base64Audio, audioFilePath) = data
        showVoiceNoteConfirmation = false
        pendingVoiceNoteData = null

        sendingMessage = true
        val msgId = generateShortId()

        scope.launch {
            try {
                Log.d("ThreadScreen", "Preparing to send voice note. Base64 length: ${base64Audio.length}")

                // Build header
                val cmd = when {
                    editingMsgId != null -> "e"
                    replyToMsg != null -> "r"
                    else -> "s"
                }
                val refId = when {
                    editingMsgId != null -> editingMsgId
                    replyToMsg != null -> replyToMsg?.id
                    else -> null
                }

                // Split base64 into chunks if needed
                val chunks = AudioUtils.splitBase64ForSms(base64Audio)
                val totalParts = chunks.size

                Log.d("ThreadScreen", "Sending voice note: $totalParts parts, total size: ${base64Audio.length} chars")

                // Insert message into database first (so it appears in UI immediately)
                val displayContent = "🎤 Voice message"
                val newMsg = Message(
                    msgId,
                    threadId,
                    displayContent,
                    true,
                    replyToMsg?.id,
                    "pending", // Start as pending, update to sent after all parts are sent
                    System.currentTimeMillis(),
                    hasOverlayHeader = true,
                    messageType = "voice",
                    audioFilePath = audioFilePath
                )
                db.messageDao().insert(newMsg)
                Log.d("ThreadScreen", "Voice message inserted into database with ID: $msgId")

                // Send each chunk as a separate SMS
                var sentParts = 0
                var failedParts = 0
                val smsManager = SmsManager.getDefault()

                // Send all parts sequentially with proper delays
                for ((index, chunk) in chunks.withIndex()) {
                    val partNumber = index + 1
                    val header = buildString {
                        append("@i=$msgId;c=$cmd")
                        append(";t=voice") // Message type: voice
                        if (refId != null) {
                            append(";r=$refId")
                        }
                        if (totalParts > 1) {
                            append(";p=$partNumber/$totalParts")
                        }
                    }
                    val smsBody = "$header $chunk"

                    // Verify SMS body doesn't exceed limit
                    if (smsBody.length > 160) {
                        Log.e("ThreadScreen", "ERROR: SMS body length ${smsBody.length} exceeds 160 character limit!")
                        Log.e("ThreadScreen", "Header length: ${header.length}, Chunk length: ${chunk.length}")
                        failedParts++
                        continue
                    }

                    Log.d("ThreadScreen", "Sending part $partNumber/$totalParts. SMS body length: ${smsBody.length}, header: '${header}' (${header.length} chars), chunk: ${chunk.length} chars")

                    // Create unique intents for each part using unique request codes
                    // This ensures each part gets its own delivery tracking
                    val partRequestCode = (msgId.hashCode() + partNumber * 1000) and 0x7FFFFFFF
                    val deliveryRequestCode = (msgId.hashCode() + partNumber * 2000) and 0x7FFFFFFF

                    val sentIntent = PendingIntent.getBroadcast(
                        context,
                        partRequestCode,
                        Intent("SMS_SENT").apply {
                            setPackage(context.packageName)
                            putExtra("msgId", msgId)
                            putExtra("partNumber", partNumber)
                            putExtra("totalParts", totalParts)
                        },
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )

                    val deliveryIntent = PendingIntent.getBroadcast(
                        context,
                        deliveryRequestCode,
                        Intent("SMS_DELIVERED").apply {
                            setPackage(context.packageName)
                            putExtra("msgId", msgId)
                            putExtra("partNumber", partNumber)
                            putExtra("totalParts", totalParts)
                        },
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )

                    try {
                        // Send SMS on IO dispatcher - this is a blocking call
                        withContext(Dispatchers.IO) {
                            Log.d("ThreadScreen", "Calling sendTextMessage for part $partNumber/$totalParts...")
                            smsManager.sendTextMessage(
                                threadId,
                                null,
                                smsBody,
                                sentIntent,
                                deliveryIntent
                            )
                            Log.d("ThreadScreen", "sendTextMessage returned for part $partNumber/$totalParts")
                        }
                        sentParts++
                        Log.d("ThreadScreen", "Successfully queued part $partNumber/$totalParts for sending (${sentParts}/$totalParts sent so far)")

                        // Delay between parts to ensure they're sent in order
                        // and to avoid overwhelming the SMS service
                        if (partNumber < totalParts) {
                            kotlinx.coroutines.delay(300) // 300ms delay between parts
                        }
                    } catch (e: Exception) {
                        failedParts++
                        Log.e("ThreadScreen", "Error sending part $partNumber/$totalParts: ${e.message}", e)
                        e.printStackTrace()
                        // Continue sending remaining parts even if one fails
                    }
                }

                if (failedParts > 0) {
                    Log.e("ThreadScreen", "Failed to send $failedParts out of $totalParts parts")
                    db.messageDao().updateStatus(msgId, "failed")
                } else if (sentParts == totalParts) {
                    Log.d("ThreadScreen", "Successfully queued all $sentParts parts of voice note for sending")
                    // Update status to sent (delivery will be tracked by delivery receiver)
                    db.messageDao().updateStatus(msgId, "sent")
                } else {
                    Log.w("ThreadScreen", "Unexpected: sent $sentParts parts but expected $totalParts")
                    db.messageDao().updateStatus(msgId, "failed")
                }

                // Clear state
                replyToMsg = null
                editingMsgId = null
            } catch (e: Exception) {
                Log.e("ThreadScreen", "Error in sendVoiceNote: ${e.message}", e)
                e.printStackTrace()
                // Update message status to failed if it was inserted
                try {
                    db.messageDao().updateStatus(msgId, "failed")
                } catch (_: Exception) {}
            } finally {
                sendingMessage = false
                isRecording = false
                recordingTime = 0
            }
        }
    }

    fun cancelSendVoiceNote() {
        showVoiceNoteConfirmation = false
        pendingVoiceNoteData = null
        pendingVoiceNoteParts = 0
        isRecording = false
        recordingTime = 0
        // Clean up the recording file
        scope.launch(Dispatchers.IO) {
            try {
                val filePath = currentRecordingFilePath
                filePath?.let { path ->
                    val file = java.io.File(path)
                    if (file.exists()) {
                        file.delete()
                        Log.d("ThreadScreen", "Deleted cancelled voice note file: $path")
                    }
                }
            } catch (e: Exception) {
                Log.e("ThreadScreen", "Error deleting cancelled voice note file: ${e.message}")
            }
        }
        currentRecordingFilePath = null
    }

    fun cancelRecording() {
        if (!isRecording) return

        Log.d("ThreadScreen", "Cancelling recording (will discard)...")
        // Set flag to not send
        shouldSendRecording = false
        // Signal to stop recording
        AudioUtils.stopCurrentRecording()
        // Cancel the recording job - this will trigger CancellationException
        recordingJob?.cancel()

        // Clean up state immediately for UI
        isRecording = false
        recordingTime = 0

        // Clean up the recording file (will be handled in catch block, but also try here)
        scope.launch(Dispatchers.IO) {
            try {
                // Wait a bit for the recording to stop
                kotlinx.coroutines.delay(300)
                val filePath = AudioUtils.getCurrentRecordingFilePath() ?: currentRecordingFilePath
                filePath?.let { path ->
                    val file = java.io.File(path)
                    if (file.exists()) {
                        file.delete()
                        Log.d("ThreadScreen", "Deleted cancelled recording file: $path")
                    }
                }
                currentRecordingFilePath = null
            } catch (e: Exception) {
                Log.e("ThreadScreen", "Error deleting cancelled recording file: ${e.message}")
            }
        }
    }

    fun sendRecording() {
        if (!isRecording) return

        Log.d("ThreadScreen", "Sending recording immediately (will stop and send)...")
        // Signal to stop recording and send immediately
        AudioUtils.stopCurrentRecording()
        shouldSendRecording = true // User wants to send this recording
        // Don't cancel the job - let it complete so the file is saved and sent
        // The recording loop will exit when shouldStopRecording is true
    }

    fun startRecording() {
        if (isRecording || sendingMessage) return

        // Check RECORD_AUDIO permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e("ThreadScreen", "RECORD_AUDIO permission not granted. Please grant permission in app settings.")
            // Show a toast or snackbar to inform user
            // For now, just log and return
            return
        }

        recordingJob = scope.launch {
            try {
                isRecording = true
                recordingTime = 0
                shouldSendRecording = true // Reset flag
                currentRecordingFilePath = null // Reset file path

                Log.d("ThreadScreen", "Starting audio recording...")

                // Start recording
                val audioFilePath = AudioUtils.recordAudio(context, MAX_RECORDING_DURATION_MS)
                currentRecordingFilePath = audioFilePath // Store for potential cleanup

                Log.d("ThreadScreen", "Recording completed. Audio file path: $audioFilePath")
                Log.d("ThreadScreen", "shouldSendRecording = $shouldSendRecording, isRecording = $isRecording")

                // Check if recording was cancelled before processing
                if (!shouldSendRecording || !isRecording) {
                    Log.d("ThreadScreen", "Recording was cancelled by user (shouldSendRecording=$shouldSendRecording), not sending")
                    // Clean up the file if it was created
                    audioFilePath?.let { path ->
                        try {
                            val file = java.io.File(path)
                            if (file.exists()) {
                                file.delete()
                                Log.d("ThreadScreen", "Deleted cancelled recording file: $path")
                            }
                            else {
                                Log.e("ThreadScreen", "Recording file not found")
                            }
                        } catch (e: Exception) {
                            Log.e("ThreadScreen", "Error deleting cancelled recording: ${e.message}")
                        }
                    }
                    // Update UI state
                    isRecording = false
                    recordingTime = 0
                    currentRecordingFilePath = null
                    return@launch
                }

                // Now set isRecording = false since recording is done and we're about to process
                isRecording = false
                recordingTime = 0

                if (audioFilePath != null) {
                    // Verify file has content (minimum size check)
                    val file = java.io.File(audioFilePath)
                    Log.d("ThreadScreen", "Checking audio file: exists=${file.exists()}, size=${file.length()} bytes")

                    if (file.exists() && file.length() > 0) {
                        Log.d("ThreadScreen", "Audio file is valid, proceeding to encode and send")
                        // Compress and encode audio
                        Log.d("ThreadScreen", "Compressing and encoding audio...")
                        val base64Audio = AudioUtils.compressAndEncodeAudio(audioFilePath)

                        if (base64Audio != null && base64Audio.isNotEmpty()) {
                            Log.d("ThreadScreen", "Audio encoded successfully. Base64 length: ${base64Audio.length} chars")
                            // Prepare voice note for sending (will show confirmation dialog)
                            Log.d("ThreadScreen", "Preparing voice note for confirmation...")
                            sendVoiceNote(base64Audio, audioFilePath)
                            Log.d("ThreadScreen", "sendVoiceNote() called, confirmation dialog should appear")
                        } else {
                            Log.e("ThreadScreen", "Failed to encode audio or encoded audio is empty. base64Audio is null: ${base64Audio == null}")
                            // Failed to encode
                        }
                    } else {
                        Log.e("ThreadScreen", "Audio file validation failed: exists=${file.exists()}, size=${file.length()} bytes")
                        if (!file.exists()) {
                            Log.e("ThreadScreen", "Audio file does not exist at path: $audioFilePath")
                        } else if (file.length() == 0L) {
                            Log.e("ThreadScreen", "Audio file exists but is empty (0 bytes)")
                        }
                    }
                } else {
                    Log.e("ThreadScreen", "Failed to record audio - audioFilePath is null. Recording may have failed or been cancelled.")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d("ThreadScreen", "Recording job cancelled")
                isRecording = false
                recordingTime = 0
                // Clean up the file if it was created
                try {
                    val filePath = currentRecordingFilePath ?: AudioUtils.getCurrentRecordingFilePath()
                    filePath?.let { path ->
                        val file = java.io.File(path)
                        if (file.exists()) {
                            file.delete()
                            Log.d("ThreadScreen", "Deleted cancelled recording file: $path")
                        }
                    }
                } catch (ex: Exception) {
                    Log.e("ThreadScreen", "Error cleaning up cancelled recording: ${ex.message}")
                }
                currentRecordingFilePath = null
                // Don't send if cancelled
                shouldSendRecording = false
            } catch (e: Exception) {
                Log.e("ThreadScreen", "Error in startRecording: ${e.message}", e)
                e.printStackTrace()
                isRecording = false
                recordingTime = 0
                currentRecordingFilePath = null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(contactName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(threadId, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Toggle overlay header option
                    IconButton(onClick = { useOverlayHeader = !useOverlayHeader }) {
                        Icon(
                            if (useOverlayHeader) Icons.Default.Star else Icons.Default.Email,
                            contentDescription = if (useOverlayHeader) "Using overlay headers" else "Sending as regular SMS",
                            tint = if (useOverlayHeader) Color.White else Color.White.copy(alpha = 0.7f)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(Modifier.padding(8.dp)) {
                    // Reply indicator
                    AnimatedVisibility(
                        visible = replyToMsg != null,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Replying to",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        replyToMsg?.content ?: "",
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(onClick = { replyToMsg = null }) {
                                    Icon(Icons.Default.Close, contentDescription = "Cancel reply", modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }

                    // Edit indicator
                    AnimatedVisibility(
                        visible = editingMsgId != null,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.tertiary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Editing message",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = {
                                    editingMsgId = null
                                    inputText = ""
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Cancel edit", modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.Bottom) {
                        // Show different UI when recording vs not recording
                        if (isRecording) {
                            // Recording state: Show Cancel and Send buttons
                            // Cancel button (left side)
                            IconButton(
                                onClick = { cancelRecording() },
                                enabled = !sendingMessage
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Cancel recording",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            // Spacer to push send button to the right
                            Spacer(Modifier.weight(1f))
                            // Send button (right side) - sends immediately, stops recording
                            FloatingActionButton(
                                onClick = { sendRecording() },
                                modifier = Modifier.size(56.dp),
                                containerColor = MaterialTheme.colorScheme.primary,
                                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp),
//                                enabled = !sendingMessage
                            ) {
                                Icon(
                                    Icons.Default.Send,
                                    contentDescription = "Send voice note",
                                    tint = Color.White
                                )
                            }
                        } else {
                            // Not recording: Show mic button and text input
                            // Microphone button for voice notes (only if overlay header is enabled)
                            //  if (useOverlayHeader) {
                            if (false) {
                                IconButton(
                                    onClick = { startRecording() },
                                    enabled = !sendingMessage && inputText.isBlank()
                                ) {
                                    Icon(
                                        Icons.Default.Phone,
                                        contentDescription = "Record voice note",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = inputText,
                                onValueChange = { inputText = it },
                                placeholder = { Text("Message") },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp, max = 120.dp),
                                shape = RoundedCornerShape(24.dp),
                                maxLines = 5,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                )
                            )
                            Spacer(Modifier.width(8.dp))
                            FloatingActionButton(
                                onClick = { sendMessage() },
                                modifier = Modifier.size(56.dp),
                                containerColor = if (inputText.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp)
                            ) {
                                Icon(
                                    if (editingMsgId != null) Icons.Default.Check else Icons.Default.Send,
                                    contentDescription = "Send",
                                    tint = if (inputText.isNotBlank()) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    // Recording indicator
                    AnimatedVisibility(
                        visible = isRecording,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Phone,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Recording voice note...",
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        "Tap Send to send now or Cancel to discard",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        // Voice note confirmation dialog
        if (showVoiceNoteConfirmation) {
            AlertDialog(
                onDismissRequest = { cancelSendVoiceNote() },
                title = {
                    Text(
                        "Send Voice Note?",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column {
                        Text(
                            "This voice note will be sent as $pendingVoiceNoteParts SMS message${if (pendingVoiceNoteParts > 1) "s" else ""}.",
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        if (pendingVoiceNoteParts > 1) {
                            Text(
                                "The recipient will receive $pendingVoiceNoteParts separate messages that will be automatically combined.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { confirmSendVoiceNote() }
                    ) {
                        Text("Send")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { cancelSendVoiceNote() }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (messages.isEmpty()) {
                EmptyThreadState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    reverseLayout = true,
                    contentPadding = PaddingValues(8.dp)
                ) {
                    items(messages.reversed()) { m ->
                        MessageBubble(
                            message = m,
                            allMessages = messages,
                            onReply = { replyToMsg = it },
                            onEdit = {
                                inputText = it.content
                                editingMsgId = it.id
                            },
                            onDelete = {
                                val header = "@i=${generateShortId()};c=d;r=${it.id}"
                                val smsBody = "$header "
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            // Delete command doesn't need delivery reports
                                            SmsManager.getDefault().sendTextMessage(threadId, null, smsBody, null, null)
                                        }
                                        db.messageDao().deleteById(it.id)
                                    } catch (_: Exception) {}
                                }
                            },
                            onCopy = { }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

