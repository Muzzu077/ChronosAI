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
