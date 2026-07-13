ALTER TABLE profiles
  ADD COLUMN IF NOT EXISTS avatar_zoom numeric(4,2) NOT NULL DEFAULT 1,
  ADD COLUMN IF NOT EXISTS avatar_position_x integer NOT NULL DEFAULT 50,
  ADD COLUMN IF NOT EXISTS avatar_position_y integer NOT NULL DEFAULT 50;

CREATE TABLE IF NOT EXISTS profile_blocks (
  user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  block_id text NOT NULL,
  block_type text NOT NULL,
  title text NULL,
  content text NULL,
  position integer NOT NULL,
  size text NOT NULL DEFAULT 'medio',
  visible boolean NOT NULL DEFAULT true,
  background_type text NOT NULL DEFAULT 'padrao',
  background_value text NULL,
  overlay_opacity integer NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, block_id)
);

CREATE INDEX IF NOT EXISTS idx_profile_blocks_user_position
ON profile_blocks (user_id, position);
