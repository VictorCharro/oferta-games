CREATE TABLE IF NOT EXISTS game_reviews (
  id bigserial PRIMARY KEY,
  game_id bigint NOT NULL REFERENCES games(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  rating smallint NOT NULL CHECK (rating BETWEEN 1 AND 5),
  comentario text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (game_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_game_reviews_game ON game_reviews (game_id, created_at DESC);

CREATE TABLE IF NOT EXISTS game_review_votes (
  review_id bigint NOT NULL REFERENCES game_reviews(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  util boolean NOT NULL,
  PRIMARY KEY (review_id, user_id)
);
