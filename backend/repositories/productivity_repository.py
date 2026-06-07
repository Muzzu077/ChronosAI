import asyncio
import sqlite3
import datetime
import json
import uuid
from repositories.db_config import DB_FILE

def _sync_get_daily_log(user_id: str, log_date: str) -> dict:
    try:
        conn = sqlite3.connect(DB_FILE)
        conn.row_factory = sqlite3.Row
        cursor = conn.cursor()
        cursor.execute("SELECT * FROM daily_logs WHERE user_id = ? AND log_date = ?", (user_id, log_date))
        row = cursor.fetchone()
        conn.close()
        return dict(row) if row else {}
    except Exception as e:
        print(f"SQLite Log Get Error: {e}")
        return {}

async def get_daily_log(user_id: str, log_date: str) -> dict:
    return await asyncio.to_thread(_sync_get_daily_log, user_id, log_date)


def _sync_update_daily_log(user_id: str, log_date: str, updates: dict) -> dict:
    try:
        conn = sqlite3.connect(DB_FILE)
        cursor = conn.cursor()
        now_str = datetime.datetime.now(datetime.timezone.utc).isoformat()
        
        cursor.execute("SELECT 1 FROM daily_logs WHERE user_id = ? AND log_date = ?", (user_id, log_date))
        exists = cursor.fetchone()
        
        if not exists:
            log_id = str(uuid.uuid4())
            cursor.execute("""
            INSERT INTO daily_logs (id, user_id, log_date, tasks_planned, tasks_completed, tasks_skipped, completion_rate, focus_minutes, study_minutes, sleep_minutes, notes, created_at)
            VALUES (?, ?, ?, 0, 0, 0, 0.0, 0, 0, 0, '', ?)
            """, (log_id, user_id, log_date, now_str))
            
        if updates:
            set_clause = ", ".join([f"{k} = ?" for k in updates.keys()])
            params = list(updates.values()) + [user_id, log_date]
            cursor.execute(f"UPDATE daily_logs SET {set_clause} WHERE user_id = ? AND log_date = ?", params)
            
        conn.commit()
        conn.close()
        return _sync_get_daily_log(user_id, log_date)
    except Exception as e:
        print(f"SQLite Log Update Error: {e}")
        return {}

async def update_daily_log(user_id: str, log_date: str, updates: dict) -> dict:
    return await asyncio.to_thread(_sync_update_daily_log, user_id, log_date, updates)


def _sync_get_productivity_stats(user_id: str, days: int = 7) -> list:
    try:
        conn = sqlite3.connect(DB_FILE)
        conn.row_factory = sqlite3.Row
        cursor = conn.cursor()
        cursor.execute("SELECT * FROM daily_logs WHERE user_id = ? ORDER BY log_date DESC LIMIT ?", (user_id, days))
        rows = cursor.fetchall()
        conn.close()
        return [dict(r) for r in rows]
    except Exception as e:
        print(f"SQLite Stats Get Error: {e}")
        return []

async def get_productivity_stats(user_id: str, days: int = 7) -> list:
    return await asyncio.to_thread(_sync_get_productivity_stats, user_id, days)


def _sync_save_productivity_score(user_id: str, log_date: str, score: float, factors: dict) -> dict:
    try:
        conn = sqlite3.connect(DB_FILE)
        cursor = conn.cursor()
        ps_id = str(uuid.uuid4())
        now_str = datetime.datetime.now(datetime.timezone.utc).isoformat()
        fact_str = json.dumps(factors)
        cursor.execute("""
        INSERT INTO productivity_scores (id, user_id, log_date, score, factors, created_at)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT(user_id, log_date) DO UPDATE SET
            score = excluded.score,
            factors = excluded.factors,
            created_at = excluded.created_at
        """, (ps_id, user_id, log_date, score, fact_str, now_str))
        conn.commit()
        conn.close()
        return {"log_date": log_date, "score": score, "factors": factors}
    except Exception as e:
        print(f"SQLite Save Productivity Score Error: {e}")
        return {}

async def save_productivity_score(user_id: str, log_date: str, score: float, factors: dict) -> dict:
    return await asyncio.to_thread(_sync_save_productivity_score, user_id, log_date, score, factors)


def _sync_get_productivity_scores(user_id: str, limit: int = 7) -> list:
    try:
        conn = sqlite3.connect(DB_FILE)
        conn.row_factory = sqlite3.Row
        cursor = conn.cursor()
        cursor.execute("SELECT * FROM productivity_scores WHERE user_id = ? ORDER BY log_date DESC LIMIT ?", (user_id, limit))
        rows = cursor.fetchall()
        conn.close()
        res = []
        for r in rows:
            d = dict(r)
            try:
                d["factors"] = json.loads(d["factors"])
            except Exception:
                pass
            res.append(d)
        return res
    except Exception as e:
        print(f"SQLite Get Productivity Scores Error: {e}")
        return []

async def get_productivity_scores(user_id: str, limit: int = 7) -> list:
    return await asyncio.to_thread(_sync_get_productivity_scores, user_id, limit)
