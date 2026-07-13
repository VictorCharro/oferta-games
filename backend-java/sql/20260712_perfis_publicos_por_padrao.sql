-- Mantem os perfis existentes como estao e torna apenas os novos perfis publicos por padrao.
ALTER TABLE profiles
  ALTER COLUMN is_public SET DEFAULT true;
