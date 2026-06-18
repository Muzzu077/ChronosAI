import os
import datetime
import json
import asyncio
import re
from typing import Annotated
from dotenv import load_dotenv
from livekit.agents import JobContext, WorkerOptions, cli, llm, stt, tts
from livekit.agents.voice import Agent as VoicePipelineAgent, AgentSession
from livekit.plugins import openai, silero
import db
import time_resolver

# Load Environment State
load_dotenv()

# Sanitize environment variables to prevent copy-paste whitespace errors from breaking LiveKit
for key in ["LIVEKIT_API_KEY", "LIVEKIT_API_SECRET", "LIVEKIT_URL", "OPENROUTER_API_KEY", "OPENROUTER_MODEL", "SUPABASE_URL", "SUPABASE_KEY"]:
    if os.getenv(key):
        os.environ[key] = os.environ[key].strip()

OPENROUTER_API_KEY = os.environ.get("OPENROUTER_API_KEY", "")
OPENROUTER_MODEL = os.environ.get("OPENROUTER_MODEL", "openai/gpt-4o-mini")



class ChronosAIFunctionContext(llm.ToolContext):
    def __init__(self, user_id: str):
        super().__init__([])
        self.user_id = user_id

    @llm.function_tool(description="Configure a new Life Template or activate an existing one. Replaces old active templates.")
    async def configure_life_template(
        self,
        template_name: str
    ) -> str:
        """
        Configure a new active Life Template.
        """
        try:
            template = await db.create_life_template(self.user_id, template_name, active=1)
            return f"Successfully created and activated Life Template: '{template_name}' (ID: {template.get('id')}). You can now add daily routine blocks to it."
        except Exception as e:
            print(f"[Agent Tool Error] {str(e)}")
            return f"Error configuring life template: {str(e)}"

    @llm.function_tool(description="Add a time block to the active Life Template. Supported block types: prayer, sleep, college, commute, meal, study, deep_work, personal. Priorities: fixed, preferred, flexible.")
    async def add_template_block(
        self,
        block_name: str,
        start_time: str,
        end_time: str,
        block_type: str,
        priority: str = "preferred"
    ) -> str:
        """
        Add a block to the active Life Template.
        
        Args:
            block_name: Name of the block (e.g. 'Deep Work', 'Sleep', 'Fajr').
            start_time: Start time in HH:MM format (e.g. '20:15', '08:00').
            end_time: End time in HH:MM format (e.g. '01:30', '16:30').
            block_type: Type of the block (prayer, sleep, college, commute, meal, study, deep_work, personal).
            priority: Priority level of the block (fixed, preferred, flexible).
        """
        try:
            active_temp = await db.ensure_default_template(self.user_id)
            tid = active_temp.get("id")
            if not tid:
                return "Error: Could not find or establish an active Life Template."
                
            block = await db.add_life_time_block(tid, block_name, start_time, end_time, block_type, priority)
            return f"Successfully added block '{block_name}' ({block_type}) to Life Template from {start_time} to {end_time} [Priority: {priority}]."
        except Exception as e:
            print(f"[Agent Tool Error] {str(e)}")
            return f"Error adding block to template: {str(e)}"

    @llm.function_tool(description="Schedule a task or reminder for the user's daily planner. Supports exact UTC timestamps and relative phrases like 'after Maghrib', 'tomorrow morning', 'at 4 PM'.")
    async def schedule_reminder(
        self,
        task_description: str,
        target_datetime_iso: str,
        domain: str = "Personal",
        priority: str = "MEDIUM",
        goal_id: str = None
    ) -> str:
        """
        Schedule a task or reminder.
        
        Args:
            task_description: The short description or summary of what task needs to be performed.
            target_datetime_iso: ISO-8601 UTC timestamp or relative phrase (e.g. 'tomorrow morning', 'after Maghrib', 'after college').
            domain: The life domain/category (e.g. Academic, Cybersecurity, AI/ML, Career, Health, Spiritual, Relationships, Personal).
            priority: Interruption priority level (LOW, MEDIUM, HIGH, CRITICAL).
            goal_id: Optional UUID of the goal this task is associated with.
        """
        try:
            resolved_time = await time_resolver.resolve_relative_time(self.user_id, target_datetime_iso)
            print(f"[Agent Tool Invoke] scheduling task: '{task_description}' for user_id: {self.user_id} at {resolved_time} (input: {target_datetime_iso})")
            task = await db.insert_task(self.user_id, task_description, resolved_time)
            task_id = task.get("id")
            
            if task_id:
                # Save task metadata locally
                await db.set_task_metadata(task_id, domain=domain, priority=priority, goal_id=goal_id)
                # Increment scheduled count in focus window
                try:
                    dt_parsed = datetime.datetime.fromisoformat(resolved_time.replace("Z", "+00:00"))
                    await db.update_focus_window(self.user_id, dt_parsed.hour, domain, scheduled_delta=1, completed_delta=0)
                except Exception as ex:
                    print(f"Error updating focus window on insert: {ex}")
                    
            res_msg = f"Successfully scheduled task: '{task_description}' for {resolved_time} UTC (input: '{target_datetime_iso}') under domain '{domain}' with priority {priority}."
            return res_msg
        except Exception as e:
            print(f"[Agent Tool Error] {str(e)}")
            return f"Failed to schedule task: {str(e)}"

    @llm.function_tool(description="Reschedule an existing task to a new date and time. Supports relative time phrases.")
    async def reschedule_task(
        self,
        task_id: str,
        new_datetime_iso: str
    ) -> str:
        """
        Reschedule an existing task.
        
        Args:
            task_id: The UUID of the task to reschedule.
            new_datetime_iso: New ISO-8601 UTC timestamp or relative phrase (e.g. 'tomorrow night', 'after Isha').
        """
        try:
            resolved_time = await time_resolver.resolve_relative_time(self.user_id, new_datetime_iso)
            print(f"[Agent Tool Invoke] rescheduling task {task_id} to {resolved_time} (input: {new_datetime_iso})")
            
            # Fetch old task details to update focus window
            old_tasks = await db.get_user_tasks(self.user_id)
            old_task = next((t for t in old_tasks if t.get("id") == task_id), None)
            
            response = await asyncio.to_thread(
                lambda: db.supabase_client.table("daily_tasks")
                .update({"scheduled_time": resolved_time, "status": "pending"})
                .eq("id", task_id)
                .eq("user_id", self.user_id)
                .execute()
            )
            
            if hasattr(response, "data") and response.data:
                # Increment reschedule count in metadata
                meta = await db.get_task_metadata(task_id)
                new_count = meta.get("reschedule_count", 0) + 1
                await db.set_task_metadata(task_id, reschedule_count=new_count)
                
                # Update focus window
                if old_task:
                    try:
                        old_time_str = old_task.get("scheduled_time")
                        old_dt = datetime.datetime.fromisoformat(old_time_str.replace("Z", "+00:00"))
                        new_dt = datetime.datetime.fromisoformat(resolved_time.replace("Z", "+00:00"))
                        domain = old_task.get("domain", "Personal")
                        
                        await db.update_focus_window(self.user_id, old_dt.hour, domain, scheduled_delta=-1, completed_delta=0)
                        await db.update_focus_window(self.user_id, new_dt.hour, domain, scheduled_delta=1, completed_delta=0)
                    except Exception as ex:
                        print(f"Error shifting focus windows on reschedule: {ex}")
                
                res_msg = f"Successfully rescheduled task to {resolved_time} UTC (input: '{new_datetime_iso}')."
                return res_msg
            return "Task not found or failed to reschedule."
        except Exception as e:
            print(f"[Agent Tool Error] {str(e)}")
            return f"Error rescheduling task: {str(e)}"

    @llm.function_tool(description="Mark an existing task as completed.")
    async def mark_task_complete(
        self,
        task_id: str
    ) -> str:
        """
        Mark an existing task as completed.
        
        Args:
            task_id: The UUID of the task to complete.
        """
        try:
            print(f"[Agent Tool Invoke] marking task completed: {task_id}")
            task = await db.update_task_status(self.user_id, task_id, "completed")
            if task:
                now_date = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d")
                log = await db.get_daily_log(self.user_id, now_date)
                completed_count = log.get("tasks_completed", 0) + 1
                planned_count = max(log.get("tasks_planned", 0), completed_count)
                rate = round((completed_count / planned_count) * 100, 2) if planned_count > 0 else 100.0
                
                await db.update_daily_log(self.user_id, now_date, {
                    "tasks_completed": completed_count,
                    "tasks_planned": planned_count,
                    "completion_rate": rate
                })
                return f"Successfully marked task '{task.get('task_description')}' as completed and updated productivity logs."
            return "Task not found or not updated."
        except Exception as e:
            print(f"[Agent Tool Error] {str(e)}")
            return f"Error marking task complete: {str(e)}"

    @llm.function_tool(description="Update metadata for an existing task, such as domain category, priority level, or goal alignment.")
    async def set_task_metadata(
        self,
        task_id: str,
        domain: str = None,
        priority: str = None,
        goal_id: str = None
    ) -> str:
        """
        Update metadata for an existing task.
        
        Args:
            task_id: The UUID of the task.
            domain: The life domain/category (e.g. Academic, Cybersecurity, AI/ML, Career, Health, Spiritual, Relationships, Personal).
            priority: Interruption priority level (LOW, MEDIUM, HIGH, CRITICAL).
            goal_id: Optional UUID of a goal this task is linked to.
        """
        try:
            print(f"[Agent Tool Invoke] setting metadata for task {task_id}: domain={domain}, priority={priority}, goal={goal_id}")
            meta = await db.set_task_metadata(task_id, domain=domain, priority=priority, goal_id=goal_id)
            return f"Successfully updated task metadata: domain={meta.get('domain')}, priority={meta.get('priority')}, goal_id={meta.get('goal_id')}."
        except Exception as e:
            print(f"[Agent Tool Error] {str(e)}")
            return f"Failed to set task metadata: {str(e)}"

    @llm.function_tool(description="Create a new long-term personal goal (e.g. 'Become SOC Analyst', 'Learn AI Engineering').")
    async def create_goal(
        self,
        title: str,
        description: str = None,
        target_date: str = None
    ) -> str:
        """
        Create a new long-term personal goal.
        
        Args:
            title: Title of the goal.
            description: Detailed description of the goal.
            target_date: Target completion date in YYYY-MM-DD format.
        """
        try:
            print(f"[Agent Tool Invoke] creating goal: '{title}'")
            goal = await db.create_goal(self.user_id, title, description, target_date)
            return f"Successfully created goal '{title}' with ID {goal.get('id')}."
        except Exception as e:
            print(f"[Agent Tool Error] {str(e)}")
            return f"Failed to create goal: {str(e)}"

    @llm.function_tool(description="Retrieve list of all active long-term goals.")
    async def get_goals(self) -> str:
        try:
            print(f"[Agent Tool Invoke] getting goals")
            goals = await db.get_goals(self.user_id)
            if not goals:
                return "No goals found. Create goals using create_goal()."
            
            res = "Here are your active goals:\n"
            for g in goals:
                res += f"- Goal: '{g.get('title')}' (ID: {g.get('id')}), Status: {g.get('status')}, Target Date: {g.get('target_date') or 'none'}\n"
                if g.get("description"):
                    res += f"  Description: {g.get('description')}\n"
            return res
        except Exception as e:
            print(f"[Agent Tool Error] {str(e)}")
            return f"Failed to retrieve goals: {str(e)}"

    @llm.function_tool(description="Log progress update for a specific long-term goal.")
    async def log_goal_progress(
        self,
        goal_id: str,
        progress_value: float,
        notes: str = None
    ) -> str:
        """
        Log progress update for a specific goal.
        
        Args:
            goal_id: The UUID of the goal.
            progress_value: Progress increment or absolute value.
            notes: Brief notes about this progress.
        """
        try:
            print(f"[Agent Tool Invoke] logging goal progress for goal {goal_id}: value={progress_value}")
            log_date = datetime.datetime.utcnow().strftime("%Y-%m-%d")
            await db.log_goal_progress(self.user_id, goal_id, log_date, progress_value, notes)
            return f"Successfully logged progress for goal: {progress_value} on {log_date}."
        except Exception as e:
            print(f"[Agent Tool Error] {str(e)}")
            return f"Failed to log goal progress: {str(e)}"

    @llm.function_tool(description="Retrieve focus windows statistics (best and worst hours based on completion history).")
    async def get_focus_windows(self) -> str:
        try:
            print(f"[Agent Tool Invoke] getting focus windows")
            windows = await db.get_focus_windows(self.user_id)
            if not windows:
                return "No focus windows recorded yet. Begin scheduling and completing tasks to generate patterns."
            
            res = "Productivity by hour of day (Focus Windows):\n"
            sorted_windows = sorted(windows, key=lambda w: w.get("productivity_score", 0.0), reverse=True)
            for w in sorted_windows[:5]:
                res += f"- Hour {w.get('hour_of_day')}:00, Category: {w.get('category')}, Scheduled: {w.get('tasks_scheduled')}, Completed: {w.get('tasks_completed')}, Score: {int(w.get('productivity_score', 0.0)*100)}%\n"
            return res
        except Exception as e:
            print(f"[Agent Tool Error] {str(e)}")
            return f"Failed to retrieve focus windows: {str(e)}"

    @llm.function_tool(description="Generate a comprehensive Weekly Review performance report and next week recommendations.")
    async def weekly_review(self) -> str:
        try:
            print(f"[Agent Tool Invoke] weekly review")
            logs = await db.get_productivity_stats(self.user_id, 7)
            goals = await db.get_goals(self.user_id)
            windows = await db.get_focus_windows(self.user_id)
            
            if not logs:
                return "Not enough history to generate a weekly review. Start completing tasks first."
            
            planned = sum(l.get("tasks_planned", 0) for l in logs)
            completed = sum(l.get("tasks_completed", 0) for l in logs)
            skipped = sum(l.get("tasks_skipped", 0) for l in logs)
            rate = round((completed / planned) * 100, 2) if planned > 0 else 0.0
            
            best_hour = "None"
            if windows:
                sorted_w = sorted(windows, key=lambda w: w.get("productivity_score", 0.0), reverse=True)
                best_w = sorted_w[0]
                best_hour = f"{best_w.get('hour_of_day')}:00 (score {int(best_w.get('productivity_score',0)*100)}%)"
            
            report = (
                "--- CHRONOSAI WEEKLY PERFORMANCE REVIEW ---\n"
                f"- Tasks Planned: {planned}\n"
                f"- Tasks Completed: {completed}\n"
                f"- Tasks Skipped: {skipped}\n"
                f"- Average Completion Rate: {rate}%\n"
                f"- Best Focus Window: {best_hour}\n\n"
                "Long-Term Goals Progress:\n"
            )
            for g in goals[:3]:
                progress = await db.get_goal_progress(self.user_id, g.get("id"))
                latest_prog = len(progress) if progress else 0
                report += f"- '{g.get('title')}': {latest_prog} progress logs\n"
                
            report += (
                "\nRecommendations for next week:\n"
                "1. Shift high-intensity work to your peak focus windows.\n"
                "2. Balance spiritual and academic/career domains to keep up productivity."
            )
            return report
        except Exception as e:
            print(f"[Agent Tool Error] {str(e)}")
            return f"Failed to generate weekly review: {str(e)}"

    @llm.function_tool(description="Save or update a preference/fact in the user's memory (e.g. sleep hours, college timings, career goals).")
    async def save_user_memory(
        self,
        memory_key: str,
        memory_value: str
    ) -> str:
        """
        Save or update a preference/fact in the user's memory.
        
        Args:
            memory_key: The key of the memory (e.g., 'college_timings', 'career_goal', 'sleep_pattern').
            memory_value: The details or description value for this memory.
        """
        try:
            print(f"[Agent Tool Invoke] saving memory: {memory_key} = {memory_value}")
            await db.save_agent_memory(self.user_id, memory_key, {"value": memory_value})
            return f"Successfully saved to memory: {memory_key} = {memory_value}."
        except Exception as e:
            print(f"[Agent Tool Error] {str(e)}")
            return f"Failed to save memory: {str(e)}"

    @llm.function_tool(description="Retrieve a preference or fact from the user's memory.")
    async def get_user_memory(
        self,
        memory_key: str
    ) -> str:
        """
        Retrieve a preference or fact from the user's memory.
        
        Args:
            memory_key: The memory key to lookup.
        """
        try:
            print(f"[Agent Tool Invoke] retrieving memory: {memory_key}")
            mem = await db.get_agent_memory(self.user_id, memory_key)
            if mem:
                val = mem.get("memory_value", {}).get("value")
                return f"Memory for {memory_key}: {val}"
            return f"No memory found for key: {memory_key}"
        except Exception as e:
            print(f"[Agent Tool Error] {str(e)}")
            return f"Error retrieving memory: {str(e)}"

    @llm.function_tool(description="Fetch daily prayer timings for a given city and country and save them to the scheduler.")
    async def fetch_prayer_times(
        self,
        city: str,
        country: str,
        date_str: str
    ) -> str:
        """
        Fetch daily prayer timings.
        
        Args:
            city: City name (e.g. 'Chennai').
            country: Country name (e.g. 'India').
            date_str: Date in YYYY-MM-DD format.
        """
        try:
            print(f"[Agent Tool Invoke] fetching prayer times for {city}, {country} on {date_str}")
            res = await db.fetch_and_store_prayer_times(self.user_id, city, country, date_str)
            if res:
                return (
                    f"Successfully fetched prayer times for {city}, {country} on {date_str}. "
                    f"Fajr: {res.get('fajr')}, Dhuhr: {res.get('dhuhr')}, Asr: {res.get('asr')}, "
                    f"Maghrib: {res.get('maghrib')}, Isha: {res.get('isha')}."
                )
            return "Failed to fetch prayer times."
        except Exception as e:
            print(f"[Agent Tool Error] {str(e)}")
            return f"Error fetching prayer times: {str(e)}"

    @llm.function_tool(description="Record that the user completed a specific prayer (Fajr, Dhuhr, Asr, Maghrib, Isha).")
    async def record_prayer(
        self,
        prayer_name: str,
        date_str: str
    ) -> str:
        """
        Record that the user completed a specific prayer.
        
        Args:
            prayer_name: Name of the prayer.
            date_str: Date in YYYY-MM-DD format.
        """
        try:
            print(f"[Agent Tool Invoke] recording prayer: {prayer_name} on {date_str}")
            mem_key = f"prayer_log_{date_str}"
            existing = await db.get_agent_memory(self.user_id, mem_key)
            prayers_list = []
            if existing:
                prayers_list = existing.get("memory_value", {}).get("prayers", [])
            
            clean_name = prayer_name.strip().capitalize()
            if clean_name not in prayers_list:
                prayers_list.append(clean_name)
                
            await db.save_agent_memory(self.user_id, mem_key, {"prayers": prayers_list})
            return f"Recorded completion of {clean_name} prayer for {date_str}."
        except Exception as e:
            print(f"[Agent Tool Error] {str(e)}")
            return f"Error recording prayer: {str(e)}"

    @llm.function_tool(description="Generate and save an hourly daily schedule block for the user.")
    async def generate_schedule(
        self,
        schedule_date: str,
        summary: str,
        blocks_json: str
    ) -> str:
        """
        Generate and save an hourly daily schedule block.
        
        Args:
            schedule_date: Date in YYYY-MM-DD format.
            summary: Brief summary of the day's goals.
            blocks_json: JSON array of blocks, e.g. '[{"start": "08:00", "end": "09:00", "activity": "AI assignment", "notes": "Avoid prayer overlap"}]'.
        """
        try:
            print(f"[Agent Tool Invoke] generating schedule for {schedule_date}")
            blocks_list = json.loads(blocks_json)
            await db.save_user_schedule(self.user_id, schedule_date, summary, blocks_list)
            
            # Replicate each block as a task in the daily_tasks table so it displays on the Schedule screen
            for block in blocks_list:
                activity = block.get("activity", "Scheduled Task")
                start_time = block.get("start", "09:00")
                notes = block.get("notes", "")
                
                # Construct local iso timestamp
                local_iso = f"{schedule_date}T{start_time}:00"
                # Resolve to UTC using our robust resolver
                resolved_utc = await time_resolver.resolve_relative_time(self.user_id, local_iso)
                
                task_description = activity
                if notes:
                    task_description += f" ({notes})"
                
                print(f"[generate_schedule] Replicating block '{task_description}' to daily_tasks at {resolved_utc} UTC")
                task = await db.insert_task(self.user_id, task_description, resolved_utc)
                task_id = task.get("id")
                if task_id:
                    # Set metadata
                    domain = block.get("domain", "Personal")
                    priority = block.get("priority", "MEDIUM")
                    await db.set_task_metadata(task_id, domain=domain, priority=priority)
                    # Increment focus window scheduled count
                    try:
                        hour = int(start_time.split(":")[0])
                        await db.update_focus_window(self.user_id, hour, domain, scheduled_delta=1, completed_delta=0)
                    except Exception as fw_err:
                        print(f"Error updating focus window in generate_schedule: {fw_err}")

            log = await db.get_daily_log(self.user_id, schedule_date)
            planned_count = max(log.get("tasks_planned", 0), len(blocks_list))
            completed_count = log.get("tasks_completed", 0)
            rate = round((completed_count / planned_count) * 100, 2) if planned_count > 0 else 100.0
            
            await db.update_daily_log(self.user_id, schedule_date, {
                "tasks_planned": planned_count,
                "completion_rate": rate
            })
            return f"Successfully generated and saved schedule for {schedule_date} with {len(blocks_list)} blocks, replicated as tasks to the daily planner."
        except Exception as e:
            print(f"[Agent Tool Error] {str(e)}")
            return f"Error generating schedule: {str(e)}"

    @llm.function_tool(description="Analyze productivity and task completion rates for the user over the last week.")
    async def analyze_productivity(self) -> str:
        try:
            print(f"[Agent Tool Invoke] analyzing productivity")
            stats = await db.get_productivity_stats(self.user_id, 7)
            if not stats:
                return "No productivity statistics recorded yet. Start completing tasks to generate analytics."
            
            summary = "Here are your productivity stats for the last 7 days:\n"
            for s in stats:
                summary += (
                    f"- Date: {s.get('log_date')}, Tasks Planned: {s.get('tasks_planned')}, "
                    f"Completed: {s.get('tasks_completed')}, Skipped: {s.get('tasks_skipped')}, "
                    f"Completion Rate: {s.get('completion_rate')}%\n"
                )
            return summary
        except Exception as e:
            print(f"[Agent Tool Error] {str(e)}")
            return f"Error analyzing productivity: {str(e)}"

import urllib.request
import time

async def query_whisper(wav_bytes, hf_token):
    # Whisper large v3 turbo
    try:
        from huggingface_hub import InferenceClient
        client = InferenceClient(token=hf_token, provider='hf-inference')
        # We can pass wav_bytes directly to automatic_speech_recognition
        output = client.automatic_speech_recognition(wav_bytes, model="openai/whisper-large-v3-turbo")
        if output and getattr(output, "text", None):
            return output.text
    except Exception as e:
        print(f"[InferenceClient Whisper Error] {e}, trying raw HTTP request to router...", flush=True)

    # Fallback to direct request with new router URL
    url = "https://router.huggingface.co/hf-inference/models/openai/whisper-large-v3-turbo"
    headers = {
        "Authorization": f"Bearer {hf_token}",
        "Content-Type": "audio/wav"
    }
    
    loop = asyncio.get_event_loop()
    
    def _send():
        # Retry up to 10 times with delay for cold starts
        for attempt in range(10):
            try:
                req = urllib.request.Request(url, data=wav_bytes, headers=headers, method="POST")
                with urllib.request.urlopen(req) as res:
                    resp_bytes = res.read()
                    data = json.loads(resp_bytes.decode("utf-8"))
                    if isinstance(data, dict) and "text" in data:
                        return data["text"]
                    elif isinstance(data, dict) and "error" in data and "loading" in data["error"]:
                        print(f"[HF STT] Model is loading, waiting 3s (attempt {attempt+1}/10)...", flush=True)
                        time.sleep(3)
                        continue
            except Exception as e:
                # Check for HTTPError 503 or model loading
                if hasattr(e, "code") and e.code == 503:
                    print(f"[HF STT] Model is loading (503 Service Unavailable), waiting 3s (attempt {attempt+1}/10)...", flush=True)
                    time.sleep(3)
                    continue
                print(f"[HF STT API Request Error] {e}", flush=True)
                break
        return ""
        
    return await loop.run_in_executor(None, _send)

async def query_tts(text, hf_token):
    eleven_key = (os.getenv("ELEVEN_API_KEY") or os.getenv("ELEVENLABS_API_KEY") or "").strip()
    if eleven_key:
        print("[HF TTS] Using ElevenLabs for Text-to-Speech synthesis...", flush=True)
        voice_id = (os.getenv("ELEVEN_VOICE_ID") or "Akash").strip()

        voice_map = {
            "Akash": "Akash",
            "Rachel": "21m00Tcm4TlvDq8ikWAM",
            "Domi": "AZnzlk1XvdvUeBnXmlld",
            "Bella": "EXAVITQu4vr4xnSDxMaL",
            "Antoni": "ErXwobaYiN019PkySvjV",
            "Josh": "KYF276x1DGJy24tD5sqP",
            "Patrick": "ODq5Fm9bW3CXAxnsPy57"
        }
        actual_voice = voice_map.get(voice_id, voice_id)
        url = f"https://api.elevenlabs.io/v1/text-to-speech/{actual_voice}/stream?output_format=pcm_16000"
        headers = {
            "xi-api-key": eleven_key,
            "Content-Type": "application/json"
        }
        payload = {
            "text": text,
            "model_id": "eleven_multilingual_v2",
            "voice_settings": {
                "stability": 0.5,
                "similarity_boost": 0.75
            }
        }
        
        loop = asyncio.get_event_loop()
        
        def _send_eleven():
            try:
                req = urllib.request.Request(url, data=json.dumps(payload).encode("utf-8"), headers=headers, method="POST")
                with urllib.request.urlopen(req) as res:
                    return res.read()
            except Exception as e:
                print(f"[ElevenLabs TTS Error] {e}", flush=True)
                if hasattr(e, "read"):
                    print("Error details:", e.read().decode("utf-8"))
                return None
                
        audio = await loop.run_in_executor(None, _send_eleven)
        if audio:
            return audio, 16000, 1

    # Fallback to edge-tts (High quality, free, Microsoft Edge TTS)
    try:
        import edge_tts
        import av
        import io
        
        voice_id = (os.getenv("ELEVEN_VOICE_ID") or "Neerja").strip()
        voice_map = {
            "Neerja": "en-IN-NeerjaNeural",
            "Prabhat": "en-IN-PrabhatNeural",
            "Akash": "en-IN-NeerjaNeural"
        }
        edge_voice = voice_map.get(voice_id, "en-IN-NeerjaNeural")
        
        print(f"[edge-tts] Synthesizing speech using voice: {edge_voice}...", flush=True)
        communicate = edge_tts.Communicate(text, edge_voice)
        mp3_bytes = b""
        
        async def _run_edge_tts():
            nonlocal mp3_bytes
            async for chunk in communicate.stream():
                if chunk["type"] == "audio":
                    mp3_bytes += chunk["data"]
                    
        # Since we are in an async def query_tts, we can directly await
        await _run_edge_tts()
                
        if mp3_bytes:
            # Decode MP3 to PCM 16000Hz mono using PyAV
            input_file = io.BytesIO(mp3_bytes)
            container = av.open(input_file)
            resampler = av.AudioResampler(format='s16', layout='mono', rate=16000)
            
            pcm_chunks = []
            stream = container.streams.audio[0]
            for frame in container.decode(stream):
                resampled_frames = resampler.resample(frame)
                for rf in resampled_frames:
                    pcm_chunks.append(bytes(rf.planes[0]))
            
            pcm_data = b"".join(pcm_chunks)
            if pcm_data:
                print(f"[edge-tts] Successfully synthesized {len(pcm_data)} PCM bytes", flush=True)
                return pcm_data, 16000, 1
    except Exception as e:
        print(f"[edge-tts Error] {e}. Falling back to Hugging Face serverless...", flush=True)

    # Secondary Fallback to Hugging Face router API
    url = "https://router.huggingface.co/hf-inference/models/ai4bharat/indic-parler-tts"
    headers = {
        "Authorization": f"Bearer {hf_token}",
        "Content-Type": "application/json"
    }
    payload = {
        "inputs": text,
        "parameters": {
            "description": "A female speaker with a clear and natural Indian English accent, high-quality audio, studio recording."
        }
    }
    
    loop = asyncio.get_event_loop()
    
    def _send_hf():
        for attempt in range(5):
            try:
                req = urllib.request.Request(url, data=json.dumps(payload).encode("utf-8"), headers=headers, method="POST")
                with urllib.request.urlopen(req) as res:
                    return res.read()
            except Exception as e:
                if hasattr(e, "code") and e.code == 503:
                    print(f"[HF TTS] Model is loading (503 Service Unavailable), waiting 3s (attempt {attempt+1}/5)...", flush=True)
                    time.sleep(3)
                    continue
                print(f"[HF TTS API Request Error] {e}", flush=True)
                break
        return None
        
    wav_bytes = await loop.run_in_executor(None, _send_hf)
    if wav_bytes:
        try:
            import io
            import wave
            wav_io = io.BytesIO(wav_bytes)
            with wave.open(wav_io, "rb") as wav_file:
                sample_rate = wav_file.getframerate()
                num_channels = wav_file.getnchannels()
                pcm_data = wav_file.readframes(wav_file.getnframes())
                return pcm_data, sample_rate, num_channels
        except Exception as e:
            print(f"[HF TTS] Error parsing WAV bytes: {e}", flush=True)
            
    return None, 16000, 1

class HFSTT(stt.STT):
    def __init__(self, hf_token: str = None):
        super().__init__(
            capabilities=stt.STTCapabilities(streaming=False, interim_results=False, diarization=False)
        )
        self.hf_token = hf_token or os.getenv("HF_TOKEN") or os.getenv("HUGGINGFACE_TOKEN")

    async def _recognize_impl(self, buffer, *, language=None, conn_options=None):
        try:
            if isinstance(buffer, list):
                frames = buffer
            else:
                frames = [buffer]
            
            if not frames:
                return stt.SpeechEvent(
                    type=stt.SpeechEventType.FINAL_TRANSCRIPT,
                    alternatives=[stt.SpeechData(text="", language="en")]
                )
                
            sample_rate = frames[0].sample_rate
            num_channels = frames[0].num_channels
            pcm_data = b"".join(f.data for f in frames)
            
            import io
            import wave
            wav_buf = io.BytesIO()
            with wave.open(wav_buf, "wb") as wav_file:
                wav_file.setnchannels(num_channels)
                wav_file.setsampwidth(2)
                wav_file.setframerate(sample_rate)
                wav_file.writeframes(pcm_data)
            wav_bytes = wav_buf.getvalue()
            
            text = await query_whisper(wav_bytes, self.hf_token)
            print(f"[HF STT] Transcribed text: '{text}'", flush=True)
            
            return stt.SpeechEvent(
                type=stt.SpeechEventType.FINAL_TRANSCRIPT,
                alternatives=[stt.SpeechData(text=text, language="en")]
            )
        except Exception as e:
            print(f"[HF STT Error in _recognize_impl] {e}", flush=True)
            return stt.SpeechEvent(
                type=stt.SpeechEventType.FINAL_TRANSCRIPT,
                alternatives=[stt.SpeechData(text="", language="en")]
            )

class HFTTSChunkedStream(tts.ChunkedStream):
    async def _run(self, output_emitter: tts.AudioEmitter) -> None:
        text = self._input_text
        print(f"[Agent Speak (HF TTS)] Synthesizing: '{text[:60]}'", flush=True)
        
        pcm_data, sample_rate, num_channels = await query_tts(text, self._tts.hf_token)
        
        # Always initialize the emitter to prevent 'AudioEmitter isn't started' crash
        output_emitter.initialize(
            request_id=self._conn_options.request_id if self._conn_options else "hf-tts-id",
            sample_rate=sample_rate or 16000,
            num_channels=num_channels or 1,
            mime_type="audio/pcm"
        )
        
        if not pcm_data:
            print("[Agent Speak (HF TTS)] No audio produced, pushing silence", flush=True)
            # Push a short silence frame so the pipeline doesn't stall
            output_emitter.push(b'\x00' * 3200)
            return
        
        chunk_size = 4000
        for i in range(0, len(pcm_data), chunk_size):
            chunk = pcm_data[i:i+chunk_size]
            output_emitter.push(chunk)

class HFTTS(tts.TTS):
    def __init__(self, hf_token: str = None):
        super().__init__(
            capabilities=tts.TTSCapabilities(streaming=False, aligned_transcript=False),
            sample_rate=16000,
            num_channels=1
        )
        self.hf_token = hf_token or os.getenv("HF_TOKEN") or os.getenv("HUGGINGFACE_TOKEN")

    def synthesize(self, text: str, *, conn_options=None) -> tts.ChunkedStream:
        return HFTTSChunkedStream(tts=self, input_text=text, conn_options=conn_options)


async def entrypoint(ctx: JobContext):
    await ctx.connect()
    user_id = ctx.room.name.replace("room-", "") if ctx.room.name else "00000000-0000-0000-0000-000000000000"
    
    print(f"[Agent Startup] Loading memories for user: {user_id}")
    memories = await db.get_agent_memories(user_id)
    profile = await db.get_user_profile(user_id)
    goals = await db.get_goals(user_id)
    focus_windows = await db.get_focus_windows(user_id)
    behavior_patterns = await db.get_behavior_patterns(user_id)
    
    tz_str = profile.get("timezone") or "Asia/Kolkata"
    from zoneinfo import ZoneInfo
    try:
        user_tz = ZoneInfo(tz_str)
    except Exception:
        user_tz = ZoneInfo("Asia/Kolkata")
        
    now_local = datetime.datetime.now(user_tz)
    current_time_str = now_local.strftime("%A, %B %d, %Y at %I:%M %p ") + tz_str
    today_date_str = now_local.strftime("%Y-%m-%d")
    
    prayers = await db.get_prayer_times(user_id, today_date_str)
    
    active_template = await db.ensure_default_template(user_id)
    template_id = active_template.get("id")
    template_blocks = await db.get_life_template_blocks(template_id) if template_id else []
    
    # Construct Memory Summary
    memory_summary = ""
    for m in memories:
        memory_summary += f"- {m['memory_key']}: {m['memory_value'].get('value') if isinstance(m['memory_value'], dict) else m['memory_value']}\n"
        
    # Construct Goals Summary
    goals_summary = ""
    if goals:
        goals_summary = "\nActive Goals:\n"
        for g in goals:
            goals_summary += f"- '{g.get('title')}' (Status: {g.get('status')}, Target: {g.get('target_date') or 'none'})\n"
    else:
        goals_summary = "\nNo active long-term goals set yet."

    # Construct Focus Windows Summary
    focus_summary = ""
    if focus_windows:
        focus_summary = "\nFocus Windows (Hour & Category Productivity):\n"
        sorted_fw = sorted(focus_windows, key=lambda w: w.get("productivity_score", 0.0), reverse=True)
        for w in sorted_fw[:5]:
            focus_summary += f"- Hour {w.get('hour_of_day')}:00 ({w.get('category')}): {w.get('tasks_completed')}/{w.get('tasks_scheduled')} completed (Score: {int(w.get('productivity_score', 0.0)*100)}%)\n"
    else:
        focus_summary = "\nNo focus windows patterns recorded yet."

    # Construct Behavior Patterns
    patterns_summary = ""
    if behavior_patterns:
        patterns_summary = "\nLearned Behavioral Patterns:\n"
        for k, v in behavior_patterns.items():
            patterns_summary += f"- {k}: {v}\n"
    else:
        patterns_summary = "\nNo learned behavioral patterns recorded yet."
        
    # Construct Prayer Summary
    prayer_summary = "No prayer times fetched yet for today."
    if prayers:
        prayer_summary = (
            f"Fajr: {prayers.get('fajr')}, Dhuhr: {prayers.get('dhuhr')}, "
            f"Asr: {prayers.get('asr')}, Maghrib: {prayers.get('maghrib')}, Isha: {prayers.get('isha')}"
        )
        
    # Construct Life Template Blocks Summary
    template_summary = ""
    if template_blocks:
        template_summary = f"\nActive Life Template Routine ('{active_template.get('template_name')}'):\n"
        for b in template_blocks:
            template_summary += f"- Block: '{b.get('block_name')}' | Type: {b.get('block_type')} | Time: {b.get('start_time')} - {b.get('end_time')} | Priority: {b.get('priority')}\n"
    else:
        template_summary = "\nNo active life template blocks configured."
        
    all_tasks = await db.get_user_tasks(user_id)
    incomplete_tasks = [t for t in all_tasks if t.get("status") in ("pending", "reminded")]
    incomplete_summary = ""
    for t in incomplete_tasks:
        incomplete_summary += f"- Task: '{t.get('task_description')}' (ID: {t.get('id')}) scheduled at {t.get('scheduled_time')}\n"

    system_prompt = (
        "You are 'ChronosAI', a highly advanced AI Chief of Staff, daily planner voice assistant, and behavioral coach.\n"
        "You speak with a professional, warm, supportive, and actionable composure.\n"
        "Your goal is to parse user scheduling commands, clarify details, optimize daily schedules, and manage memory.\n"
        "You MUST keep your verbal responses highly concise, direct, and tailored for oral communication.\n\n"
        "Time context (CRITICAL):\n"
        f"The server current local date and time is {current_time_str}.\n"
        "Use this as your absolute factual reference point for evaluating all relative timeline queries.\n"
        "When scheduling a task or reminder, always pass the raw relative time phrase (e.g. 'after 5 minutes', 'tomorrow morning', 'after Maghrib') directly to the `schedule_reminder` tool as `target_datetime_iso`, rather than trying to calculate or compute the absolute ISO timestamp yourself.\n\n"
        f"User Memory / Profile:\n{memory_summary if memory_summary else 'No prior memory stored.'}\n"
        f"User Active Life Template Blocks:\n{template_summary}\n"
        f"{patterns_summary}\n"
        f"{goals_summary}\n"
        f"{focus_summary}\n"
        f"Today's Prayer Times:\n{prayer_summary}\n\n"
        f"Pending/Incomplete Tasks requiring accountability:\n{incomplete_summary if incomplete_summary else 'None.'}\n\n"
        "Life Template Rules (CRITICAL):\n"
        "1. Daily Planning & Distributing Goals: When automatically planning or distributing general tasks/goals (e.g., 'Finish AI assignment', 'Study math'), you MUST schedule them inside the user's available preferred/flexible template blocks (e.g., Deep Work block, Study block) and completely avoid fixed blocks like sleep, prayers, or college.\n"
        "2. Direct User Reminders & Timers: If the user directly commands you to set a reminder or schedule a task at a specific time or time offset (e.g., 'remind me to drink water after 5 minutes', 'remind me at 11 AM', 'put it now'), you MUST schedule it exactly when requested. Do not refuse, do not block it, and do not suggest a different time, even if it falls within a Sleep, Prayer, or College block. User direct commands ALWAYS take absolute precedence over the template restrictions.\n"
        "3. Every day, the user only provides goals (e.g., 'Finish AI assignment'). You must place them into available blocks and call 'generate_schedule' to persist the timeline.\n"
        "4. Avoid placing difficult tasks in hours where focus_windows indicate low productivity.\n\n"
        "Behavioral Coach Guidelines:\n"
        "1. Behavioral Learning: Analyze completion rates. Notice when the user is productive (e.g. 9 PM - 1 AM) vs when they skip (e.g. 5 PM - 7 PM). Proactively coach them: 'I've noticed you complete 95% of tasks late at night but skip early evening work. Let's move this to your peak window.'\n"
        "2. Adaptive Scheduling: When scheduling or rescheduling, prefer historical focus windows and avoid placing important work in low-productivity windows. Automatically reduce overload if they are swamped.\n"
        "3. Long-Term Goals: Every task contributes toward goals like 'Become SOC Analyst' or 'Learn AI Engineering'. Proactively highlight progress: 'This task will help you progress on your Cybersecurity path.'\n"
        "4. Interruption Priorities (LOW, MEDIUM, HIGH, CRITICAL):\n"
        "   - LOW: Silent banner notification only (no voice).\n"
        "   - MEDIUM: Notification + light voice prompt.\n"
        "   - HIGH: Detailed active voice session.\n"
        "   - CRITICAL: High-urgency repeated alert.\n"
        "5. Chief of Staff Mode & Autonomous Proactivity:\n"
        "   - Act like an autonomous, proactive Chief of Staff, NOT a passive chatbot. Do not just wait for commands.\n"
        "   - Automatically call relevant tools (like `generate_schedule`, `analyze_productivity`, `get_focus_windows`) to evaluate and optimize the user's routine, without waiting for the user to ask you to do so.\n"
        "   - Proactively suggest optimizations. If the user joins the session, immediately analyze their schedule, identify bottlenecks, highlight goal alignment, and suggest concrete schedule blocks.\n"
        "   - Provide accountability checks dynamically. If a task was scheduled but not completed, ask for the reason, learn from it, and adjust their future schedule template automatically.\n"
        "6. Voice Conversation Cycle: Prompt the user after executing tools. Ask clarifying questions, guide them through daily planning."
    )
    
    initial_ctx = llm.ChatContext()
    initial_ctx.add_message(role="system", content=system_prompt)
    
    # Load LLM: Prefer Hugging Face Serverless Qwen 2.5 72B Instruct if HF_TOKEN is available,
    # otherwise fall back to OpenRouter.
    hf_token = os.getenv("HF_TOKEN") or os.getenv("HUGGINGFACE_TOKEN")
    if hf_token:
        print("[Agent Startup] Initializing Hugging Face Serverless LLM (Qwen/Qwen2.5-72B-Instruct)...", flush=True)
        openrouter_llm = openai.LLM(
            model="Qwen/Qwen2.5-72B-Instruct",
            api_key=hf_token,
            base_url="https://router.huggingface.co/v1"
        )
    else:
        print("[Agent Startup] Initializing OpenRouter LLM...", flush=True)
        openrouter_llm = openai.LLM(
            model=OPENROUTER_MODEL,
            api_key=OPENROUTER_API_KEY,
            base_url="https://openrouter.ai/api/v1"
        )
    
    # Load VAD model ONCE and reuse to avoid ~200MB wasted RAM
    vad_model = silero.VAD.load()
    stt_plugin = stt.StreamAdapter(stt=HFSTT(), vad=vad_model)
    tts_plugin = tts.StreamAdapter(tts=HFTTS())
    
    fnc_ctx = ChronosAIFunctionContext(user_id)
    agent = VoicePipelineAgent(
        vad=vad_model,
        stt=stt_plugin,
        llm=openrouter_llm,
        tts=tts_plugin,
        chat_ctx=initial_ctx,
        tools=fnc_ctx.flatten(),
        instructions=system_prompt
    )
    
    conversation_transcript = []
    MAX_TRANSCRIPT_LINES = 100

    session = AgentSession()

    @session.on("conversation_item_added")
    def on_item_added(event):
        item = event.item
        if isinstance(item, llm.ChatMessage):
            role = item.role
            text = item.text_content
            if text:
                if role == "user":
                    formatted_text = f"You: {text}"
                elif role == "assistant":
                    formatted_text = f"ChronosAI: {text}"
                else:
                    return
                print(f"[Transcript] ({role}): {text[:80]}")
                if not conversation_transcript or conversation_transcript[-1] != formatted_text:
                    conversation_transcript.append(formatted_text)
                    if len(conversation_transcript) > MAX_TRANSCRIPT_LINES:
                        del conversation_transcript[:len(conversation_transcript) - MAX_TRANSCRIPT_LINES]
                payload = formatted_text.encode('utf-8')
                asyncio.create_task(ctx.room.local_participant.publish_data(payload=payload, reliable=True))

    await session.start(agent, room=ctx.room)

    greeting_triggered = False

    async def save_conversation_summary():
        if not conversation_transcript:
            return
        clean_lines = [l for l in conversation_transcript if not l.startswith("SYSTEM_")]
        if not clean_lines:
            return
        history_text = "\n".join(clean_lines[-20:])
        summary_prompt = (
            "You are a summarization assistant. Summarize the key scheduling requests, "
            "user preferences, completed tasks, and reschedulings discussed in the following "
            "conversation in 2-3 bullet points. Keep it extremely concise and direct.\n\n"
            f"Conversation:\n{history_text}"
        )
        try:
            sum_ctx = llm.ChatContext()
            sum_ctx.add_message(role="user", content=summary_prompt)
            stream = openrouter_llm.chat(chat_ctx=sum_ctx)
            summary = ""
            async for chunk in stream:
                if chunk.delta and chunk.delta.content:
                    summary += chunk.delta.content
            if summary.strip():
                existing = await db.get_agent_memory(user_id, "conversation_summary")
                past_summaries = ""
                if existing:
                    val = existing.get("memory_value")
                    if isinstance(val, dict):
                        past_summaries = val.get("value", "")
                    else:
                        past_summaries = str(val)
                
                import datetime as dt
                try:
                    local_tz = ZoneInfo(tz_str)
                except Exception:
                    local_tz = datetime.timezone.utc
                now_local_ts = dt.datetime.now(local_tz)
                timestamp = now_local_ts.strftime("%Y-%m-%d %I:%M %p")
                
                new_entry = f"[{timestamp}] {summary.strip()}"
                if past_summaries:
                    new_history = f"{past_summaries}\n{new_entry}"
                else:
                    new_history = new_entry
                
                lines = new_history.split("\n")
                if len(lines) > 5:
                    new_history = "\n".join(lines[-5:])
                    
                await db.save_agent_memory(user_id, "conversation_summary", {"value": new_history})
                print(f"[Agent Summary Save] Success")
        except Exception as e:
            print(f"[Agent Summary Save Error] {e}")

    def publish_text(text: str):
        payload = text.encode('utf-8')
        asyncio.create_task(ctx.room.local_participant.publish_data(payload=payload, reliable=True))

    def agent_say(text: str):
        if not text.startswith("SYSTEM_NOTIFICATION:"):
            session.say(text, allow_interruptions=True)
            conversation_transcript.append(f"ChronosAI: {text}")
            if len(conversation_transcript) > MAX_TRANSCRIPT_LINES:
                del conversation_transcript[:len(conversation_transcript) - MAX_TRANSCRIPT_LINES]
            asyncio.create_task(save_conversation_summary())
        else:
            content = text.replace("SYSTEM_NOTIFICATION:", "").strip()
            conversation_transcript.append(f"ChronosAI [Silent Notification]: {content}")
            if len(conversation_transcript) > MAX_TRANSCRIPT_LINES:
                del conversation_transcript[:len(conversation_transcript) - MAX_TRANSCRIPT_LINES]
            publish_text(text)

    def process_system_message(msg: str):
        nonlocal greeting_triggered
        greeting_triggered = True
        try:
            if msg.startswith("SYSTEM_REMINDER:"):
                payload = msg.replace("SYSTEM_REMINDER:", "").strip()
                parts = payload.split(" | ")
                if len(parts) >= 3:
                    task_id, priority, reminder_text = parts[0], parts[1], " | ".join(parts[2:])
                elif len(parts) == 2:
                    task_id, priority, reminder_text = parts[0], "MEDIUM", parts[1]
                else:
                    task_id, priority, reminder_text = "unknown", "MEDIUM", payload
                
                print(f"[Agent] System reminder for task {task_id} ({priority}): {reminder_text}")
                
                reminder_lower = reminder_text.lower()
                is_checkpoint = False
                cp_type = ""
                if "checkpoint:" in reminder_lower:
                    cp_type = reminder_text.split(":")[-1].strip().upper().replace(" ", "_")
                    is_checkpoint = True
                elif "morning standup" in reminder_lower:
                    cp_type = "MORNING_STANDUP"
                    is_checkpoint = True
                elif "deep work start" in reminder_lower:
                    cp_type = "DEEP_WORK_START"
                    is_checkpoint = True
                elif "accountability check" in reminder_lower:
                    cp_type = "ACCOUNTABILITY_CHECK"
                    is_checkpoint = True
                elif "day review" in reminder_lower:
                    cp_type = "DAY_REVIEW"
                    is_checkpoint = True
                
                if is_checkpoint:
                    async def handle_checkpoint():
                        import uuid as _uuid
                        try:
                            _uuid.UUID(task_id)
                            await db.update_task_status(user_id, task_id, "completed")
                        except (ValueError, Exception) as cp_err:
                            print(f"Checkpoint status update: {cp_err}")
                        
                        try:
                            _profile = await db.get_user_profile(user_id)
                            _tz_str = _profile.get("timezone") or "Asia/Kolkata"
                            try:
                                _tz = ZoneInfo(_tz_str)
                            except Exception:
                                _tz = ZoneInfo("Asia/Kolkata")
                            local_date_str = datetime.datetime.now(_tz).strftime("%Y-%m-%d")
                            cp_id = f"checkpoint_{user_id}_{cp_type}_{local_date_str}"
                            
                            def _mark_cp(cp_id_val, now_val):
                                import sqlite3 as _sq
                                conn = _sq.connect(db.DB_FILE)
                                conn.execute("PRAGMA busy_timeout=5000")
                                conn.execute(
                                    "INSERT INTO task_status_tracker (task_id, status, updated_at) VALUES (?, 'reminded', ?) ON CONFLICT(task_id) DO UPDATE SET status = 'reminded', updated_at = ?",
                                    (cp_id_val, now_val, now_val))
                                conn.commit()
                                conn.close()
                            
                            await asyncio.to_thread(_mark_cp, cp_id, datetime.datetime.now(datetime.timezone.utc).isoformat())
                        except Exception as local_err:
                            print(f"Checkpoint local mark error: {local_err}")
                            
                        await asyncio.sleep(1.0)
                        
                        if cp_type == "MORNING_STANDUP":
                            agent_say("Good morning. Today's routine schedule is loaded. What are your main priorities or goals today?")
                        elif cp_type == "DEEP_WORK_START":
                            tasks_list = await db.get_user_tasks(user_id)
                            pending_tasks = [t for t in tasks_list if t.get("status") in ("pending", "reminded") and "checkpoint" not in t.get("task_description", "").lower()]
                            task_bullets = "".join([f"\n{i+1}. {t.get('task_description')}" for i, t in enumerate(pending_tasks[:3])])
                            if task_bullets:
                                agent_say(f"Your Deep Work session is starting now. Today's tasks are:{task_bullets}\nWhich task would you like to begin first?")
                            else:
                                agent_say("Your Deep Work session is starting now. You don't have any tasks scheduled. What would you like to focus on?")
                        elif cp_type == "ACCOUNTABILITY_CHECK":
                            tasks_list = await db.get_user_tasks(user_id)
                            pending_tasks = [t for t in tasks_list if t.get("status") in ("pending", "reminded") and "checkpoint" not in t.get("task_description", "").lower()]
                            if pending_tasks:
                                agent_say(f"Quick check-in: You planned to work on '{pending_tasks[0].get('task_description')}'. Have you completed it, or should we reschedule?")
                            else:
                                agent_say("Quick check-in: How is your focus block going? Are you staying on track?")
                        elif cp_type == "DAY_REVIEW":
                            tasks_list = await db.get_user_tasks(user_id)
                            completed = [t for t in tasks_list if t.get("status") == "completed" and "checkpoint" not in t.get("task_description", "").lower()]
                            pending = [t for t in tasks_list if t.get("status") in ("pending", "reminded") and "checkpoint" not in t.get("task_description", "").lower()]
                            total = len(completed) + len(pending)
                            msg_text = f"It's time for your daily review. You've completed {len(completed)} out of {total} tasks today."
                            if pending:
                                pending_desc = ", ".join([f"'{t.get('task_description')}'" for t in pending[:2]])
                                msg_text += f" You still have pending work: {pending_desc}. Would you like me to move them to tomorrow's template?"
                            else:
                                msg_text += " Perfect job on completing everything today! Rest up."
                            agent_say(msg_text)
                    
                    asyncio.create_task(handle_checkpoint())
                    return
                
                async def handle_regular_reminder():
                    await db.mark_task_reminded(task_id)
                    await asyncio.sleep(1.0)
                    if "test reminder" in reminder_text.lower():
                        agent_say("This is a test reminder.")
                    else:
                        spoken_task = reminder_text
                        if spoken_task and spoken_task[0].isupper():
                            words = spoken_task.split()
                            if words and not words[0].isupper():
                                words[0] = words[0].lower()
                                spoken_task = " ".join(words)
                        agent_say(f"Hi! This is your reminder call to {spoken_task}. If you have completed this task, can I mark it complete? Or would you like to reschedule it?")
                    
                asyncio.create_task(handle_regular_reminder())
                
            elif msg.startswith("SYSTEM_ACCOUNTABILITY:"):
                payload = msg.replace("SYSTEM_ACCOUNTABILITY:", "").strip()
                parts = payload.split(" | ")
                if len(parts) >= 3:
                    task_id, priority, rem_text = parts[0], parts[1], " | ".join(parts[2:])
                elif len(parts) == 2:
                    task_id, priority, rem_text = parts[0], "MEDIUM", parts[1]
                else:
                    task_id, priority, rem_text = "unknown", "MEDIUM", payload
                    
                print(f"[Agent] Accountability check for task {task_id} ({priority}): {rem_text}")
                
                async def handle_accountability():
                    await asyncio.sleep(1.0)
                    if priority == "LOW":
                        agent_say(f"SYSTEM_NOTIFICATION: Accountability: Did you complete '{rem_text}'?")
                    elif priority == "MEDIUM":
                        agent_say(f"I noticed you had '{rem_text}' scheduled. Did you manage to complete it, or should we reschedule?")
                    elif priority == "HIGH":
                        agent_say(f"Accountability check: I see your high-priority task '{rem_text}' is still pending. Did you finish it, or do we need to reschedule?")
                    elif priority == "CRITICAL":
                        agent_say(f"Urgent follow-up: Your critical task '{rem_text}' remains incomplete. Please tell me if you've completed it or if we should reschedule it right now.")
                
                asyncio.create_task(handle_accountability())
                if priority != "CRITICAL":
                    async def do_accounted():
                        try:
                            await db.update_task_status(user_id, task_id, "accounted")
                        except Exception as e:
                            print(f"Error marking task accounted: {e}")
                    asyncio.create_task(do_accounted())
        except Exception as e:
            print(f"Error processing system message: {e}")

    @ctx.room.on("data_received")
    def on_data_received(data_packet):
        try:
            msg = data_packet.data.decode('utf-8')
            if msg.startswith("SYSTEM_REMINDER:") or msg.startswith("SYSTEM_ACCOUNTABILITY:"):
                process_system_message(msg)
            elif msg == "SYSTEM_HANGUP":
                print("[Agent] Received SYSTEM_HANGUP. Saving final conversation summary.")
                asyncio.create_task(save_conversation_summary())
            elif msg.startswith("SYSTEM_CONNECT:"):
                print(f"[Agent] Connection notification: {msg}")
                if "TEST_CALL" in msg:
                    trigger_test_greeting()
                elif "MANUAL" in msg:
                    trigger_morning_greeting()
            else:
                # --- TEXT CHAT from Android client ---
                print(f"[Agent] Received chat message: {msg}")
                conversation_transcript.append(f"User: {msg}")

                async def respond_to_text_msg():
                    try:
                        # generate_reply handles the full LLM → TTS pipeline
                        # conversation_item_added event auto-publishes to data channel
                        speech = await session.generate_reply(user_input=msg)
                        # Wait for the speech to finish playing
                        await speech.wait_for_playout()
                    except Exception as err:
                        print(f"Error in respond_to_text_msg: {err}")
                        try:
                            agent_say("I'm sorry, could you repeat that?")
                        except Exception:
                            pass
                
                asyncio.create_task(respond_to_text_msg())
        except Exception as e:
            print(f"Data receive error: {e}")
    
    # --- GREETING FUNCTIONS ---
    def trigger_test_greeting():
        nonlocal greeting_triggered
        if greeting_triggered:
            return
        greeting_triggered = True
        user_name = profile.get("display_name", "there")
        greeting_text = f"Hello {user_name}, this is a ChronosAI test call. Can you hear me?"
        print(f"[Agent Startup] Test Greeting: {greeting_text}")
        agent_say(greeting_text)

    def trigger_morning_greeting():
        nonlocal greeting_triggered
        if greeting_triggered:
            return
        greeting_triggered = True
        user_name = profile.get("display_name", "there")
        greeting_parts = [f"Hello {user_name}, I am ChronosAI, your Chief of Staff."]
        
        if incomplete_tasks:
            greeting_parts.append(
                f"I notice you have {len(incomplete_tasks)} pending tasks, "
                f"including '{incomplete_tasks[0].get('task_description')}'. Would you like to mark them complete or reschedule?"
            )
        else:
            greeting_parts.append("All your scheduled tasks are fully caught up.")
            
        if prayers:
            greeting_parts.append("I have today's prayer times blocked out in your system.")
            
        greeting_parts.append("How should we shape your day today?")
        greeting_text = " ".join(greeting_parts)
        
        print(f"[Agent Startup] Dynamic Greeting: {greeting_text}")
        agent_say(greeting_text)

    @ctx.room.on("participant_connected")
    def on_participant_connected(participant):
        print(f"[Agent] Participant connected: {participant.identity}.", flush=True)
        async def delayed_greeting():
            await asyncio.sleep(2.0)
            trigger_morning_greeting()
        asyncio.create_task(delayed_greeting())

    if ctx.room.remote_participants:
        print("[Agent Startup] Remote participants already in room.", flush=True)
        async def delayed_greeting_startup():
            await asyncio.sleep(2.0)
            trigger_morning_greeting()
        asyncio.create_task(delayed_greeting_startup())


if __name__ == "__main__":
    cli.run_app(WorkerOptions(entrypoint_fnc=entrypoint, agent_name="voice_agent"))
