import os
import datetime
import asyncio
import sqlite3
from dotenv import load_dotenv
from apscheduler.schedulers.asyncio import AsyncIOScheduler
from livekit.api import LiveKitAPI, CreateRoomRequest, SendDataRequest
import db

load_dotenv()

LIVEKIT_URL = (os.getenv("LIVEKIT_URL") or "").strip()
LIVEKIT_API_KEY = (os.getenv("LIVEKIT_API_KEY") or "").strip()
LIVEKIT_API_SECRET = (os.getenv("LIVEKIT_API_SECRET") or "").strip()


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
                priority = task.get("priority", "MEDIUM")
                
                print(f"Scheduler: Processing task '{task_description}' (ID: {task_id}, Priority: {priority}) for user room: room-{user_id}")
                
                room_name = f"room-{user_id}"
                dispatch_success = False
                api = None
                try:
                    print(f"Scheduler: Triggering RoomServiceClient to ensure room: {room_name}")
                    api = LiveKitAPI(
                        url=LIVEKIT_URL,
                        api_key=LIVEKIT_API_KEY,
                        api_secret=LIVEKIT_API_SECRET
                    )
                    await api.room.create_room(CreateRoomRequest(
                        name=room_name,
                        empty_timeout=600,
                        max_participants=2
                    ))
                    
                    reminder_msg = f"SYSTEM_REMINDER: {task_id} | {priority} | {task_description}"
                    await api.room.send_data(SendDataRequest(
                        room=room_name,
                        data=reminder_msg.encode('utf-8'),
                        kind=1 # RELIABLE
                    ))
                    dispatch_success = True
                    print(f"Scheduler: Success creating/establishing livekit room: {room_name}")
                    
                except Exception as e:
                    print(f"Scheduler: LiveKitAPI room connection error for {room_name}: {str(e)}")
                finally:
                    if api:
                        try:
                            await api.aclose()
                        except Exception:
                            pass
                    
                if dispatch_success:
                    await db.mark_task_reminded(task_id)
                    print(f"Scheduler: Completed processing task ID: {task_id}. Marked as 'reminded' in local tracker.")
                else:
                    print(f"Scheduler: Skipping mark_reminded for task {task_id} — dispatch failed, will retry next cycle.")
        else:
            print("Scheduler: No pending tasks found at this interval.")

        # --- DYNAMIC LIFE TEMPLATE CHECKPOINT LOOP ---
        try:
            conn = sqlite3.connect(db.DB_FILE)
            conn.execute("PRAGMA busy_timeout=5000")
            cursor = conn.cursor()
            cursor.execute("SELECT id FROM users")
            user_ids = [row[0] for row in cursor.fetchall()]
            conn.close()
            
            cp_title_map = {
                "MORNING_STANDUP": "Checkpoint: Morning Standup",
                "DEEP_WORK_START": "Checkpoint: Deep Work Start",
                "ACCOUNTABILITY_CHECK": "Checkpoint: Accountability Check",
                "DAY_REVIEW": "Checkpoint: Day Review"
            }
            
            for uid in user_ids:
                profile = await db.get_user_profile(uid)
                tz_str = profile.get("timezone") or "Asia/Kolkata"
                try:
                    from zoneinfo import ZoneInfo
                    tz = ZoneInfo(tz_str)
                except Exception:
                    tz = datetime.timezone(datetime.timedelta(hours=5, minutes=30))
                    
                local_date_str = datetime.datetime.now(tz).strftime("%Y-%m-%d")
                checkpoints = await db.get_daily_checkpoints_utc(uid, local_date_str)
                
                # Fetch existing tasks to check if checkpoints are already inserted
                try:
                    user_tasks = await db.get_user_tasks(uid)
                except Exception as t_err:
                    print(f"Scheduler error getting tasks for {uid}: {t_err}")
                    user_tasks = []
                
                for cp in checkpoints:
                    cp_type = cp["checkpoint_type"]
                    cp_time_str = cp["utc_time"]
                    cp_title = cp_title_map.get(cp_type, f"Checkpoint: {cp_type}")
                    
                    # Check if this checkpoint task already exists for today
                    exists = any(
                        t.get("task_description") == cp_title and 
                        t.get("scheduled_time", "")[:10] == cp_time_str[:10]
                        for t in user_tasks
                    )
                    
                    if not exists:
                        print(f"Scheduler: Auto-populating checkpoint task '{cp_title}' at {cp_time_str} for user {uid}")
                        try:
                            task = await db.insert_task(uid, cp_title, cp_time_str)
                            task_id = task.get("id")
                            if task_id:
                                await db.set_task_metadata(task_id, domain="Personal", priority="HIGH")
                        except Exception as ins_err:
                            print(f"Scheduler failed to insert checkpoint task: {ins_err}")
                
                for cp in checkpoints:
                    cp_type = cp["checkpoint_type"]
                    cp_time_str = cp["utc_time"]
                    
                    cp_dt = datetime.datetime.fromisoformat(cp_time_str.replace("Z", "+00:00"))
                    if cp_dt <= now_utc and (now_utc - cp_dt).total_seconds() < 300:
                        cp_id = f"checkpoint_{uid}_{cp_type}_{local_date_str}"
                        
                        status = None
                        try:
                            conn_check = sqlite3.connect(db.DB_FILE)
                            cursor_check = conn_check.cursor()
                            cursor_check.execute("SELECT status FROM task_status_tracker WHERE task_id = ?", (cp_id,))
                            row = cursor_check.fetchone()
                            status = row[0] if row else None
                            conn_check.close()
                        except Exception as check_ex:
                            print(f"Error checking checkpoint status for {cp_id}: {check_ex}")
                            
                        if status != "reminded":
                            print(f"Scheduler: Triggering Checkpoint Call for user: {uid}, Type: {cp_type} (ID: {cp_id})")
                            room_name = f"room-{uid}"
                            api = None
                            try:
                                api = LiveKitAPI(
                                    url=LIVEKIT_URL,
                                    api_key=LIVEKIT_API_KEY,
                                    api_secret=LIVEKIT_API_SECRET
                                )
                                await api.room.create_room(CreateRoomRequest(
                                    name=room_name,
                                    empty_timeout=600,
                                    max_participants=2
                                ))
                                
                                checkpoint_payload = f"SYSTEM_REMINDER: {cp_id} | HIGH | Checkpoint: {cp_type}"
                                await api.room.send_data(SendDataRequest(
                                    room=room_name,
                                    data=checkpoint_payload.encode('utf-8'),
                                    kind=1
                                ))
                                
                                conn_upd = sqlite3.connect(db.DB_FILE)
                                conn_upd.execute("PRAGMA busy_timeout=5000")
                                cursor_upd = conn_upd.cursor()
                                now_str = datetime.datetime.now(datetime.timezone.utc).isoformat()
                                cursor_upd.execute("""
                                INSERT INTO task_status_tracker (task_id, status, updated_at)
                                VALUES (?, 'reminded', ?)
                                ON CONFLICT(task_id) DO UPDATE SET status = 'reminded', updated_at = ?
                                """, (cp_id, now_str, now_str))
                                conn_upd.commit()
                                conn_upd.close()
                                print(f"Scheduler: Checkpoint {cp_id} dispatched and marked reminded.")
                            except Exception as e:
                                print(f"Scheduler: Checkpoint dispatch error: {e}")
                            finally:
                                if api:
                                    try:
                                        await api.aclose()
                                    except Exception:
                                        pass
        except Exception as outer_ex:
            print(f"Scheduler error in checkpoint poll cycle: {outer_ex}")

        # --- ACCOUNTABILITY LOOP ---
        # Look for tasks that are still not resolved
        check_time_5m = (now_utc - datetime.timedelta(minutes=5)).isoformat()
        check_time_1m = (now_utc - datetime.timedelta(minutes=1)).isoformat()
        accountability_tasks = await db.get_accountability_candidates(check_time_5m, check_time_1m)
        
        if accountability_tasks:
            print(f"Scheduler: Found {len(accountability_tasks)} accountability candidates.")
            for task in accountability_tasks:
                task_id = task.get("id")
                user_id = task.get("user_id")
                task_description = task.get("task_description")
                priority = task.get("priority", "MEDIUM")
                
                room_name = f"room-{user_id}"
                api = None
                try:
                    api = LiveKitAPI(
                        url=LIVEKIT_URL,
                        api_key=LIVEKIT_API_KEY,
                        api_secret=LIVEKIT_API_SECRET
                    )
                    await api.room.create_room(CreateRoomRequest(
                        name=room_name,
                        empty_timeout=600,
                        max_participants=2
                    ))
                    
                    accountability_msg = f"SYSTEM_ACCOUNTABILITY: {task_id} | {priority} | {task_description}"
                    await api.room.send_data(SendDataRequest(
                        room=room_name,
                        data=accountability_msg.encode('utf-8'),
                        kind=1 # RELIABLE
                    ))
                    print(f"Scheduler: Dispatched accountability query for '{task_description}'")
                except Exception as e:
                    print(f"Scheduler: Accountability dispatch failed: {str(e)}")
                finally:
                    if api:
                        try:
                            await api.aclose()
                        except Exception:
                            pass
                    
                # For CRITICAL tasks, keep 'reminded' status but bump timestamp to trigger again in 1 min
                if priority == "CRITICAL":
                    await db.mark_task_reminded(task_id)
                else:
                    await db.update_task_status(user_id, task_id, "accounted")
                
    except Exception as e:
        print(f"Scheduler error in poll cycle: {str(e)}")

def start_scheduler():
    """Initializes and registers the 60-second AsyncIO task poller."""
    scheduler.add_job(poll_scheduled_tasks, "interval", seconds=60, next_run_time=datetime.datetime.now())
    scheduler.start()
    print("Scheduler: Started AsyncIOScheduler successfully. Polling every 60 seconds.")

