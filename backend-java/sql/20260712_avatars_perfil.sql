-- Execute no SQL Editor do Supabase uma unica vez.
-- Cria um bucket publico de avatares e limita cada usuario a sua propria pasta.
INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES ('avatars', 'avatars', true, 2097152, ARRAY['image/jpeg', 'image/png', 'image/webp'])
ON CONFLICT (id) DO UPDATE
SET public = true,
    file_size_limit = EXCLUDED.file_size_limit,
    allowed_mime_types = EXCLUDED.allowed_mime_types;

DROP POLICY IF EXISTS "Leitura publica de avatars" ON storage.objects;
CREATE POLICY "Leitura publica de avatars"
ON storage.objects FOR SELECT
USING (bucket_id = 'avatars');

DROP POLICY IF EXISTS "Upload do proprio avatar" ON storage.objects;
CREATE POLICY "Upload do proprio avatar"
ON storage.objects FOR INSERT TO authenticated
WITH CHECK (bucket_id = 'avatars' AND (storage.foldername(name))[1] = (select auth.uid()::text));

DROP POLICY IF EXISTS "Atualizacao do proprio avatar" ON storage.objects;
CREATE POLICY "Atualizacao do proprio avatar"
ON storage.objects FOR UPDATE TO authenticated
USING (bucket_id = 'avatars' AND owner_id = (select auth.uid()::text))
WITH CHECK (bucket_id = 'avatars' AND (storage.foldername(name))[1] = (select auth.uid()::text));
