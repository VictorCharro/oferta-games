ALTER TABLE games ADD COLUMN IF NOT EXISTS instant_gaming_url text NULL;
ALTER TABLE games ADD COLUMN IF NOT EXISTS last_instant_gaming_sync_at timestamptz NULL;

-- normalized_title: mesma normalizacao usada em GeradorSlug (sem acento, minusculo, hifens),
-- calculada no Java na hora de gravar; usada pra casar exato com o titulo do nosso catalogo.
CREATE TABLE IF NOT EXISTS instant_gaming_catalog (
  product_id integer PRIMARY KEY,
  title text NOT NULL,
  normalized_title text NOT NULL,
  url text NOT NULL,
  discovered_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_instant_gaming_catalog_normalized_title ON instant_gaming_catalog (normalized_title);

-- Linha unica: guarda ate onde a varredura por id numerico de produto ja chegou.
CREATE TABLE IF NOT EXISTS instant_gaming_scan_cursor (
  id boolean PRIMARY KEY DEFAULT true,
  last_scanned_id integer NOT NULL DEFAULT 0,
  CONSTRAINT instant_gaming_scan_cursor_singleton CHECK (id)
);
INSERT INTO instant_gaming_scan_cursor (id, last_scanned_id) VALUES (true, 0)
ON CONFLICT (id) DO NOTHING;
