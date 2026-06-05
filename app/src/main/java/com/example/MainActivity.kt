package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

// =========================================================================
// 1. DATA ENTRIES & MODEL CONVENTIONS
// =========================================================================

enum class SatoriTaskStatus { PENDING, UPCOMING, COMPLETED }

data class SatoriTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val time: String,
    val description: String = "",
    val isPm: Boolean,
    val status: SatoriTaskStatus,
    val colorBarHex: Long
)

enum class VoiceSessionState { IDLE, CONNECTING, LISTENING }

// =========================================================================
// 2. STATE MANAGER (VIEWMODEL)
// =========================================================================

class SatoriViewModel : ViewModel() {
    private val _currentTab = MutableStateFlow(0) 
    val currentTab = _currentTab.asStateFlow()

    private val _isNewTaskSheetOpen = MutableStateFlow(false)
    val isNewTaskSheetOpen = _isNewTaskSheetOpen.asStateFlow()

    private val _tasks = MutableStateFlow<List<SatoriTask>>(emptyList())
    val tasks = _tasks.asStateFlow()

    private val _voiceSessionState = MutableStateFlow(VoiceSessionState.IDLE)
    val voiceSessionState = _voiceSessionState.asStateFlow()

    private val _liveTranscript = MutableStateFlow("")
    val liveTranscript = _liveTranscript.asStateFlow()

    private val _userName = MutableStateFlow("")
    val userName = _userName.asStateFlow()

    fun loadUserName(context: android.content.Context) {
        val prefs = context.getSharedPreferences("ChronosPrefs", android.content.Context.MODE_PRIVATE)
        _userName.value = prefs.getString("user_name", "") ?: ""
    }

    fun saveOnboarding(context: android.content.Context, name: String, role: String, goal: String) {
        val prefs = context.getSharedPreferences("ChronosPrefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("user_name", name)
            putString("user_role", role)
            putString("user_goal", goal)
        }.apply()
        _userName.value = name
        suggestInitialTasks(role, goal)
    }

    private fun suggestInitialTasks(role: String, goal: String) {
        val suggested = when(role) {
            "Student" -> listOf(
                Pair("Morning Lecture Prep", "08:30"),
                Pair("Study Session", "15:00"),
                Pair("Evening Review", "20:00")
            )
            "Software Engineer" -> listOf(
                Pair("Code Review", "09:30"),
                Pair("Deep Work Block", "14:00"),
                Pair("Deploy Sync", "17:00")
            )
            else -> listOf(
                Pair("Strategy Planning", "10:00"),
                Pair("Network Session", "16:00")
            )
        }
        suggested.forEach { (title, time) ->
            addTask(title, time, false, "Suggested based on your $role role.")
        }
    }

    fun saveUserName(context: android.content.Context, name: String) {
        val prefs = context.getSharedPreferences("ChronosPrefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("user_name", name).apply()
        _userName.value = name
    }

    fun setTab(index: Int) {
        _currentTab.value = index
    }

    fun setNewTaskSheetOpen(open: Boolean) {
        _isNewTaskSheetOpen.value = open
    }

    fun addTask(title: String, time: String, isPm: Boolean, description: String = "") {
        val taskId = UUID.randomUUID().toString()
        val newTask = SatoriTask(
            id = taskId,
            title = title,
            time = time,
            isPm = isPm,
            description = description,
            status = SatoriTaskStatus.PENDING,
            colorBarHex = 0xFF4285F4
        )
        _tasks.value = _tasks.value + newTask

        viewModelScope.launch {
            try {
                val calendar = java.util.Calendar.getInstance()
                val timeParts = time.split(":")
                var hour = timeParts[0].toInt()
                val min = timeParts[1].toInt()
                if (isPm && hour < 12) hour += 12
                if (!isPm && hour == 12) hour = 0
                calendar.set(java.util.Calendar.HOUR_OF_DAY, hour)
                calendar.set(java.util.Calendar.MINUTE, min)
                calendar.set(java.util.Calendar.SECOND, 0)
                val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                format.timeZone = java.util.TimeZone.getTimeZone("UTC")
                val isoTime = format.format(calendar.time)
                
                com.example.android_integration.SupabaseManager.insertTask(title, isoTime)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun deleteTask(taskId: String) {
        _tasks.value = _tasks.value.filterNot { it.id == taskId }
        viewModelScope.launch {
            try {
                com.example.android_integration.SupabaseManager.deleteTask(taskId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun toggleTaskCompletion(taskId: String) {
        var newStatusStr = ""
        _tasks.value = _tasks.value.map {
            if (it.id == taskId) {
                val nextStatus = when (it.status) {
                    SatoriTaskStatus.PENDING, SatoriTaskStatus.UPCOMING -> SatoriTaskStatus.COMPLETED
                    SatoriTaskStatus.COMPLETED -> SatoriTaskStatus.PENDING
                }
                newStatusStr = if (nextStatus == SatoriTaskStatus.COMPLETED) "completed" else "pending"
                it.copy(status = nextStatus)
            } else it
        }
        if (newStatusStr.isNotEmpty()) {
            viewModelScope.launch {
                try {
                    com.example.android_integration.SupabaseManager.updateTaskStatus(taskId, newStatusStr)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    init {
        fetchTasksFromSupabase()
    }

    fun fetchTasksFromSupabase() {
        viewModelScope.launch {
            try {
                val dbTasks = com.example.android_integration.SupabaseManager.fetchDailyTasks()
                _tasks.value = dbTasks.map { 
                    SatoriTask(
                        id = it.id ?: UUID.randomUUID().toString(),
                        title = it.task_description,
                        time = it.scheduled_time.split("T").last().take(5),
                        isPm = false,
                        status = if (it.status == "pending") SatoriTaskStatus.PENDING else SatoriTaskStatus.COMPLETED,
                        colorBarHex = 0xFF4285F4
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun startVoiceSession(context: android.content.Context) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            if (context is androidx.activity.ComponentActivity) {
                androidx.core.app.ActivityCompat.requestPermissions(
                    context,
                    arrayOf(android.Manifest.permission.RECORD_AUDIO),
                    101
                )
            }
            return
        }

        _voiceSessionState.value = VoiceSessionState.CONNECTING
        _currentTab.value = 1
        viewModelScope.launch {
            try {
                val userId = com.example.android_integration.SupabaseManager.getActiveUserId() ?: "00000000-0000-0000-0000-000000000000"
                val token = com.example.android_integration.ApiClient().fetchLiveKitToken(userId)
                
                val intent = android.content.Intent(context, com.example.android_integration.VoiceReceiverService::class.java).apply {
                    action = com.example.android_integration.VoiceReceiverService.ACTION_START_CALL
                    putExtra(com.example.android_integration.VoiceReceiverService.EXTRA_JWT_TOKEN, token)
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                _voiceSessionState.value = VoiceSessionState.LISTENING
            } catch (e: Exception) {
                e.printStackTrace()
                _voiceSessionState.value = VoiceSessionState.IDLE
            }
        }
    }

    fun endVoiceSession(context: android.content.Context) {
        _voiceSessionState.value = VoiceSessionState.IDLE
        _liveTranscript.value = ""
        val intent = android.content.Intent(context, com.example.android_integration.VoiceReceiverService::class.java).apply {
            action = com.example.android_integration.VoiceReceiverService.ACTION_STOP_CALL
        }
        context.startService(intent)
    }

    fun sendChatMessage(context: android.content.Context, msg: String) {
        val intent = android.content.Intent(context, com.example.android_integration.VoiceReceiverService::class.java).apply {
            action = com.example.android_integration.VoiceReceiverService.ACTION_SEND_CHAT
            putExtra(com.example.android_integration.VoiceReceiverService.EXTRA_CHAT_MESSAGE, msg)
        }
        context.startService(intent)
    }

    fun updateTranscript(text: String) {
        _liveTranscript.value = text
    }
}

// =========================================================================
// 3. THEME & COLORS (STITCH AESTHETIC)
// =========================================================================

private val ChronosAIProfessionalColorScheme = lightColorScheme(
    primary = Color(0xFF4285F4),         
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD2E3FC),
    onPrimaryContainer = Color(0xFF174EA6),
    secondary = Color(0xFF34A853),       
    onSecondary = Color.White,
    tertiary = Color(0xFFFABB05),        
    onTertiary = Color.White,
    background = Color(0xFFFFFFFF),      
    surface = Color(0xFFF8F9FA),         
    surfaceVariant = Color(0xFFE8EAED),  
    onBackground = Color(0xFF202124),    
    onSurface = Color(0xFF202124),
    onSurfaceVariant = Color(0xFF5F6368), 
    outline = Color(0xFFDADCE0),         
    outlineVariant = Color(0xFFF1F3F4)
)

@Composable
fun ChronosAITheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ChronosAIProfessionalColorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}

// =========================================================================
// 4. MAIN NATIVE LAYER HOOK
// =========================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ChronosAITheme {
                MainAppContainer()
            }
        }
    }
}

@Composable
fun MainAppContainer() {
    val model: SatoriViewModel = viewModel()
    val currentTab by model.currentTab.collectAsState()
    val isNewTaskOpen by model.isNewTaskSheetOpen.collectAsState()
    val voiceState by model.voiceSessionState.collectAsState()
    val userName by model.userName.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        model.loadUserName(context)
    }

    if (userName.isEmpty()) {
        OnboardingScreen(onComplete = { name, role, goal -> model.saveOnboarding(context, name, role, goal) })
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            ChronosBottomBar(
                selectedIndex = currentTab,
                onTabSelected = { index -> model.setTab(index) }
            )
        },
        floatingActionButton = {
            if (currentTab == 0) {
                FloatingActionButton(
                    onClick = { model.setNewTaskSheetOpen(true) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            AnimatedContent(
                targetState = currentTab,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "nav"
            ) { targetTab ->
                when (targetTab) {
                    0 -> ScheduleScreen(model, context)
                    1 -> AIAssistantScreen(model, context)
                    2 -> FocusTimerScreen()
                    3 -> ProfileScreen(userName)
                }
            }

            if (isNewTaskOpen) {
                NewTaskDialog(
                    onDismiss = { model.setNewTaskSheetOpen(false) },
                    onConfirm = { name, time, isPm, desc ->
                        model.addTask(name, time, isPm, desc)
                        model.setNewTaskSheetOpen(false)
                    }
                )
            }
        }
    }
}

@Composable
fun ChronosBottomBar(selectedIndex: Int, onTabSelected: (Int) -> Unit) {
    NavigationBar(containerColor = Color.White, tonalElevation = 8.dp) {
        val items = listOf(
            Triple("Schedule", Icons.Default.DateRange, 0),
            Triple("Assistant", Icons.Default.Face, 1),
            Triple("Focus", Icons.Default.PlayArrow, 2),
            Triple("Profile", Icons.Default.Person, 3)
        )
        items.forEach { (label, icon, index) ->
            NavigationBarItem(
                selected = selectedIndex == index,
                onClick = { onTabSelected(index) },
                icon = { Icon(icon, contentDescription = label) },
                label = { Text(label) }
            )
        }
    }
}

// =========================================================================
// SCREEN 1: SCHEDULE
// =========================================================================

@Composable
fun ScheduleScreen(model: SatoriViewModel, context: android.content.Context) {
    val tasks by model.tasks.collectAsState()
    val userName by model.userName.collectAsState()
    val activeCount = tasks.count { it.status != SatoriTaskStatus.COMPLETED }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 24.dp)
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        Text("Good morning, $userName", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("You have $activeCount tasks scheduled for today.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Card(
            onClick = { model.startVoiceSession(context) },
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth().height(80.dp)
        ) {
            Row(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Call, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text("Talk to Chronos", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("Real-time AI voice assistant", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        Text("YOUR TIMELINE", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black, letterSpacing = 2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        Spacer(modifier = Modifier.height(16.dp))

        if (tasks.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No tasks scheduled yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                items(tasks, key = { it.id }) { task ->
                    TaskRowItem(task, onCheckedChange = { model.toggleTaskCompletion(task.id) }, onDelete = { model.deleteTask(task.id) })
                }
            }
        }
    }
}

@Composable
fun TaskRowItem(task: SatoriTask, onCheckedChange: () -> Unit, onDelete: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = task.status == SatoriTaskStatus.COMPLETED, onCheckedChange = { onCheckedChange() })
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    fontWeight = FontWeight.Bold,
                    textDecoration = if (task.status == SatoriTaskStatus.COMPLETED) TextDecoration.LineThrough else null,
                    color = if (task.status == SatoriTaskStatus.COMPLETED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
                Text("${task.time} ${if (task.isPm) "PM" else "AM"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFB00020), modifier = Modifier.size(20.dp))
            }
        }
    }
}

// =========================================================================
// SCREEN 2: ASSISTANT
// =========================================================================

@Composable
fun AIAssistantScreen(model: SatoriViewModel, context: android.content.Context) {
    val voiceState by model.voiceSessionState.collectAsState()
    val transcript by model.liveTranscript.collectAsState()
    var chatMessage by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (voiceState) {
            VoiceSessionState.IDLE, VoiceSessionState.CONNECTING -> {
                Column(modifier = Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box(modifier = Modifier.size(120.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        if (voiceState == VoiceSessionState.CONNECTING) {
                            CircularProgressIndicator(modifier = Modifier.size(80.dp))
                        } else {
                            Icon(Icons.Default.Face, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(40.dp))
                    Text(if (voiceState == VoiceSessionState.CONNECTING) "Syncing..." else "Chronos AI", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("I am ready to help you optimize your day. Establish a link to begin.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(48.dp))
                    Button(onClick = { model.startVoiceSession(context) }, shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth().height(60.dp)) {
                        Text("ESTABLISH NEURAL LINK", fontWeight = FontWeight.Bold)
                    }
                }
            }
            VoiceSessionState.LISTENING -> {
                Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                    Spacer(modifier = Modifier.height(40.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                         Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(Color(0xFF34A853)))
                         Spacer(modifier = Modifier.width(12.dp))
                         Text("LIVE CONNECTION ACTIVE", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color(0xFF34A853))
                    }
                    Spacer(modifier = Modifier.height(40.dp))
                    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth().weight(1f)) {
                        Column(modifier = Modifier.padding(24.dp)) {
                            Text("TRANSCRIPTION", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(16.dp))
                            AnimatedAudioWaveform()
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(transcript.ifEmpty { "Listening for voice..." }, style = MaterialTheme.typography.bodyLarge, fontSize = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = chatMessage, onValueChange = { chatMessage = it }, placeholder = { Text("Type a command...") }, shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f))
                        Spacer(modifier = Modifier.width(12.dp))
                        FloatingActionButton(onClick = { if (chatMessage.isNotBlank()) { model.sendChatMessage(context, chatMessage); chatMessage = "" } }, containerColor = MaterialTheme.colorScheme.primary, shape = CircleShape) {
                            Icon(Icons.Default.Send, contentDescription = null, tint = Color.White)
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = { model.endVoiceSession(context) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB00020)), shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth().height(60.dp)) {
                        Text("DISCONNECT", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// =========================================================================
// SCREEN 3: FOCUS
// =========================================================================

@Composable
fun FocusTimerScreen() {
    var timerSeconds by remember { mutableStateOf(1500) } 
    var isRunning by remember { mutableStateOf(false) }

    LaunchedEffect(isRunning) {
        while (isRunning && timerSeconds > 0) {
            delay(1000)
            timerSeconds--
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("DEEP FOCUS", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, letterSpacing = 2.sp)
        Spacer(modifier = Modifier.height(24.dp))
        Box(modifier = Modifier.size(240.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = { timerSeconds / 1500f }, strokeWidth = 10.dp, modifier = Modifier.fillMaxSize())
            Text(String.format("%02d:%02d", timerSeconds / 60, timerSeconds % 60), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Black)
        }
        Spacer(modifier = Modifier.height(40.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { isRunning = !isRunning }, shape = RoundedCornerShape(12.dp)) {
                Text(if (isRunning) "PAUSE" else "START")
            }
            OutlinedButton(onClick = { isRunning = false; timerSeconds = 1500 }, shape = RoundedCornerShape(12.dp)) {
                Text("RESET")
            }
        }
    }
}

// =========================================================================
// SCREEN 4: PROFILE
// =========================================================================

@Composable
fun ProfileScreen(userName: String) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(modifier = Modifier.height(40.dp))
        Box(modifier = Modifier.size(100.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text(if (userName.isNotEmpty()) userName.take(1).uppercase() else "C", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(userName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("ChronosAI Power User", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(40.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(24.dp))
        ProfileSettingRow("Daily Reminders", true)
        ProfileSettingRow("Voice Synthesis", true)
        ProfileSettingRow("Advanced Scheduling", false)
        Spacer(modifier = Modifier.weight(1f))
        Text("ChronosAI v1.0.0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun ProfileSettingRow(label: String, initialValue: Boolean) {
    var checked by remember { mutableStateOf(initialValue) }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = { checked = it })
    }
}

// =========================================================================
// DIALOGS
// =========================================================================

@Composable
fun NewTaskDialog(onDismiss: () -> Unit, onConfirm: (String, String, Boolean, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("") }
    var isPm by remember { mutableStateOf(true) }
    var desc by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Schedule Task", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Task Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = time, onValueChange = { time = it }, label = { Text("Time (HH:MM)") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Period: ")
                    TextButton(onClick = { isPm = false }) { Text("AM", color = if (!isPm) MaterialTheme.colorScheme.primary else Color.Gray) }
                    TextButton(onClick = { isPm = true }) { Text("PM", color = if (isPm) MaterialTheme.colorScheme.primary else Color.Gray) }
                }
                OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = { onConfirm(name, time, isPm, desc) }) { Text("Schedule") }
                }
            }
        }
    }
}


@Composable
fun OnboardingScreen(onComplete: (String, String, String) -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf("") }

    val roles = listOf("Student", "Software Engineer", "Designer", "Cybersecurity Analyst")
    val goals = listOf("Deep Focus", "Perfect Scheduling", "Voice Control", "Productivity Boost")

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AnimatedContent(targetState = step, label = "onboarding_steps") { currentStep ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when (currentStep) {
                    0 -> {
                        Icon(Icons.Default.Face, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(80.dp))
                        Spacer(modifier = Modifier.height(32.dp))
                        Text("Welcome to ChronosAI", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("What should I call you?", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(32.dp))
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            placeholder = { Text("Your Name") },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    1 -> {
                        Text("Professional Identity", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Select your primary role:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(24.dp))
                        roles.forEach { r ->
                            Button(
                                onClick = { role = r; step = 2 },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = if (role == r) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                                contentColor = if (role == r) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                            ) {
                                Text(r)
                            }
                        }
                    }
                    2 -> {
                        Text("Core Objective", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("What do you want to achieve?", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(24.dp))
                        goals.forEach { g ->
                            Button(
                                onClick = { goal = g },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = if (goal == g) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                                contentColor = if (goal == g) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                            ) {
                                Text(g)
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        if (step == 0) {
            Button(
                onClick = { if (name.isNotBlank()) step = 1 },
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth().height(60.dp)
            ) {
                Text("NEXT", fontWeight = FontWeight.Bold)
            }
        } else if (step == 2 && goal.isNotBlank()) {
            Button(
                onClick = { onComplete(name.trim(), role, goal) },
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth().height(60.dp)
            ) {
                Text("GET STARTED", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun AnimatedAudioWaveform() {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    Row(
        modifier = Modifier.fillMaxWidth().height(100.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0..15) {
            val animHeight by infiniteTransition.animateFloat(
                initialValue = 10f,
                targetValue = 80f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 400 + i * 50, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bar_$i"
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .width(4.dp)
                    .height(animHeight.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}
