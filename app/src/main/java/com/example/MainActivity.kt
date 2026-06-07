package com.example

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
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

enum class ChronosTaskStatus { PENDING, UPCOMING, COMPLETED }

data class ChronosTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val time: String,
    val description: String = "",
    val isPm: Boolean,
    val status: ChronosTaskStatus,
    val colorBarHex: Long
)

enum class VoiceSessionState { IDLE, CONNECTING, LISTENING }

// =========================================================================
// 2. STATE MANAGER (VIEWMODEL)
// =========================================================================

class ChronosViewModel : ViewModel() {
    private val apiClient = ApiClient()

    private val triggeredTaskIds = mutableSetOf<String>()
    private val _pendingReminder = MutableStateFlow<Pair<String, String>?>(null)
    val pendingReminder = _pendingReminder.asStateFlow()

    fun clearPendingReminder() {
        _pendingReminder.value = null
    }

    private fun startLocalReminderChecker() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            while (true) {
                delay(5000)
                try {
                    checkPendingReminders()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun checkPendingReminders() {
        val currentTasks = _tasks.value
        val now = java.util.Calendar.getInstance()
        val currentHour = now.get(java.util.Calendar.HOUR_OF_DAY)
        val currentMin = now.get(java.util.Calendar.MINUTE)
        
        for (task in currentTasks) {
            if (task.status == ChronosTaskStatus.PENDING && !triggeredTaskIds.contains(task.id)) {
                val timeParts = task.time.split(":")
                if (timeParts.size == 2) {
                    var taskHour = timeParts[0].toIntOrNull() ?: 0
                    val taskMin = timeParts[1].toIntOrNull() ?: 0
                    if (task.isPm && taskHour < 12) taskHour += 12
                    if (!task.isPm && taskHour == 12) taskHour = 0
                    
                    val taskTotalMinutes = taskHour * 60 + taskMin
                    val currentTotalMinutes = currentHour * 60 + currentMin
                    
                    if (taskTotalMinutes <= currentTotalMinutes && (currentTotalMinutes - taskTotalMinutes) < 2) {
                        triggeredTaskIds.add(task.id)
                        _pendingReminder.value = Pair(task.title, task.id)
                    }
                }
            }
        }
    }

    init {
        com.example.android_integration.VoiceReceiverService.activeViewModel = this
        startLocalReminderChecker()
        
        viewModelScope.launch {
            while (true) {
                delay(15000)
                try {
                    if (_userId.value.isNotEmpty()) {
                        fetchTasksFromBackend()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        com.example.android_integration.VoiceReceiverService.activeViewModel = null
        ringtone?.stop()
    }

    // Callbacks for Speech/TTS bound to MainActivity
    var onSpeakRequested: ((String, Boolean) -> Unit)? = null
    var onListenRequested: (() -> Unit)? = null

    private val _showIncomingCall = MutableStateFlow(false)
    val showIncomingCall = _showIncomingCall.asStateFlow()

    private val _showActiveCall = MutableStateFlow(false)
    val showActiveCall = _showActiveCall.asStateFlow()

    private val _activeCallText = MutableStateFlow("")
    val activeCallText = _activeCallText.asStateFlow()

    private val _activeCallTaskId = MutableStateFlow("")
    val activeCallTaskId = _activeCallTaskId.asStateFlow()
    
    private var ringtone: android.media.Ringtone? = null

    fun triggerIncomingCall(context: android.content.Context, text: String, taskId: String) {
        _liveTranscript.value = ""
        _activeCallText.value = text
        _activeCallTaskId.value = taskId
        _showIncomingCall.value = true
        _showActiveCall.value = false
        
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            try {
                if (ringtone == null) {
                    val ringtoneUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
                    ringtone = android.media.RingtoneManager.getRingtone(context, ringtoneUri)
                }
                ringtone?.play()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun acceptCall(context: android.content.Context) {
        ringtone?.stop()
        _showIncomingCall.value = false
        _showActiveCall.value = true
        
        // Start LiveKit session
        startVoiceSession(context)
    }

    fun declineCall() {
        ringtone?.stop()
        _showIncomingCall.value = false
        _showActiveCall.value = false
        _activeCallText.value = ""
        _activeCallTaskId.value = ""
    }
    
    fun hangUpCall(context: android.content.Context) {
        ringtone?.stop()
        _showIncomingCall.value = false
        _showActiveCall.value = false
        _activeCallText.value = ""
        _activeCallTaskId.value = ""
        endVoiceSession(context)
    }

    fun speakActiveCallText(text: String) {
        val shouldListen = _showActiveCall.value
        onSpeakRequested?.invoke(text, shouldListen)
    }

    private val _currentTab = MutableStateFlow(0) 
    val currentTab = _currentTab.asStateFlow()

    private val _isNewTaskSheetOpen = MutableStateFlow(false)
    val isNewTaskSheetOpen = _isNewTaskSheetOpen.asStateFlow()

    private val _tasks = MutableStateFlow<List<ChronosTask>>(emptyList())
    val tasks = _tasks.asStateFlow()

    private val _voiceSessionState = MutableStateFlow(VoiceSessionState.IDLE)
    val voiceSessionState = _voiceSessionState.asStateFlow()

    private val _liveTranscript = MutableStateFlow("")
    val liveTranscript = _liveTranscript.asStateFlow()

    private val _userName = MutableStateFlow("")
    val userName = _userName.asStateFlow()

    private val _isProfileLoaded = MutableStateFlow(false)
    val isProfileLoaded = _isProfileLoaded.asStateFlow()

    private val _userRole = MutableStateFlow("Student")
    val userRole = _userRole.asStateFlow()

    private val _userGoal = MutableStateFlow("Cybersecurity")
    val userGoal = _userGoal.asStateFlow()

    private val _userTimezone = MutableStateFlow("Asia/Kolkata")
    val userTimezone = _userTimezone.asStateFlow()

    private val _showSettingsDialog = MutableStateFlow(false)
    val showSettingsDialog = _showSettingsDialog.asStateFlow()

    private val _showEditProfileDialog = MutableStateFlow(false)
    val showEditProfileDialog = _showEditProfileDialog.asStateFlow()

    private val _userId = MutableStateFlow("")

    fun setVoiceSessionState(state: VoiceSessionState) {
        _voiceSessionState.value = state
    }

    fun setShowSettingsDialog(show: Boolean) {
        _showSettingsDialog.value = show
    }

    fun setShowEditProfileDialog(show: Boolean) {
        _showEditProfileDialog.value = show
    }

    fun loadUserName(context: android.content.Context) {
        val prefs = context.getSharedPreferences("ChronosPrefs", android.content.Context.MODE_PRIVATE)
        val name = prefs.getString("user_name", "") ?: ""
        val role = prefs.getString("user_role", "Student") ?: "Student"
        val goal = prefs.getString("user_goal", "Cybersecurity") ?: "Cybersecurity"
        val tz = prefs.getString("user_timezone", "Asia/Kolkata") ?: "Asia/Kolkata"
        _userName.value = name
        _userRole.value = role
        _userGoal.value = goal
        _userTimezone.value = tz
        val uid = getOrCreateUserId(context)
        _userId.value = uid
        _isProfileLoaded.value = true
        fetchTasksFromBackend()

        if (name.isNotEmpty()) {
            viewModelScope.launch {
                try {
                    apiClient.updateProfile(uid, name, role, goal, tz)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun saveOnboarding(context: android.content.Context, name: String, role: String, goal: String) {
        val prefs = context.getSharedPreferences("ChronosPrefs", android.content.Context.MODE_PRIVATE)
        val uid = getOrCreateUserId(context)
        _userId.value = uid
        prefs.edit().apply {
            putString("user_name", name)
            putString("user_role", role)
            putString("user_goal", goal)
            putString("user_timezone", "Asia/Kolkata")
        }.apply()
        _userName.value = name
        _userRole.value = role
        _userGoal.value = goal
        _userTimezone.value = "Asia/Kolkata"
        suggestInitialTasks(role, goal)

        viewModelScope.launch {
            try {
                apiClient.updateProfile(uid, name, role, goal, "Asia/Kolkata")
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isProfileLoaded.value = true
            }
        }
    }

    fun updateProfileSettings(context: android.content.Context, name: String, role: String, goal: String, timezone: String) {
        val prefs = context.getSharedPreferences("ChronosPrefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("user_name", name)
            putString("user_role", role)
            putString("user_goal", goal)
            putString("user_timezone", timezone)
        }.apply()
        _userName.value = name
        _userRole.value = role
        _userGoal.value = goal
        _userTimezone.value = timezone

        val uid = _userId.value
        viewModelScope.launch {
            try {
                apiClient.updateProfile(uid, name, role, goal, timezone)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun getOrCreateUserId(context: android.content.Context): String {
        return "293dafd6-72d4-4dc9-a668-4ba8f8586ca7"
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
        val newTask = ChronosTask(
            id = taskId,
            title = title,
            time = time,
            isPm = isPm,
            description = description,
            status = ChronosTaskStatus.PENDING,
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
                    if (it.id == taskId) savedTask.toChronosTask(description) else it
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
                    ChronosTaskStatus.PENDING, ChronosTaskStatus.UPCOMING -> ChronosTaskStatus.COMPLETED
                    ChronosTaskStatus.COMPLETED -> ChronosTaskStatus.PENDING
                }
                newStatusStr = if (nextStatus == ChronosTaskStatus.COMPLETED) "completed" else "pending"
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
                _tasks.value = apiClient.fetchDailyTasks(userId).map { it.toChronosTask() }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun startTestVoiceCall(context: android.content.Context) {
        _activeCallText.value = "TEST_CALL"
        _activeCallTaskId.value = "TEST_CALL_ID"
        _showActiveCall.value = true
        startVoiceSession(context)
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
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val prefix = if (trimmed.startsWith("You:") || trimmed.startsWith("ChronosAI:")) "" else "ChronosAI: "
        if (_liveTranscript.value.isEmpty()) {
            _liveTranscript.value = "$prefix$trimmed"
        } else {
            _liveTranscript.value = _liveTranscript.value + "\n$prefix$trimmed"
        }
    }

    private fun DailyTaskDto.toChronosTask(description: String = ""): ChronosTask {
        var hour = 0
        var minute = "00"
        var isPm = false
        try {
            // Parse robustly using java.time OffsetDateTime (e.g. 2026-06-05T18:32:00+00:00)
            val odt = java.time.OffsetDateTime.parse(scheduledTime)
            val localTime = odt.atZoneSameInstant(java.time.ZoneId.systemDefault())
            hour = localTime.hour
            minute = "%02d".format(localTime.minute)
            isPm = hour >= 12
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                // Fallback to Instant parsing (e.g. 2026-06-05T18:32:00Z)
                val instant = java.time.Instant.parse(scheduledTime)
                val localTime = instant.atZone(java.time.ZoneId.systemDefault())
                hour = localTime.hour
                minute = "%02d".format(localTime.minute)
                isPm = hour >= 12
            } catch (ex: Exception) {
                ex.printStackTrace()
                val timePart = scheduledTime.substringAfter("T", "00:00").take(5)
                hour = timePart.substringBefore(":").toIntOrNull() ?: 0
                minute = timePart.substringAfter(":", "00").take(2)
                isPm = hour >= 12
            }
        }

        val hour12 = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return ChronosTask(
            id = id,
            title = taskDescription,
            time = "%02d:%s".format(hour12, minute),
            description = description,
            isPm = isPm,
            status = if (status == "completed") ChronosTaskStatus.COMPLETED else ChronosTaskStatus.PENDING,
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
        
        val model = androidx.lifecycle.ViewModelProvider(this)[ChronosViewModel::class.java]
        model.loadUserName(applicationContext)
        
        enableEdgeToEdge()
        setContent {
            ChronosAITheme {
                MainAppContainer(model)
            }
        }
    }
}

@Composable
fun MainAppContainer(model: ChronosViewModel) {
    val currentTab by model.currentTab.collectAsState()
    val isNewTaskOpen by model.isNewTaskSheetOpen.collectAsState()
    val voiceState by model.voiceSessionState.collectAsState()
    val userName by model.userName.collectAsState()
    val isProfileLoaded by model.isProfileLoaded.collectAsState()
    val context = LocalContext.current

    if (!isProfileLoaded) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    if (userName.isEmpty()) {
        OnboardingScreen(onComplete = { name, role, goal -> model.saveOnboarding(context, name, role, goal) })
        return
    }

    val pendingReminder by model.pendingReminder.collectAsState()
    LaunchedEffect(pendingReminder) {
        val reminder = pendingReminder
        if (reminder != null) {
            model.triggerIncomingCall(context, reminder.first, reminder.second)
            model.clearPendingReminder()
        }
    }

    val showIncomingCall by model.showIncomingCall.collectAsState()
    val showActiveCall by model.showActiveCall.collectAsState()
    val activeCallText by model.activeCallText.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
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
                        2 -> FocusTimerScreen(model)
                        3 -> ProfileScreen(model)
                    }
                }

                val showSettings by model.showSettingsDialog.collectAsState()
                val showEditProfile by model.showEditProfileDialog.collectAsState()
                val userRole by model.userRole.collectAsState()
                val userGoal by model.userGoal.collectAsState()
                val userTimezone by model.userTimezone.collectAsState()

                if (showSettings) {
                    SettingsDialog(
                        onDismiss = { model.setShowSettingsDialog(false) },
                        context = context,
                        model = model
                    )
                }

                if (showEditProfile) {
                    EditProfileDialog(
                        currentName = userName,
                        currentRole = userRole,
                        currentGoal = userGoal,
                        currentTimezone = userTimezone,
                        onDismiss = { model.setShowEditProfileDialog(false) },
                        onConfirm = { name, role, goal, timezone ->
                            model.updateProfileSettings(context, name, role, goal, timezone)
                            model.setShowEditProfileDialog(false)
                        }
                    )
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

        if (showIncomingCall) {
            IncomingCallOverlay(
                text = activeCallText,
                onAccept = { model.acceptCall(context) },
                onDecline = { model.declineCall() }
            )
        }

        if (showActiveCall) {
            ActiveCallOverlay(
                model = model,
                context = context,
                onHangUp = { model.hangUpCall(context) }
            )
        }
    }
}

@Composable
fun ChronosTopBar(userName: String, aiActive: Boolean = false, onSettingsClick: (() -> Unit)? = null) {
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
            Icon(
                imageVector = Icons.Default.DateRange,
                contentDescription = "Chronos AI Logo",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            "Chronos AI",
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
        if (onSettingsClick != null) {
            IconButton(onClick = onSettingsClick) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp)
                )
            }
        }
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
fun ScheduleScreen(model: ChronosViewModel, context: android.content.Context) {
    val tasks by model.tasks.collectAsState()
    val userName by model.userName.collectAsState()
    val activeCount = tasks.count { it.status != ChronosTaskStatus.COMPLETED }
    val completedCount = tasks.count { it.status == ChronosTaskStatus.COMPLETED }
    val goalProgress = if (tasks.isEmpty()) 0f else completedCount.toFloat() / tasks.size

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        ChronosTopBar(userName, aiActive = true, onSettingsClick = { model.setShowSettingsDialog(true) })
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
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F0FE)),
                    border = BorderStroke(1.dp, Color(0xFFADCCF8)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text("MOCK VOICE TEST", color = Color(0xFF1A73E8), fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Creates a 'Test Reminder' scheduled for 2 minutes from now, automatically starts Nova Voice, and verifies the end-to-end voice loop.", fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(18.dp))
                        Button(
                            onClick = {
                                val cal = java.util.Calendar.getInstance()
                                cal.add(java.util.Calendar.MINUTE, 2)
                                val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
                                val min = cal.get(java.util.Calendar.MINUTE)
                                val isPm = hour >= 12
                                val hour12 = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
                                val timeStr = "%02d:%02d".format(hour12, min)
                                
                                model.addTask("Test Reminder", timeStr, isPm, "This is a test reminder.")
                                model.startVoiceSession(context)
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A73E8))
                        ) {
                            Text("START TEST", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TaskRowItem(task: ChronosTask, onCheckedChange: () -> Unit, onDelete: () -> Unit) {
    val completed = task.status == ChronosTaskStatus.COMPLETED
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
fun AIAssistantScreen(model: ChronosViewModel, context: android.content.Context) {
    val voiceState by model.voiceSessionState.collectAsState()
    val transcript by model.liveTranscript.collectAsState()
    var chatMessage by remember { mutableStateOf("") }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    // Keep scroll position at the bottom of the transcript during call
    LaunchedEffect(transcript) {
        if (transcript.isNotEmpty()) {
            val lines = transcript.split("\n").filter { it.isNotBlank() }
            if (lines.isNotEmpty()) {
                try {
                    listState.animateScrollToItem(lines.size - 1)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ChronosTopBar("Chronos Assistant", aiActive = voiceState != VoiceSessionState.IDLE, onSettingsClick = { model.setShowSettingsDialog(true) })
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        // Header Status Card
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = ChronosPanel),
            border = BorderStroke(1.dp, ChronosLine),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Glowing orb indicator
                val orbColor = when (voiceState) {
                    VoiceSessionState.IDLE -> Color(0xFF4CAF50) // Steady green
                    VoiceSessionState.CONNECTING -> Color(0xFFFF9800) // Pulsing orange
                    VoiceSessionState.LISTENING -> Color(0xFFC9672A) // Pulsing rust
                }
                
                val infiniteTransition = rememberInfiniteTransition(label = "orbPulse")
                val scale by infiniteTransition.animateFloat(
                    initialValue = 0.8f,
                    targetValue = 1.2f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "orbPulseScale"
                )
                
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .graphicsLayer {
                            if (voiceState != VoiceSessionState.IDLE) {
                                scaleX = scale
                                scaleY = scale
                            }
                        }
                        .clip(CircleShape)
                        .background(orbColor)
                        .border(2.dp, Color.White, CircleShape)
                )
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    val statusText = when (voiceState) {
                        VoiceSessionState.IDLE -> "Chronos Engine Ready"
                        VoiceSessionState.CONNECTING -> "Establishing Secure Link..."
                        VoiceSessionState.LISTENING -> "Neural Link Active"
                    }
                    val subText = when (voiceState) {
                        VoiceSessionState.IDLE -> "Press mic or type to begin planning"
                        VoiceSessionState.CONNECTING -> "Synchronizing state vectors..."
                        VoiceSessionState.LISTENING -> "Voice and text sync active"
                    }
                    Text(
                        text = statusText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = ChronosInk,
                        fontFamily = ChronosSerif
                    )
                    Text(
                        text = subText,
                        fontSize = 12.sp,
                        color = ChronosMuted,
                        fontFamily = ChronosSans
                    )
                }
                
                // Voice Mode Button
                IconButton(
                    onClick = {
                        if (voiceState == VoiceSessionState.IDLE) {
                            model.startVoiceSession(context)
                        } else {
                            model.endVoiceSession(context)
                        }
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (voiceState != VoiceSessionState.IDLE) Color(0xFFD43A2F) else MaterialTheme.colorScheme.primary)
                ) {
                    Icon(
                        imageVector = if (voiceState != VoiceSessionState.IDLE) Icons.Default.Close else Icons.Default.Mic,
                        contentDescription = "Toggle Call",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Main Conversation Area
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (transcript.isBlank()) {
                // Welcome and suggestions screen
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(Color(0xFFE5925E), Color(0xFFC9672A), Color(0xFF9D3E3E))
                                )
                            )
                            .border(2.dp, Color(0xFFFFF6EE), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Face,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    Text(
                        "I am ChronosAI",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                        color = ChronosInk,
                        fontFamily = ChronosSerif
                    )
                    Text(
                        "Your Chief of Staff. I schedule your tasks, track daily accountability, and keep your life in balance.",
                        textAlign = TextAlign.Center,
                        fontSize = 14.sp,
                        color = ChronosMuted,
                        fontFamily = ChronosSans,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        "SUGGESTED ACTIONS",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Black,
                        fontSize = 11.sp,
                        letterSpacing = 1.5.sp,
                        fontFamily = ChronosSans
                    )
                    
                    // 2x2 beautiful cards for suggestions
                    val suggestions = listOf(
                        Pair("Plan Study", "Make a detailed schedule for my exam study starting at 8 PM."),
                        Pair("Analytics", "How was my productivity and completion rate this week?"),
                        Pair("Prayer Block", "Fetch today's prayer times and block them in my schedule."),
                        Pair("Reschedule", "I got busy. Can you reschedule my incomplete tasks to tomorrow?")
                    )
                    
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        suggestions.chunked(2).forEach { rowList ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                rowList.forEach { (label, text) ->
                                    Card(
                                        onClick = { chatMessage = text },
                                        shape = RoundedCornerShape(18.dp),
                                        colors = CardDefaults.cardColors(containerColor = ChronosPanel),
                                        border = BorderStroke(1.dp, ChronosLine),
                                        modifier = Modifier.weight(1f).height(85.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = label,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = ChronosInk,
                                                maxLines = 1
                                            )
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("Try prompt", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Transcript message list
                val lines = transcript.split("\n").filter { it.isNotBlank() }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
                ) {
                    items(lines) { line ->
                        val isMe = line.startsWith("You:")
                        val bubbleBg = if (isMe) Color(0xFFC9672A) else ChronosPanel
                        val align = if (isMe) Alignment.End else Alignment.Start
                        val textColor = if (isMe) Color.White else ChronosInk
                        val borderStroke = if (isMe) null else BorderStroke(1.dp, ChronosLine)
                        val bubbleShape = if (isMe) {
                            RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 2.dp)
                        } else {
                            RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 2.dp, bottomEnd = 20.dp)
                        }

                        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = align) {
                            Surface(
                                shape = bubbleShape,
                                color = bubbleBg,
                                border = borderStroke,
                                modifier = Modifier.widthIn(max = 280.dp)
                            ) {
                                Text(
                                    text = line.substringAfter(":").trim(),
                                    color = textColor,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                                    fontSize = 15.sp,
                                    fontFamily = ChronosSans
                                )
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = if (isMe) "You" else "ChronosAI",
                                color = ChronosMuted,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 8.dp),
                                fontFamily = ChronosSans
                            )
                        }
                    }
                }
            }
        }

        // Active Audio Waveform Card
        if (voiceState == VoiceSessionState.LISTENING) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1C1B)),
                border = BorderStroke(1.dp, Color(0xFF33302E)),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Voice Link Streaming Active", color = Color(0xFFE2A57E), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = ChronosSans)
                    Spacer(modifier = Modifier.height(4.dp))
                    AnimatedAudioWaveform(modifier = Modifier.fillMaxWidth().height(48.dp), bars = 24)
                }
            }
        }

        // Footer Input Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = chatMessage,
                onValueChange = { chatMessage = it },
                placeholder = { Text("Type response or planning intent...") },
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = ChronosPanel,
                    unfocusedContainerColor = ChronosPanel,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = ChronosLine
                ),
                modifier = Modifier.weight(1f)
            )
            
            Spacer(modifier = Modifier.width(8.dp))

            // Microphone Voice Button
            FloatingActionButton(
                onClick = {
                    if (voiceState == VoiceSessionState.IDLE) {
                        model.startVoiceSession(context)
                    } else {
                        model.endVoiceSession(context)
                    }
                },
                containerColor = if (voiceState != VoiceSessionState.IDLE) Color(0xFFD43A2F) else MaterialTheme.colorScheme.primary,
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = if (voiceState != VoiceSessionState.IDLE) Icons.Default.Close else Icons.Default.Mic,
                    contentDescription = "Voice Call",
                    modifier = Modifier.size(20.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(8.dp))
            
            FloatingActionButton(
                onClick = {
                    if (chatMessage.isNotBlank()) {
                        model.updateTranscript("You: $chatMessage")
                        model.sendChatMessage(context, chatMessage)
                        if (voiceState == VoiceSessionState.IDLE) {
                            model.startVoiceSession(context)
                        }
                        chatMessage = ""
                    }
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send", modifier = Modifier.size(20.dp))
            }
        }
    }
}

// =========================================================================
// SCREEN 3: FOCUS
// =========================================================================

@Composable
fun FocusTimerScreen(model: ChronosViewModel) {
    var timerSeconds by remember { mutableStateOf(1500) } 
    var isRunning by remember { mutableStateOf(false) }

    LaunchedEffect(isRunning) {
        while (isRunning && timerSeconds > 0) {
            delay(1000)
            timerSeconds--
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ChronosTopBar("Focus", onSettingsClick = { model.setShowSettingsDialog(true) })
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
fun ProfileScreen(model: ChronosViewModel) {
    val userName by model.userName.collectAsState()
    val userRole by model.userRole.collectAsState()
    val userGoal by model.userGoal.collectAsState()
    val userTimezone by model.userTimezone.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ChronosTopBar(userName, onSettingsClick = { model.setShowSettingsDialog(true) })
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
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable { model.setShowEditProfileDialog(true) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(modifier = Modifier.height(18.dp))
                Text(userName.ifBlank { "Alex Thorne" }, style = MaterialTheme.typography.headlineMedium)
                Text(userRole, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfilePill(userGoal, Color(0xFFFFEEE4))
                    ProfilePill("Beta Tester", Color(0xFFE8E0D8))
                }
            }
            item {
                ProfilePanel("User Credentials") {
                    ProfileMeta("ROLE / POSITION", userRole)
                    ProfileMeta("PRIMARY GOAL", userGoal)
                    ProfileMeta("TIMEZONE", userTimezone)
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
fun EditProfileDialog(
    currentName: String,
    currentRole: String,
    currentGoal: String,
    currentTimezone: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, role: String, goal: String, timezone: String) -> Unit
) {
    var name by remember { mutableStateOf(currentName) }
    var role by remember { mutableStateOf(currentRole) }
    var goal by remember { mutableStateOf(currentGoal) }
    var timezone by remember { mutableStateOf(currentTimezone) }

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
                        Text("Profile Settings", style = MaterialTheme.typography.headlineMedium)
                        Text("Update your personal attributes", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Surface(shape = RoundedCornerShape(24.dp), color = Color(0xFFFFEFE6), border = BorderStroke(1.dp, Color(0xFFEFC9AF))) {
                        Text("EDIT", modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Display Name") },
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = role,
                    onValueChange = { role = it },
                    label = { Text("Role / Profession") },
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = goal,
                    onValueChange = { goal = it },
                    label = { Text("Primary Goal") },
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = timezone,
                    onValueChange = { timezone = it },
                    label = { Text("Timezone") },
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(18.dp), modifier = Modifier.weight(1f).height(58.dp)) {
                        Text("Discard", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { if (name.isNotBlank() && role.isNotBlank() && goal.isNotBlank() && timezone.isNotBlank()) onConfirm(name, role, goal, timezone) },
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.weight(1.25f).height(58.dp)
                    ) {
                        Text("Save Changes", fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsDialog(
    onDismiss: () -> Unit,
    context: android.content.Context,
    model: ChronosViewModel
) {
    val prefs = context.getSharedPreferences("ChronosPrefs", android.content.Context.MODE_PRIVATE)
    var remindersEnabled by remember { mutableStateOf(prefs.getBoolean("settings_reminders_enabled", true)) }
    var voicePlanningEnabled by remember { mutableStateOf(prefs.getBoolean("settings_voice_planning_enabled", true)) }
    var gatewayUrl by remember { mutableStateOf(prefs.getString("settings_gateway_url", "http://127.0.0.1:8080") ?: "http://127.0.0.1:8080") }

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
                        Text("System Settings", style = MaterialTheme.typography.headlineMedium)
                        Text("Configure app preferences", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Surface(shape = RoundedCornerShape(24.dp), color = Color(0xFFFFEFE6), border = BorderStroke(1.dp, Color(0xFFEFC9AF))) {
                        Text("SYSTEM", modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Task Voice Reminders", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Speak scheduled items aloud", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = remindersEnabled,
                        onCheckedChange = {
                            remindersEnabled = it
                            prefs.edit().putBoolean("settings_reminders_enabled", it).apply()
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("AI Daily Standup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Initiate voice planning every morning", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = voicePlanningEnabled,
                        onCheckedChange = {
                            voicePlanningEnabled = it
                            prefs.edit().putBoolean("settings_voice_planning_enabled", it).apply()
                        }
                    )
                }

                OutlinedTextField(
                    value = gatewayUrl,
                    onValueChange = {
                        gatewayUrl = it
                        prefs.edit().putString("settings_gateway_url", it).apply()
                    },
                    label = { Text("FastAPI Gateway Endpoint") },
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        onDismiss()
                        model.startTestVoiceCall(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth().height(58.dp)
                ) {
                    Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Test Voice Call", fontWeight = FontWeight.Black)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth().height(58.dp)
                    ) {
                        Text("Close Settings", fontWeight = FontWeight.Black)
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
        Text("Chronos AI", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
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
                    .height(120.dp)
                    .graphicsLayer {
                        scaleY = animHeight / peak
                    }
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

@Composable
fun IncomingCallOverlay(text: String, onAccept: () -> Unit, onDecline: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "ringPulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale"
    )
    val opacity by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseOpacity"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF151413))
            .clickable(enabled = false) {}, // Intercept clicks
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Spacer(modifier = Modifier.height(72.dp))
            
            // Pulsing Avatar
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(160.dp)) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .graphicsLayer(scaleX = scale, scaleY = scale)
                        .alpha(opacity)
                        .clip(CircleShape)
                        .background(Color(0xFFE2A57E))
                )
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFFC9672A), Color(0xFF9D3E3E))
                            )
                        )
                        .border(3.dp, Color(0xFFF7E5D5), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "C",
                        color = Color.White,
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
            
            Text("ChronosAI", color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("Incoming Task Call", color = Color(0xFFA99F96), fontSize = 16.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Black)

            Spacer(modifier = Modifier.height(48.dp))
            
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF232120)),
                border = BorderStroke(1.dp, Color(0xFF3B3836)),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text,
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 48.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Decline Button
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FloatingActionButton(
                        onClick = onDecline,
                        containerColor = Color(0xFFD43A2F),
                        contentColor = Color.White,
                        shape = CircleShape,
                        modifier = Modifier.size(76.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Decline", modifier = Modifier.size(34.dp))
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Decline", color = Color(0xFFA99F96), fontSize = 14.sp)
                }

                // Accept Button
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FloatingActionButton(
                        onClick = onAccept,
                        containerColor = Color(0xFF34A853),
                        contentColor = Color.White,
                        shape = CircleShape,
                        modifier = Modifier.size(76.dp)
                    ) {
                        Icon(Icons.Default.Call, contentDescription = "Accept", modifier = Modifier.size(34.dp))
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Accept", color = Color(0xFFA99F96), fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
fun ActiveCallOverlay(model: ChronosViewModel, context: android.content.Context, onHangUp: () -> Unit) {
    val transcript by model.liveTranscript.collectAsState()
    val activeCallText by model.activeCallText.collectAsState()
    var callSeconds by remember { mutableStateOf(0) }
    var chatMessage by remember { mutableStateOf("") }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            callSeconds++
        }
    }
    
    // Automatically scroll to the end of transcript on changes
    LaunchedEffect(transcript) {
        if (transcript.isNotEmpty()) {
            val lines = transcript.split("\n").filter { it.isNotBlank() }
            if (lines.isNotEmpty()) {
                listState.animateScrollToItem(lines.size - 1)
            }
        }
    }

    val minutes = callSeconds / 60
    val seconds = callSeconds % 60
    val timerStr = "%02d:%02d".format(minutes, seconds)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF1A1A1A), Color(0xFF0F0F0F))
                )
            )
            .clickable(enabled = false) {}, // Intercept clicks
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(48.dp))
            
            Text("ChronosAI Live Link", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Text(timerStr, color = Color(0xFFE2A57E), fontSize = 18.sp, fontWeight = FontWeight.Black)

            Spacer(modifier = Modifier.height(28.dp))
            
            AnimatedAudioWaveform(modifier = Modifier.fillMaxWidth().height(100.dp), bars = 28)

            Spacer(modifier = Modifier.height(28.dp))
            
            // Conversation Transcript Card
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1C1B)),
                border = BorderStroke(1.dp, Color(0xFF33302E)),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "Live Transcript",
                        color = Color(0xFF8C847C),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.5.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (transcript.isBlank()) {
                            Text(
                                "Connected. ChronosAI is reading the reminder...",
                                color = Color(0xFF8C847C),
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                            )
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                val lines = transcript.split("\n").filter { it.isNotBlank() }
                                items(lines) { line ->
                                    val isMe = line.startsWith("You:")
                                    val bubbleBg = if (isMe) Color(0xFFC9672A) else Color(0xFF2A2826)
                                    val align = if (isMe) Alignment.End else Alignment.Start
                                    val textColor = Color.White
                                    
                                    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = align) {
                                        Surface(
                                            shape = RoundedCornerShape(18.dp),
                                            color = bubbleBg,
                                            modifier = Modifier.widthIn(max = 260.dp)
                                        ) {
                                            Text(
                                                text = line.substringAfter(":").trim(),
                                                color = textColor,
                                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                                fontSize = 15.sp
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            if (isMe) "You" else "ChronosAI",
                                            color = Color(0xFF8C847C),
                                            fontSize = 10.sp,
                                            modifier = Modifier.padding(horizontal = 8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            
            // Text Input field for typing commands
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = chatMessage,
                    onValueChange = { chatMessage = it },
                    placeholder = { Text("Type response...", color = Color(0xFF8C847C)) },
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFE2A57E),
                        unfocusedBorderColor = Color(0xFF33302E)
                    ),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                FloatingActionButton(
                    onClick = {
                        model.onListenRequested?.invoke()
                    },
                    containerColor = Color(0xFFE2A57E),
                    contentColor = Color.White,
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Speak", modifier = Modifier.size(24.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                FloatingActionButton(
                    onClick = {
                        if (chatMessage.isNotBlank()) {
                            model.updateTranscript("You: $chatMessage")
                            model.sendChatMessage(context, chatMessage)
                            chatMessage = ""
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send", modifier = Modifier.size(24.dp))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            
            // Disconnect Call Button
            FloatingActionButton(
                onClick = onHangUp,
                containerColor = Color(0xFFD43A2F),
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier.size(76.dp)
            ) {
                Icon(Icons.Default.Call, contentDescription = "Disconnect Call", modifier = Modifier.size(34.dp))
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun ChronosSplashScreen() {
    val infiniteTransition = rememberInfiniteTransition(label = "splashPulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logoPulse"
    )
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "logoRotate"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(140.dp)
                    .graphicsLayer(scaleX = scale, scaleY = scale)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(
                            width = 2.dp,
                            brush = Brush.sweepGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.secondary,
                                    MaterialTheme.colorScheme.primary
                                )
                            ),
                            shape = CircleShape
                        )
                )

                Icon(
                    imageVector = Icons.Default.DateRange,
                    contentDescription = "Chronos Logo",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(60.dp)
                        .graphicsLayer(rotationZ = rotation)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "CHRONOS AI",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = ChronosSerif,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 4.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Chief of Staff",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = ChronosSerif,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 2.sp
            )

            Spacer(modifier = Modifier.height(64.dp))

            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
                modifier = Modifier.size(36.dp)
            )
        }
    }
}
