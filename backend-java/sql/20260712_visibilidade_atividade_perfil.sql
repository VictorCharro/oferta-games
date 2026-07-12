ALTER TABLE profiles
  ADD COLUMN IF NOT EXISTS show_recent_activity boolean NOT NULL DEFAULT true;
