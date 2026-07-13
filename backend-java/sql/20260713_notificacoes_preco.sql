CREATE TABLE IF NOT EXISTS price_notifications (
  id bigserial PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  game_id bigint NOT NULL REFERENCES games(id) ON DELETE CASCADE,
  previous_price numeric(10,2) NOT NULL,
  current_price numeric(10,2) NOT NULL,
  store_name text NULL,
  read_at timestamptz NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_price_notifications_user_created
  ON price_notifications (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_price_notifications_user_unread
  ON price_notifications (user_id, created_at DESC) WHERE read_at IS NULL;
