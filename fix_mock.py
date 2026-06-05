import re
with open('app/src/main/java/com/example/MainActivity.kt', 'r') as f:
    content = f.read()

# Remove mock dialog data
content = content.replace('var taskName by remember { mutableStateOf("Synthesize strategy brief") }', 
                          'var taskName by remember { mutableStateOf("") }')
content = content.replace('var taskTime by remember { mutableStateOf("02:30") }', 
                          'var taskTime by remember { mutableStateOf("") }')
content = content.replace('var desc by remember { mutableStateOf("Coordinate with the product design team for final handoff.") }', 
                          'var desc by remember { mutableStateOf("") }')

with open('app/src/main/java/com/example/MainActivity.kt', 'w') as f:
    f.write(content)
print("Mock data removed.")
