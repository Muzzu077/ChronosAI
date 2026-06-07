import asyncio
import datetime
from zoneinfo import ZoneInfo
import db
import time_resolver

async def run_tests():
    print("--- STARTING TIME RESOLVER UNIT TESTS ---")
    user_id = "00000000-0000-0000-0000-000000000000"
    
    # Initialize SQLite database schema
    db.init_sqlite()
    
    today_str = datetime.datetime.now().strftime("%Y-%m-%d")
    
    print("\nTest 1: Resolving 'tomorrow morning'")
    res1 = await time_resolver.resolve_relative_time(user_id, "tomorrow morning")
    print(f"Resolved 'tomorrow morning' to: {res1}")
    assert "T" in res1
    
    print("\nTest 2: Resolving 'after Maghrib'")
    res2 = await time_resolver.resolve_relative_time(user_id, "after Maghrib")
    print(f"Resolved 'after Maghrib' to: {res2}")
    assert "T" in res2
    
    print("\nTest 3: Resolving 'after college'")
    res3 = await time_resolver.resolve_relative_time(user_id, "after college")
    print(f"Resolved 'after college' to: {res3}")
    assert "T" in res3

    print("\nTest 4: Resolving 'tomorrow night'")
    res4 = await time_resolver.resolve_relative_time(user_id, "tomorrow night")
    print(f"Resolved 'tomorrow night' to: {res4}")
    assert "T" in res4

    print("\n--- ALL TIME RESOLVER TESTS PASSED ---")

if __name__ == "__main__":
    asyncio.run(run_tests())
