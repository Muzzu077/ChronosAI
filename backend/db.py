import os
import asyncio
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
