import re

with open('app/src/main/java/com/example/MainActivity.kt', 'r') as f:
    content = f.read()

# 1. Update Theme to Light Mode
content = content.replace('private val SatoriDarkColorScheme = darkColorScheme(', 
'''private val ChronosAILightColorScheme = lightColorScheme(''')

content = content.replace('SatoriTheme', 'ChronosAITheme')
content = content.replace('SatoriDarkColorScheme', 'ChronosAILightColorScheme')

# Change dark theme colors to light theme palette
content = re.sub(r'primary = Color\(0xFFD0BCFF\),.*?onError = Color\(0xFF690005\)', 
'''    primary = Color(0xFF0F52BA),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E3FF),
    onPrimaryContainer = Color(0xFF001849),
    secondary = Color(0xFF006C5B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF75F8DF),
    onSecondaryContainer = Color(0xFF00201A),
    tertiary = Color(0xFFB84000),
    onTertiary = Color.White,
    background = Color(0xFFFAFAFA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF0F0F0),
    onBackground = Color(0xFF1A1C1E),
    onSurface = Color(0xFF1A1C1E),
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC3C6CF),
    error = Color(0xFFBA1A1A),
    onError = Color.White''', content, flags=re.DOTALL)

# 2. Add Username to ViewModel
vm_addition = '''    private val _userName = MutableStateFlow("")
    val userName = _userName.asStateFlow()

    fun loadUserName(context: android.content.Context) {
        val prefs = context.getSharedPreferences("ChronosPrefs", android.content.Context.MODE_PRIVATE)
        _userName.value = prefs.getString("user_name", "") ?: ""
    }

    fun saveUserName(context: android.content.Context, name: String) {
        val prefs = context.getSharedPreferences("ChronosPrefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("user_name", name).apply()
        _userName.value = name
    }
'''
content = content.replace('val liveTranscript = _liveTranscript.asStateFlow()', 
    'val liveTranscript = _liveTranscript.asStateFlow()\n\n' + vm_addition)

# 3. Add Onboarding Logic to MainAppContainer
main_app_container_start = '''@Composable
fun MainAppContainer() {
    val model: SatoriViewModel = viewModel()
    val currentTab by model.currentTab.collectAsState()
    val isNewTaskOpen by model.isNewTaskSheetOpen.collectAsState()
    val voiceState by model.voiceSessionState.collectAsState()

    val context = LocalContext.current'''

main_app_container_new = '''@Composable
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
        OnboardingScreen(onComplete = { name -> model.saveUserName(context, name) })
        return
    }'''
content = content.replace(main_app_container_start, main_app_container_new)

# 4. Remove Hardcoded UI Names / Replace with userName
content = content.replace('fun HeaderSection(activeCount: Int)', 'fun HeaderSection(activeCount: Int, userName: String)')
content = content.replace('HeaderSection(activeCount = activeCount)', 'HeaderSection(activeCount = activeCount, userName = model.userName.collectAsState().value)')
content = content.replace('Text(\n                text = "Hello, Alex Thorne",', 'Text(\n                text = "Hello, $userName",')
content = content.replace('Text(\n                text = "Alex Thorne",', 'Text(\n                text = userName,')

# 5. Add OnboardingScreen Composable
onboarding_composable = '''
@Composable
fun OnboardingScreen(onComplete: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Welcome to ChronosAI", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(modifier = Modifier.height(16.dp))
        Text("What should I call you?", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = { Text("Your Name") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = { if (name.isNotBlank()) onComplete(name.trim()) },
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text("GET STARTED")
        }
    }
}
'''
content += onboarding_composable

# 6. Change hardcoded Dark Backgrounds to Theme Colors to fix "too dark" issue.
# General replacements for common dark colors.
content = content.replace('Color(0xFF131313)', 'MaterialTheme.colorScheme.background')
content = content.replace('Color(0xFF1E1E1E)', 'MaterialTheme.colorScheme.surface')
content = content.replace('Color(0xFF1C1B1B)', 'MaterialTheme.colorScheme.surface')
content = content.replace('Color(0xFF2C2C2C)', 'MaterialTheme.colorScheme.outlineVariant')
content = content.replace('Color(0xFF282828)', 'MaterialTheme.colorScheme.surfaceVariant')
content = content.replace('Color(0xFF16151A)', 'MaterialTheme.colorScheme.surface')
content = content.replace('Color(0xFF1E1731)', 'MaterialTheme.colorScheme.primaryContainer')
content = content.replace('Color(0xFF261D3A)', 'MaterialTheme.colorScheme.primaryContainer')

# Fix text colors for light theme visibility
content = content.replace('color = Color.White', 'color = MaterialTheme.colorScheme.onSurface')
content = content.replace('color = Color(0xFFAAAAAA)', 'color = MaterialTheme.colorScheme.onSurfaceVariant')
content = content.replace('color = Color(0xFFABABAB)', 'color = MaterialTheme.colorScheme.onSurfaceVariant')
content = content.replace('color = Color(0xFF888888)', 'color = MaterialTheme.colorScheme.onSurfaceVariant')
content = content.replace('color = Color(0xFF8C8C8C)', 'color = MaterialTheme.colorScheme.onSurfaceVariant')
content = content.replace('color = Color(0xFF908C99)', 'color = MaterialTheme.colorScheme.onSurfaceVariant')
content = content.replace('color = Color.Gray', 'color = MaterialTheme.colorScheme.onSurfaceVariant')

# Specific fixes for text colors inside solid primary buttons
content = content.replace('color = MaterialTheme.colorScheme.onSurface\n                        )', 'color = MaterialTheme.colorScheme.onPrimary\n                        )')

with open('app/src/main/java/com/example/MainActivity.kt', 'w') as f:
    f.write(content)
print("UI script completed.")
