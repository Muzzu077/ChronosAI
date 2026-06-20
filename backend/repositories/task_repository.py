import asyncio
import sqlite3
import datetime
from repositories.db_config import supabase_client, DB_FILE

def _sync_insert_task(user_id: str, task_description: str, scheduled_time: str) -> dict:
    data = {
        "user_id": user_id,
        "task_description": task_description,
        "scheduled_time": scheduled_time,
        "status": "pending"
    }
    response = supabase_client.table("daily_tasks").insert(data).execute()
    if hasattr(response, "data"):
        return response.data[0] if response.data else {}
    return response if response else {}

async def insert_task(user_id: str, task_description: str, scheduled_time_iso: str) -> dict:
    return await asyncio.to_thread(_sync_insert_task, user_id, task_description, scheduled_time_iso)


def _sync_get_user_tasks(user_id: str) -> list:
    response = supabase_client.table("daily_tasks") \
        .select("*") \
        .eq("user_id", user_id) \
        .order("scheduled_time") \
        .execute()
    tasks = response.data if hasattr(response, "data") else (response if isinstance(response, list) else [])
    return _sync_merge_tasks_metadata(tasks)

async def get_user_tasks(user_id: str) -> list:
    return await asyncio.to_thread(_sync_get_user_tasks, user_id)


def _sync_get_pending_tasks(current_time: str) -> list:
    response = supabase_client.table("daily_tasks") \
        .select("*") \
        .eq("status", "pending") \
        .lte("scheduled_time", current_time) \
        .execute()
    tasks = response.data if hasattr(response, "data") else []
    
    try:
        conn = sqlite3.connect(DB_FILE)
        cursor = conn.cursor()
        cursor.execute("SELECT task_id, status FROM task_status_tracker")
        tracked = {row[0]: row[1] for row in cursor.fetchall()}
        conn.close()
    except Exception as e:
        print(f"SQLite pending tasks filter error: {e}")
        tracked = {}
        
    filtered = []
    for t in tasks:
        tid = t.get("id")
        status_local = tracked.get(tid)
        if status_local in ("reminded", "accounted", "completed", "skipped"):
            continue
        filtered.append(t)
    return _sync_merge_tasks_metadata(filtered)

async def get_pending_tasks(current_time_iso: str) -> list:
    return await asyncio.to_thread(_sync_get_pending_tasks, current_time_iso)


def _sync_mark_task_completed(task_id: str) -> dict:
    task_data = {}
    try:
        res = supabase_client.table("daily_tasks").select("*").eq("id", task_id).execute()
        if hasattr(res, "data") and res.data:
            task_data = res.data[0]
    except Exception as e:
        print(f"Fetch task error before mark completed: {e}")

    response = supabase_client.table("daily_tasks") \
        .update({"status": "completed"}) \
        .eq("id", task_id) \
        .execute()
        
    try:
        conn = sqlite3.connect(DB_FILE)
        cursor = conn.cursor()
        now_str = datetime.datetime.now(datetime.timezone.utc).isoformat()
        cursor.execute("""
        INSERT INTO task_status_tracker (task_id, status, updated_at)
        VALUES (?, 'completed', ?)
        ON CONFLICT(task_id) DO UPDATE SET status = 'completed', updated_at = ?
        """, (task_id, now_str, now_str))
        conn.commit()
        conn.close()
    except Exception as e:
        print(f"SQLite mark completed error: {e}")

    if task_data:
        user_id = task_data.get("user_id")
        scheduled_time_str = task_data.get("scheduled_time")
        if user_id and scheduled_time_str:
            try:
                dt_sched = datetime.datetime.fromisoformat(scheduled_time_str.replace("Z", "+00:00"))
                hour_of_day = dt_sched.hour
                
                meta = _sync_get_task_metadata(task_id)
                domain = meta.get("domain", "Personal")
                
                from repositories.behavior_repository import _sync_update_focus_window
                _sync_update_focus_window(user_id, hour_of_day, domain, scheduled_delta=0, completed_delta=1)
                
                goal_id = meta.get("goal_id")
                if goal_id:
                    log_date = dt_sched.strftime("%Y-%m-%d")
                    from repositories.goal_repository import _sync_log_goal_progress
                    _sync_log_goal_progress(user_id, goal_id, log_date, 1.0, f"Completed task: {task_data.get('task_description')}")
            except Exception as ex:
                print(f"Error updating focus window on completion: {ex}")

    if hasattr(response, "data"):
        return response.data[0] if response.data else {}
    return response if response else {}

async def mark_task_completed(task_id: str) -> dict:
    return await asyncio.to_thread(_sync_mark_task_completed, task_id)


def _sync_mark_task_reminded(task_id: str) -> dict:
    try:
        conn = sqlite3.connect(DB_FILE)
        cursor = conn.cursor()
        now_str = datetime.datetime.now(datetime.timezone.utc).isoformat()
        cursor.execute("""
        INSERT INTO task_status_tracker (task_id, status, updated_at)
        VALUES (?, 'reminded', ?)
        ON CONFLICT(task_id) DO UPDATE SET status = 'reminded', updated_at = ?
        """, (task_id, now_str, now_str))
        conn.commit()
        conn.close()
    except Exception as e:
        print(f"SQLite mark reminded error: {e}")
        
    return {"id": task_id, "status": "reminded"}

async def mark_task_reminded(task_id: str) -> dict:
    return await asyncio.to_thread(_sync_mark_task_reminded, task_id)


def _sync_update_task_status(user_id: str, task_id: str, status: str) -> dict:
    response = None
    if status in ("pending", "completed"):
        response = supabase_client.table("daily_tasks") \
            .update({"status": status}) \
            .eq("id", task_id) \
            .eq("user_id", user_id) \
            .execute()
            
    try:
        conn = sqlite3.connect(DB_FILE)
        cursor = conn.cursor()
        now_str = datetime.datetime.now(datetime.timezone.utc).isoformat()
        cursor.execute("""
        INSERT INTO task_status_tracker (task_id, status, updated_at)
        VALUES (?, ?, ?)
        ON CONFLICT(task_id) DO UPDATE SET status = ?, updated_at = ?
        """, (task_id, status, now_str, status, now_str))
        conn.commit()
        conn.close()
    except Exception as e:
        print(f"SQLite update status error: {e}")

    if response and hasattr(response, "data") and response.data:
        return response.data[0]
    return {"id": task_id, "status": status}

async def update_task_status(user_id: str, task_id: str, status: str) -> dict:
    return await asyncio.to_thread(_sync_update_task_status, user_id, task_id, status)


def _sync_delete_task(user_id: str, task_id: str) -> None:
    supabase_client.table("daily_tasks") \
        .delete() \
        .eq("id", task_id) \
        .eq("user_id", user_id) \
        .execute()
        
    try:
        conn = sqlite3.connect(DB_FILE)
        cursor = conn.cursor()
        cursor.execute("DELETE FROM task_status_tracker WHERE task_id = ?", (task_id,))
        conn.commit()
        conn.close()
    except Exception as e:
        print(f"SQLite delete task tracker error: {e}")

async def delete_task(user_id: str, task_id: str) -> None:
    await asyncio.to_thread(_sync_delete_task, user_id, task_id)


def _sync_get_all_tasks(user_id: str) -> list:
    response = supabase_client.table("daily_tasks").select("*").eq("user_id", user_id).execute()
    if hasattr(response, "data"):
        return response.data
    return response if isinstance(response, list) else []

async def get_all_tasks(user_id: str) -> list:
    return await asyncio.to_thread(_sync_get_all_tasks, user_id)


def _sync_get_tasks_by_status(user_id: str, status: str) -> list:
    response = supabase_client.table("daily_tasks").select("*").eq("user_id", user_id).eq("status", status).execute()
    if hasattr(response, "data"):
        return response.data
    return response if isinstance(response, list) else []

async def get_tasks_by_status(user_id: str, status: str) -> list:
    return await asyncio.to_thread(_sync_get_tasks_by_status, user_id, status)


def _sync_get_accountability_candidates(current_time_5m: str, current_time_1m: str) -> list:
    try:
        conn = sqlite3.connect(DB_FILE)
        cursor = conn.cursor()
        cursor.execute("SELECT task_id, updated_at FROM task_status_tracker WHERE status = 'reminded'")
        reminded_rows = cursor.fetchall()
        conn.close()
    except Exception as e:
        print(f"SQLite accountability candidates select error: {e}")
        reminded_rows = []

    if not reminded_rows:
        return []

    reminded_map = {row[0]: row[1] for row in reminded_rows}
    reminded_ids = [rid for rid in reminded_map.keys() if not rid.startswith("checkpoint_")]
    if not reminded_ids:
        return []

    try:
        response = supabase_client.table("daily_tasks") \
            .select("*") \
            .in_("id", reminded_ids) \
            .eq("status", "pending") \
            .execute()
        
        if hasattr(response, "data") and response.data:
            enriched = _sync_merge_tasks_metadata(response.data)
            filtered = []
            for t in enriched:
                tid = t.get("id")
                priority = t.get("priority", "MEDIUM")
                reminded_time_str = reminded_map.get(tid)
                
                if reminded_time_str:
                    try:
                        reminded_dt = datetime.datetime.fromisoformat(reminded_time_str.replace("Z", "+00:00"))
                        dt_5m = datetime.datetime.fromisoformat(current_time_5m.replace("Z", "+00:00"))
                        dt_1m = datetime.datetime.fromisoformat(current_time_1m.replace("Z", "+00:00"))
                        
                        if priority == "CRITICAL":
                            if reminded_dt <= dt_1m:
                                filtered.append(t)
                        else:
                            if reminded_dt <= dt_5m:
                                filtered.append(t)
                    except Exception as ex:
                        print(f"Error parsing reminded_time: {ex}")
                        filtered.append(t)
            return filtered
    except Exception as e:
        print(f"Supabase accountability check error: {e}")
        
    return []

async def get_accountability_candidates(current_time_5m: str, current_time_1m: str) -> list:
    return await asyncio.to_thread(_sync_get_accountability_candidates, current_time_5m, current_time_1m)


def _sync_set_task_metadata(
    task_id: str,
    domain: str = None,
    priority: str = None,
    goal_id: str = None,
    reschedule_count: int = None,
    reminder_count: int = None,
    interrupted: int = None
) -> dict:
    try:
        conn = sqlite3.connect(DB_FILE)
        cursor = conn.cursor()
        
        cursor.execute("SELECT 1 FROM task_metadata WHERE task_id = ?", (task_id,))
        exists = cursor.fetchone()
        
        if not exists:
            cursor.execute("""
            INSERT INTO task_metadata (task_id, domain, priority, goal_id, reschedule_count, reminder_count, interrupted)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, (task_id, domain or "Personal", priority or "MEDIUM", goal_id, reschedule_count or 0, reminder_count or 0, interrupted or 0))
        else:
            updates = []
            params = []
            if domain is not None:
                updates.append("domain = ?")
                params.append(domain)
            if priority is not None:
                updates.append("priority = ?")
                params.append(priority)
            if goal_id is not None:
                updates.append("goal_id = ?")
                params.append(goal_id)
            if reschedule_count is not None:
                updates.append("reschedule_count = ?")
                params.append(reschedule_count)
            if reminder_count is not None:
                updates.append("reminder_count = ?")
                params.append(reminder_count)
            if interrupted is not None:
                updates.append("interrupted = ?")
                params.append(interrupted)
                
            if updates:
                query = f"UPDATE task_metadata SET {', '.join(updates)} WHERE task_id = ?"
                params.append(task_id)
                cursor.execute(query, params)
                
        conn.commit()
        conn.close()
        return _sync_get_task_metadata(task_id)
    except Exception as e:
        print(f"SQLite Set Task Metadata Error: {e}")
        return {}

def _sync_get_task_metadata(task_id: str) -> dict:
    try:
        conn = sqlite3.connect(DB_FILE)
        conn.row_factory = sqlite3.Row
        cursor = conn.cursor()
        cursor.execute("SELECT * FROM task_metadata WHERE task_id = ?", (task_id,))
        row = cursor.fetchone()
        conn.close()
        return dict(row) if row else {}
    except Exception as e:
        print(f"SQLite Get Task Metadata Error: {e}")
        return {}

def _sync_merge_tasks_metadata(tasks: list) -> list:
    if not tasks:
        return []
    task_ids = [t.get("id") for t in tasks if t.get("id")]
    if not task_ids:
        return tasks
        
    try:
        conn = sqlite3.connect(DB_FILE)
        conn.row_factory = sqlite3.Row
        cursor = conn.cursor()
        placeholders = ", ".join(["?"] * len(task_ids))
        cursor.execute(f"SELECT * FROM task_metadata WHERE task_id IN ({placeholders})", task_ids)
        rows = cursor.fetchall()
        conn.close()
        
        metadata_map = {r["task_id"]: dict(r) for r in rows}
        
        enriched_tasks = []
        for t in tasks:
            t_copy = dict(t)
            tid = t_copy.get("id")
            meta = metadata_map.get(tid, {})
            t_copy["domain"] = meta.get("domain", "Personal")
            t_copy["priority"] = meta.get("priority", "MEDIUM")
            t_copy["goal_id"] = meta.get("goal_id", None)
            t_copy["reschedule_count"] = meta.get("reschedule_count", 0)
            t_copy["reminder_count"] = meta.get("reminder_count", 0)
            t_copy["interrupted"] = meta.get("interrupted", 0)
            enriched_tasks.append(t_copy)
        return enriched_tasks
    except Exception as e:
        print(f"SQLite Merge Tasks Metadata Error: {e}")
        return tasks

async def set_task_metadata(
    task_id: str,
    domain: str = None,
    priority: str = None,
    goal_id: str = None,
    reschedule_count: int = None,
    reminder_count: int = None,
    interrupted: int = None
) -> dict:
    return await asyncio.to_thread(_sync_set_task_metadata, task_id, domain, priority, goal_id, reschedule_count, reminder_count, interrupted)

async def get_task_metadata(task_id: str) -> dict:
    return await asyncio.to_thread(_sync_get_task_metadata, task_id)

async def merge_tasks_metadata(tasks: list) -> list:
    return await asyncio.to_thread(_sync_merge_tasks_metadata, tasks)
