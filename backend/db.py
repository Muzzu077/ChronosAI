import os
import asyncio
import datetime
from dotenv import load_dotenv
from supabase import create_client, Client

# Load environment variables
load_dotenv()

SUPABASE_URL = os.getenv("SUPABASE_URL")
SUPABASE_KEY = os.getenv("SUPABASE_KEY")

if not SUPABASE_URL or not SUPABASE_KEY:
    raise ValueError("SUPABASE_URL and SUPABASE_KEY must be configured in environment variables.")

# Initialize the Supabase Client
supabase_client: Client = create_client(SUPABASE_URL, SUPABASE_KEY)


def _sync_insert_task(user_id: str, task_description: str, scheduled_time: str) -> dict:
    """Synchronously inserts a task using the Supabase client."""
    data = {
        "user_id": user_id,
        "task_description": task_description,
        "scheduled_time": scheduled_time,
        "status": "pending"
    }
    response = supabase_client.table("daily_tasks").insert(data).execute()
    # Support both old and new postgrest-py response shapes
    if hasattr(response, "data"):
        return response.data[0] if response.data else {}
    return response if response else {}


async def insert_task(user_id: str, task_description: str, scheduled_time_iso: str) -> dict:
    """Asynchronously inserts a task into public.daily_tasks table. Wraps sync operation in threads to keep loop unblocked."""
    return await asyncio.to_thread(_sync_insert_task, user_id, task_description, scheduled_time_iso)


def _sync_get_user_tasks(user_id: str) -> list:
    response = supabase_client.table("daily_tasks") \
        .select("*") \
        .eq("user_id", user_id) \
        .order("scheduled_time") \
        .execute()
    if hasattr(response, "data"):
        return response.data
    return response if isinstance(response, list) else []


async def get_user_tasks(user_id: str) -> list:
    return await asyncio.to_thread(_sync_get_user_tasks, user_id)


def _sync_get_pending_tasks(current_time: str) -> list:
    """Synchronously retrieves pending tasks due up to the current_time."""
    response = supabase_client.table("daily_tasks") \
        .select("*") \
        .eq("status", "pending") \
        .lte("scheduled_time", current_time) \
        .execute()
    if hasattr(response, "data"):
        return response.data
    return response if isinstance(response, list) else []


async def get_pending_tasks(current_time_iso: str) -> list:
    """Asynchronously queries tasks where status is 'pending' and scheduled_time is <= current_time_iso."""
    return await asyncio.to_thread(_sync_get_pending_tasks, current_time_iso)


def _sync_mark_task_completed(task_id: str) -> dict:
    """Synchronously marks a task completed in public.daily_tasks."""
    response = supabase_client.table("daily_tasks") \
        .update({"status": "completed"}) \
        .eq("id", task_id) \
        .execute()
    if hasattr(response, "data"):
        return response.data[0] if response.data else {}
    return response if response else {}


async def mark_task_completed(task_id: str) -> dict:
    """Asynchronously updates a task's status to 'completed'."""
    return await asyncio.to_thread(_sync_mark_task_completed, task_id)


def _sync_mark_task_reminded(task_id: str) -> dict:
    response = supabase_client.table("daily_tasks") \
        .update({"status": "reminded"}) \
        .eq("id", task_id) \
        .execute()
    if hasattr(response, "data"):
        return response.data[0] if response.data else {}
    return response if response else {}


async def mark_task_reminded(task_id: str) -> dict:
    return await asyncio.to_thread(_sync_mark_task_reminded, task_id)


def _sync_update_task_status(user_id: str, task_id: str, status: str) -> dict:
    response = supabase_client.table("daily_tasks") \
        .update({"status": status}) \
        .eq("id", task_id) \
        .eq("user_id", user_id) \
        .execute()
    if hasattr(response, "data"):
        return response.data[0] if response.data else {}
    return response if response else {}


async def update_task_status(user_id: str, task_id: str, status: str) -> dict:
    return await asyncio.to_thread(_sync_update_task_status, user_id, task_id, status)


def _sync_delete_task(user_id: str, task_id: str) -> None:
    supabase_client.table("daily_tasks") \
        .delete() \
        .eq("id", task_id) \
        .eq("user_id", user_id) \
        .execute()


async def delete_task(user_id: str, task_id: str) -> None:
    await asyncio.to_thread(_sync_delete_task, user_id, task_id)


# --- USER PROFILE ---
def _sync_get_user_profile(user_id: str) -> dict:
    response = supabase_client.table("users").select("*").eq("id", user_id).execute()
    if hasattr(response, "data") and response.data:
        return response.data[0]
    return {}


async def get_user_profile(user_id: str) -> dict:
    return await asyncio.to_thread(_sync_get_user_profile, user_id)


def _sync_update_user_profile(user_id: str, data: dict) -> dict:
    # Ensure ID is in dictionary for upsert
    upsert_data = {"id": user_id, **data}
    response = supabase_client.table("users").upsert(upsert_data).execute()
    if hasattr(response, "data") and response.data:
        return response.data[0]
    return {}


async def update_user_profile(user_id: str, data: dict) -> dict:
    return await asyncio.to_thread(_sync_update_user_profile, user_id, data)


# --- AGENT MEMORY ---
def _sync_save_agent_memory(user_id: str, memory_key: str, memory_value: dict, confidence: float = 0.9, source: str = "user") -> dict:
    data = {
        "user_id": user_id,
        "memory_key": memory_key,
        "memory_value": memory_value,
        "confidence": confidence,
        "source": source,
        "updated_at": datetime.datetime.now(datetime.timezone.utc).isoformat()
    }
    response = supabase_client.table("agent_memory").upsert(data, on_conflict="user_id,memory_key").execute()
    if hasattr(response, "data") and response.data:
        return response.data[0]
    return {}


async def save_agent_memory(user_id: str, memory_key: str, memory_value: dict, confidence: float = 0.9, source: str = "user") -> dict:
    return await asyncio.to_thread(_sync_save_agent_memory, user_id, memory_key, memory_value, confidence, source)


def _sync_get_agent_memories(user_id: str) -> list:
    response = supabase_client.table("agent_memory").select("*").eq("user_id", user_id).execute()
    if hasattr(response, "data"):
        return response.data
    return response if isinstance(response, list) else []


async def get_agent_memories(user_id: str) -> list:
    return await asyncio.to_thread(_sync_get_agent_memories, user_id)


def _sync_get_agent_memory(user_id: str, memory_key: str) -> dict:
    response = supabase_client.table("agent_memory").select("*").eq("user_id", user_id).eq("memory_key", memory_key).execute()
    if hasattr(response, "data") and response.data:
        return response.data[0]
    return {}


async def get_agent_memory(user_id: str, memory_key: str) -> dict:
    return await asyncio.to_thread(_sync_get_agent_memory, user_id, memory_key)


# --- SCHEDULES ---
def _sync_save_user_schedule(user_id: str, schedule_date: str, summary: str, blocks: list, generated_by: str = 'planner_agent') -> dict:
    data = {
        "user_id": user_id,
        "schedule_date": schedule_date,
        "summary": summary,
        "blocks": blocks,
        "generated_by": generated_by
    }
    response = supabase_client.table("schedules").insert(data).execute()
    if hasattr(response, "data") and response.data:
        return response.data[0]
    return {}


async def save_user_schedule(user_id: str, schedule_date: str, summary: str, blocks: list, generated_by: str = 'planner_agent') -> dict:
    return await asyncio.to_thread(_sync_save_user_schedule, user_id, schedule_date, summary, blocks, generated_by)


def _sync_get_user_schedule(user_id: str, schedule_date: str) -> dict:
    # Get the latest schedule for this date
    response = supabase_client.table("schedules") \
        .select("*") \
        .eq("user_id", user_id) \
        .eq("schedule_date", schedule_date) \
        .order("created_at", desc=True) \
        .execute()
    if hasattr(response, "data") and response.data:
        return response.data[0]
    return {}


async def get_user_schedule(user_id: str, schedule_date: str) -> dict:
    return await asyncio.to_thread(_sync_get_user_schedule, user_id, schedule_date)


# --- PRAYER TIMES ---
def _sync_insert_prayer_times(user_id: str, prayer_date: str, location: dict, timings: dict) -> dict:
    data = {
        "user_id": user_id,
        "prayer_date": prayer_date,
        "location": location,
        "fajr": timings.get("Fajr"),
        "dhuhr": timings.get("Dhuhr"),
        "asr": timings.get("Asr"),
        "maghrib": timings.get("Maghrib"),
        "isha": timings.get("Isha")
    }
    response = supabase_client.table("prayer_times").upsert(data, on_conflict="user_id,prayer_date").execute()
    if hasattr(response, "data") and response.data:
        return response.data[0]
    return {}


async def insert_prayer_times(user_id: str, prayer_date: str, location: dict, timings: dict) -> dict:
    return await asyncio.to_thread(_sync_insert_prayer_times, user_id, prayer_date, location, timings)


def _sync_get_prayer_times(user_id: str, prayer_date: str) -> dict:
    response = supabase_client.table("prayer_times").select("*").eq("user_id", user_id).eq("prayer_date", prayer_date).execute()
    if hasattr(response, "data") and response.data:
        return response.data[0]
    return {}


async def get_prayer_times(user_id: str, prayer_date: str) -> dict:
    return await asyncio.to_thread(_sync_get_prayer_times, user_id, prayer_date)


# --- DAILY LOGS & ANALYTICS ---
def _sync_get_daily_log(user_id: str, log_date: str) -> dict:
    response = supabase_client.table("daily_logs").select("*").eq("user_id", user_id).eq("log_date", log_date).execute()
    if hasattr(response, "data") and response.data:
        return response.data[0]
    return {}


async def get_daily_log(user_id: str, log_date: str) -> dict:
    return await asyncio.to_thread(_sync_get_daily_log, user_id, log_date)


def _sync_update_daily_log(user_id: str, log_date: str, updates: dict) -> dict:
    # Check if row exists, if not initialize it
    check = supabase_client.table("daily_logs").select("*").eq("user_id", user_id).eq("log_date", log_date).execute()
    if hasattr(check, "data") and not check.data:
        init_data = {
            "user_id": user_id,
            "log_date": log_date,
            "tasks_planned": 0,
            "tasks_completed": 0,
            "tasks_skipped": 0,
            "completion_rate": 0.0,
            "focus_minutes": 0,
            "study_minutes": 0,
            "sleep_minutes": 0,
            "notes": ""
        }
        supabase_client.table("daily_logs").insert(init_data).execute()
        
    response = supabase_client.table("daily_logs").update(updates).eq("user_id", user_id).eq("log_date", log_date).execute()
    if hasattr(response, "data") and response.data:
        return response.data[0]
    return {}


async def update_daily_log(user_id: str, log_date: str, updates: dict) -> dict:
    return await asyncio.to_thread(_sync_update_daily_log, user_id, log_date, updates)


def _sync_get_productivity_stats(user_id: str, days: int = 7) -> list:
    response = supabase_client.table("daily_logs") \
        .select("*") \
        .eq("user_id", user_id) \
        .order("log_date", desc=True) \
        .limit(days) \
        .execute()
    if hasattr(response, "data"):
        return response.data
    return response if isinstance(response, list) else []


async def get_productivity_stats(user_id: str, days: int = 7) -> list:
    return await asyncio.to_thread(_sync_get_productivity_stats, user_id, days)


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


def _sync_get_accountability_candidates(current_time: str) -> list:
    # Get all reminded tasks
    response = supabase_client.table("daily_tasks") \
        .select("*") \
        .eq("status", "reminded") \
        .lte("scheduled_time", current_time) \
        .execute()
    if hasattr(response, "data"):
        return response.data
    return response if isinstance(response, list) else []


async def get_accountability_candidates(current_time_iso: str) -> list:
    return await asyncio.to_thread(_sync_get_accountability_candidates, current_time_iso)


def _sync_fetch_and_store_prayer_times(user_id: str, city: str, country: str, date_str: str) -> dict:
    import urllib.request
    import urllib.parse
    import json
    try:
        from zoneinfo import ZoneInfo
    except ImportError:
        # Fallback if zoneinfo is not present
        class UTCZone(datetime.tzinfo):
            def utcoffset(self, dt): return datetime.timedelta(0)
            def tzname(self, dt): return "UTC"
            def dst(self, dt): return datetime.timedelta(0)
        ZoneInfo = lambda x: UTCZone()

    try:
        dt = datetime.datetime.strptime(date_str, "%Y-%m-%d")
        api_date = dt.strftime("%d-%m-%Y")
    except Exception:
        dt = datetime.datetime.now()
        api_date = dt.strftime("%d-%m-%Y")
        date_str = dt.strftime("%Y-%m-%d")

    url = f"http://api.aladhan.com/v1/timingsByCity/{api_date}?city={urllib.parse.quote(city)}&country={urllib.parse.quote(country)}"
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req, timeout=5) as response:
            res_data = json.loads(response.read().decode('utf-8'))
            if res_data.get("code") == 200:
                data = res_data.get("data", {})
                timings = data.get("timings", {})
                timezone_str = data.get("meta", {}).get("timezone", "UTC")
                
                try:
                    tz = ZoneInfo(timezone_str)
                except Exception:
                    tz = datetime.timezone.utc
                
                utc_timings = {}
                for name, t_str in timings.items():
                    # Parse time (like "04:32") and convert to UTC
                    try:
                        # strip any extra info like " (IST)"
                        clean_t_str = t_str.split(" ")[0]
                        hour, minute = map(int, clean_t_str.split(":"))
                        local_dt = datetime.datetime.combine(dt.date(), datetime.time(hour, minute), tzinfo=tz)
                        utc_timings[name] = local_dt.astimezone(datetime.timezone.utc).isoformat()
                    except Exception as ex:
                        print(f"Error parsing time {t_str} for {name}: {ex}")
                
                location = {"city": city, "country": country, "timezone": timezone_str}
                # Store in DB
                return _sync_insert_prayer_times(user_id, date_str, location, utc_timings)
    except Exception as e:
        print(f"Error fetching prayer times: {e}")
    
    # Fallback mock data if offline or error
    print("Using fallback prayer times for Chennai/default...")
    fallback_timings = {
        "Fajr": f"{date_str}T04:30:00+05:30",
        "Dhuhr": f"{date_str}T12:15:00+05:30",
        "Asr": f"{date_str}T15:30:00+05:30",
        "Maghrib": f"{date_str}T18:30:00+05:30",
        "Isha": f"{date_str}T19:45:00+05:30"
    }
    # Convert fallback to UTC for storage
    utc_fallbacks = {}
    for name, t_str in fallback_timings.items():
        try:
            # Parse offset string like +05:30
            dt_parsed = datetime.datetime.fromisoformat(t_str)
            utc_fallbacks[name] = dt_parsed.astimezone(datetime.timezone.utc).isoformat()
        except Exception:
            utc_fallbacks[name] = f"{date_str}T12:00:00Z"
            
    location = {"city": city, "country": country, "timezone": "Asia/Kolkata", "fallback": True}
    return _sync_insert_prayer_times(user_id, date_str, location, utc_fallbacks)


async def fetch_and_store_prayer_times(user_id: str, city: str, country: str, date_str: str) -> dict:
    return await asyncio.to_thread(_sync_fetch_and_store_prayer_times, user_id, city, country, date_str)


