import asyncio
import os
from dotenv import load_dotenv
from livekit.plugins import openai
from livekit.agents import llm

load_dotenv()

async def main():
    OPENROUTER_API_KEY = os.getenv("OPENROUTER_API_KEY")
    OPENROUTER_MODEL = os.getenv("OPENROUTER_MODEL", "openai/gpt-4o-mini")
    
    print(f"Key: {OPENROUTER_API_KEY[:8]}...")
    print(f"Model: {OPENROUTER_MODEL}")
    
    openrouter_llm = openai.LLM(
        model=OPENROUTER_MODEL,
        api_key=OPENROUTER_API_KEY,
        base_url="https://openrouter.ai/api/v1"
    )
    
    ctx = llm.ChatContext()
    ctx.add_message(role="system", content="You are a helpful assistant.")
    ctx.add_message(role="user", content="Say hello!")
    
    stream = openrouter_llm.chat(chat_ctx=ctx)
    async for chunk in stream:
        print("Chunk dir:", dir(chunk))
        print("Chunk.choices:", getattr(chunk, "choices", None))
        print("Chunk.delta:", getattr(chunk, "delta", None))
        if getattr(chunk, "delta", None):
            print("Chunk.delta dir:", dir(chunk.delta))
            print("Chunk.delta.content:", getattr(chunk.delta, "content", None))
        break

if __name__ == '__main__':
    asyncio.run(main())
