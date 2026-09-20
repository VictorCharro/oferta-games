-- Aplicar no banco do CATALOGO antes de publicar o backend que atualiza o ranking.
-- O default da sete dias de transicao aos ranks existentes e aos jogos recem-importados.
ALTER TABLE games ADD COLUMN IF NOT EXISTS rank_updated_at timestamptz NOT NULL DEFAULT now();
