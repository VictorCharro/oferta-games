-- Ordenacao manual dos favoritos pessoais (catalogo + Steam) no perfil.
-- Uma unica sequencia de posicao por usuario, combinando as duas tabelas.
ALTER TABLE profile_favorites ADD COLUMN IF NOT EXISTS position integer NOT NULL DEFAULT 0;
ALTER TABLE profile_steam_favorites ADD COLUMN IF NOT EXISTS position integer NOT NULL DEFAULT 0;

-- Backfill: preserva a ordem atual (mais recentes primeiro) como posicao inicial.
WITH combinado AS (
  SELECT user_id, created_at, 'g'::text AS tipo, game_id AS gid, NULL::integer AS aid
  FROM profile_favorites
  UNION ALL
  SELECT user_id, created_at, 's'::text AS tipo, NULL::bigint AS gid, app_id AS aid
  FROM profile_steam_favorites
),
ordenado AS (
  SELECT *, (row_number() OVER (PARTITION BY user_id ORDER BY created_at DESC) - 1) AS pos
  FROM combinado
)
UPDATE profile_favorites f
SET position = o.pos
FROM ordenado o
WHERE o.tipo = 'g' AND o.user_id = f.user_id AND o.gid = f.game_id;

WITH combinado AS (
  SELECT user_id, created_at, 'g'::text AS tipo, game_id AS gid, NULL::integer AS aid
  FROM profile_favorites
  UNION ALL
  SELECT user_id, created_at, 's'::text AS tipo, NULL::bigint AS gid, app_id AS aid
  FROM profile_steam_favorites
),
ordenado AS (
  SELECT *, (row_number() OVER (PARTITION BY user_id ORDER BY created_at DESC) - 1) AS pos
  FROM combinado
)
UPDATE profile_steam_favorites f
SET position = o.pos
FROM ordenado o
WHERE o.tipo = 's' AND o.user_id = f.user_id AND o.aid = f.app_id;
