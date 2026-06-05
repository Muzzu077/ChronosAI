import os
import datetime
import json
import asyncio
from typing import Annotated
from dotenv import load_dotenv
from livekit.agents import JobContext, WorkerOptions, cli, llm
from livekit.agents.voice import Agent as VoicePipelineAgent
from livekit.plugins import openai, silero
import db

# Load Environment State
load_dotenv()

OPENROUTER_API_KEY = os.getenv("OPENROUTER_API_KEY")
OPENROUTER_MODEL = os.getenv("OPENROUTER_MODEL", "poolside/laguna-m1")

class ChronosAIFunctionContext(llm.ToolContext):
    def __init__(self, user_id: str):
        super().__init__()
        self.user_id = user_id

    @llm.function_tool(description="Schedule a task or reminder for the user's daily planner.")
    async def schedule_reminder(
        self,
        task_description: Annotated[str, llm.TypeInfo(description="The short description or summary of what task needs to be performed.")],
        target_datetime_iso: Annotated[str, llm.TypeInfo(description="ISO-8601 UTC timestamp of when this task is scheduled (e.g. '2026-06-05T14:30:00Z').")],
        user_id: Annotated[str, llm.TypeInfo(description="Optional user UUID. Defaults to active session user.")] = None
    ) -> str:
        uid = user_id if user_id else self.user_id
        try:
            print(f"[Agent Tool Invoke] scheduling task: '{task_description}' for user_id: {uid} at {target_datetime_iso}")
            await db.insert_task(uid, task_description, target_datetime_iso)
            return f"Successfully scheduled task: '{task_description}' for {target_datetime_iso} UTC."
        except Exception as e:
            print(f"[Agent Tool Error] {str(e)}")
            return f"Failed to schedule task in database: {str(e)}"

    @llm.function_tool(description="Reschedule an existing task to a new date and time.")
    async def reschedule_task(
        self,
        task_id: Annotated[str, llm.TypeInfo(description="The UUID of the task to reschedule.")],
        new_datetime_iso: Annotated[str, llm.TypeInfo(description="New ISO-8601 UTC timestamp (e.g. '2026-06-05T20:00:00Z').")]
    ) -> str:
        try:
            print(f"[Agent Tool Invoke] rescheduling task {task_id} to {new_datetime_iso}")
            response = await asyncio.to_thread(
                lambda: db.supabase_client.table("daily_tasks")
                .update({"scheduled_time": new_datetime_iso, "status": "pending"})
                .eq("id", task_id)
                .eq("user_id", self.user_id)
                .execute()
            )
            if hasattr(response, "data") and response.data:
                return f"Successfully rescheduled task to {new_datetime_iso}."
            return "Task not found or failed to reschedule."
        except Exception as e:
            print(f"[Agent Tool Error] {str(e)}")
            return f"Error rescheduling task: {str(e)}"

    @llm.function_tool(description="Mark an existing task as completed.")
    async def mark_task_complete(
        self,
        task_id: Annotated[str, llm.TypeInfo(description="The UUID of the task to complete.")]
    ) -> str:
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

    @llm.function_tool(description="Save or update a preference/fact in the user's memory (e.g. sleep hours, college timings, career goals).")
    async def save_user_memory(
        self,
        memory_key: Annotated[str, llm.TypeInfo(description="The key of the memory (e.g., 'college_timings', 'career_goal', 'sleep_pattern').")],
        memory_value: Annotated[str, llm.TypeInfo(description="The details or description value for this memory.")]
    ) -> str:
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
        memory_key: Annotated[str, llm.TypeInfo(description="The memory key to lookup.")]
    ) -> str:
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
        city: Annotated[str, llm.TypeInfo(description="City name (e.g. 'Chennai').")],
        country: Annotated[str, llm.TypeInfo(description="Country name (e.g. 'India').")],
        date_str: Annotated[str, llm.TypeInfo(description="Date in YYYY-MM-DD format.")]
    ) -> str:
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
        prayer_name: Annotated[str, llm.TypeInfo(description="Name of the prayer.")],
        date_str: Annotated[str, llm.TypeInfo(description="Date in YYYY-MM-DD format.")]
    ) -> str:
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
        schedule_date: Annotated[str, llm.TypeInfo(description="Date in YYYY-MM-DD format.")],
        summary: Annotated[str, llm.TypeInfo(description="Brief summary of the day's goals.")],
        blocks_json: Annotated[str, llm.TypeInfo(description="JSON array of blocks, e.g. '[{\"start\": \"08:00\", \"end\": \"09:00\", \"activity\": \"AI assignment\", \"notes\": \"Avoid prayer overlap\"}]'.")]
    ) -> str:
        try:
            print(f"[Agent Tool Invoke] generating schedule for {schedule_date}")
            blocks_list = json.loads(blocks_json)
            await db.save_user_schedule(self.user_id, schedule_date, summary, blocks_list)
            
            log = await db.get_daily_log(self.user_id, schedule_date)
            planned_count = max(log.get("tasks_planned", 0), len(blocks_list))
            completed_count = log.get("tasks_completed", 0)
            rate = round((completed_count / planned_count) * 100, 2) if planned_count > 0 else 100.0
            
            await db.update_daily_log(self.user_id, schedule_date, {
                "tasks_planned": planned_count,
                "completion_rate": rate
            })
            return f"Successfully generated and saved schedule for {schedule_date} with {len(blocks_list)} blocks."
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

async def entrypoint(ctx: JobContext):
    # Establish connection with LiveKit session
    await ctx.connect()
    
    # 5.2 Timezone Injection
    now_utc = datetime.datetime.now(datetime.timezone.utc)
    current_time_str = now_utc.strftime("%A, %B %d, %Y at %I:%M %p UTC")
    today_date_str = now_utc.strftime("%Y-%m-%d")
    
    # Extract the user_id from the room name
    user_id = ctx.room.name.replace("room-", "") if ctx.room.name else "00000000-0000-0000-0000-000000000000"
    
    # --- MORNING PLANNING & MEMORY LAYER INJECTION ---
    print(f"[Agent Startup] Loading memories for user: {user_id}")
    memories = await db.get_agent_memories(user_id)
    profile = await db.get_user_profile(user_id)
    prayers = await db.get_prayer_times(user_id, today_date_str)
    
    # Construct Memory Summary
    memory_summary = ""
    for m in memories:
        memory_summary += f"- {m['memory_key']}: {m['memory_value'].get('value') if isinstance(m['memory_value'], dict) else m['memory_value']}\n"
        
    # Construct Prayer Summary
    prayer_summary = "No prayer times fetched yet for today."
    if prayers:
        prayer_summary = (
            f"Fajr: {prayers.get('fajr')}, Dhuhr: {prayers.get('dhuhr')}, "
            f"Asr: {prayers.get('asr')}, Maghrib: {prayers.get('maghrib')}, Isha: {prayers.get('isha')}"
        )
        
    # Accountability: Check for pending/reminded tasks
    all_tasks = await db.get_user_tasks(user_id)
    incomplete_tasks = [t for t in all_tasks if t.get("status") in ("pending", "reminded")]
    incomplete_summary = ""
    for t in incomplete_tasks:
        incomplete_summary += f"- Task: '{t.get('task_description')}' (ID: {t.get('id')}) scheduled at {t.get('scheduled_time')}\n"

    # Define system instructions for the Cognitive Voice Agent
    system_prompt = (
        "You are 'ChronosAI', a highly advanced AI Chief of Staff and daily planner voice assistant.\n"
        "You speak with a professional, warm, and helpful composure.\n"
        "Your goal is to parse user scheduling commands, clarify details if ambiguous, create optimized daily schedules, and manage user memory.\n"
        "You MUST keep your verbal responses highly concise, direct, and tailored for oral communication (no markdown lists or long academic sentences).\n\n"
        "Time context (CRITICAL):\n"
        f"The server current UTC date and time is {current_time_str}.\n"
        "Use this as your absolute factual reference point for evaluating all relative timeline queries.\n\n"
        f"User Memory / Profile:\n{memory_summary if memory_summary else 'No prior memory stored.'}\n\n"
        f"Today's Prayer Times:\n{prayer_summary}\n\n"
        f"Pending/Incomplete Tasks requiring accountability:\n{incomplete_summary if incomplete_summary else 'None.'}\n\n"
        "Core Guidelines & Capabilities:\n"
        "1. AI Schedule Generation: When a user lists multiple priorities or a rough plan, use generate_schedule() to create an optimized daily block plan. Always schedule around prayer times and their personal timings (like college or sleep).\n"
        "2. Accountability Check: If there are incomplete tasks from yesterday, ask the user about them. Use mark_task_complete() or reschedule_task() based on their response.\n"
        "3. User Memory: Actively save new preferences/schedules/habits the user tells you using save_user_memory().\n"
        "4. Voice Conversation Cycle: Make sure to follow up and prompt the user after executing tools. Never stop at 'processing your request'. Ask questions, prompt for feedback, and guide them through planning."
    )
    
    initial_ctx = llm.ChatContext()
    initial_ctx.append(role="system", text=system_prompt)
    
    # Cognitive AI Engine utilizing OpenRouter OpenAO-compatible model
    openrouter_llm = openai.LLM(
        model=OPENROUTER_MODEL,
        api_key=OPENROUTER_API_KEY,
        base_url="https://openrouter.ai/api/v1"
    )
    
    stt_plugin = openai.STT()
    tts_plugin = openai.TTS()
    
    fnc_ctx = ChronosAIFunctionContext(user_id)
    agent = VoicePipelineAgent(
        vad=silero.VAD.load(),
        stt=stt_plugin,
        llm=openrouter_llm,
        tts=tts_plugin,
        fnc_ctx=fnc_ctx,
        chat_ctx=initial_ctx
    )
    
    # Hook agent up to the room
    agent.start(ctx.room)
    
    @ctx.room.on("data_received")
    def on_data_received(data_packet):
        try:
            msg = data_packet.data.decode('utf-8')
            if msg.startswith("SYSTEM_REMINDER:"):
                reminder_text = msg.replace("SYSTEM_REMINDER:", "").strip()
                print(f"[Agent] Received system reminder: {reminder_text}")
                asyncio.create_task(agent.say(f"Alert: It is time for your scheduled task. {reminder_text}", allow_interruptions=True))
            elif msg.startswith("SYSTEM_ACCOUNTABILITY:"):
                rem_text = msg.replace("SYSTEM_ACCOUNTABILITY:", "").strip()
                print(f"[Agent] Received system accountability check: {rem_text}")
                asyncio.create_task(agent.say(f"I noticed that you had '{rem_text}' scheduled. Did you manage to complete it, or should we reschedule?", allow_interruptions=True))
            else:
                print(f"[Agent] Received chat message: {msg}")
                agent.chat_ctx.append(role="user", text=msg)
                
                async def respond_to_text_msg():
                    try:
                        tools = []
                        if hasattr(agent.fnc_ctx, "function_tools") and agent.fnc_ctx.function_tools:
                            tools = list(agent.fnc_ctx.function_tools.values())
                        
                        stream = openrouter_llm.chat(chat_ctx=agent.chat_ctx, tools=tools)
                        response_text = ""
                        tool_calls_to_run = []
                        
                        async for chunk in stream:
                            if chunk.choices and chunk.choices[0].delta.content:
                                response_text += chunk.choices[0].delta.content
                            if chunk.choices and chunk.choices[0].delta.tool_calls:
                                for tc in chunk.choices[0].delta.tool_calls:
                                    tool_calls_to_run.append(tc)
                                    
                        if tool_calls_to_run:
                            for tc in tool_calls_to_run:
                                func_name = tc.function.name
                                try:
                                    args = json.loads(tc.function.arguments)
                                except Exception:
                                    args = {}
                                if hasattr(agent.fnc_ctx, func_name):
                                    func = getattr(agent.fnc_ctx, func_name)
                                    print(f"[Agent Chat Tool] Executing {func_name} with {args}")
                                    try:
                                        res = await func(**args)
                                        agent.chat_ctx.append(role="system", text=f"Tool {func_name} executed. Result: {res}")
                                        
                                        stream2 = openrouter_llm.chat(chat_ctx=agent.chat_ctx, tools=tools)
                                        response_text = ""
                                        async for chunk in stream2:
                                            if chunk.choices and chunk.choices[0].delta.content:
                                                response_text += chunk.choices[0].delta.content
                                    except Exception as ex:
                                        print(f"Chat Tool Error: {ex}")
                                        
                        if response_text:
                            agent.chat_ctx.append(role="assistant", text=response_text)
                            await agent.say(response_text, allow_interruptions=True)
                    except Exception as err:
                        print(f"Error in respond_to_text_msg: {err}")
                        await agent.say("I encountered an issue processing that.", allow_interruptions=True)
                
                asyncio.create_task(respond_to_text_msg())
        except Exception as e:
            print(f"Data receive error: {e}")
    
    # --- DYNAMIC MORNING STANDUP GREETING ---
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
    await agent.say(greeting_text, allow_interruptions=True)

if __name__ == "__main__":
    cli.run_app(WorkerOptions(entrypoint_fnc=entrypoint))

