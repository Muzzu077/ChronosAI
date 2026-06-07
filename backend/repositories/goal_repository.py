import asyncio
import sqlite3
import datetime
import uuid
from repositories.db_config import DB_FILE

def _sync_create_goal(user_id: str, title: str, description: str = None, target_date: str = None) -> dict:
    try:
        conn = sqlite3.connect(DB_FILE)
        cursor = conn.cursor()
        goal_id = str(uuid.uuid4())
        now_str = datetime.datetime.now(datetime.timezone.utc).isoformat()
        cursor.execute("""
        INSERT INTO goals (id, user_id, title, description, target_date, status, created_at)
        VALUES (?, ?, ?, ?, ?, 'active', ?)
        """, (goal_id, user_id, title, description, target_date, now_str))
        conn.commit()
        conn.close()
        return {"id": goal_id, "user_id": user_id, "title": title, "description": description, "target_date": target_date, "status": "active"}
    except Exception as e:
        print(f"SQLite Create Goal Error: {e}")
        return {}

async def create_goal(user_id: str, title: str, description: str = None, target_date: str = None) -> dict:
    return await asyncio.to_thread(_sync_create_goal, user_id, title, description, target_date)


def _sync_get_goals(user_id: str) -> list:
    try:
        conn = sqlite3.connect(DB_FILE)
        conn.row_factory = sqlite3.Row
        cursor = conn.cursor()
        cursor.execute("SELECT * FROM goals WHERE user_id = ? ORDER BY created_at DESC", (user_id,))
        rows = cursor.fetchall()
        conn.close()
        return [dict(r) for r in rows]
    except Exception as e:
        print(f"SQLite Get Goals Error: {e}")
        return []

async def get_goals(user_id: str) -> list:
    return await asyncio.to_thread(_sync_get_goals, user_id)


def _sync_update_goal_status(goal_id: str, status: str) -> bool:
    try:
        conn = sqlite3.connect(DB_FILE)
        cursor = conn.cursor()
        cursor.execute("UPDATE goals SET status = ? WHERE id = ?", (status, goal_id))
        conn.commit()
        conn.close()
        return True
    except Exception as e:
        print(f"SQLite Update Goal Status Error: {e}")
        return False

async def update_goal_status(goal_id: str, status: str) -> bool:
    return await asyncio.to_thread(_sync_update_goal_status, goal_id, status)


def _sync_log_goal_progress(user_id: str, goal_id: str, log_date: str, progress_value: float, notes: str = None) -> dict:
    try:
        conn = sqlite3.connect(DB_FILE)
        cursor = conn.cursor()
        progress_id = str(uuid.uuid4())
        now_str = datetime.datetime.now(datetime.timezone.utc).isoformat()
        cursor.execute("""
        INSERT INTO goal_progress (id, user_id, goal_id, log_date, progress_value, notes, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(user_id, goal_id, log_date) DO UPDATE SET
            progress_value = excluded.progress_value,
            notes = excluded.notes,
            created_at = excluded.created_at
        """, (progress_id, user_id, goal_id, log_date, progress_value, notes, now_str))
        conn.commit()
        conn.close()
        return {"goal_id": goal_id, "log_date": log_date, "progress_value": progress_value, "notes": notes}
    except Exception as e:
        print(f"SQLite Log Goal Progress Error: {e}")
        return {}

async def log_goal_progress(user_id: str, goal_id: str, log_date: str, progress_value: float, notes: str = None) -> dict:
    return await asyncio.to_thread(_sync_log_goal_progress, user_id, goal_id, log_date, progress_value, notes)


def _sync_get_goal_progress(user_id: str, goal_id: str) -> list:
    try:
        conn = sqlite3.connect(DB_FILE)
        conn.row_factory = sqlite3.Row
        cursor = conn.cursor()
        cursor.execute("SELECT * FROM goal_progress WHERE user_id = ? AND goal_id = ? ORDER BY log_date ASC", (user_id, goal_id))
        rows = cursor.fetchall()
        conn.close()
        return [dict(r) for r in rows]
    except Exception as e:
        print(f"SQLite Get Goal Progress Error: {e}")
        return []

async def get_goal_progress(user_id: str, goal_id: str) -> list:
    return await asyncio.to_thread(_sync_get_goal_progress, user_id, goal_id)
