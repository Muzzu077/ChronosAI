import re
with open('app/src/main/java/com/example/MainActivity.kt', 'r') as f:
    content = f.read()

# Fix ProfileScreen to be dynamic and professional
profile_screen = '''
@Composable
fun ProfileScreen(userName: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(40.dp))
        
        Box(
            modifier = Modifier.size(100.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (userName.isNotEmpty()) userName.take(1).uppercase() else "C",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(text = userName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(text = "ChronosAI Power User", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        
        Spacer(modifier = Modifier.height(40.dp))
        
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Professional Settings Rows
        ProfileSettingRow("Daily Reminders", true)
        ProfileSettingRow("Voice Synthesis", true)
        ProfileSettingRow("Advanced Scheduling", false)
        
        Spacer(modifier = Modifier.weight(1f))
        
        Text(text = "ChronosAI v1.0.0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun ProfileSettingRow(label: String, initialValue: Boolean) {
    var checked by remember { mutableStateOf(initialValue) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = { checked = it })
    }
}
'''

# Find the old ProfileScreen and replace it
content = re.sub(r'@Composable\nfun ProfileScreen\(userName: String\).*?@Composable\nfun SettingsSwitchRow', profile_screen + '\n@Composable\nfun SettingsSwitchRow', content, flags=re.DOTALL)

with open('app/src/main/java/com/example/MainActivity.kt', 'w') as f:
    f.write(content)
print("Profile overhaul applied.")
