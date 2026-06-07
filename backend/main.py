import os
import uuid
import datetime
from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from livekit.api import AccessToken, VideoGrants
from dotenv import load_dotenv
from pydantic import BaseModel, Field

# Load scheduler startup logic
import db
from scheduler import start_scheduler

load_dotenv()

LIVEKIT_API_KEY = os.getenv("LIVEKIT_API_KEY")
LIVEKIT_API_SECRET = os.getenv("LIVEKIT_API_SECRET")

if not LIVEKIT_API_KEY or not LIVEKIT_API_SECRET:
    raise ValueError("LIVEKIT_API_KEY and LIVEKIT_API_SECRET must be configured in environment variables.")

# Create the FastAPI instance
app = FastAPI(
    title="ChronosAI API Gateway",
    description="Backend Gateway for Chronos representation 'ChronosAI' AI voice planner WebRTC sessions.",
    version="1.0.0"
)

# CORS configuration: Restrict allowed origins for security (can be configured in .env)
ALLOWED_ORIGINS = os.getenv("ALLOWED_ORIGINS", "*").split(",")

app.add_middleware(
    CORSMiddleware,
    allow_origins=ALLOWED_ORIGINS,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


class TaskCreateRequest(BaseModel):
    user_id: uuid.UUID
    task_description: str = Field(..., min_length=1, max_length=500)
    scheduled_time: datetime.datetime


class TaskStatusUpdateRequest(BaseModel):
    user_id: uuid.UUID
    status: str = Field(..., pattern="^(pending|reminded|completed|skipped)$")


@app.on_event("startup")
def on_startup():
    """Startup hook: triggers scheduler to run task polling loops."""
    print("Application Startup: Launching Asynchronous Polling Scheduler...")
    start_scheduler()


@app.get("/")
def read_root():
    return {
        "status": "healthy",
        "service": "ChronosAI Backend Gateway",
        "features": ["WebRTC token engine", "Supabase polling synchronization", "Voice Pipeline scheduler"]
    }


@app.get("/tasks")
async def list_tasks(user_id: uuid.UUID = Query(..., description="ChronosAI user UUID.")):
    try:
        return {"tasks": await db.get_user_tasks(str(user_id))}
    except Exception as e:
        print(f"Error listing tasks for {user_id}: {str(e)}")
        raise HTTPException(status_code=500, detail="Failed to list tasks.")


@app.post("/tasks", status_code=201)
async def create_task(request: TaskCreateRequest):
    try:
        task = await db.insert_task(
            str(request.user_id),
            request.task_description.strip(),
            request.scheduled_time.astimezone(datetime.timezone.utc).isoformat()
        )
        return {"task": task}
    except Exception as e:
        print(f"Error creating task for {request.user_id}: {str(e)}")
        raise HTTPException(status_code=500, detail="Failed to create task.")


@app.patch("/tasks/{task_id}/status")
async def update_task_status(task_id: uuid.UUID, request: TaskStatusUpdateRequest):
    try:
        task = await db.update_task_status(str(request.user_id), str(task_id), request.status)
        if not task:
            raise HTTPException(status_code=404, detail="Task not found.")
        return {"task": task}
    except HTTPException:
        raise
    except Exception as e:
        print(f"Error updating task {task_id}: {str(e)}")
        raise HTTPException(status_code=500, detail="Failed to update task.")


@app.delete("/tasks/{task_id}", status_code=204)
async def delete_task(task_id: uuid.UUID, user_id: uuid.UUID = Query(..., description="ChronosAI user UUID.")):
    try:
        await db.delete_task(str(user_id), str(task_id))
        return None
    except Exception as e:
        print(f"Error deleting task {task_id}: {str(e)}")
        raise HTTPException(status_code=500, detail="Failed to delete task.")


class ProfileUpdateRequest(BaseModel):
    display_name: str = None
    role: str = None
    primary_goal: str = None
    timezone: str = None

class MemorySaveRequest(BaseModel):
    memory_key: str
    memory_value: dict
    confidence: float = 0.9
    source: str = "user"

class ScheduleSaveRequest(BaseModel):
    schedule_date: datetime.date
    summary: str
    blocks: list
    generated_by: str = "planner_agent"

class PrayerFetchRequest(BaseModel):
    city: str
    country: str
    date_str: str = None

class DailyLogUpdateRequest(BaseModel):
    tasks_planned: int = None
    tasks_completed: int = None
    tasks_skipped: int = None
    focus_minutes: int = None
    study_minutes: int = None
    sleep_minutes: int = None
    notes: str = None


@app.get("/users/{user_id}/profile")
async def get_user_profile(user_id: uuid.UUID):
    try:
        profile = await db.get_user_profile(str(user_id))
        return {"profile": profile}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to fetch profile: {str(e)}")

@app.put("/users/{user_id}/profile")
async def update_user_profile(user_id: uuid.UUID, request: ProfileUpdateRequest):
    try:
        data = {k: v for k, v in request.dict().items() if v is not None}
        profile = await db.update_user_profile(str(user_id), data)
        return {"profile": profile}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to update profile: {str(e)}")

@app.get("/users/{user_id}/memory")
async def get_user_memories(user_id: uuid.UUID):
    try:
        memories = await db.get_agent_memories(str(user_id))
        return {"memories": memories}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to fetch memories: {str(e)}")

@app.post("/users/{user_id}/memory")
async def save_user_memory(user_id: uuid.UUID, request: MemorySaveRequest):
    try:
        memory = await db.save_agent_memory(
            str(user_id),
            request.memory_key,
            request.memory_value,
            request.confidence,
            request.source
        )
        return {"memory": memory}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to save memory: {str(e)}")

@app.get("/users/{user_id}/schedules")
async def get_user_schedule(user_id: uuid.UUID, date: datetime.date = Query(..., description="Schedule date in YYYY-MM-DD format.")):
    try:
        schedule = await db.get_user_schedule(str(user_id), str(date))
        return {"schedule": schedule}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to fetch schedule: {str(e)}")

@app.post("/users/{user_id}/schedules")
async def save_user_schedule(user_id: uuid.UUID, request: ScheduleSaveRequest):
    try:
        schedule = await db.save_user_schedule(
            str(user_id),
            str(request.schedule_date),
            request.summary,
            request.blocks,
            request.generated_by
        )
        return {"schedule": schedule}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to save schedule: {str(e)}")

@app.get("/users/{user_id}/prayer-times")
async def get_prayer_times(user_id: uuid.UUID, date: datetime.date = Query(..., description="Date in YYYY-MM-DD format.")):
    try:
        prayers = await db.get_prayer_times(str(user_id), str(date))
        return {"prayer_times": prayers}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to fetch prayer times: {str(e)}")

@app.post("/users/{user_id}/prayer-times/fetch")
async def fetch_and_store_prayer_times(user_id: uuid.UUID, request: PrayerFetchRequest):
    try:
        date_str = request.date_str if request.date_str else datetime.datetime.now().strftime("%Y-%m-%d")
        prayers = await db.fetch_and_store_prayer_times(str(user_id), request.city, request.country, date_str)
        return {"prayer_times": prayers}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to fetch and store prayer times: {str(e)}")

@app.get("/users/{user_id}/productivity")
async def get_productivity_stats(user_id: uuid.UUID, days: int = Query(7, description="Number of days of history to retrieve.")):
    try:
        stats = await db.get_productivity_stats(str(user_id), days)
        return {"stats": stats}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to fetch productivity stats: {str(e)}")

@app.patch("/users/{user_id}/daily-log")
async def update_daily_log(user_id: uuid.UUID, log_date: datetime.date = Query(..., description="Date of the log."), request: DailyLogUpdateRequest = None):
    try:
        updates = {k: v for k, v in request.dict().items() if v is not None}
        log = await db.update_daily_log(str(user_id), str(log_date), updates)
        return {"log": log}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to update daily log: {str(e)}")


@app.get("/get-listen-token")
def get_listen_token(user_id: str = Query(..., description="The Unique ID (UUID) of the client user to authenticate room partition.")):
    """5.4 Token REST Gateway endpoint: Validates user_id, generates a scoped LiveKit connection JWT."""
    # Simple validation of user_id to ensure it's not empty/malformed
    if not user_id or len(user_id.strip()) < 3:
        raise HTTPException(
            status_code=400,
            detail="Invalid user_id parameter. Must be a valid unique string identifiers."
        )
        
    try:
        room_name = f"room-{user_id.strip()}"
        identity = f"user-{user_id.strip()}"
        
        # Instantiate a LiveKit AccessToken
        # Grant capabilities: join room, subscribe to feeds, publish micro media
        token = AccessToken(
            api_key=LIVEKIT_API_KEY,
            api_secret=LIVEKIT_API_SECRET
        )
        
        grants = VideoGrants(
            room_join=True,
            room=room_name,
            # Permissions necessary for high fidelity voice interaction:
            can_publish=True,
            can_subscribe=True,
            can_publish_data=True
        )
        
        token.with_grants(grants)
        token.with_identity(identity)
        token.with_name(f"Participant {user_id[:8]}")
        
        signed_jwt = token.to_jwt()
        
        return {
            "token": signed_jwt,
            "room_name": room_name,
            "identity": identity,
            "server_url": os.getenv("LIVEKIT_URL", "wss://voice-call-aelv823z.livekit.cloud")
        }
        
    except Exception as e:
        print(f"Error generating AccessToken: {str(e)}")
        raise HTTPException(
            status_code=500,
            detail=f"AccessToken creation failed: {str(e)}"
        )


if __name__ == "__main__":
    import uvicorn
    # In standard development, run fastapi gateway on port 8080
    uvicorn.run("main:app", host="0.0.0.0", port=8080, reload=True)
