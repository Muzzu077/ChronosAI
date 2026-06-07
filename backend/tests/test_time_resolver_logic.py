import pytest
import datetime
from zoneinfo import ZoneInfo
import db
import time_resolver

@pytest.fixture(scope="module", autouse=True)
def setup_db():
    db.init_sqlite()

@pytest.mark.asyncio
async def test_resolve_tomorrow_morning():
    user_id = "00000000-0000-0000-0000-000000000000"
    resolved = await time_resolver.resolve_relative_time(user_id, "tomorrow morning")
    assert resolved is not None
    assert "T" in resolved
    dt_resolved = datetime.datetime.fromisoformat(resolved)
    tomorrow = datetime.datetime.now(ZoneInfo("Asia/Kolkata")) + datetime.timedelta(days=1)
    assert dt_resolved.strftime("%Y-%m-%d") == tomorrow.strftime("%Y-%m-%d")

@pytest.mark.asyncio
async def test_resolve_after_maghrib():
    user_id = "00000000-0000-0000-0000-000000000000"
    resolved = await time_resolver.resolve_relative_time(user_id, "after Maghrib")
    assert resolved is not None
    assert "T" in resolved

@pytest.mark.asyncio
async def test_resolve_after_college():
    user_id = "00000000-0000-0000-0000-000000000000"
    resolved = await time_resolver.resolve_relative_time(user_id, "after college")
    assert resolved is not None
    assert "T" in resolved
    dt_resolved = datetime.datetime.fromisoformat(resolved)
    # College default is 16:00 local time (10:30 UTC)
    assert dt_resolved.hour == 10

@pytest.mark.asyncio
async def test_resolve_tomorrow_night():
    user_id = "00000000-0000-0000-0000-000000000000"
    resolved = await time_resolver.resolve_relative_time(user_id, "tomorrow night")
    assert resolved is not None
    assert "T" in resolved
    dt_resolved = datetime.datetime.fromisoformat(resolved)
    tomorrow = datetime.datetime.now(ZoneInfo("Asia/Kolkata")) + datetime.timedelta(days=1)
    assert dt_resolved.strftime("%Y-%m-%d") == tomorrow.strftime("%Y-%m-%d")
