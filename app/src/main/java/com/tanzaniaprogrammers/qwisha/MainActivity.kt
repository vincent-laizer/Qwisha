package com.tanzaniaprogrammers.qwisha

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.provider.ContactsContract
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

// Generate a 5-character alphanumeric ID
fun generateShortId(): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    return (1..5)
        .map { chars.random() }
        .joinToString("")
}

class MainActivity : ComponentActivity() {

    private lateinit var db: AppDatabase
    private var smsSentReceiver: BroadcastReceiver? = null
    private var smsDeliveredReceiver: BroadcastReceiver? = null

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "sms_overlay_db")
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()

        // Create notification channel
        createNotificationChannel()

        // Request permissions
        val permissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            // handle result if needed
        }
        permissionsLauncher.launch(arrayOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.POST_NOTIFICATIONS
        ))

        // Register SMS delivery receivers
        smsSentReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val msgId = intent?.getStringExtra("msgId") ?: return
                val resultCode = resultCode
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val status = when (resultCode) {
                            RESULT_OK -> "sent"
                            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "failed"
                            SmsManager.RESULT_ERROR_NO_SERVICE -> "failed"
                            SmsManager.RESULT_ERROR_NULL_PDU -> "failed"
                            SmsManager.RESULT_ERROR_RADIO_OFF -> "failed"
                            else -> "pending"
                        }
                        db.messageDao().updateStatus(msgId, status)
                    }
                }
            }
        }

        smsDeliveredReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val msgId = intent?.getStringExtra("msgId") ?: return
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        db.messageDao().updateStatus(msgId, "delivered")
                    }
                }
            }
        }

        // Use RECEIVER_NOT_EXPORTED for app-local broadcasts (Android 13+)
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Context.RECEIVER_NOT_EXPORTED
        } else {
            0
        }

        registerReceiver(smsSentReceiver, IntentFilter("SMS_SENT"), flag)
        registerReceiver(smsDeliveredReceiver, IntentFilter("SMS_DELIVERED"), flag)

        // Note: SMS reception is handled by SmsReceiver declared in AndroidManifest.xml
        // This ensures SMS are received even when the app is closed

        setContent {
            SmsOverlayTheme {
                val navController = rememberNavController()
                val openThreadId = intent.getStringExtra("openThreadId")

                // Navigate to thread if opened from notification
                LaunchedEffect(openThreadId) {
                    if (openThreadId != null) {
                        navController.navigate("chat/$openThreadId")
                    }
                }

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    NavHost(navController = navController, startDestination = "threads") {
                        composable("threads") { ConversationsScreen(navController, db) }
                        composable("chat/{threadId}") { backStackEntry ->
                            val threadId = backStackEntry.arguments?.getString("threadId") ?: ""
                            ThreadScreen(threadId, db) { navController.popBackStack() }
                        }
                        composable("settings") { SettingsScreen(navController) }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        smsSentReceiver?.let { unregisterReceiver(it) }
        smsDeliveredReceiver?.let { unregisterReceiver(it) }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "sms_channel",
                "SMS Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for received SMS messages"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

}

// ------------------ Room Entities & DAO ------------------

@Entity
data class Message(
    @PrimaryKey val id: String,
    val threadId: String,
    val content: String,
    val outgoing: Boolean,
    val replyTo: String?,
    val status: String,
    val timestamp: Long,
    val hasOverlayHeader: Boolean = false  // True if message was sent/received with overlay protocol header
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM Message WHERE threadId = :threadId ORDER BY timestamp ASC")
    fun getMessagesFlow(threadId: String): kotlinx.coroutines.flow.Flow<List<Message>>

    @Query("SELECT * FROM Message ORDER BY timestamp DESC")
    fun getAllMessagesFlow(): kotlinx.coroutines.flow.Flow<List<Message>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(msg: Message)

    @Query("UPDATE Message SET content = :content WHERE id = :id")
    suspend fun updateContent(id: String, content: String)

    @Query("UPDATE Message SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("DELETE FROM Message WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("""
        SELECT * FROM Message 
        WHERE content LIKE '%' || :query || '%' OR threadId LIKE '%' || :query || '%'
        ORDER BY timestamp DESC
    """)
    fun searchMessagesFlow(query: String): kotlinx.coroutines.flow.Flow<List<Message>>
}

@Database(entities = [Message::class], version = 2)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    companion object {
        // Migration from version 1 to 2: add hasOverlayHeader column
        val MIGRATION_1_2 = androidx.room.migration.Migration(1, 2) {
            it.execSQL("ALTER TABLE Message ADD COLUMN hasOverlayHeader INTEGER NOT NULL DEFAULT 0")
        }
    }
}

data class ThreadSummary(
    val threadId: String,
    val contactName: String,
    val snippet: String,
    val lastAtEpoch: Long,
    val unread: Int = 0
)

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
    var useOverlayHeader by remember { mutableStateOf(true) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    // Load contact name
    var contactName by remember { mutableStateOf(threadId) }
    LaunchedEffect(threadId) {
        contactName = getContactName(context, threadId)
    }

    // Observe Room messages in real-time
    val messages by db.messageDao()
        .getMessagesFlow(threadId)
        .collectAsState(initial = emptyList())

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
                            }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun MessageBubble(
    message: Message,
    allMessages: List<Message>,
    onReply: (Message) -> Unit,
    onEdit: (Message) -> Unit,
    onDelete: (Message) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

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
                    Text(
                        message.content,
                        color = if (message.outgoing) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                    )
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

@Composable
fun EmptyThreadState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Send,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "No messages yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Send your first message",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun ConversationsScreen(navController: NavHostController, db: AppDatabase) {
    var query by remember { mutableStateOf("") }
    var showNewMessageDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val messages by if (query.isNotBlank()) {
        db.messageDao().searchMessagesFlow(query).collectAsState(initial = emptyList())
    } else {
        db.messageDao().getAllMessagesFlow().collectAsState(initial = emptyList())
    }

    // Load contact names for thread IDs
    val contactNames = remember { mutableStateMapOf<String, String>() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(messages.map { it.threadId }.distinct().joinToString()) {
        val uniqueThreadIds = messages.map { it.threadId }.distinct()
        uniqueThreadIds.forEach { threadId ->
            if (!contactNames.containsKey(threadId)) {
                scope.launch {
                    try {
                        val name = getContactName(context, threadId)
                        contactNames[threadId] = name
                    } catch (e: Exception) {
                        contactNames[threadId] = threadId
                    }
                }
            }
        }
    }

    // Create thread summaries from messages
    val threadSummaries = messages
        .groupBy { it.threadId }
        .map { (threadId, msgs) ->
            val lastMsg = msgs.maxByOrNull { it.timestamp }
            ThreadSummary(
                threadId = threadId,
                contactName = contactNames[threadId] ?: threadId,
                snippet = lastMsg?.content ?: "",
                lastAtEpoch = lastMsg?.timestamp ?: 0L,
                unread = msgs.count { !it.outgoing && it.status != "read" }
            )
        }
        .sortedByDescending { it.lastAtEpoch }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Qwisha", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { navController.navigate("settings") }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNewMessageDialog = true },
                icon = { Icon(Icons.Default.Create, contentDescription = null) },
                text = { Text("New Message") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Search bar - always visible
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search messages") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
            )

            if (threadSummaries.isEmpty()) {
                EmptyState(modifier = Modifier.fillMaxSize())
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(threadSummaries) { thread ->
                        ConversationRow(thread) { navController.navigate("chat/${thread.threadId}") }
                        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    }
                }
            }
        }

        // New Message Dialog
        if (showNewMessageDialog) {
            NewMessageDialog(
                onDismiss = { showNewMessageDialog = false },
                onConfirm = { phoneNumber ->
                    showNewMessageDialog = false
                    navController.navigate("chat/$phoneNumber")
                }
            )
        }
    }
}

data class Contact(
    val name: String,
    val phoneNumber: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewMessageDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var phoneNumber by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var showContacts by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Load contacts
    LaunchedEffect(Unit) {
        contacts = loadContacts(context)
    }

    val filteredContacts = if (searchQuery.isBlank()) {
        contacts
    } else {
        contacts.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                    it.phoneNumber.contains(searchQuery, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Message") },
        text = {
            Column(modifier = Modifier.heightIn(max = 400.dp)) {
                OutlinedTextField(
                    value = phoneNumber,
                    onValueChange = {
                        phoneNumber = it
                        showError = false
                        // Auto-show contacts when typing
                        if (it.isNotBlank()) {
                            showContacts = true
                        }
                    },
                    label = { Text("Phone Number") },
                    placeholder = { Text("+255 123 456 789") },
                    singleLine = true,
                    isError = showError,
                    supportingText = if (showError) {
                        { Text("Please enter a valid phone number") }
                    } else null,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done,
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (phoneNumber.isNotBlank()) {
                                onConfirm(phoneNumber.trim())
                            } else {
                                showError = true
                            }
                        }
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { showContacts = !showContacts }) {
                            Icon(
                                if (showContacts) Icons.Default.KeyboardArrowUp else Icons.Default.AccountBox,
                                contentDescription = "Toggle contacts"
                            )
                        }
                    }
                )

                if (showContacts && contacts.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Contacts",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                    )
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search contacts...") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 200.dp)
                    ) {
                        items(filteredContacts) { contact ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        phoneNumber = contact.phoneNumber
                                        showContacts = false
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AvatarPlaceholder(name = contact.name, modifier = Modifier.size(40.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        contact.name,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        contact.phoneNumber,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }
                            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (phoneNumber.isNotBlank()) {
                        onConfirm(phoneNumber.trim())
                    } else {
                        showError = true
                    }
                }
            ) {
                Text("Start Chat")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun SmsOverlayTheme(content: @Composable () -> Unit) {
    val colorScheme = lightColorScheme(
        primary = Color(0xFF00695C),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFB2DFDB),
        secondary = Color(0xFF26A69A),
        surface = Color(0xFFFAFAFA),
        surfaceVariant = Color(0xFFE0E0E0),
        background = Color(0xFFFFFFFF),
        onSurface = Color(0xFF212121),
        onSurfaceVariant = Color(0xFF424242),
        outline = Color(0xFFBDBDBD)
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}

@Composable
fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.MailOutline,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "No conversations yet",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Start a conversation — works offline via SMS",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun ConversationRow(thread: ThreadSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp)
    ) {
        AvatarPlaceholder(name = thread.contactName)

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    thread.contactName,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatTime(thread.lastAtEpoch),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                thread.snippet,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }

        if (thread.unread > 0) {
            Spacer(modifier = Modifier.width(12.dp))
            Badge(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White,
                modifier = Modifier.align(Alignment.CenterVertically)
            ) {
                Text(
                    thread.unread.toString(),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
fun AvatarPlaceholder(name: String, modifier: Modifier = Modifier) {
    val initials = name
        .split(' ', '+', '-')
        .mapNotNull { it.firstOrNull()?.uppercaseChar()?.toString() }
        .take(2)
        .joinToString("")
        .ifEmpty { name.take(2).uppercase() }

    // Generate a background color based on name
    val colorSeed = (name.fold(0) { acc, c -> acc + c.code } % 360)
    val bg = Color.hsl(colorSeed.toFloat(), 0.5f, 0.5f)

    Box(
        modifier = modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            initials,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
    }
}

// Load contacts from device
suspend fun loadContacts(context: Context): List<Contact> = withContext(Dispatchers.IO) {
    val contacts = mutableListOf<Contact>()
    try {
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )
        cursor?.use {
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (it.moveToNext()) {
                val name = it.getString(nameIndex) ?: ""
                val number = it.getString(numberIndex) ?: ""
                if (name.isNotBlank() && number.isNotBlank()) {
                    // Clean phone number (remove spaces, dashes, etc.)
                    val cleanNumber = number.replace(Regex("[^+0-9]"), "")
                    contacts.add(Contact(name, cleanNumber))
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    contacts.distinctBy { it.phoneNumber }
}

// Get contact name from phone number
suspend fun getContactName(context: Context, phoneNumber: String): String = withContext(Dispatchers.IO) {
    try {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val selection = "${ContactsContract.CommonDataKinds.Phone.NUMBER} = ?"
        val selectionArgs = arrayOf(phoneNumber)

        context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val name = cursor.getString(nameIndex)
                if (!name.isNullOrBlank()) {
                    return@withContext name
                }
            }
        }

        // Try with cleaned number (without formatting)
        val cleanNumber = phoneNumber.replace(Regex("[^+0-9]"), "")
        context.contentResolver.query(uri, projection, selection, arrayOf(cleanNumber), null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val name = cursor.getString(nameIndex)
                if (!name.isNullOrBlank()) {
                    return@withContext name
                }
            }
        }

        // Try matching by number ending (for cases where country code differs)
        val numberSuffix = cleanNumber.takeLast(9) // Last 9 digits
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null,
            null,
            null
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (cursor.moveToNext()) {
                val number = cursor.getString(numberIndex) ?: ""
                val cleanStoredNumber = number.replace(Regex("[^+0-9]"), "")
                if (cleanStoredNumber.endsWith(numberSuffix) && numberSuffix.length >= 7) {
                    val name = cursor.getString(nameIndex)
                    if (!name.isNullOrBlank()) {
                        return@withContext name
                    }
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    phoneNumber // Return phone number if contact not found
}

@RequiresApi(Build.VERSION_CODES.O)
fun formatTime(timestamp: Long): String {
    val now = Instant.now()
    val msgTime = Instant.ofEpochMilli(timestamp)
    val zone = ZoneId.systemDefault()

    val nowDate = now.atZone(zone).toLocalDate()
    val msgDate = msgTime.atZone(zone).toLocalDate()

    return when {
        msgDate == nowDate -> {
            // Today: show time only
            DateTimeFormatter.ofPattern("HH:mm").format(msgTime.atZone(zone))
        }
        msgDate == nowDate.minusDays(1) -> {
            // Yesterday
            "Yesterday"
        }
        msgDate.year == nowDate.year -> {
            // This year: show month and day
            DateTimeFormatter.ofPattern("MMM d").format(msgTime.atZone(zone))
        }
        else -> {
            // Different year: show full date
            DateTimeFormatter.ofPattern("MMM d, yyyy").format(msgTime.atZone(zone))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun SettingsScreen(navController: NavHostController) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            item {
                Text(
                    "About",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            "Developed by",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Vicent Laizer",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://tanzaniaprogrammers.com"))
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "TanzaniaProgrammers.com",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "Visit our website",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                            Icon(
                                Icons.Default.ExitToApp,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    // Placeholder for "How it works" page
                                    // You can implement this later
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "How it works",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "Learn about Qwisha",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                            Icon(
                                Icons.Default.ExitToApp,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }
    }
}
