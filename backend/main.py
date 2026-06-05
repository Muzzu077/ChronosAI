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
    description="Backend Gateway for Satori representation 'ChronosAI' AI voice planner WebRTC sessions.",
    version="1.0.0"
)

# 5.4 Allow CORS ["*"]
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
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
