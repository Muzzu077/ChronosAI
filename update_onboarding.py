import re

with open('app/src/main/java/com/example/MainActivity.kt', 'r') as f:
    content = f.read()

# 1. Update OnboardingScreen to be multi-step
new_onboarding = '''
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
'''
content = re.sub(r'@Composable\nfun OnboardingScreen.*?}\n(?=$)', new_onboarding, content, flags=re.DOTALL)

# 2. Update ViewModel to handle new onboarding and suggestions
content = content.replace('fun saveUserName(context: android.content.Context, name: String) {', 
'''fun saveOnboarding(context: android.content.Context, name: String, role: String, goal: String) {
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

    fun saveUserName(context: android.content.Context, name: String) {''')

# 3. Update MainAppContainer to call saveOnboarding
content = content.replace('OnboardingScreen(onComplete = { name -> model.saveUserName(context, name) })', 
                         'OnboardingScreen(onComplete = { name, role, goal -> model.saveOnboarding(context, name, role, goal) })')

# 4. Add animated Waveform to AIAssistantScreen
waveform_comp = '''
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
'''
content = content.replace('Text(transcript.ifEmpty { "Listening..." }, style = MaterialTheme.typography.bodyLarge, fontSize = 18.sp)',
    '''AnimatedAudioWaveform()
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(transcript.ifEmpty { "Listening for voice..." }, style = MaterialTheme.typography.bodyLarge, fontSize = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())''')

# Add the component at the end
content += waveform_comp

with open('app/src/main/java/com/example/MainActivity.kt', 'w') as f:
    f.write(content)
