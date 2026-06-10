import asyncio
import sqlite3
import os
import sys

# Add current directory to path
sys.path.append(os.path.abspath(os.path.dirname(__file__)))

from repositories.db_config import supabase_client, DB_FILE

async def clear_database():
    user_id = "293dafd6-72d4-4dc9-a668-4ba8f8586ca7"
    print(f"Clearing tasks for user: {user_id}...")
    
    # 1. Clear Supabase tasks
    try:
        response = supabase_client.table("daily_tasks").delete().eq("user_id", user_id).execute()
        print("Supabase tasks cleared successfully.")
    except Exception as e:
        print(f"Error clearing Supabase tasks: {e}")
        
    # 2. Clear SQLite tables
    try:
        conn = sqlite3.connect(DB_FILE)
        cursor = conn.cursor()
        
        # Clear tracker
        cursor.execute("DELETE FROM task_status_tracker")
        print(f"Cleared task_status_tracker: {cursor.rowcount} rows deleted.")
        
        # Clear metadata
        cursor.execute("DELETE FROM task_metadata")
        print(f"Cleared task_metadata: {cursor.rowcount} rows deleted.")
        
        conn.commit()
        conn.close()
        print("SQLite tables cleared successfully.")
    except Exception as e:
        print(f"Error clearing SQLite tables: {e}")

if __name__ == "__main__":
    asyncio.run(clear_database())
