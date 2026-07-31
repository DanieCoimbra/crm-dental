-- Migration: F04 Storage Avatars
-- Description: Criação do bucket 'avatars' e configuração de RLS em storage.objects

-- Cria o bucket se não existir e o torna público
INSERT INTO storage.buckets (id, name, public)
VALUES ('avatars', 'avatars', true)
ON CONFLICT (id) DO NOTHING;

-- Habilita RLS na tabela de objetos do storage (caso ainda não esteja)
ALTER TABLE storage.objects ENABLE ROW LEVEL SECURITY;

-- Limpeza idempotente
DROP POLICY IF EXISTS "Avatar Public Access" ON storage.objects;
DROP POLICY IF EXISTS "Avatar Upload" ON storage.objects;
DROP POLICY IF EXISTS "Avatar Update" ON storage.objects;
DROP POLICY IF EXISTS "Avatar Delete" ON storage.objects;

-- 1. Leitura: Qualquer pessoa pode ler imagens (público)
CREATE POLICY "Avatar Public Access" ON storage.objects
    FOR SELECT TO public
    USING (bucket_id = 'avatars');

-- 2. Escrita (Insert): Usuários autenticados podem enviar arquivos
CREATE POLICY "Avatar Upload" ON storage.objects
    FOR INSERT TO authenticated
    WITH CHECK (bucket_id = 'avatars');

-- 3. Atualização (Update): Apenas o usuário que fez o upload (dono) pode alterá-lo
CREATE POLICY "Avatar Update" ON storage.objects
    FOR UPDATE TO authenticated
    USING (bucket_id = 'avatars' AND auth.uid() = owner)
    WITH CHECK (bucket_id = 'avatars' AND auth.uid() = owner);

-- 4. Exclusão (Delete): Apenas o usuário que fez o upload (dono) pode apagá-lo
CREATE POLICY "Avatar Delete" ON storage.objects
    FOR DELETE TO authenticated
    USING (bucket_id = 'avatars' AND auth.uid() = owner);
