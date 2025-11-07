package com.tanzaniaprogrammers.qwisha

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import android.telephony.SmsMessage
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
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

class MainActivity : ComponentActivity() {

    private lateinit var db: AppDatabase
    private var smsReceiver: BroadcastReceiver? = null

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "sms_overlay_db").build()

        // Request permissions
        val permissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            // handle result if needed
        }
        permissionsLauncher.launch(arrayOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        ))

        // Register SMS receiver
        smsReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "android.provider.Telephony.SMS_RECEIVED") {
                    val bundle = intent.extras
                    val pdus = bundle?.get("pdus") as? Array<*>
                    pdus?.forEach { pdu ->
                        val msg = SmsMessage.createFromPdu(pdu as ByteArray)
                        val body = msg.messageBody
                        val sender = msg.originatingAddress ?: ""
                        handleIncomingSms(sender, body)
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsReceiver, IntentFilter("android.provider.Telephony.SMS_RECEIVED"), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(smsReceiver, IntentFilter("android.provider.Telephony.SMS_RECEIVED"))
        }

        setContent {
            SmsOverlayTheme {
                val navController = rememberNavController()
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    NavHost(navController = navController, startDestination = "threads") {
                        composable("threads") { ConversationsScreen(navController, db) }
                        composable("chat/{threadId}") { backStackEntry ->
                            val threadId = backStackEntry.arguments?.getString("threadId") ?: ""
                            ThreadScreen(threadId, db) { navController.popBackStack() }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        smsReceiver?.let { unregisterReceiver(it) }
    }

    private fun handleIncomingSms(sender: String, body: String) {
        lifecycleScope.launch {
            try {
                val json = JSONObject(body)
                val cmd = json.getString("cmd")
                val msgId = json.getString("id")
                val replyTo = json.optString("replyTo", null)
                val content = json.optString("content", null)

                withContext(Dispatchers.IO) {
                    when (cmd) {
                        "send" -> db.messageDao().insert(Message(msgId, sender, content ?: "", false, replyTo, "delivered", System.currentTimeMillis()))
                        "edit" -> db.messageDao().updateContent(msgId, content ?: "")
                        "delete" -> db.messageDao().deleteById(msgId)
                    }
                }
            } catch (_: Exception) {
                // ignore non-overlay messages
            }
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
    val timestamp: Long
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

    @Query("DELETE FROM Message WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("""
        SELECT * FROM Message 
        WHERE content LIKE '%' || :query || '%' OR threadId LIKE '%' || :query || '%'
        ORDER BY timestamp DESC
    """)
    fun searchMessagesFlow(query: String): kotlinx.coroutines.flow.Flow<List<Message>>
}

@Database(entities = [Message::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
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

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

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
        val msgId = editingMsgId ?: UUID.randomUUID().toString()
        val json = JSONObject().apply {
            put("id", msgId)
            put("cmd", if (editingMsgId != null) "edit" else "send")
            put("replyTo", replyToMsg?.id ?: JSONObject.NULL)
            put("content", inputText)
        }
        val smsBody = json.toString()

        // Send SMS
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    SmsManager.getDefault().sendTextMessage(threadId, null, smsBody, null, null)
                }

                val newMsg = Message(
                    msgId,
                    threadId,
                    inputText,
                    true,
                    replyToMsg?.id,
                    "sent",
                    System.currentTimeMillis()
                )

                db.messageDao().insert(newMsg)

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
                        Text(threadId, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("Tap to view contact", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
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
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(24.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { sendMessage() }),
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
                                val json = JSONObject().apply {
                                    put("id", it.id)
                                    put("cmd", "delete")
                                }
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            SmsManager.getDefault().sendTextMessage(threadId, null, json.toString(), null, null)
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
                    Text(
                        formatTime(message.timestamp),
                        fontSize = 10.sp,
                        color = if (message.outgoing) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }

            // Context menu
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Reply") },
                    onClick = {
                        onReply(message)
                        showMenu = false
                    },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) }
                )
                if (message.outgoing) {
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
                        onDelete(message)
                        showMenu = false
                    },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) }
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
    var showSearch by remember { mutableStateOf(false) }
    var showNewMessageDialog by remember { mutableStateOf(false) }

    val messages by if (query.isNotBlank()) {
        db.messageDao().searchMessagesFlow(query).collectAsState(initial = emptyList())
    } else {
        db.messageDao().getAllMessagesFlow().collectAsState(initial = emptyList())
    }

    // Create thread summaries from messages
    val threadSummaries = messages
        .groupBy { it.threadId }
        .map { (threadId, msgs) ->
            val lastMsg = msgs.maxByOrNull { it.timestamp }
            ThreadSummary(
                threadId = threadId,
                contactName = threadId,
                snippet = lastMsg?.content ?: "",
                lastAtEpoch = lastMsg?.timestamp ?: 0L,
                unread = msgs.count { !it.outgoing && it.status != "read" }
            )
        }
        .sortedByDescending { it.lastAtEpoch }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Messages", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
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
            // Search bar
            AnimatedVisibility(
                visible = showSearch,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search messages or contacts") },
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
                        .padding(16.dp),
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
                )
            }

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewMessageDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var phoneNumber by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Message") },
        text = {
            Column {
                Text(
                    "Enter a phone number to start a conversation",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = phoneNumber,
                    onValueChange = {
                        phoneNumber = it
                        showError = false
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
                    modifier = Modifier.fillMaxWidth()
                )
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