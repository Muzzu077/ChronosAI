# db.py - Facade Layer for Database Access
# Re-exports modular repositories to ensure perfect backward compatibility.

from repositories.db_config import (
    supabase_client,
    DB_FILE,
    init_sqlite
)

from repositories.task_repository import (
    insert_task,
    get_user_tasks,
    get_pending_tasks,
    mark_task_completed,
    mark_task_reminded,
    update_task_status,
    delete_task,
    get_all_tasks,
    get_tasks_by_status,
    get_accountability_candidates,
    set_task_metadata,
    get_task_metadata,
    merge_tasks_metadata
)

from repositories.profile_repository import (
    get_user_profile,
    update_user_profile
)

from repositories.memory_repository import (
    save_agent_memory,
    get_agent_memories,
    get_agent_memory
)

from repositories.schedule_repository import (
    save_user_schedule,
    get_user_schedule
)

from repositories.prayer_repository import (
    insert_prayer_times,
    get_prayer_times,
    fetch_and_store_prayer_times
)

from repositories.productivity_repository import (
    get_daily_log,
    update_daily_log,
    get_productivity_stats,
    save_productivity_score,
    get_productivity_scores
)

from repositories.behavior_repository import (
    save_behavior_pattern,
    get_behavior_patterns,
    update_focus_window,
    get_focus_windows
)

from repositories.goal_repository import (
    create_goal,
    get_goals,
    update_goal_status,
    log_goal_progress,
    get_goal_progress
)

from repositories.template_repository import (
    create_life_template,
    add_life_time_block,
    get_active_life_template,
    get_life_template_blocks,
    ensure_default_template,
    get_daily_checkpoints_utc
)

