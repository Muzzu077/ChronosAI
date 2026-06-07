import asyncio
import sqlite3
import datetime
from repositories.db_config import DB_FILE

def _sync_get_user_profile(user_id: str) -> dict:
    try:
        conn = sqlite3.connect(DB_FILE)
        conn.row_factory = sqlite3.Row
        cursor = conn.cursor()
        cursor.execute("SELECT * FROM users WHERE id = ?", (user_id,))
        row = cursor.fetchone()
        conn.close()
        return dict(row) if row else {}
    except Exception as e:
        print(f"SQLite Profile Error: {e}")
        return {}

async def get_user_profile(user_id: str) -> dict:
    return await asyncio.to_thread(_sync_get_user_profile, user_id)


def _sync_update_user_profile(user_id: str, data: dict) -> dict:
    try:
        conn = sqlite3.connect(DB_FILE)
        cursor = conn.cursor()
        now_str = datetime.datetime.now(datetime.timezone.utc).isoformat()
        
        cursor.execute("SELECT 1 FROM users WHERE id = ?", (user_id,))
        exists = cursor.fetchone()
        
        if exists:
            set_clause = ", ".join([f"{k} = ?" for k in data.keys()])
            params = list(data.values()) + [now_str, user_id]
            cursor.execute(f"UPDATE users SET {set_clause}, updated_at = ? WHERE id = ?", params)
        else:
            cols = ["id", "created_at", "updated_at"] + list(data.keys())
            placeholders = ", ".join(["?"] * len(cols))
            params = [user_id, now_str, now_str] + list(data.values())
            cursor.execute(f"INSERT INTO users ({', '.join(cols)}) VALUES ({placeholders})", params)
            
        conn.commit()
        conn.close()
        return _sync_get_user_profile(user_id)
    except Exception as e:
        print(f"SQLite Profile Update Error: {e}")
        return {}

async def update_user_profile(user_id: str, data: dict) -> dict:
    return await asyncio.to_thread(_sync_update_user_profile, user_id, data)
