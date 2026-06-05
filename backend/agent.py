import os
import datetime
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
    @llm.function_tool(description="Schedule a task or reminder for the user's daily planner.")
    async def schedule_reminder(
        self,
        user_id: Annotated[str, llm.TypeInfo(description="The unique UUID of the user to identify their data.")],
        task_description: Annotated[str, llm.TypeInfo(description="The short description or summary of what task needs to be performed.")],
        target_datetime_iso: Annotated[str, llm.TypeInfo(description="ISO-8601 UTC timestamp of when this task is scheduled (e.g. '2026-06-05T14:30:00Z').")]
    ) -> str:
        """Call the db layer when LLM triggers schedule_reminder."""
        try:
            print(f"[Agent Tool Invoke] scheduling task: '{task_description}' for user_id: {user_id} at {target_datetime_iso}")
            await db.insert_task(user_id, task_description, target_datetime_iso)
            return f"Successfully scheduled task: '{task_description}' for {target_datetime_iso} UTC."
        except Exception as e:
            print(f"[Agent Tool Error] {str(e)}")
            return f"Failed to schedule task in database: {str(e)}"

async def entrypoint(ctx: JobContext):
    # Establish connection with LiveKit session
    await ctx.connect()
    
    # 5.2 Timezone Injection
    # Dynamically fetch the current server UTC time and build a precise baseline representation
    now_utc = datetime.datetime.now(datetime.timezone.utc)
    current_time_str = now_utc.strftime("%A, %B %d, %Y at %I:%M %p UTC")
    
    # Extract the user_id from the room name (format: room-{user_id})
    user_id = ctx.room.name.replace("room-", "") if ctx.room.name else "00000000-0000-0000-0000-000000000000"
    
    # Define system instructions for the Cognitive Voice Agent
    system_prompt = (
        "You are 'ChronosAI', a highly specialized, concise AI daily planner voice assistant.\n"
        "You speak with a professional, helpful composure.\n"
        "Your goal is to parse user scheduling commands, clarify details if ambiguous, and execute system commands seamlessly.\n"
        "You MUST keep your verbal responses highly concise, direct, and tailored for oral communication (no markdown lists or long academic sentences).\n\n"
        "Time context (CRITICAL):\n"
        f"The server current UTC date and time is {current_time_str}.\n"
        "Use this as your absolute factual reference point for evaluating all relative timeline queries (such as 'tomorrow', 'next monday', or 'in 15 minutes').\n\n"
        "System tools:\n"
        f"- Call 'schedule_reminder' whenever the user wants to add, book, or log an entry. You MUST supply the user_id EXACTLY as '{user_id}'.\n"
        "- Be meticulous about converting user terms like '2:30 PM' relative to the base date and format them correctly into ISO-8601 UTC strings."
    )
    ...
    # Initial greeting via TTS
    await agent.say("Hello, I am ChronosAI. Neural Link successfully establishing. I am ready to schedule your timeline.", allow_interruptions=True)
        text=system_prompt
    )
    
    # Cognitive AI Engine utilizing OpenRouter OpenAO-compatible model
    openrouter_llm = openai.LLM(
        model=OPENROUTER_MODEL,
        api_key=OPENROUTER_API_KEY,
        base_url="https://openrouter.ai/api/v1"
    )
    
    # Instantiate standard Speech-to-Text and Text-to-Speech engines
    stt_plugin = openai.STT()
    tts_plugin = openai.TTS()
    
    # Build complete autonomous agent voice loop
    agent = VoicePipelineAgent(
        vad=silero.VAD.load(),
        stt=stt_plugin,
        llm=openrouter_llm,
        tts=tts_plugin,
        fnc_ctx=ChronosAIFunctionContext(),
        chat_ctx=initial_ctx
    )
    
    # Hook agent up to the room
    agent.start(ctx.room)
    
    @ctx.room.on("data_received")
    def on_data_received(data_packet):
        try:
            msg = data_packet.data.decode('utf-8')
            import asyncio
            if msg.startswith("SYSTEM_REMINDER:"):
                reminder_text = msg.replace("SYSTEM_REMINDER:", "").strip()
                print(f"[Agent] Received system reminder: {reminder_text}")
                asyncio.create_task(agent.say(f"Alert: It is time for your scheduled task. {reminder_text}", allow_interruptions=True))
            else:
                print(f"[Agent] Received chat message: {msg}")
                # Inject the message into the agent's chat context
                agent.chat_ctx.append(role="user", text=msg)
                
                # Trigger a response generation
                # We can use agent.say() with a specific prompt or just let it process the context
                # To make it "act" on the message (e.g. schedule something), we need to trigger the LLM.
                # VoicePipelineAgent doesn't have a direct 'process_context' for text, 
                # but we can speak a response which will trigger the full pipeline logic including tools.
                asyncio.create_task(agent.say("Processing your request.", allow_interruptions=True))
        except Exception as e:
            print(f"Data receive error: {e}")
    
    # Initial greeting via TTS
    await agent.say("Hello, I am Nightcrawler. Neural Link successfully establishing. I am ready to schedule your timeline.", allow_interruptions=True)

if __name__ == "__main__":
    # Start the standalone LiveKit worker agent process
    cli.run_app(WorkerOptions(entrypoint_fnc=entrypoint))
