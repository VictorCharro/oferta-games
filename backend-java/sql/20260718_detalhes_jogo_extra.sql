ALTER TABLE game_details
  ADD COLUMN IF NOT EXISTS trailer_url text,
  ADD COLUMN IF NOT EXISTS trailer_thumbnail text,
  ADD COLUMN IF NOT EXISTS about_full text,
  ADD COLUMN IF NOT EXISTS feature_highlights jsonb,
  ADD COLUMN IF NOT EXISTS categories text[],
  ADD COLUMN IF NOT EXISTS requirements_min text,
  ADD COLUMN IF NOT EXISTS requirements_rec text;
