import asyncio
import sqlite3
import datetime
import json
import uuid
from repositories.db_config import DB_FILE

def _sync_save_agent_memory(user_id: str, memory_key: str, memory_value: dict, confidence: float = 0.9, source: str = "user") -> dict:
    try:
        conn = sqlite3.connect(DB_FILE)
        cursor = conn.cursor()
        now_str = datetime.datetime.now(datetime.timezone.utc).isoformat()
        val_str = json.dumps(memory_value)
        mem_id = str(uuid.uuid4())
        
        cursor.execute("""
        INSERT INTO agent_memory (id, user_id, memory_key, memory_value, confidence, source, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(user_id, memory_key) DO UPDATE SET
            memory_value = excluded.memory_value,
            confidence = excluded.confidence,
            source = excluded.source,
            updated_at = excluded.updated_at
        """, (mem_id, user_id, memory_key, val_str, confidence, source, now_str, now_str))
        
        conn.commit()
        conn.close()
        return _sync_get_agent_memory(user_id, memory_key)
    except Exception as e:
        print(f"SQLite Memory Save Error: {e}")
        return {}

async def save_agent_memory(user_id: str, memory_key: str, memory_value: dict, confidence: float = 0.9, source: str = "user") -> dict:
    return await asyncio.to_thread(_sync_save_agent_memory, user_id, memory_key, memory_value, confidence, source)


def _sync_get_agent_memories(user_id: str) -> list:
    try:
        conn = sqlite3.connect(DB_FILE)
        conn.row_factory = sqlite3.Row
        cursor = conn.cursor()
        cursor.execute("SELECT * FROM agent_memory WHERE user_id = ?", (user_id,))
        rows = cursor.fetchall()
        conn.close()
        
        res = []
        for r in rows:
            d = dict(r)
            try:
                d["memory_value"] = json.loads(d["memory_value"])
            except Exception:
                pass
            res.append(d)
        return res
    except Exception as e:
        print(f"SQLite Memories Get Error: {e}")
        return []

async def get_agent_memories(user_id: str) -> list:
    return await asyncio.to_thread(_sync_get_agent_memories, user_id)


def _sync_get_agent_memory(user_id: str, memory_key: str) -> dict:
    try:
        conn = sqlite3.connect(DB_FILE)
        conn.row_factory = sqlite3.Row
        cursor = conn.cursor()
        cursor.execute("SELECT * FROM agent_memory WHERE user_id = ? AND memory_key = ?", (user_id, memory_key))
        row = cursor.fetchone()
        conn.close()
        if row:
            d = dict(row)
            try:
                d["memory_value"] = json.loads(d["memory_value"])
            except Exception:
                pass
            return d
        return {}
    except Exception as e:
        print(f"SQLite Memory Get Error: {e}")
        return {}

async def get_agent_memory(user_id: str, memory_key: str) -> dict:
    return await asyncio.to_thread(_sync_get_agent_memory, user_id, memory_key)
