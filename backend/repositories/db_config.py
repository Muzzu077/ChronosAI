import os
import sqlite3
from dotenv import load_dotenv
from supabase import create_client, Client

# Load environment variables
load_dotenv()

SUPABASE_URL = (os.getenv("SUPABASE_URL") or "").strip()
SUPABASE_KEY = (os.getenv("SUPABASE_KEY") or "").strip()

if not SUPABASE_URL or not SUPABASE_KEY:
    raise ValueError("SUPABASE_URL and SUPABASE_KEY must be configured in environment variables.")


# Initialize Supabase Client
supabase_client: Client = create_client(SUPABASE_URL, SUPABASE_KEY)

# SQLite database file configuration
DB_FILE = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "chronosai.db"))

def init_sqlite():
    conn = sqlite3.connect(DB_FILE)
    # Enable WAL mode for safe concurrent reads/writes from multiple threads
    conn.execute("PRAGMA journal_mode=WAL")
    # Set busy timeout to 5 seconds to avoid 'database is locked' errors
    conn.execute("PRAGMA busy_timeout=5000")
    cursor = conn.cursor()
    
    # Create tables matching Supabase schema
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS users (
        id TEXT PRIMARY KEY,
        display_name TEXT,
        role TEXT,
        primary_goal TEXT,
        timezone TEXT DEFAULT 'UTC',
        created_at TEXT,
        updated_at TEXT
    )""")
    
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS schedules (
        id TEXT PRIMARY KEY,
        user_id TEXT,
        schedule_date TEXT,
        generated_by TEXT DEFAULT 'planner_agent',
        summary TEXT,
        blocks TEXT,
        created_at TEXT
    )""")
    
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS prayer_times (
        id TEXT PRIMARY KEY,
        user_id TEXT,
        prayer_date TEXT,
        location TEXT,
        fajr TEXT,
        dhuhr TEXT,
        asr TEXT,
        maghrib TEXT,
        isha TEXT,
        created_at TEXT,
        UNIQUE(user_id, prayer_date)
    )""")
    
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS daily_logs (
        id TEXT PRIMARY KEY,
        user_id TEXT,
        log_date TEXT,
        tasks_planned INTEGER DEFAULT 0,
        tasks_completed INTEGER DEFAULT 0,
        tasks_skipped INTEGER DEFAULT 0,
        completion_rate REAL DEFAULT 0.0,
        focus_minutes INTEGER DEFAULT 0,
        study_minutes INTEGER DEFAULT 0,
        sleep_minutes INTEGER,
        notes TEXT,
        created_at TEXT,
        UNIQUE(user_id, log_date)
    )""")
    
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS agent_memory (
        id TEXT PRIMARY KEY,
        user_id TEXT,
        memory_key TEXT,
        memory_value TEXT,
        confidence REAL DEFAULT 0.5,
        source TEXT DEFAULT 'user',
        created_at TEXT,
        updated_at TEXT,
        UNIQUE(user_id, memory_key)
    )""")
    
    # Track reminded, accounted, skipped, and completed tasks locally to bypass Supabase status check constraints
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS task_status_tracker (
        task_id TEXT PRIMARY KEY,
        status TEXT,
        updated_at TEXT
    )""")
    
    # --- CHRONOSAI V4 BEHAVIORAL TABLES ---
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS task_metadata (
        task_id TEXT PRIMARY KEY,
        domain TEXT DEFAULT 'Personal',
        priority TEXT DEFAULT 'MEDIUM',
        goal_id TEXT,
        reschedule_count INTEGER DEFAULT 0,
        reminder_count INTEGER DEFAULT 0,
        interrupted INTEGER DEFAULT 0
    )""")
 
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS behavior_patterns (
        id TEXT PRIMARY KEY,
        user_id TEXT,
        pattern_key TEXT,
        pattern_value TEXT,
        created_at TEXT,
        updated_at TEXT,
        UNIQUE(user_id, pattern_key)
    )""")
 
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS focus_windows (
        id TEXT PRIMARY KEY,
        user_id TEXT,
        hour_of_day INTEGER,
        category TEXT,
        tasks_scheduled INTEGER DEFAULT 0,
        tasks_completed INTEGER DEFAULT 0,
        productivity_score REAL DEFAULT 0.0,
        updated_at TEXT,
        UNIQUE(user_id, hour_of_day, category)
    )""")
 
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS productivity_scores (
        id TEXT PRIMARY KEY,
        user_id TEXT,
        log_date TEXT,
        score REAL DEFAULT 0.0,
        factors TEXT,
        created_at TEXT,
        UNIQUE(user_id, log_date)
    )""")
 
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS goals (
        id TEXT PRIMARY KEY,
        user_id TEXT,
        title TEXT NOT NULL,
        description TEXT,
        target_date TEXT,
        status TEXT DEFAULT 'active',
        created_at TEXT
    )""")
 
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS goal_progress (
        id TEXT PRIMARY KEY,
        user_id TEXT,
        goal_id TEXT,
        log_date TEXT,
        progress_value REAL DEFAULT 0.0,
        notes TEXT,
        created_at TEXT,
        UNIQUE(user_id, goal_id, log_date)
    )""")

    cursor.execute("""
    CREATE TABLE IF NOT EXISTS user_life_templates (
        id TEXT PRIMARY KEY,
        user_id TEXT,
        template_name TEXT,
        active INTEGER DEFAULT 0,
        created_at TEXT
    )""")

    cursor.execute("""
    CREATE TABLE IF NOT EXISTS life_time_blocks (
        id TEXT PRIMARY KEY,
        template_id TEXT,
        block_name TEXT,
        start_time TEXT,
        end_time TEXT,
        block_type TEXT,
        priority TEXT,
        FOREIGN KEY(template_id) REFERENCES user_life_templates(id) ON DELETE CASCADE
    )""")
    
    conn.commit()
    conn.close()

# Initialize DB on load
init_sqlite()
