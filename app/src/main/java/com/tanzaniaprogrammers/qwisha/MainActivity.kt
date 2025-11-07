package com.tanzaniaprogrammers.qwisha

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SmsOverlayTheme {
                val navController = rememberNavController()

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    NavHost(navController = navController, startDestination = "threads") {
                        composable("threads") { ConversationsScreen(navController) }
                        composable("chat/{threadId}") { backStackEntry ->
                            val threadId = backStackEntry.arguments?.getString("threadId") ?: ""
                            ThreadScreen(title = threadId, onBack = { navController.popBackStack() }, messages = sampleChatMessages())
                        }
                    }
                }
            }
        }
    }
}

// ------------------ Models ------------------
data class ThreadSummary(val threadId: String, val contactName: String, val snippet: String, val lastAtEpoch: Long, val unread: Int = 0)
data class ChatMsg(val id: String, val text: String, val outgoing: Boolean)

// ------------------ UI ------------------
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun ConversationsScreen(navController: NavHostController) {
    var query by remember { mutableStateOf("") }
    val threads = remember { sampleThreads() }
    val filtered = remember(query, threads) { if (query.isBlank()) threads else threads.filter { it.contactName.contains(query, true) || it.snippet.contains(query, true) } }

    Scaffold(
        topBar = { ConversationsTopBar(query) { query = it } },
        floatingActionButton = { FloatingActionButton(onClick = { }) { Icon(imageVector = Icons.Default.Create, contentDescription = "New") } }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (filtered.isEmpty()) EmptyState(modifier = Modifier.fillMaxSize())
            else LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filtered.size) { i ->
                    ConversationRow(thread = filtered[i]) { navController.navigate("chat/${filtered[i].threadId}") }
                    Divider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationsTopBar(query: String, onQueryChange: (String) -> Unit) {
    TopAppBar(
        title = { Text("Messages", fontWeight = FontWeight.SemiBold) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        actions = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = query, onValueChange = onQueryChange, placeholder = { Text("Search messages or contacts") }, singleLine = true, leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = "Search") }, modifier = Modifier.height(48.dp).widthIn(min = 200.dp, max = 600.dp), shape = RoundedCornerShape(24.dp))
            }
        }
    )
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
private fun ConversationRow(thread: ThreadSummary, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(12.dp)) {
        AvatarPlaceholder(name = thread.contactName)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(thread.contactName, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(modifier = Modifier.weight(1f))
                Text(formatEpoch(thread.lastAtEpoch), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(thread.snippet, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (thread.unread > 0) {
            Spacer(modifier = Modifier.width(8.dp))
            BadgedBox(badge = { Text(thread.unread.toString()) }) { }
        }
    }
}

@Composable
private fun AvatarPlaceholder(name: String, modifier: Modifier = Modifier) {
    val initials = name.split(' ').mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString()
    val colorSeed = (initials.fold(0) { acc, c -> acc + c.code } % 360)
    val bg = Color.hsl(colorSeed.toFloat(), 0.6f, 0.5f)
    Box(modifier = modifier.size(52.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(initials, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(imageVector = Icons.Default.Create, contentDescription = null, modifier = Modifier.size(64.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text("No messages yet", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(6.dp))
            Text("Start a conversation — works offline via SMS", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(title: String, onBack: () -> Unit, messages: List<ChatMsg>) {
    var inputText by remember { mutableStateOf("") }
    Scaffold(
        topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } }) },
        bottomBar = {
            Row(Modifier.padding(8.dp)) {
                OutlinedTextField(value = inputText, onValueChange = { inputText = it }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send))
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { /* TODO: send message */ }) { Icon(Icons.Default.Create, contentDescription = "Send") }
            }
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), reverseLayout = true) {
            items(messages) { m ->
                Box(modifier = Modifier.fillMaxWidth().padding(6.dp), contentAlignment = if (m.outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
                    Surface(shape = MaterialTheme.shapes.medium, color = if (m.outgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant) {
                        Text(m.text, Modifier.padding(12.dp))
                    }
                }
            }
        }
    }
}

// ------------------ Helpers ------------------
@RequiresApi(Build.VERSION_CODES.O)
private fun formatEpoch(epochMillis: Long): String {
    val instant = Instant.ofEpochMilli(epochMillis)
    val fmt = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault())
    return fmt.format(instant)
}

private fun sampleThreads(): List<ThreadSummary> {
    val names = listOf("Amina", "James", "School Admin", "Moses", "Nia", "Support")
    return List(12) { i ->
        val name = names[i % names.size]
        val snippet = listOf("See you tomorrow", "Thanks!", "Exam results released", "Ok", "Call me", "Got it")[i % 6]
        ThreadSummary(i.toString(36).uppercase(), name, snippet, System.currentTimeMillis() - i * 60_000L, if (i % 4 == 0) Random.nextInt(1,5) else 0)
    }
}

private fun sampleChatMessages(): List<ChatMsg> = List(20) { i -> ChatMsg(i.toString(), "Sample message $i", outgoing = i % 2 == 0) }

// ------------------ Theme ------------------
@Composable
fun SmsOverlayTheme(content: @Composable () -> Unit) {
    val colorScheme = lightColorScheme(primary = Color(0xFF00695C), onPrimary = Color.White, surface = Color(0xFFF1F3F4))
    MaterialTheme(colorScheme = colorScheme, typography = Typography(), content = content)
}