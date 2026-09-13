-- Storage do bucket avatars: listagem so da propria pasta (issue #30).
--
-- A policy "Leitura publica de avatars" (SELECT pra public) deixava QUALQUER um, so com a chave anon
-- que vai no bundle do site, listar o bucket inteiro — e o nome de cada pasta e o UUID de um
-- usuario. Bucket publico nao precisa de policy de SELECT pra servir arquivo por URL
-- (/object/public/... nao passa por RLS), entao avatares, banners e imagens de bloco continuam
-- carregando.
--
-- A policy nova mantem o que o site usa de verdade: o usuario logado lista a PROPRIA pasta — a
-- exclusao de conta (ContaService) lista {uid}/ e {uid}/blocks/ pra apagar os arquivos.

drop policy if exists "Leitura publica de avatars" on storage.objects;

create policy "Listagem da propria pasta de avatars"
  on storage.objects for select
  to authenticated
  using (bucket_id = 'avatars' and (storage.foldername(name))[1] = (select auth.uid()::text));
