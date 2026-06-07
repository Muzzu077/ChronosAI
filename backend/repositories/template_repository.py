import asyncio
import sqlite3
import datetime
import uuid
from repositories.db_config import DB_FILE

def _sync_create_life_template(user_id: str, template_name: str, active: int = 1) -> dict:
    try:
        conn = sqlite3.connect(DB_FILE)
        cursor = conn.cursor()
        
        # If active, set all other templates of this user to inactive
        if active == 1:
            cursor.execute("UPDATE user_life_templates SET active = 0 WHERE user_id = ?", (user_id,))
            
        template_id = str(uuid.uuid4())
        now_str = datetime.datetime.now(datetime.timezone.utc).isoformat()
        cursor.execute("""
        INSERT INTO user_life_templates (id, user_id, template_name, active, created_at)
        VALUES (?, ?, ?, ?, ?)
        """, (template_id, user_id, template_name, active, now_str))
        conn.commit()
        conn.close()
        return {"id": template_id, "user_id": user_id, "template_name": template_name, "active": active}
    except Exception as e:
        print(f"SQLite Create Life Template Error: {e}")
        return {}

async def create_life_template(user_id: str, template_name: str, active: int = 1) -> dict:
    return await asyncio.to_thread(_sync_create_life_template, user_id, template_name, active)


def _sync_add_life_time_block(template_id: str, block_name: str, start_time: str, end_time: str, block_type: str, priority: str) -> dict:
    try:
        conn = sqlite3.connect(DB_FILE)
        cursor = conn.cursor()
        block_id = str(uuid.uuid4())
        cursor.execute("""
        INSERT INTO life_time_blocks (id, template_id, block_name, start_time, end_time, block_type, priority)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """, (block_id, template_id, block_name, start_time, end_time, block_type, priority))
        conn.commit()
        conn.close()
        return {"id": block_id, "template_id": template_id, "block_name": block_name, "start_time": start_time, "end_time": end_time, "block_type": block_type, "priority": priority}
    except Exception as e:
        print(f"SQLite Add Time Block Error: {e}")
        return {}

async def add_life_time_block(template_id: str, block_name: str, start_time: str, end_time: str, block_type: str, priority: str) -> dict:
    return await asyncio.to_thread(_sync_add_life_time_block, template_id, block_name, start_time, end_time, block_type, priority)


def _sync_get_active_life_template(user_id: str) -> dict:
    try:
        conn = sqlite3.connect(DB_FILE)
        conn.row_factory = sqlite3.Row
        cursor = conn.cursor()
        cursor.execute("SELECT * FROM user_life_templates WHERE user_id = ? AND active = 1 LIMIT 1", (user_id,))
        row = cursor.fetchone()
        conn.close()
        return dict(row) if row else {}
    except Exception as e:
        print(f"SQLite Get Active Template Error: {e}")
        return {}

async def get_active_life_template(user_id: str) -> dict:
    return await asyncio.to_thread(_sync_get_active_life_template, user_id)


def _sync_get_life_template_blocks(template_id: str) -> list:
    try:
        conn = sqlite3.connect(DB_FILE)
        conn.row_factory = sqlite3.Row
        cursor = conn.cursor()
        cursor.execute("SELECT * FROM life_time_blocks WHERE template_id = ? ORDER BY start_time ASC", (template_id,))
        rows = cursor.fetchall()
        conn.close()
        return [dict(r) for r in rows]
    except Exception as e:
        print(f"SQLite Get Time Blocks Error: {e}")
        return []

async def get_life_template_blocks(template_id: str) -> list:
    return await asyncio.to_thread(_sync_get_life_template_blocks, template_id)


def _sync_ensure_default_template(user_id: str) -> dict:
    # Check if there is an active template
    active_temp = _sync_get_active_life_template(user_id)
    if active_temp:
        return active_temp
        
    print(f"No active template found for {user_id}. Creating default Life Template...")
    template = _sync_create_life_template(user_id, "Default Weekly Schedule", active=1)
    tid = template.get("id")
    
    if tid:
        # Fajr
        _sync_add_life_time_block(tid, "Fajr", "04:20", "04:45", "prayer", "fixed")
        # Sleep
        _sync_add_life_time_block(tid, "Sleep", "01:30", "04:20", "sleep", "fixed")
        # College
        _sync_add_life_time_block(tid, "College", "08:00", "16:30", "college", "fixed")
        # Deep Work
        _sync_add_life_time_block(tid, "Deep Work", "20:15", "01:30", "deep_work", "preferred")
        
    return _sync_get_active_life_template(user_id)

async def ensure_default_template(user_id: str) -> dict:
    return await asyncio.to_thread(_sync_ensure_default_template, user_id)


def _sync_get_daily_checkpoints_utc(user_id: str, date_str: str) -> list:
    from zoneinfo import ZoneInfo
    try:
        conn = sqlite3.connect(DB_FILE)
        conn.row_factory = sqlite3.Row
        cursor = conn.cursor()
        cursor.execute("SELECT timezone FROM users WHERE id = ?", (user_id,))
        row = cursor.fetchone()
        tz_str = row["timezone"] if row and row["timezone"] else "Asia/Kolkata"
        conn.close()
    except Exception:
        tz_str = "Asia/Kolkata"
        
    try:
        tz = ZoneInfo(tz_str)
    except Exception:
        tz = ZoneInfo("Asia/Kolkata")
        
    # Get active template
    active_temp = _sync_ensure_default_template(user_id)
    tid = active_temp.get("id")
    blocks = _sync_get_life_template_blocks(tid) if tid else []
    
    # Parse target date
    try:
        target_date = datetime.datetime.strptime(date_str, "%Y-%m-%d").date()
    except Exception:
        target_date = datetime.datetime.now(tz).date()
        
    # Find start/end of blocks
    college_start = "08:00"
    deep_work_start = None
    deep_work_end = None
    sleep_start = "01:30"
    
    for b in blocks:
        btype = b.get("block_type")
        if btype == "college":
            college_start = b.get("start_time", "08:00")
        elif btype == "deep_work":
            deep_work_start = b.get("start_time")
            deep_work_end = b.get("end_time")
        elif btype == "sleep":
            sleep_start = b.get("start_time", "01:30")
            
    checkpoints = []
    
    # 1. MORNING_STANDUP: 45 minutes before college_start
    try:
        h_col, m_col = map(int, college_start.split(":"))
        col_dt = datetime.datetime.combine(target_date, datetime.time(h_col, m_col))
        standup_dt = col_dt - datetime.timedelta(minutes=45)
        standup_utc = standup_dt.replace(tzinfo=tz).astimezone(datetime.timezone.utc).isoformat()
        checkpoints.append({
            "checkpoint_type": "MORNING_STANDUP",
            "utc_time": standup_utc,
            "description": "Morning priorities check-in."
        })
    except Exception as ex:
        print(f"Error calculating morning standup: {ex}")
        
    # 2. DEEP_WORK_START: At beginning of Deep Work
    if deep_work_start:
        try:
            h_dw, m_dw = map(int, deep_work_start.split(":"))
            dw_dt = datetime.datetime.combine(target_date, datetime.time(h_dw, m_dw))
            dw_utc = dw_dt.replace(tzinfo=tz).astimezone(datetime.timezone.utc).isoformat()
            checkpoints.append({
                "checkpoint_type": "DEEP_WORK_START",
                "utc_time": dw_utc,
                "description": f"Deep Work session starting. Targets set in routine."
            })
        except Exception as ex:
            print(f"Error calculating deep work start: {ex}")
            
    # 3. ACCOUNTABILITY_CHECK: Halfway through Deep Work block
    if deep_work_start and deep_work_end:
        try:
            h_start, m_start = map(int, deep_work_start.split(":"))
            h_end, m_end = map(int, deep_work_end.split(":"))
            
            dt_start = datetime.datetime.combine(target_date, datetime.time(h_start, m_start))
            dt_end = datetime.datetime.combine(target_date, datetime.time(h_end, m_end))
            if dt_end < dt_start:
                dt_end += datetime.timedelta(days=1)
                
            duration = dt_end - dt_start
            midpoint = dt_start + (duration / 2)
            midpoint_utc = midpoint.replace(tzinfo=tz).astimezone(datetime.timezone.utc).isoformat()
            checkpoints.append({
                "checkpoint_type": "ACCOUNTABILITY_CHECK",
                "utc_time": midpoint_utc,
                "description": "Mid-session accountability check-in."
            })
        except Exception as ex:
            print(f"Error calculating accountability check: {ex}")
            
    # 4. DAY_REVIEW: 15 minutes before sleep starts
    if sleep_start:
        try:
            h_sl, m_sl = map(int, sleep_start.split(":"))
            sl_dt = datetime.datetime.combine(target_date, datetime.time(h_sl, m_sl))
            if h_sl < 12:
                sl_dt += datetime.timedelta(days=1)
                
            review_dt = sl_dt - datetime.timedelta(minutes=15)
            review_utc = review_dt.replace(tzinfo=tz).astimezone(datetime.timezone.utc).isoformat()
            checkpoints.append({
                "checkpoint_type": "DAY_REVIEW",
                "utc_time": review_utc,
                "description": "Daily review and wrap-up."
            })
        except Exception as ex:
            print(f"Error calculating day review: {ex}")
            
    return checkpoints

async def get_daily_checkpoints_utc(user_id: str, date_str: str) -> list:
    return await asyncio.to_thread(_sync_get_daily_checkpoints_utc, user_id, date_str)

