import os
import datetime
import asyncio
from dotenv import load_dotenv
from apscheduler.schedulers.asyncio import AsyncIOScheduler
from livekit.api import LiveKitAPI
import db

load_dotenv()

LIVEKIT_URL = os.getenv("LIVEKIT_URL")
LIVEKIT_API_KEY = os.getenv("LIVEKIT_API_KEY")
LIVEKIT_API_SECRET = os.getenv("LIVEKIT_API_SECRET")

scheduler = AsyncIOScheduler()

async def poll_scheduled_tasks():
    """5.3 The Asynchronous Polling Engine: Runs every 60 seconds.
    Queries pending tasks, dispatches WebRTC AI notifications, marks tasks as completed,
    and runs accountability checks on tasks that have expired."""
    now_utc = datetime.datetime.now(datetime.timezone.utc)
    now_iso = now_utc.isoformat()
    
    print(f"[{now_iso}] Scheduler: Polling daily_tasks for pending items near or before {now_iso}")
    
    try:
        # Get pending tasks that are due
        pending_tasks = await db.get_pending_tasks(now_iso)
        
        if pending_tasks:
            print(f"Scheduler: Found {len(pending_tasks)} pending task(s) to dispatch!")
            for task in pending_tasks:
                task_id = task.get("id")
                user_id = task.get("user_id")
                task_description = task.get("task_description")
                
                print(f"Scheduler: Processing task '{task_description}' (ID: {task_id}) for user room: room-{user_id}")
                
                room_name = f"room-{user_id}"
                try:
                    print(f"Scheduler: Triggering RoomServiceClient to ensure room: {room_name}")
                    api = LiveKitAPI(
                        url=LIVEKIT_URL,
                        api_key=LIVEKIT_API_KEY,
                        api_secret=LIVEKIT_API_SECRET
                    )
                    await api.room.create_room(
                        name=room_name,
                        empty_timeout=600,
                        max_participants=2
                    )
                    
                    # Dispatch the reminder to the agent listening in the room via data channel
                    import json
                    reminder_msg = f"SYSTEM_REMINDER: {task_description}"
                    from livekit.api import SendDataRequest
                    await api.room.send_data(SendDataRequest(
                        room=room_name,
                        data=reminder_msg.encode('utf-8'),
                        kind=1 # RELIABLE
                    ))
                    await api.aclose()
                    print(f"Scheduler: Success creating/establishing livekit room: {room_name}")
                    
                except Exception as e:
                    print(f"Scheduler: LiveKitAPI room connection error for {room_name}: {str(e)}")
                    
                await db.mark_task_reminded(task_id)
                print(f"Scheduler: Completed processing task ID: {task_id}. Marked as 'reminded' in Supabase.")
        else:
            print("Scheduler: No pending tasks found at this interval.")
            
        # --- ACCOUNTABILITY LOOP ---
        # Look for tasks that were reminded at least 5 minutes ago and are still not resolved
        check_time_iso = (now_utc - datetime.timedelta(minutes=5)).isoformat()
        accountability_tasks = await db.get_accountability_candidates(check_time_iso)
        
        if accountability_tasks:
            print(f"Scheduler: Found {len(accountability_tasks)} accountability candidates.")
            for task in accountability_tasks:
                task_id = task.get("id")
                user_id = task.get("user_id")
                task_description = task.get("task_description")
                
                room_name = f"room-{user_id}"
                try:
                    api = LiveKitAPI(
                        url=LIVEKIT_URL,
                        api_key=LIVEKIT_API_KEY,
                        api_secret=LIVEKIT_API_SECRET
                    )
                    await api.room.create_room(
                        name=room_name,
                        empty_timeout=600,
                        max_participants=2
                    )
                    
                    accountability_msg = f"SYSTEM_ACCOUNTABILITY: {task_description}"
                    from livekit.api import SendDataRequest
                    await api.room.send_data(SendDataRequest(
                        room=room_name,
                        data=accountability_msg.encode('utf-8'),
                        kind=1 # RELIABLE
                    ))
                    await api.aclose()
                    print(f"Scheduler: Dispatched accountability query for '{task_description}'")
                except Exception as e:
                    print(f"Scheduler: Accountability dispatch failed: {str(e)}")
                    
                # Mark as accounted so we don't ask again next minute
                await db.update_task_status(user_id, task_id, "accounted")
                
    except Exception as e:
        print(f"Scheduler error in poll cycle: {str(e)}")

def start_scheduler():
    """Initializes and registers the 60-second AsyncIO task poller."""
    scheduler.add_job(poll_scheduled_tasks, "interval", seconds=60, next_run_time=datetime.datetime.now())
    scheduler.start()
    print("Scheduler: Started AsyncIOScheduler successfully. Polling every 60 seconds.")

