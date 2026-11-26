CREATE INDEX ix_solution_sessions_user_completed ON solution_sessions (user_id, completed_at DESC, id DESC) WHERE status = 'COMPLETED';
