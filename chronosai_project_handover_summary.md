# ChronosAI Project Handover Summary

This document serves as a complete project context and developer handover guide for **ChronosAI**. It compiles all architectural details, recent updates, system configurations, resolved issues, and next steps to ensure another AI coding agent can seamlessly pick up and continue the work.

---

## 1. Project Overview & Tech Stack
ChronosAI is an **AI Voice Assistant + Productivity OS** designed to help users structure their routines (using a Life Template Engine), stay accountable via voice calls, and manage schedules under strict timezone constraints.

### Architecture Map
* **Android Frontend**: Native Android app built in Kotlin, Jetpack Compose, and LiveKit Android SDK.
* **FastAPI Backend**: Located in the `backend/` directory, exposing REST endpoints for task, profile, and session management.
* **Database (Hybrid)**:
  * **Supabase**: Remote PostgreSQL database storing tasks (`daily_tasks`), schedules, and profiles.
  * **SQLite (`backend/chronosai.db`)**: Local database storing behavioral metadata (`task_metadata`), task statuses (`task_status_tracker`), and templates (`user_life_templates`).
* **LiveKit Voice Infrastructure**: WebRTC audio and data channel gateway running a python worker (`agent.py`) that uses OpenRouter (GPT-4o-mini) to talk to the user.

---

## 2. Key Developer Credentials & Configuration
All environment variables are stored in `backend/.env`.

* **Active User UUID**: `293dafd6-72d4-4dc9-a668-4ba8f8586ca7`
* **Default Timezone**: `Asia/Kolkata` (Indian Standard Time)
* **Supabase URL**: `https://bobdgiankoywtyysxtnl.supabase.co`
* **LiveKit WebSocket URL**: `wss://voice-call-aelv823z.livekit.cloud`
* **SQLite Database Path**: `backend/chronosai.db`

---

## 3. Important Source Files
* **[MainActivity.kt](file:///home/muzzu/Downloads/ChronosAI/app/src/main/java/com/example/MainActivity.kt)**: Core UI container, task scheduling dialogs, and the local reminder checking polling loop.
* **[VoiceReceiverService.kt](file:///home/muzzu/Downloads/ChronosAI/app/src/main/java/com/example/android_integration/VoiceReceiverService.kt)**: Foreground service that manages the WebRTC socket, muting start/stop beeps, and TTS playback.
* **[ApiClient.kt](file:///home/muzzu/Downloads/ChronosAI/app/src/main/java/com/example/android_integration/ApiClient.kt)**: Network layer routing Android HTTP requests to the FastAPI backend.
* **[main.py](file:///home/muzzu/Downloads/ChronosAI/backend/main.py)**: FastAPI entrypoint hosting endpoints like `/tasks`, `/get-listen-token`.
* **[scheduler.py](file:///home/muzzu/Downloads/ChronosAI/backend/scheduler.py)**: Backend AsyncIO task scheduler polling for due tasks and triggering LiveKit room events.
* **[agent.py](file:///home/muzzu/Downloads/ChronosAI/backend/agent.py)**: Voice worker agent implementing the conversation LLM flow, tool calling (reschedule, complete), and `SYSTEM_REMINDER` payload logic.

---

## 4. Completed Work & Resolved Issues

### A. Fix for the Reminder Call Bug (Critical)
* **The Problem**: When the user scheduled a custom reminder (e.g. "Drink water in 2 minutes"), it appeared on the list but they never received the reminder call.
* **The Cause**: The local reminder checking loop in the Android app (`checkPendingReminders` in `MainActivity.kt`) had a filter checking `&& isCheckpoint`. It would only trigger incoming call overlays for checkpoints (Morning Standup, Day Review, etc.) and completely ignored normal user tasks.
* **The Solution**: Edited `MainActivity.kt` to remove the `isCheckpoint` restriction. Now, **any** pending task (whether a checkpoint or user-initiated reminder) will trigger the incoming call screen and ring tone when the current time matches the scheduled time.

### B. Muting SpeechRecognizer Start/Stop Beeps
* **The Problem**: A Google Assistant-like mic beep was constantly sounding during speech input, interrupting natural conversation.
* **The Solution**: Modified `VoiceReceiverService.kt` to temporarily mute `AudioManager.STREAM_SYSTEM` immediately before calling `startListening()`, and unmute it once speech recognition has successfully started or stopped.

### C. Clean UI & Mock Data Removal
* **Visual Filters**: Modified `MainActivity.kt` to filter out any tasks starting with `"Checkpoint:"` or `"SYSTEM_"` from the main Visual Schedule screen. Checkpoints are kept in the database for backend scheduling but hidden from the user's dashboard to keep the view clean.
* **Card Purges**: Removed the "MOCK VOICE TEST" card from the home layout and deleted mock onboarding tasks.
* **Database Clean Start**: Created and executed `backend/clear_all_tasks.py` to purge all existing mock tasks in both the Supabase `daily_tasks` table and local SQLite `task_status_tracker` / `task_metadata` tables. The user's visual schedule is now fully fresh and ready.

### D. Timezone & Voice Naturalness
* **Timezone**: Tasks are parsed and displayed using the device's system timezone and sent to Supabase in UTC. LLM prompts ensure that dates/offsets requested by the user are resolved using the `Asia/Kolkata` zone.
* **TTS Pacing**: Speed rate in `VoiceReceiverService` was adjusted to `1.05f` and set to use local English neural voices for high naturalness.

### E. Speakerphone Manual Toggle (Speaker Option)
* **The Problem**: The user requested the ability to toggle speakerphone output during active call sessions (both scheduled reminder calls and manual assistant sessions).
* **The Solution**:
  * Exposed `ACTION_TOGGLE_SPEAKER` and `EXTRA_SPEAKER_ON` in `VoiceReceiverService.kt` to update the speakerphone state of `AudioManager` programmatically.
  * Added `isSpeakerphoneOn` state flow and `toggleSpeakerphone(context)` in `ChronosViewModel`.
  * Rendered a FloatingActionButton with the `Icons.Default.VolumeUp` icon next to the active call controls in both `ActiveCallOverlay` (reminder calls) and `AIAssistantScreen` (manual voice chat), enabling easy manual speaker toggle during active calls.

---

## 5. Running & Testing Instructions

### A. Start the Backend API
Run this command from the `backend/` directory:
```bash
venv/bin/python main.py
```
This runs the gateway on `http://127.0.0.1:8080`.

### B. Start the LiveKit Agent Worker
Run this command from the `backend/` directory:
```bash
venv/bin/python -u agent.py dev
```

### C. Deploy & Route Network to Android Device
1. Connect the Android device over USB.
2. Setup port forwarding so the device can access the local server:
   ```bash
   adb reverse tcp:8080 tcp:8080
   ```
3. Build and install the app debug APK:
   ```bash
   ./gradlew installDebug
   ```

---

## 6. Next Steps for Continued Development
The next developer agent should work on the following:
1. **User Verification**: Ensure that the user's device successfully receives the reminder call for custom tasks (like "Drink water in 2 minutes") and that the mic beep remains silent.
2. **Weekly planning goals distribution**: Build/extend the `planner_agent` in the backend to automatically map weekly goals into available slots within the user's preferred Life Template blocks (like Deep Work).
3. **Background Services resilience**: Check if the foreground service needs a background alarm wake-lock to support triggering calls even when the screen is locked or the app is killed.
