# ChronosAI

ChronosAI is an advanced AI Personal Operating System, voice-controlled daily planner, and behavioral coaching assistant built for students, developers, and professionals. 

It integrates a high-fidelity **Android application** with a **FastAPI gateway** and a **LiveKit-driven real-time voice pipeline**, powered by state-of-the-art serverless LLMs.

---

## 🛠️ Key Capabilities & Features

### 1. AI Voice Pipeline & Dialog
* **Real-time WebRTC Call:** Low-latency bidirectional voice call powered by **LiveKit Cloud**.
* **Indian English Voice Synthesizer:** Integrates Microsoft **`edge-tts`** locally to synthesize warm, natural Indian English voices (`en-IN-NeerjaNeural` / `en-IN-PrabhatNeural`) completely free of cost.
* **Speech-to-Text (STT):** High-speed Whisper Large V3 Turbo transcription.
* **OpenAI-Compatible Tool LLM:** Powered by **`Qwen/Qwen2.5-72B-Instruct`** on Hugging Face Serverless, enabling autonomous function calling (creating tasks, scheduling routines, editing memory) during call sessions.

### 2. Intelligent Agent Engines
* **Adaptive Scheduling Engine:** Analyzes your historical task completion rates per hour (Focus Windows) and maps your daily priorities to your peak productivity hours.
* **Weekly Prep Planner:** Breaks down a long-term goal/exam deadline (e.g. "Exam in 14 days") into proportional preparation milestones across 1-4 weeks, inserting them directly into your schedule.
* **Emotional Workload Adjustment:** Responds to user fatigue (e.g. "exhausted", "tired") by postponing non-critical tasks to tomorrow and protecting sleep windows.
* **Accountability Engine:** Triggers voice follow-up sessions for expired tasks, asking you if you finished them and recording completions, skips, or reschedules.

---

## 🏗️ Architecture

```text
       ┌─────────────────────────────────────────────────────────┐
       │                   Android Client App                    │
       │   (Jetpack Compose Dashboard, Foreground Voice Service) │
       └────────────────────────────┬────────────────────────────┘
                                    │ Http REST / WebSockets
                                    ▼
       ┌─────────────────────────────────────────────────────────┐
       │                  FastAPI Backend Gateway                │
       │             (Token Vending, Scheduler Poller)           │
       └───────┬────────────────────┬────────────────────┬───────┘
               │                    │                    │
               ▼                    ▼                    ▼
     ┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐
     │  Supabase Cloud  │ │  Local SQLite DB │ │  LiveKit Cloud   │
     │  (Shared Tasks)  │ │ (Focus, Memory)  │ │  (WebRTC Rooms)  │
     └──────────────────┘ └──────────────────┘ └──────────────────┘
```

### Database Model
* **Supabase Cloud PostgreSQL:** Stores user tasks and profile settings in real-time, allowing direct synchronization with the Android front-end checklist.
* **Local SQLite (`chronosai.db`):** Maintained on the backend to store routine templates, focus window scores, goal milestones, and user facts/memory with high-speed WAL concurrency.

---

## 📂 Project Structure

```text
ChronosAI/
├── app/                  # Android Kotlin source code
│   └── src/main/java/com/example/
│       ├── MainActivity.kt               # Jetpack Compose UI & State VM
│       └── android_integration/
│           ├── ApiClient.kt              # HTTP REST Gateway client
│           └── VoiceReceiverService.kt   # Foreground WebRTC & TTS service
├── backend/              # FastAPI Gateway & LiveKit Agent
│   ├── agent.py          # LiveKit Voice Agent pipeline & LLM tools
│   ├── main.py           # FastAPI Web app & WS connection server
│   ├── scheduler.py      # Background task checker & poller
│   ├── db.py             # SQLite/Supabase database facade
│   ├── Dockerfile        # Container build schema
│   ├── requirements.txt  # Python requirements
│   └── repositories/     # SQLite table CRUD queries
└── README.md             # Project documentation
```

---

## ⚙️ Backend Installation & Setup

### 1. Install Dependencies
Ensure you have Python 3.11+ installed. Run the following inside the `backend/` directory:
```bash
cd backend
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```

### 2. Configure Environment Variables
Create a `backend/.env` file with your credentials:
```env
# LiveKit WebRTC credentials
LIVEKIT_URL=wss://voice-call-xxxxxx.livekit.cloud
LIVEKIT_API_KEY=APIxxxxxx
LIVEKIT_API_SECRET=sMxxxxxx

# Supabase database config
SUPABASE_URL=https://xxxxxx.supabase.co
SUPABASE_KEY=eyJxxxxxx (Service role key for admin access)

# AI LLM Access
HF_TOKEN=hf_xxxxxx (Hugging Face user key)
```

### 3. Run Locally
Start the FastAPI server:
```bash
python main.py
```
This runs the HTTP gateway on `http://localhost:8080`.

In a separate terminal tab, run the LiveKit agent worker process:
```bash
python agent.py dev
```

---

## 📱 Android Client Run Configuration

1. Open the root directory in **Android Studio**.
2. Run the `app` configuration on a physical Android device or emulator.
3. **Physical Device Port Redirection:** If testing against a local backend, connect your device via USB (with USB debugging enabled) and run:
   ```bash
   adb reverse tcp:8080 tcp:8080
   ```
4. **Production Server Routing:** In [ApiClient.kt](file:///home/muzzu/Downloads/ChronosAI/app/src/main/java/com/example/android_integration/ApiClient.kt#L36), configure `CLOUD_URL` to point to your deployed Hugging Face Space URL.

---

## 🚀 Deploying to Hugging Face Spaces

1. Create a new **Docker Space** on Hugging Face.
2. In the Space settings, add your credentials as secret variables:
   * `HF_TOKEN`, `LIVEKIT_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`, `SUPABASE_URL`, `SUPABASE_KEY`.
3. Add the Hugging Face git remote and push the `backend` folder contents:
   ```bash
   cd backend
   git push hf main
   ```
4. **Uptime Keep-Alive (Crucial):** Free Hugging Face Spaces go to sleep after 48 hours of inactivity. To ensure that your background scheduler poller and voice agent are persistently online, configure a free keep-alive monitor (such as UptimeRobot or CronJob.org) to ping your Space URL `/` endpoint once every 30 minutes.

---

## 🔒 Security Hardening

* **API Rate Limiting:** Enforces an in-memory IP rate limiter limiting connections to **100 requests per 60 seconds** per host.
* **Token TTL Protection:** LiveKit Access Tokens vend with an explicit **15-minute expiration** window.
* **Parameterized Queries:** SQLite calls use parameterized SQL bindings to completely prevent injection vulnerabilities.
