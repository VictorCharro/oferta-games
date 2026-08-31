-- Distingue alerta de queda comum de "meta de preco atingida" (favorites.target_price).
-- Default 'queda' mantem as notificacoes ja existentes com o comportamento antigo.
ALTER TABLE price_notifications
  ADD COLUMN IF NOT EXISTS tipo text NOT NULL DEFAULT 'queda';
