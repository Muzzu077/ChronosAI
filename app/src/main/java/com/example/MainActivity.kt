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
import androidx.compose.ui.text.font.Font
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
import com.example.android_integration.ApiClient
import com.example.android_integration.DailyTaskDto
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
    private val apiClient = ApiClient()

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

    private val _userId = MutableStateFlow("")

    fun loadUserName(context: android.content.Context) {
        val prefs = context.getSharedPreferences("ChronosPrefs", android.content.Context.MODE_PRIVATE)
        _userName.value = prefs.getString("user_name", "") ?: ""
        _userId.value = getOrCreateUserId(context)
        fetchTasksFromBackend()
    }

    fun saveOnboarding(context: android.content.Context, name: String, role: String, goal: String) {
        val prefs = context.getSharedPreferences("ChronosPrefs", android.content.Context.MODE_PRIVATE)
        _userId.value = getOrCreateUserId(context)
        prefs.edit().apply {
            putString("user_name", name)
            putString("user_role", role)
            putString("user_goal", goal)
        }.apply()
        _userName.value = name
        suggestInitialTasks(role, goal)
    }

    private fun getOrCreateUserId(context: android.content.Context): String {
        val prefs = context.getSharedPreferences("ChronosPrefs", android.content.Context.MODE_PRIVATE)
        prefs.getString("chronos_user_id", null)?.let { return it }
        val newUserId = UUID.randomUUID().toString()
        prefs.edit().putString("chronos_user_id", newUserId).apply()
        return newUserId
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
                val userId = _userId.value.ifBlank { return@launch }
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
                
                val savedTask = apiClient.createTask(userId, title, isoTime)
                _tasks.value = _tasks.value.map {
                    if (it.id == taskId) savedTask.toSatoriTask(description) else it
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun deleteTask(taskId: String) {
        _tasks.value = _tasks.value.filterNot { it.id == taskId }
        viewModelScope.launch {
            try {
                val userId = _userId.value.ifBlank { return@launch }
                apiClient.deleteTask(userId, taskId)
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
                    val userId = _userId.value.ifBlank { return@launch }
                    apiClient.updateTaskStatus(userId, taskId, newStatusStr)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun fetchTasksFromBackend() {
        viewModelScope.launch {
            try {
                val userId = _userId.value.ifBlank { return@launch }
                _tasks.value = apiClient.fetchDailyTasks(userId).map { it.toSatoriTask() }
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
                val userId = _userId.value.ifBlank { getOrCreateUserId(context).also { _userId.value = it } }
                val session = apiClient.fetchLiveKitSession(userId)
                
                val intent = android.content.Intent(context, com.example.android_integration.VoiceReceiverService::class.java).apply {
                    action = com.example.android_integration.VoiceReceiverService.ACTION_START_CALL
                    putExtra(com.example.android_integration.VoiceReceiverService.EXTRA_JWT_TOKEN, session.token)
                    putExtra(com.example.android_integration.VoiceReceiverService.EXTRA_SERVER_URL, session.serverUrl)
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

    private fun DailyTaskDto.toSatoriTask(description: String = ""): SatoriTask {
        val timePart = scheduledTime.substringAfter("T", "00:00").take(5)
        val hour = timePart.substringBefore(":").toIntOrNull() ?: 0
        val minute = timePart.substringAfter(":", "00").take(2)
        val isPm = hour >= 12
        val hour12 = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return SatoriTask(
            id = id,
            title = taskDescription,
            time = "%02d:%s".format(hour12, minute),
            description = description,
            isPm = isPm,
            status = if (status == "completed") SatoriTaskStatus.COMPLETED else SatoriTaskStatus.PENDING,
            colorBarHex = 0xFF4285F4
        )
    }
}

// =========================================================================
// 3. THEME & COLORS
// =========================================================================

private val ChronosCream = Color(0xFFFBF6EE)
private val ChronosPanel = Color(0xFFFFFCF7)
private val ChronosPanelAlt = Color(0xFFF5EEE5)
private val ChronosInk = Color(0xFF3F342F)
private val ChronosMuted = Color(0xFF756C63)
private val ChronosRust = Color(0xFFC9672A)
private val ChronosRustDark = Color(0xFF9D3E3E)
private val ChronosLine = Color(0xFFE2D8CD)
private val ChronosSerif = FontFamily(Font(R.font.dejavu_serif))
private val ChronosSans = FontFamily(Font(R.font.dejavu_sans))

private val ChronosAIProfessionalColorScheme = lightColorScheme(
    primary = ChronosRust,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF7E5D5),
    onPrimaryContainer = ChronosRust,
    secondary = ChronosRustDark,
    onSecondary = Color.White,
    background = ChronosCream,
    surface = ChronosPanel,
    surfaceVariant = ChronosPanelAlt,
    onBackground = ChronosInk,
    onSurface = ChronosInk,
    onSurfaceVariant = ChronosMuted,
    outline = ChronosLine,
    outlineVariant = Color(0xFFEFE6DC),
    error = Color(0xFFD43A2F)
)

private val DefaultChronosTypography = Typography()
private val ChronosTypography = DefaultChronosTypography.copy(
    displayLarge = DefaultChronosTypography.displayLarge.copy(fontFamily = ChronosSerif, color = ChronosInk),
    displayMedium = DefaultChronosTypography.displayMedium.copy(fontFamily = ChronosSerif, color = ChronosInk),
    headlineLarge = DefaultChronosTypography.headlineLarge.copy(fontFamily = ChronosSerif, color = ChronosInk),
    headlineMedium = DefaultChronosTypography.headlineMedium.copy(fontFamily = ChronosSerif, color = ChronosInk),
    headlineSmall = DefaultChronosTypography.headlineSmall.copy(fontFamily = ChronosSerif, color = ChronosInk),
    titleLarge = DefaultChronosTypography.titleLarge.copy(fontFamily = ChronosSerif, color = ChronosInk),
    titleMedium = DefaultChronosTypography.titleMedium.copy(fontFamily = ChronosSans, color = ChronosInk),
    titleSmall = DefaultChronosTypography.titleSmall.copy(fontFamily = ChronosSans, color = ChronosInk),
    bodyLarge = DefaultChronosTypography.bodyLarge.copy(fontFamily = ChronosSans, color = ChronosInk),
    bodyMedium = DefaultChronosTypography.bodyMedium.copy(fontFamily = ChronosSans, color = ChronosInk),
    bodySmall = DefaultChronosTypography.bodySmall.copy(fontFamily = ChronosSans, color = ChronosInk),
    labelLarge = DefaultChronosTypography.labelLarge.copy(fontFamily = ChronosSans),
    labelMedium = DefaultChronosTypography.labelMedium.copy(fontFamily = ChronosSans),
    labelSmall = DefaultChronosTypography.labelSmall.copy(fontFamily = ChronosSans)
)

@Composable
fun ChronosAITheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ChronosAIProfessionalColorScheme,
        typography = ChronosTypography,
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
	                    shape = CircleShape
	                ) {
	                    Icon(Icons.Default.Add, contentDescription = "Add task", modifier = Modifier.size(34.dp))
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
fun ChronosTopBar(userName: String, aiActive: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF171412), Color(0xFF7E3A2C), Color(0xFFE8C8AF))
                    )
                )
                .border(2.dp, Color(0xFFF0D7C5), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                userName.take(1).ifBlank { "C" }.uppercase(),
                color = Color.White,
                fontWeight = FontWeight.Black
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            "Satori AI",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        if (aiActive) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFFFFEFE5),
                border = BorderStroke(1.dp, Color(0xFFF1CFB6))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("AI ACTIVE", fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
        }
        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = ChronosMuted, modifier = Modifier.size(34.dp))
    }
}

@Composable
fun ScreenTitle(title: String, action: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (action != null) {
            Text(action, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
fun ChronosBottomBar(selectedIndex: Int, onTabSelected: (Int) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        val items = listOf(
            Triple("Schedule", Icons.Default.DateRange, 0),
            Triple("Assistant", Icons.Default.Call, 1),
            Triple("Focus", Icons.Default.PlayArrow, 2),
            Triple("Profile", Icons.Default.Person, 3)
        )
        items.forEach { (label, icon, index) ->
            NavigationBarItem(
	                selected = selectedIndex == index,
	                onClick = { onTabSelected(index) },
	                icon = { Icon(icon, contentDescription = label) },
	                label = { Text(label.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Black) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = Color(0xFFFFEEE2),
                        unselectedIconColor = ChronosMuted,
                        unselectedTextColor = ChronosMuted
                    )
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
    val completedCount = tasks.count { it.status == SatoriTaskStatus.COMPLETED }
    val goalProgress = if (tasks.isEmpty()) 0f else completedCount.toFloat() / tasks.size

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        ChronosTopBar(userName, aiActive = true)
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(top = 34.dp, bottom = 120.dp)
        ) {
            item {
                Text(
                    "Hello, ${userName.ifBlank { "Alex" }}",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Black
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "You have $activeCount tasks to focus on today.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text("DAILY GOAL", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                        Text("${(goalProgress * 100).toInt()}%", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Black)
                        LinearProgressIndicator(
                            progress = { goalProgress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(12.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color(0xFFE6DED3)
                        )
                    }
                }
            }
            item { ScreenTitle("Schedule", "View all") }
            if (tasks.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No tasks scheduled yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                items(tasks, key = { it.id }) { task ->
                    TaskRowItem(task, onCheckedChange = { model.toggleTaskCompletion(task.id) }, onDelete = { model.deleteTask(task.id) })
                }
            }
            item {
                Card(
                    onClick = { model.startVoiceSession(context) },
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF2E8)),
                    border = BorderStroke(1.dp, Color(0xFFF2D4BE)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(58.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Call, contentDescription = null, tint = Color.White)
                            }
                            Spacer(modifier = Modifier.width(18.dp))
                            Column {
                                Text("VOICE COMMAND", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                                Text("\"Add homework due tomorrow\"", fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, fontSize = 18.sp)
                            }
                        }
                        Spacer(modifier = Modifier.height(22.dp))
                        LinearProgressIndicator(
                            progress = { 0.32f },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(8.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color(0xFFE7DDD2)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TaskRowItem(task: SatoriTask, onCheckedChange: () -> Unit, onDelete: () -> Unit) {
    val completed = task.status == SatoriTaskStatus.COMPLETED
    val accent = when {
        completed -> Color(0xFFA9A39C)
        task.isPm -> Color(0xFFE98A45)
        else -> MaterialTheme.colorScheme.primary
    }
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = if (completed) Color(0xFFF2EEE8) else MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.fillMaxWidth().heightIn(min = 86.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.width(7.dp).fillMaxHeight().background(accent))
            Spacer(modifier = Modifier.width(18.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFFF5EEE6),
                border = BorderStroke(1.dp, Color(0xFFE7DDD2)),
                modifier = Modifier.size(width = 78.dp, height = 58.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(task.time, fontWeight = FontWeight.Black, color = ChronosMuted)
                    Text(if (task.isPm) "PM" else "AM", fontWeight = FontWeight.Black, color = accent)
                }
            }
            Spacer(modifier = Modifier.width(18.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (completed) TextDecoration.LineThrough else null,
                    color = if (completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(12.dp).clip(CircleShape).border(2.dp, accent, CircleShape))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (completed) "Completed" else "Pending", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onCheckedChange) {
                Box(
                    modifier = Modifier.size(34.dp).clip(CircleShape).border(3.dp, if (completed) Color(0xFFB8B2AB) else Color(0xFF9A948C), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (completed) {
                        Icon(Icons.Default.Check, contentDescription = "Completed", tint = Color(0xFF8B857E), modifier = Modifier.size(22.dp))
                    }
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFB85A44), modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
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

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ChronosTopBar("Nova", aiActive = voiceState != VoiceSessionState.IDLE)
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        when (voiceState) {
            VoiceSessionState.IDLE, VoiceSessionState.CONNECTING -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                    contentPadding = PaddingValues(top = 72.dp, bottom = 120.dp)
                ) {
                    item {
                        Card(
                            shape = RoundedCornerShape(32.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F0E8)),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Nova Voice", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Normal)
                                        Text("Intelligent task synthesis", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp)
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(28.dp),
                                        color = Color(0xFFFFEFE6),
                                        border = BorderStroke(1.dp, Color(0xFFEFC9AF))
                                    ) {
                                        Text(
                                            if (voiceState == VoiceSessionState.CONNECTING) "SYNCING" else "LISTEN",
                                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Black,
                                            letterSpacing = 2.sp
                                        )
                                    }
                                }
                                Text("TASK DESCRIPTION", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                                OutlinedTextField(
                                    value = chatMessage,
                                    onValueChange = { chatMessage = it },
                                    placeholder = { Text("Plan my DSA practice and assignment tonight...") },
                                    minLines = 4,
                                    shape = RoundedCornerShape(22.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    trailingIcon = { Icon(Icons.Default.Call, contentDescription = null, tint = Color(0xFFE2A57E)) }
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    AssistChip(
                                        onClick = {},
                                        label = { Text("Today") },
                                        leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier.weight(1f)
                                    )
                                    AssistChip(
                                        onClick = {},
                                        label = { Text("14:30 PM") },
                                        leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(22.dp),
                                    color = Color(0xFFF3ECE3),
                                    border = BorderStroke(1.dp, Color(0xFFE9DED2)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(modifier = Modifier.size(58.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFFEFDCCB)), contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                                        }
                                        Spacer(modifier = Modifier.width(18.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Proactive Reminders", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                            Text("AI-guided focus nudges", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Switch(checked = true, onCheckedChange = {}, colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary))
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    OutlinedButton(
                                        onClick = { chatMessage = "" },
                                        shape = RoundedCornerShape(18.dp),
                                        modifier = Modifier.weight(1f).height(64.dp)
                                    ) { Text("Discard", fontSize = 18.sp) }
                                    Button(
                                        onClick = {
                                            if (chatMessage.isNotBlank()) model.sendChatMessage(context, chatMessage)
                                            model.startVoiceSession(context)
                                        },
                                        shape = RoundedCornerShape(18.dp),
                                        modifier = Modifier.weight(1.45f).height(64.dp)
                                    ) { Text("Schedule Task", fontSize = 18.sp, fontWeight = FontWeight.Black) }
                                }
                            }
                        }
                    }
                }
            }
            VoiceSessionState.LISTENING -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color(0xFFFFF4EA), MaterialTheme.colorScheme.background)))
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(48.dp))
                    Text("Nova is listening...", style = MaterialTheme.typography.headlineLarge, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                    Spacer(modifier = Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                        Spacer(modifier = Modifier.width(18.dp))
                        Text("NOVA VOICE V2.4", color = ChronosMuted, letterSpacing = 4.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(modifier = Modifier.height(72.dp))
                    AnimatedAudioWaveform(modifier = Modifier.fillMaxWidth().height(170.dp), bars = 34)
                    Spacer(modifier = Modifier.height(64.dp))
                    Card(
                        shape = RoundedCornerShape(28.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, Color(0xFFF0E3D8)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(30.dp)) {
                            Text("\"...planning my deep focus session for tomorrow morning...\"", color = Color(0xFFE1A37C), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, fontSize = 18.sp)
                            Spacer(modifier = Modifier.height(22.dp))
                            Text(transcript.ifEmpty { "for my research on autonomous systems. Can you also..." }, style = MaterialTheme.typography.headlineSmall, lineHeight = 38.sp)
                            Spacer(modifier = Modifier.height(28.dp))
                            Box(modifier = Modifier.width(7.dp).height(34.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFE8B795)))
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = chatMessage, onValueChange = { chatMessage = it }, placeholder = { Text("Type a command...") }, shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f))
                        Spacer(modifier = Modifier.width(12.dp))
                        FloatingActionButton(onClick = { if (chatMessage.isNotBlank()) { model.sendChatMessage(context, chatMessage); chatMessage = "" } }, containerColor = MaterialTheme.colorScheme.primary, shape = CircleShape) {
                            Icon(Icons.Default.Send, contentDescription = null, tint = Color.White)
                        }
                    }
                    Spacer(modifier = Modifier.height(28.dp))
                    FloatingActionButton(
                        onClick = { model.endVoiceSession(context) },
                        containerColor = ChronosRustDark,
                        contentColor = Color.White,
                        shape = CircleShape,
                        modifier = Modifier.size(96.dp)
                    ) {
                        Icon(Icons.Default.Call, contentDescription = "Disconnect", modifier = Modifier.size(34.dp))
                    }
                    Spacer(modifier = Modifier.height(32.dp))
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

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ChronosTopBar("Focus")
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Focus Level", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(12.dp))
            Text("Your peak productivity is 14% higher this week.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(34.dp))
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(modifier = Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { timerSeconds / 1500f },
                            strokeWidth = 12.dp,
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color(0xFFE7DDD2),
                            modifier = Modifier.fillMaxSize()
                        )
                        Text(String.format("%02d:%02d", timerSeconds / 60, timerSeconds % 60), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Black)
                    }
                    Spacer(modifier = Modifier.height(34.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { isRunning = !isRunning },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.weight(1f).height(58.dp)
                        ) { Text(if (isRunning) "Pause" else "Start", fontWeight = FontWeight.Black) }
                        OutlinedButton(
                            onClick = { isRunning = false; timerSeconds = 1500 },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.weight(1f).height(58.dp)
                        ) { Text("Reset", fontWeight = FontWeight.Black) }
                    }
                }
            }
        }
    }
}

// =========================================================================
// SCREEN 4: PROFILE
// =========================================================================

@Composable
fun ProfileScreen(userName: String) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ChronosTopBar(userName)
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(top = 28.dp, bottom = 110.dp)
        ) {
            item {
                Box(contentAlignment = Alignment.BottomEnd) {
                    Box(
                        modifier = Modifier.size(118.dp).clip(CircleShape).background(
                            Brush.linearGradient(listOf(Color(0xFF101010), Color(0xFF33444B), Color(0xFFC9672A)))
                        ).border(4.dp, Color(0xFFEBC6AA), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(userName.take(1).ifBlank { "A" }.uppercase(), color = Color.White, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black)
                    }
                    Box(modifier = Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(modifier = Modifier.height(18.dp))
                Text(userName.ifBlank { "Alex Thorne" }, style = MaterialTheme.typography.headlineMedium)
                Text("Computer Science - Year 3", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfilePill("Academic Honors", Color(0xFFFFEEE4))
                    ProfilePill("Beta Tester", Color(0xFFE8E0D8))
                }
            }
            item {
                ProfilePanel("University Credentials") {
                    ProfileMeta("INSTITUTION", "Stanford University")
                    ProfileMeta("STUDENT ID", "STU-882-990-AI")
                    ProfileMeta("ACADEMIC EMAIL", "a.thorne@stanford.edu")
                    ProfileMeta("FOCUS AREA", "Neural Networks & UIX")
                }
            }
            item {
                ProfilePanel("Focus Level") {
                    Text("92%", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
                    Text("Your peak productivity is 14% higher this week.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(18.dp))
                    LinearProgressIndicator(
                        progress = { 0.92f },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color(0xFFE7DDD2)
                    )
                }
            }
            item {
                ProfilePanel("AI Personality") {
                    Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFFFFFBF7), border = BorderStroke(1.dp, Color(0xFFE7DDD2)), modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Face, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Persona: Mentor", fontWeight = FontWeight.Black)
                                Text("Supportive, professional, and encouraging tone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    ProfileSettingRow("Predictive Scheduling", true)
                }
            }
            item {
                ProfilePanel("Notifications") {
                    NotificationRow("Daily Briefing", "8:00 AM")
                    ProfileSettingRow("Task Reminders", true)
                    ProfileSettingRow("Urgent Focus Alerts", false)
                    OutlinedButton(onClick = {}, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(54.dp)) {
                        Text("Manage AI Alerts", fontWeight = FontWeight.Black)
                    }
                }
            }
            item {
                Text("ACCOUNT PRIVACY", color = Color(0xFFD43A2F), fontWeight = FontWeight.Black, letterSpacing = 2.sp, modifier = Modifier.fillMaxWidth().padding(start = 6.dp))
                Spacer(modifier = Modifier.height(10.dp))
                Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFFFFF2EA), border = BorderStroke(1.dp, Color(0xFFF0C8BB)), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Data Sovereignty", fontWeight = FontWeight.Black)
                        Text("Download or permanently delete your AI training history and personal data.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(18.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = {}, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) { Text("Download") }
                            Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD43A2F)), shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) { Text("Delete Account") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileSettingRow(label: String, initialValue: Boolean) {
    var checked by remember { mutableStateOf(initialValue) }
    Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFFFFFBF7), border = BorderStroke(1.dp, Color(0xFFE7DDD2)), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Switch(checked = checked, onCheckedChange = { checked = it }, colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary))
        }
    }
}

@Composable
fun ProfilePill(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(18.dp), color = color, border = BorderStroke(1.dp, Color(0xFFE3D6CA))) {
        Text(text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 12.sp, color = ChronosInk)
    }
}

@Composable
fun ProfilePanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F0E8)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
fun ProfileMeta(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Color(0xFFA99F96), fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        Text(value, color = ChronosMuted, fontSize = 16.sp)
    }
}

@Composable
fun NotificationRow(label: String, value: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFFFFBF7), border = BorderStroke(1.dp, Color(0xFFE7DDD2)), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.DateRange, contentDescription = null, tint = ChronosMuted, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(label, modifier = Modifier.weight(1f), color = ChronosInk)
            Text(value, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
        }
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

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(
            shape = RoundedCornerShape(32.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F0E8)),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth().padding(24.dp)
        ) {
            Column(modifier = Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Nova Voice", style = MaterialTheme.typography.headlineMedium)
                        Text("Intelligent task synthesis", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Surface(shape = RoundedCornerShape(24.dp), color = Color(0xFFFFEFE6), border = BorderStroke(1.dp, Color(0xFFEFC9AF))) {
                        Text("SCHEDULE", modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
                    }
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Task Name") }, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Description") }, minLines = 3, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = time, onValueChange = { time = it }, label = { Text("Time (HH:MM)") }, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    FilterChip(
                        selected = !isPm,
                        onClick = { isPm = false },
                        label = { Text("AM") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = isPm,
                        onClick = { isPm = true },
                        label = { Text("PM") },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(18.dp), modifier = Modifier.weight(1f).height(58.dp)) {
                        Text("Discard", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { if (name.isNotBlank() && time.isNotBlank()) onConfirm(name, time, isPm, desc) },
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.weight(1.25f).height(58.dp)
                    ) {
                        Text("Schedule Task", fontWeight = FontWeight.Black)
                    }
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
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Satori AI", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(42.dp))
        AnimatedContent(targetState = step, label = "onboarding_steps") { currentStep ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when (currentStep) {
                    0 -> {
                        Box(modifier = Modifier.size(92.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Face, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(54.dp))
                        }
                        Spacer(modifier = Modifier.height(32.dp))
                        Text("Welcome to ChronosAI", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
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
	                                shape = RoundedCornerShape(16.dp),
	                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).height(54.dp),
	                                colors = ButtonDefaults.buttonColors(
	                                    containerColor = if (role == r) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
	                                    contentColor = if (role == r) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
	                                )
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
	                                shape = RoundedCornerShape(16.dp),
	                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).height(54.dp),
	                                colors = ButtonDefaults.buttonColors(
	                                    containerColor = if (goal == g) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
	                                    contentColor = if (goal == g) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
	                                )
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
fun AnimatedAudioWaveform(
    modifier: Modifier = Modifier.fillMaxWidth().height(100.dp),
    bars: Int = 16
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until bars) {
            val peak = if (i in (bars / 3)..(bars * 2 / 3)) 118f else 78f
            val animHeight by infiniteTransition.animateFloat(
                initialValue = 20f,
                targetValue = peak,
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
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFFF0C7AA), MaterialTheme.colorScheme.primary, Color(0xFFF3D9C6))
                        )
                    )
            )
        }
    }
}
