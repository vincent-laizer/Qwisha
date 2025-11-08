package com.tanzaniaprogrammers.qwisha

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
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
    var inputText by remember { mutableStateOf("") }
    var editingMsgId by remember { mutableStateOf<String?>(null) }
    var replyToMsg by remember { mutableStateOf<Message?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    var selectedMsg by remember { mutableStateOf<Message?>(null) }
    var sendingMessage by remember { mutableStateOf(false) }

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
        db.messageDao().markThreadAsRead(threadId)
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
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            placeholder = { Text("Type a message...") },
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
            }
        }
    ) { padding ->
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

