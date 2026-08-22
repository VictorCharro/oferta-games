-- Historico de preco minimo por jogo. So grava uma linha quando o menor preco entre as ofertas
-- muda de fato (ver RepositorioJogos.registrarHistoricoDePrecos) - nao a cada sincronizacao -
-- entao o volume fica proporcional a mudancas reais de preco, nao ao ritmo dos jobs agendados.
-- Retencao de 90 dias, podada diariamente (ver AgendadorColetas.podarHistoricoDePrecos).
CREATE TABLE IF NOT EXISTS price_history (
  id bigserial PRIMARY KEY,
  game_id bigint NOT NULL REFERENCES games(id) ON DELETE CASCADE,
  price numeric(10,2) NOT NULL,
  captured_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_price_history_game_captured ON price_history (game_id, captured_at DESC);
