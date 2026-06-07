import asyncio
import sqlite3
import datetime
import json
import uuid
from repositories.db_config import DB_FILE

def _sync_insert_prayer_times(user_id: str, prayer_date: str, location: dict, timings: dict) -> dict:
    try:
        conn = sqlite3.connect(DB_FILE)
        cursor = conn.cursor()
        now_str = datetime.datetime.now(datetime.timezone.utc).isoformat()
        loc_str = json.dumps(location)
        pt_id = str(uuid.uuid4())
        
        cursor.execute("""
        INSERT INTO prayer_times (id, user_id, prayer_date, location, fajr, dhuhr, asr, maghrib, isha, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(user_id, prayer_date) DO UPDATE SET
            location = excluded.location,
            fajr = excluded.fajr,
            dhuhr = excluded.dhuhr,
            asr = excluded.asr,
            maghrib = excluded.maghrib,
            isha = excluded.isha
        """, (pt_id, user_id, prayer_date, loc_str, timings.get("Fajr"), timings.get("Dhuhr"), timings.get("Asr"), timings.get("Maghrib"), timings.get("Isha"), now_str))
        
        conn.commit()
        conn.close()
        return _sync_get_prayer_times(user_id, prayer_date)
    except Exception as e:
        print(f"SQLite Prayer Save Error: {e}")
        return {}

async def insert_prayer_times(user_id: str, prayer_date: str, location: dict, timings: dict) -> dict:
    return await asyncio.to_thread(_sync_insert_prayer_times, user_id, prayer_date, location, timings)


def _sync_get_prayer_times(user_id: str, prayer_date: str) -> dict:
    try:
        conn = sqlite3.connect(DB_FILE)
        conn.row_factory = sqlite3.Row
        cursor = conn.cursor()
        cursor.execute("SELECT * FROM prayer_times WHERE user_id = ? AND prayer_date = ?", (user_id, prayer_date))
        row = cursor.fetchone()
        conn.close()
        if row:
            d = dict(row)
            try:
                d["location"] = json.loads(d["location"])
            except Exception:
                pass
            return d
        return {}
    except Exception as e:
        print(f"SQLite Prayer Get Error: {e}")
        return {}

async def get_prayer_times(user_id: str, prayer_date: str) -> dict:
    return await asyncio.to_thread(_sync_get_prayer_times, user_id, prayer_date)


def _sync_fetch_and_store_prayer_times(user_id: str, city: str, country: str, date_str: str) -> dict:
    import urllib.request
    import urllib.parse
    
    try:
        from zoneinfo import ZoneInfo
    except ImportError:
        class UTCZone(datetime.tzinfo):
            def utcoffset(self, dt): return datetime.timedelta(0)
            def tzname(self, dt): return "UTC"
            def dst(self, dt): return datetime.timedelta(0)
        ZoneInfo = lambda x: UTCZone()

    try:
        dt = datetime.datetime.strptime(date_str, "%Y-%m-%d")
        api_date = dt.strftime("%d-%m-%Y")
    except Exception:
        dt = datetime.datetime.now()
        api_date = dt.strftime("%d-%m-%Y")
        date_str = dt.strftime("%Y-%m-%d")

    url = f"http://api.aladhan.com/v1/timingsByCity/{api_date}?city={urllib.parse.quote(city)}&country={urllib.parse.quote(country)}"
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req, timeout=5) as response:
            res_data = json.loads(response.read().decode('utf-8'))
            if res_data.get("code") == 200:
                data = res_data.get("data", {})
                timings = data.get("timings", {})
                timezone_str = data.get("meta", {}).get("timezone", "UTC")
                
                try:
                    tz = ZoneInfo(timezone_str)
                except Exception:
                    tz = datetime.timezone.utc
                
                utc_timings = {}
                for name, t_str in timings.items():
                    try:
                        clean_t_str = t_str.split(" ")[0]
                        hour, minute = map(int, clean_t_str.split(":"))
                        local_dt = datetime.datetime.combine(dt.date(), datetime.time(hour, minute), tzinfo=tz)
                        utc_timings[name] = local_dt.astimezone(datetime.timezone.utc).isoformat()
                    except Exception as ex:
                        print(f"Error parsing time {t_str} for {name}: {ex}")
                
                location = {"city": city, "country": country, "timezone": timezone_str}
                return _sync_insert_prayer_times(user_id, date_str, location, utc_timings)
    except Exception as e:
        print(f"Error fetching prayer times: {e}")
    
    print("Using fallback prayer times for Chennai/default...")
    fallback_timings = {
        "Fajr": f"{date_str}T04:30:00+05:30",
        "Dhuhr": f"{date_str}T12:15:00+05:30",
        "Asr": f"{date_str}T15:30:00+05:30",
        "Maghrib": f"{date_str}T18:30:00+05:30",
        "Isha": f"{date_str}T19:45:00+05:30"
    }
    utc_fallbacks = {}
    for name, t_str in fallback_timings.items():
        try:
            dt_parsed = datetime.datetime.fromisoformat(t_str)
            utc_fallbacks[name] = dt_parsed.astimezone(datetime.timezone.utc).isoformat()
        except Exception:
            utc_fallbacks[name] = f"{date_str}T12:00:00Z"
            
    location = {"city": city, "country": country, "timezone": "Asia/Kolkata", "fallback": True}
    return _sync_insert_prayer_times(user_id, date_str, location, utc_fallbacks)

async def fetch_and_store_prayer_times(user_id: str, city: str, country: str, date_str: str) -> dict:
    return await asyncio.to_thread(_sync_fetch_and_store_prayer_times, user_id, city, country, date_str)
