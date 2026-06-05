import re

with open('app/src/main/java/com/example/MainActivity.kt', 'r') as f:
    content = f.read()

# 1. Redefine the Theme with "Stitch" Aesthetic
new_theme = '''
private val ChronosAIProfessionalColorScheme = lightColorScheme(
    primary = Color(0xFF4285F4),         # Google Blue
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD2E3FC),
    onPrimaryContainer = Color(0xFF174EA6),
    secondary = Color(0xFF34A853),       # Google Green
    onSecondary = Color.White,
    tertiary = Color(0xFFFABB05),        # Google Yellow
    onTertiary = Color.White,
    background = Color(0xFFFFFFFF),      # Pure White Canvas
    surface = Color(0xFFF8F9FA),         # Subtle Off-White
    surfaceVariant = Color(0xFFE8EAED),  # Gray 200
    onBackground = Color(0xFF202124),    # High-Contrast Text
    onSurface = Color(0xFF202124),
    onSurfaceVariant = Color(0xFF5F6368), # Medium-Contrast Text
    outline = Color(0xFFDADCE0),         # Light Border
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
'''

# Find the old theme block and replace it
content = re.sub(r'private val ChronosAILightColorScheme = .*?content = content\n    \)', new_theme, content, flags=re.DOTALL)

# 2. Total Overhaul of MainAppContainer and Screens
# We will use a cleaner layout with consistent 24dp rounding and "Stitch" styling.

# Replace ScheduleScreen
schedule_screen = '''
@Composable
fun ScheduleScreen(model: SatoriViewModel, context: android.content.Context) {
    val tasks by model.tasks.collectAsState()
    val userName by model.userName.collectAsState()
    val activeCount = tasks.count { it.status != SatoriTaskStatus.COMPLETED }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp)
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        
        // Dynamic Header
        Text(
            text = "Good morning, $userName",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "You have $activeCount tasks scheduled for today.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Professional Voice Link Card (Minimalist)
        Card(
            onClick = { model.startVoiceSession(context) },
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth().height(80.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "Talk to Chronos",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "Real-time AI voice assistant",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "YOUR TIMELINE",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (tasks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No tasks scheduled yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(tasks, key = { it.id }) { task ->
                    TaskRowItem(
                        task = task,
                        onCheckedChange = { model.toggleTaskCompletion(task.id) },
                        onDelete = { model.deleteTask(task.id) }
                    )
                }
                item { Spacer(modifier = Modifier.height(100.dp)) }
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = task.status == SatoriTaskStatus.COMPLETED,
                onCheckedChange = { onCheckedChange() },
                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    fontWeight = FontWeight.Bold,
                    textDecoration = if (task.status == SatoriTaskStatus.COMPLETED) TextDecoration.LineThrough else null,
                    color = if (task.status == SatoriTaskStatus.COMPLETED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${task.time} ${if (task.isPm) "PM" else "AM"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFB00020), modifier = Modifier.size(20.dp))
            }
        }
    }
}
'''

# Replace AIAssistantScreen
assistant_screen = '''
@Composable
fun AIAssistantScreen(model: SatoriViewModel, context: android.content.Context) {
    val voiceState by model.voiceSessionState.collectAsState()
    val transcript by model.liveTranscript.collectAsState()
    var chatMessage by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when (voiceState) {
            VoiceSessionState.IDLE, VoiceSessionState.CONNECTING -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier.size(120.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        if (voiceState == VoiceSessionState.CONNECTING) {
                            CircularProgressIndicator(modifier = Modifier.size(80.dp), color = MaterialTheme.colorScheme.primary)
                        } else {
                            Icon(Icons.Default.Face, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(40.dp))
                    Text(
                        text = if (voiceState == VoiceSessionState.CONNECTING) "Syncing..." else "Chronos AI",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "I am ready to help you optimize your day. Establish a link to begin.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(48.dp))
                    Button(
                        onClick = { model.startVoiceSession(context) },
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.fillMaxWidth().height(60.dp)
                    ) {
                        Text("ESTABLISH NEURAL LINK", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                }
            }
            VoiceSessionState.LISTENING -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp)
                ) {
                    Spacer(modifier = Modifier.height(40.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                         Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(Color(0xFF34A853)))
                         Spacer(modifier = Modifier.width(12.dp))
                         Text("LIVE CONNECTION ACTIVE", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color(0xFF34A853))
                    }
                    
                    Spacer(modifier = Modifier.height(40.dp))
                    
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(24.dp)) {
                            Text("TRANSCRIPTION", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = transcript.ifEmpty { "Listening for your voice..." },
                                style = MaterialTheme.typography.bodyLarge,
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = chatMessage,
                            onValueChange = { chatMessage = it },
                            placeholder = { Text("Type a command...") },
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        FloatingActionButton(
                            onClick = { 
                                if (chatMessage.isNotBlank()) {
                                    model.sendChatMessage(context, chatMessage)
                                    chatMessage = ""
                                }
                            },
                            containerColor = MaterialTheme.colorScheme.primary,
                            shape = CircleShape
                        ) {
                            Icon(Icons.Default.Send, contentDescription = null, tint = Color.White)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Button(
                        onClick = { model.endVoiceSession(context) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB00020)),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.fillMaxWidth().height(60.dp)
                    ) {
                        Text("DISCONNECT", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
'''

# Update the Bottom Bar to match
bottom_bar = '''
@Composable
fun SatoriBottomBar(selectedIndex: Int, onTabSelected: (Int) -> Unit) {
    NavigationBar(
        containerColor = Color.White,
        tonalElevation = 8.dp
    ) {
        val items = listOf(
            Triple("Schedule", Icons.Default.DateRange, 0),
            Triple("Assistant", Icons.Default.Face, 1),
            Triple("Focus", Icons.Default.PlayArrow, 2),
            Triple("Profile", Icons.Default.Person, 3)
        )
        items.forEach { (label, icon, index) ->
            val isSelected = selectedIndex == index
            NavigationBarItem(
                selected = isSelected,
                onClick = { onTabSelected(index) },
                icon = { Icon(icon, contentDescription = label) },
                label = { Text(label) }
            )
        }
    }
}
'''

# Perform replacements
content = re.sub(r'@Composable\nfun ScheduleScreen.*?@Composable\nfun HeaderSection', schedule_screen + '\n@Composable\nfun HeaderSection', content, flags=re.DOTALL)
content = re.sub(r'@Composable\nfun AIAssistantScreen.*?@Composable\nfun LargeAudioWaveform', assistant_screen + '\n@Composable\nfun LargeAudioWaveform', content, flags=re.DOTALL)
content = re.sub(r'@Composable\nfun SatoriBottomBar.*?// =========================================================================\n// SCREEN 1', bottom_bar + '\n// =========================================================================\n// SCREEN 1', content, flags=re.DOTALL)

# Cleanup OnboardingScreen to be more professional
onboarding_pro = '''
@Composable
fun OnboardingScreen(onComplete: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
        Spacer(modifier = Modifier.height(24.dp))
        Text("Chronos AI", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
        Spacer(modifier = Modifier.height(16.dp))
        Text("Welcome to the next generation of time management.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(48.dp))
        Text("WHAT IS YOUR NAME?", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = { Text("Enter your name") },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(48.dp))
        Button(
            onClick = { if (name.isNotBlank()) onComplete(name.trim()) },
            shape = RoundedCornerShape(50),
            modifier = Modifier.fillMaxWidth().height(60.dp)
        ) {
            Text("CONTINUE", fontWeight = FontWeight.Bold)
        }
    }
}
'''
content = re.sub(r'@Composable\nfun OnboardingScreen.*?}\n(?=$)', onboarding_pro, content, flags=re.DOTALL)

with open('app/src/main/java/com/example/MainActivity.kt', 'w') as f:
    f.write(content)
print("Professional UI overhaul applied.")
