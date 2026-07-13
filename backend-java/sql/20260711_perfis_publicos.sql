CREATE TABLE IF NOT EXISTS profiles (
  user_id uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
  handle text UNIQUE,
  display_name text NOT NULL,
  bio text NULL,
  avatar_url text NULL,
  is_public boolean NOT NULL DEFAULT true,
  show_game_hours boolean NOT NULL DEFAULT true,
  show_achievements boolean NOT NULL DEFAULT true,
  show_library boolean NOT NULL DEFAULT true,
  show_favorite_games boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT profiles_handle_format CHECK (handle IS NULL OR handle ~ '^[a-z0-9][a-z0-9-]{2,29}$')
);

CREATE INDEX IF NOT EXISTS idx_profiles_public_handle ON profiles (handle) WHERE is_public = true;
