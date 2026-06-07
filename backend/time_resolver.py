import datetime
import re
from zoneinfo import ZoneInfo
import db

def parse_relative_date(phrase: str, base_date: datetime.date) -> datetime.date:
    phrase_clean = phrase.lower().strip()
    
    if "today" in phrase_clean:
        return base_date
    elif "tomorrow" in phrase_clean:
        return base_date + datetime.timedelta(days=1)
    elif "day after tomorrow" in phrase_clean:
        return base_date + datetime.timedelta(days=2)
    elif "next friday" in phrase_clean:
        # find next Friday
        days_ahead = 4 - base_date.weekday()
        if days_ahead <= 0: # target day already happened this week
            days_ahead += 7
        return base_date + datetime.timedelta(days=days_ahead)
    elif "this weekend" in phrase_clean:
        # Saturday
        days_ahead = 5 - base_date.weekday()
        if days_ahead < 0:
            days_ahead += 7
        return base_date + datetime.timedelta(days=days_ahead)
    
    # default to today
    return base_date

async def resolve_relative_time(user_id: str, relative_phrase: str, timezone_str: str = "Asia/Kolkata") -> str:
    """
    Resolves a relative time phrase (e.g. 'after Maghrib', 'tomorrow morning', 'after college')
    or an absolute ISO-like string in user local time
    into a precise ISO-8601 UTC timestamp string.
    """
    phrase_raw = relative_phrase.strip()
    
    # Get user timezone from database profile
    profile = await db.get_user_profile(user_id)
    tz_str = profile.get("timezone") or timezone_str or "Asia/Kolkata"
    try:
        tz = ZoneInfo(tz_str)
    except Exception:
        tz = ZoneInfo("Asia/Kolkata")
        
    now_local = datetime.datetime.now(tz)

    # Check for absolute ISO-like date-time format (e.g. YYYY-MM-DD or YYYY-MM-DDTHH:MM:SS)
    if re.match(r'^\d{4}-\d{2}-\d{2}', phrase_raw):
        try:
            if phrase_raw.endswith("Z"):
                # It is already in UTC
                dt = datetime.datetime.fromisoformat(phrase_raw.replace("Z", "+00:00"))
                return dt.astimezone(datetime.timezone.utc).isoformat()
            
            # If there's an explicit offset like +05:30 or -04:00
            if "+" in phrase_raw or (phrase_raw.count("-") == 3):
                dt = datetime.datetime.fromisoformat(phrase_raw)
                return dt.astimezone(datetime.timezone.utc).isoformat()
            
            # Treat as naive local time
            normalized = phrase_raw.replace(" ", "T")
            if normalized.count(":") == 1:
                normalized += ":00"
            dt_naive = datetime.datetime.fromisoformat(normalized)
            dt = dt_naive.replace(tzinfo=tz)
            return dt.astimezone(datetime.timezone.utc).isoformat()
        except Exception as e:
            print(f"Error parsing absolute date-time '{phrase_raw}' in resolve_relative_time: {e}")
            # fall through to relative parsing

    phrase = phrase_raw.lower()
    
    # Check for simple minutes/hours relative offsets (e.g. "after 5 min", "in 2 hours")
    match_offset = re.search(r'(\d+)\s*(min|minute|minutes|hour|hours|hr|hrs|sec|second|seconds)', phrase)
    if match_offset:
        value = int(match_offset.group(1))
        unit = match_offset.group(2).lower()
        if "min" in unit:
            dt_target = now_local + datetime.timedelta(minutes=value)
        elif "hour" in unit or "hr" in unit:
            dt_target = now_local + datetime.timedelta(hours=value)
        elif "sec" in unit:
            dt_target = now_local + datetime.timedelta(seconds=value)
        else:
            dt_target = now_local + datetime.timedelta(hours=1)
        return dt_target.astimezone(datetime.timezone.utc).isoformat()
        
    base_date = now_local.date()
    
    # Determine target date
    target_date = parse_relative_date(phrase, base_date)
    date_str = target_date.strftime("%Y-%m-%d")
    
    # Fetch prayer times for the target date
    prayers = await db.get_prayer_times(user_id, date_str)
    if not prayers:
        prayers = await db.fetch_and_store_prayer_times(user_id, "Chennai", "India", date_str)
        
    # Helper to parse prayer time to local datetime
    def get_prayer_dt(prayer_name: str):
        p_time = prayers.get(prayer_name.lower())
        if p_time:
            utc_dt = datetime.datetime.fromisoformat(p_time.replace("Z", "+00:00"))
            return utc_dt.astimezone(tz)
        return None

    # Fetch user memory profile for custom times
    college_memory = await db.get_agent_memory(user_id, "college_timings")
    dinner_memory = await db.get_agent_memory(user_id, "dinner_time")
    hostel_memory = await db.get_agent_memory(user_id, "hostel_arrival")
    
    # Standard time fallbacks (in local time)
    times = {
        "morning": datetime.time(9, 0),
        "afternoon": datetime.time(14, 0),
        "evening": datetime.time(17, 0),
        "night": datetime.time(21, 0),
        "dinner": datetime.time(20, 30),
        "college": datetime.time(16, 0),
        "hostel": datetime.time(18, 0),
    }
    
    # Parse custom settings from memory
    if college_memory and college_memory.get("memory_value"):
        val = str(college_memory["memory_value"].get("value", ""))
        match = re.search(r'(?:to|ends at|until)\s*(\d{1,2})(?::(\d{2}))?\s*(am|pm)?', val, re.IGNORECASE)
        if match:
            h = int(match.group(1))
            m = int(match.group(2)) if match.group(2) else 0
            ampm = match.group(3)
            if ampm and ampm.lower() == "pm" and h < 12:
                h += 12
            elif ampm and ampm.lower() == "am" and h == 12:
                h = 0
            times["college"] = datetime.time(h, m)
            
    if dinner_memory and dinner_memory.get("memory_value"):
        val = str(dinner_memory["memory_value"].get("value", ""))
        match = re.search(r'(\d{1,2})(?::(\d{2}))?\s*(am|pm)?', val, re.IGNORECASE)
        if match:
            h = int(match.group(1))
            m = int(match.group(2)) if match.group(2) else 0
            ampm = match.group(3)
            if ampm and ampm.lower() == "pm" and h < 12:
                h += 12
            times["dinner"] = datetime.time(h, m)

    if hostel_memory and hostel_memory.get("memory_value"):
        val = str(hostel_memory["memory_value"].get("value", ""))
        match = re.search(r'(\d{1,2})(?::(\d{2}))?\s*(am|pm)?', val, re.IGNORECASE)
        if match:
            h = int(match.group(1))
            m = int(match.group(2)) if match.group(2) else 0
            ampm = match.group(3)
            if ampm and ampm.lower() == "pm" and h < 12:
                h += 12
            times["hostel"] = datetime.time(h, m)

    target_time = None
    
    # Parse relative time terms
    if "fajr" in phrase:
        fajr_dt = get_prayer_dt("fajr")
        if fajr_dt:
            target_time = (fajr_dt + datetime.timedelta(minutes=30)).time()
    elif "dhuhr" in phrase:
        dhuhr_dt = get_prayer_dt("dhuhr")
        if dhuhr_dt:
            target_time = (dhuhr_dt + datetime.timedelta(minutes=30)).time()
    elif "asr" in phrase:
        asr_dt = get_prayer_dt("asr")
        if asr_dt:
            target_time = (asr_dt + datetime.timedelta(minutes=30)).time()
    elif "maghrib" in phrase:
        maghrib_dt = get_prayer_dt("maghrib")
        if maghrib_dt:
            target_time = (maghrib_dt + datetime.timedelta(minutes=30)).time()
    elif "isha" in phrase:
        isha_dt = get_prayer_dt("isha")
        if isha_dt:
            target_time = (isha_dt + datetime.timedelta(minutes=30)).time()
    elif "dinner" in phrase:
        target_time = times["dinner"]
    elif "college" in phrase:
        target_time = times["college"]
    elif "hostel" in phrase:
        target_time = times["hostel"]
    elif "morning" in phrase:
        target_time = times["morning"]
    elif "night" in phrase:
        target_time = times["night"]
    elif "afternoon" in phrase:
        target_time = times["afternoon"]
    elif "evening" in phrase:
        target_time = times["evening"]
        
    if not target_time:
        match = re.search(r'(\d{1,2})(?::(\d{2}))?\s*(am|pm)', phrase, re.IGNORECASE)
        if match:
            h = int(match.group(1))
            m = int(match.group(2)) if match.group(2) else 0
            ampm = match.group(3).lower()
            if ampm == "pm" and h < 12:
                h += 12
            elif ampm == "am" and h == 12:
                h = 0
            target_time = datetime.time(h, m)
            
    if not target_time:
        dt_target = now_local + datetime.timedelta(hours=1)
        return dt_target.astimezone(datetime.timezone.utc).isoformat()

    dt_target = datetime.datetime.combine(target_date, target_time, tzinfo=tz)
    
    if dt_target < now_local and "tomorrow" not in phrase and "next" not in phrase:
        dt_target += datetime.timedelta(days=1)
        
    return dt_target.astimezone(datetime.timezone.utc).isoformat()
