import asyncio
import sqlite3
import datetime
import json
import uuid
from repositories.db_config import DB_FILE

def _sync_save_behavior_pattern(user_id: str, pattern_key: str, pattern_value: dict) -> dict:
    try:
        conn = sqlite3.connect(DB_FILE)
        cursor = conn.cursor()
        pat_id = str(uuid.uuid4())
        now_str = datetime.datetime.now(datetime.timezone.utc).isoformat()
        val_str = json.dumps(pattern_value)
        cursor.execute("""
        INSERT INTO behavior_patterns (id, user_id, pattern_key, pattern_value, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT(user_id, pattern_key) DO UPDATE SET
            pattern_value = excluded.pattern_value,
            updated_at = excluded.updated_at
        """, (pat_id, user_id, pattern_key, val_str, now_str, now_str))
        conn.commit()
        conn.close()
        return {"pattern_key": pattern_key, "pattern_value": pattern_value}
    except Exception as e:
        print(f"SQLite Save Behavior Pattern Error: {e}")
        return {}

async def save_behavior_pattern(user_id: str, pattern_key: str, pattern_value: dict) -> dict:
    return await asyncio.to_thread(_sync_save_behavior_pattern, user_id, pattern_key, pattern_value)


def _sync_get_behavior_patterns(user_id: str) -> dict:
    try:
        conn = sqlite3.connect(DB_FILE)
        conn.row_factory = sqlite3.Row
        cursor = conn.cursor()
        cursor.execute("SELECT * FROM behavior_patterns WHERE user_id = ?", (user_id,))
        rows = cursor.fetchall()
        conn.close()
        
        patterns = {}
        for r in rows:
            try:
                patterns[r["pattern_key"]] = json.loads(r["pattern_value"])
            except Exception:
                patterns[r["pattern_key"]] = r["pattern_value"]
        return patterns
    except Exception as e:
        print(f"SQLite Get Behavior Patterns Error: {e}")
        return {}

async def get_behavior_patterns(user_id: str) -> dict:
    return await asyncio.to_thread(_sync_get_behavior_patterns, user_id)


def _sync_update_focus_window(user_id: str, hour_of_day: int, category: str, scheduled_delta: int, completed_delta: int) -> dict:
    try:
        conn = sqlite3.connect(DB_FILE)
        cursor = conn.cursor()
        
        cursor.execute("""
        SELECT tasks_scheduled, tasks_completed FROM focus_windows 
        WHERE user_id = ? AND hour_of_day = ? AND category = ?
        """, (user_id, hour_of_day, category))
        row = cursor.fetchone()
        
        now_str = datetime.datetime.now(datetime.timezone.utc).isoformat()
        
        if row:
            nsched = max(0, row[0] + scheduled_delta)
            ncomp = max(0, row[1] + completed_delta)
            score = round(ncomp / nsched, 2) if nsched > 0 else 0.0
            cursor.execute("""
            UPDATE focus_windows SET tasks_scheduled = ?, tasks_completed = ?, productivity_score = ?, updated_at = ?
            WHERE user_id = ? AND hour_of_day = ? AND category = ?
            """, (nsched, ncomp, score, now_str, user_id, hour_of_day, category))
        else:
            nsched = max(0, scheduled_delta)
            ncomp = max(0, completed_delta)
            score = round(ncomp / nsched, 2) if nsched > 0 else 0.0
            fw_id = str(uuid.uuid4())
            cursor.execute("""
            INSERT INTO focus_windows (id, user_id, hour_of_day, category, tasks_scheduled, tasks_completed, productivity_score, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, (fw_id, user_id, hour_of_day, category, nsched, ncomp, score, now_str))
            
        conn.commit()
        conn.close()
        return {"hour": hour_of_day, "category": category, "tasks_scheduled": nsched, "tasks_completed": ncomp, "productivity_score": score}
    except Exception as e:
        print(f"SQLite Update Focus Window Error: {e}")
        return {}

async def update_focus_window(user_id: str, hour_of_day: int, category: str, scheduled_delta: int, completed_delta: int) -> dict:
    return await asyncio.to_thread(_sync_update_focus_window, user_id, hour_of_day, category, scheduled_delta, completed_delta)


def _sync_get_focus_windows(user_id: str) -> list:
    try:
        conn = sqlite3.connect(DB_FILE)
        conn.row_factory = sqlite3.Row
        cursor = conn.cursor()
        cursor.execute("SELECT * FROM focus_windows WHERE user_id = ? ORDER BY hour_of_day ASC", (user_id,))
        rows = cursor.fetchall()
        conn.close()
        return [dict(r) for r in rows]
    except Exception as e:
        print(f"SQLite Get Focus Windows Error: {e}")
        return []

async def get_focus_windows(user_id: str) -> list:
    return await asyncio.to_thread(_sync_get_focus_windows, user_id)
