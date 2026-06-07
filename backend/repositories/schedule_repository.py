import asyncio
import sqlite3
import datetime
import json
import uuid
from repositories.db_config import DB_FILE

def _sync_save_user_schedule(user_id: str, schedule_date: str, summary: str, blocks: list, generated_by: str = 'planner_agent') -> dict:
    try:
        conn = sqlite3.connect(DB_FILE)
        cursor = conn.cursor()
        now_str = datetime.datetime.now(datetime.timezone.utc).isoformat()
        blocks_str = json.dumps(blocks)
        sch_id = str(uuid.uuid4())
        
        cursor.execute("""
        INSERT INTO schedules (id, user_id, schedule_date, generated_by, summary, blocks, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """, (sch_id, user_id, schedule_date, generated_by, summary, blocks_str, now_str))
        
        conn.commit()
        conn.close()
        return _sync_get_user_schedule(user_id, schedule_date)
    except Exception as e:
        print(f"SQLite Schedule Save Error: {e}")
        return {}

async def save_user_schedule(user_id: str, schedule_date: str, summary: str, blocks: list, generated_by: str = 'planner_agent') -> dict:
    return await asyncio.to_thread(_sync_save_user_schedule, user_id, schedule_date, summary, blocks, generated_by)


def _sync_get_user_schedule(user_id: str, schedule_date: str) -> dict:
    try:
        conn = sqlite3.connect(DB_FILE)
        conn.row_factory = sqlite3.Row
        cursor = conn.cursor()
        cursor.execute("SELECT * FROM schedules WHERE user_id = ? AND schedule_date = ? ORDER BY created_at DESC LIMIT 1", (user_id, schedule_date))
        row = cursor.fetchone()
        conn.close()
        if row:
            d = dict(row)
            try:
                d["blocks"] = json.loads(d["blocks"])
            except Exception:
                pass
            return d
        return {}
    except Exception as e:
        print(f"SQLite Schedule Get Error: {e}")
        return {}

async def get_user_schedule(user_id: str, schedule_date: str) -> dict:
    return await asyncio.to_thread(_sync_get_user_schedule, user_id, schedule_date)
