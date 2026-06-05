# ChronosAI V3

ChronosAI is an AI personal operating system for students, developers, and professionals. The current implementation keeps the existing Android/Kotlin app and FastAPI backend, with Android calling FastAPI for all backend data access.

## Architecture

```text
Android App
  -> FastAPI Gateway
      -> Supabase PostgreSQL
      -> LiveKit Voice Rooms
      -> Planner / Reminder / Coach / Prayer agents
```

Android must not store Supabase keys, OpenAI keys, LiveKit secrets, or service-role credentials. Put secrets in `backend/.env` only.

## Current Phase

Phase 1 foundation is implemented:

- FastAPI task CRUD endpoints.
- Android task persistence through FastAPI instead of Supabase.
- LiveKit token generation through FastAPI.
- Scheduler reminder status no longer marks tasks completed automatically.
- V3 database foundation tables for schedules, habits, reminders, prayer times, daily logs, and agent memory.

## Backend Setup

```bash
cd backend
python3.12 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp ../.env.example .env
```

Fill `backend/.env` with backend-only secrets:

```env
LIVEKIT_URL=
LIVEKIT_API_KEY=
LIVEKIT_API_SECRET=
SUPABASE_URL=
SUPABASE_KEY=
OPENROUTER_API_KEY=
OPENROUTER_MODEL=poolside/laguna-m1
```

Run the API gateway:

```bash
uvicorn main:app --host 0.0.0.0 --port 8080 --reload
```

Run the LiveKit voice worker separately when voice agent testing is needed:

```bash
python agent.py dev
```

## Android Setup

Open the project in Android Studio and run the `app` configuration.

The app uses `http://10.0.2.2:8080` for emulator access to the FastAPI backend. For a physical device, run:

```bash
adb reverse tcp:8080 tcp:8080
```

Then change the base URL in `app/src/main/java/com/example/android_integration/ApiClient.kt` to `http://localhost:8080`, or expose the backend on your LAN and use that host.

## Database

Apply `backend/schema.sql` in Supabase SQL editor. The existing `daily_tasks` table is preserved for app compatibility, and V3 tables are added for future agent memory, analytics, prayer-aware scheduling, reminders, and habit learning.

If a Supabase service-role key was previously committed into Android source, rotate it in Supabase before using this project.
