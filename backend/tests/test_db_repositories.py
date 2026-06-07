import pytest
import uuid
import datetime
import db

@pytest.fixture(scope="module", autouse=True)
def setup_db():
    db.init_sqlite()

@pytest.mark.asyncio
async def test_user_profile():
    test_user_id = str(uuid.uuid4())
    # Retrieve non-existing profile
    prof = await db.get_user_profile(test_user_id)
    assert prof == {}
    
    # Update profile (creates profile)
    updates = {
        "display_name": "Test User",
        "role": "Student",
        "primary_goal": "Acing Exams",
        "timezone": "Asia/Kolkata"
    }
    prof = await db.update_user_profile(test_user_id, updates)
    assert prof.get("display_name") == "Test User"
    assert prof.get("role") == "Student"
    assert prof.get("timezone") == "Asia/Kolkata"
    
    # Verify retrieval works
    fetched = await db.get_user_profile(test_user_id)
    assert fetched.get("display_name") == "Test User"

@pytest.mark.asyncio
async def test_goals():
    test_user_id = str(uuid.uuid4())
    # Create goal
    goal = await db.create_goal(
        user_id=test_user_id,
        title="Learn Pytest",
        description="Write automated tests for ChronosAI backend",
        target_date="2026-06-15"
    )
    assert goal.get("id") is not None
    assert goal.get("title") == "Learn Pytest"
    
    # Fetch goals
    goals = await db.get_goals(test_user_id)
    assert len(goals) == 1
    assert goals[0].get("title") == "Learn Pytest"
    
    # Update goal status
    goal_id = goal.get("id")
    success = await db.update_goal_status(goal_id, "completed")
    assert success is True
    
    # Fetch again to verify
    goals = await db.get_goals(test_user_id)
    assert goals[0].get("status") == "completed"

@pytest.mark.asyncio
async def test_daily_logs():
    test_user_id = str(uuid.uuid4())
    today_str = datetime.datetime.now().strftime("%Y-%m-%d")
    
    # Get daily log (should be empty)
    log = await db.get_daily_log(test_user_id, today_str)
    assert log == {}
    
    # Update daily log (creates it)
    updates = {
        "tasks_planned": 5,
        "tasks_completed": 3,
        "focus_minutes": 120
    }
    log = await db.update_daily_log(test_user_id, today_str, updates)
    assert log.get("tasks_planned") == 5
    assert log.get("tasks_completed") == 3
    assert log.get("focus_minutes") == 120
    
    # Get productivity stats
    stats = await db.get_productivity_stats(test_user_id, 7)
    assert len(stats) == 1
    assert stats[0].get("log_date") == today_str

@pytest.mark.asyncio
async def test_agent_memory():
    test_user_id = str(uuid.uuid4())
    mem_key = "user_preference"
    mem_val = {"theme": "dark", "notifications": True}
    
    # Save memory
    mem = await db.save_agent_memory(test_user_id, mem_key, mem_val, confidence=0.95, source="agent")
    assert mem.get("memory_key") == mem_key
    assert mem.get("memory_value") == mem_val
    assert mem.get("confidence") == 0.95
    
    # Fetch memory
    fetched = await db.get_agent_memory(test_user_id, mem_key)
    assert fetched.get("memory_value") == mem_val
    
    # Fetch all memories
    memories = await db.get_agent_memories(test_user_id)
    assert len(memories) == 1
    assert memories[0].get("memory_key") == mem_key
