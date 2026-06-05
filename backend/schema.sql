-- ChronosAI: Supabase Table and Security Policies Schema

-- Create the daily_tasks table (FK to auth.users removed for prototype flexibility)
CREATE TABLE IF NOT EXISTS public.daily_tasks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL, -- references auth.users(id) ON DELETE CASCADE,
    task_description TEXT NOT NULL,
    scheduled_time TIMESTAMPTZ NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'pending',
    created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now())
);

-- Enable Row Level Security (RLS) on the daily_tasks table
ALTER TABLE public.daily_tasks ENABLE ROW LEVEL SECURITY;

-- Select Policy: Users can only see their own tasks
CREATE POLICY "Users can select their own tasks"
ON public.daily_tasks
FOR SELECT
USING (auth.uid() = user_id);

-- Insert Policy: Users can only insert tasks for themselves
CREATE POLICY "Users can insert their own tasks"
ON public.daily_tasks
FOR INSERT
WITH CHECK (auth.uid() = user_id);

-- Update Policy: Users can only update their own tasks
CREATE POLICY "Users can update their own tasks"
ON public.daily_tasks
FOR UPDATE
USING (auth.uid() = user_id)
WITH CHECK (auth.uid() = user_id);

-- Delete Policy: Users can only delete their own tasks
CREATE POLICY "Users can delete their own tasks"
ON public.daily_tasks
FOR DELETE
USING (auth.uid() = user_id);

-- Index for scheduled time and status for faster polling
CREATE INDEX IF NOT EXISTS idx_daily_tasks_status_scheduled_time 
ON public.daily_tasks(status, scheduled_time);

-- ChronosAI V3 foundation tables.
-- Android must not connect directly to these tables; access should flow through FastAPI.

CREATE TABLE IF NOT EXISTS public.users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    display_name TEXT,
    role TEXT,
    primary_goal TEXT,
    timezone TEXT NOT NULL DEFAULT 'UTC',
    created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now()),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now())
);

CREATE TABLE IF NOT EXISTS public.schedules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    schedule_date DATE NOT NULL,
    generated_by TEXT NOT NULL DEFAULT 'planner_agent',
    summary TEXT,
    blocks JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now())
);

CREATE TABLE IF NOT EXISTS public.habits (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    name TEXT NOT NULL,
    domain TEXT,
    target JSONB NOT NULL DEFAULT '{}'::jsonb,
    stats JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now()),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now())
);

CREATE TABLE IF NOT EXISTS public.reminders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    task_id UUID,
    reminder_time TIMESTAMPTZ NOT NULL,
    channel TEXT NOT NULL DEFAULT 'voice',
    prompt TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending',
    created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now()),
    sent_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS public.prayer_times (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    prayer_date DATE NOT NULL,
    location JSONB NOT NULL DEFAULT '{}'::jsonb,
    fajr TIMESTAMPTZ,
    dhuhr TIMESTAMPTZ,
    asr TIMESTAMPTZ,
    maghrib TIMESTAMPTZ,
    isha TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now()),
    UNIQUE(user_id, prayer_date)
);

CREATE TABLE IF NOT EXISTS public.daily_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    log_date DATE NOT NULL,
    tasks_planned INTEGER NOT NULL DEFAULT 0,
    tasks_completed INTEGER NOT NULL DEFAULT 0,
    tasks_skipped INTEGER NOT NULL DEFAULT 0,
    completion_rate NUMERIC(5, 2) NOT NULL DEFAULT 0,
    focus_minutes INTEGER NOT NULL DEFAULT 0,
    study_minutes INTEGER NOT NULL DEFAULT 0,
    sleep_minutes INTEGER,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now()),
    UNIQUE(user_id, log_date)
);

CREATE TABLE IF NOT EXISTS public.agent_memory (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    memory_key TEXT NOT NULL,
    memory_value JSONB NOT NULL,
    confidence NUMERIC(4, 3) NOT NULL DEFAULT 0.500,
    source TEXT NOT NULL DEFAULT 'user',
    created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now()),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now()),
    UNIQUE(user_id, memory_key)
);

CREATE INDEX IF NOT EXISTS idx_schedules_user_date ON public.schedules(user_id, schedule_date);
CREATE INDEX IF NOT EXISTS idx_habits_user_domain ON public.habits(user_id, domain);
CREATE INDEX IF NOT EXISTS idx_reminders_status_time ON public.reminders(status, reminder_time);
CREATE INDEX IF NOT EXISTS idx_prayer_times_user_date ON public.prayer_times(user_id, prayer_date);
CREATE INDEX IF NOT EXISTS idx_daily_logs_user_date ON public.daily_logs(user_id, log_date);
CREATE INDEX IF NOT EXISTS idx_agent_memory_user_key ON public.agent_memory(user_id, memory_key);
