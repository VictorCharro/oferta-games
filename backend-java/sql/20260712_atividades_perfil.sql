CREATE TABLE IF NOT EXISTS profile_activities (
  id bigserial PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  tipo text NOT NULL,
  game_id bigint NULL REFERENCES games(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_profile_activities_user_created
ON profile_activities (user_id, created_at DESC);
