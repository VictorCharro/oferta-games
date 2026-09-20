-- A consulta usa title ~* ANY(...), portanto o indice deve ser em title, sem lower().
CREATE EXTENSION IF NOT EXISTS pg_trgm;
-- Executar fora de transacao para nao bloquear as escritas durante a criacao.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_games_title_trgm ON public.games USING gin (title gin_trgm_ops);
ANALYZE public.games;
