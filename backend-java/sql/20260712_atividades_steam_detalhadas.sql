ALTER TABLE profile_activities
  ADD COLUMN IF NOT EXISTS detalhe text NULL;

ALTER TABLE steam_connections
  ADD COLUMN IF NOT EXISTS library_activity_baselined boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS achievement_activity_baselined boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS achievement_activity_started boolean NOT NULL DEFAULT false;

CREATE TABLE IF NOT EXISTS steam_user_achievements (
  user_id uuid NOT NULL REFERENCES steam_connections(user_id) ON DELETE CASCADE,
  app_id integer NOT NULL,
  api_name text NOT NULL,
  title text NULL,
  unlocked_at timestamptz NULL,
  PRIMARY KEY (user_id, app_id, api_name)
);
