-- Libera ~102 MB em game_achievements, que sozinha ocupava 223 MB de um banco de 556 MB — acima da
-- cota de 0,5 GB do plano free do Supabase (111%, com restricoes anunciadas).
--
-- Resultado medido: banco de 556 MB -> 361 MB; a tabela de 223 MB -> 121 MB.
--
-- 1) icon_gray_url (57 MB): gravada e NUNCA lida. Nao aparecia em nenhum SELECT, nenhum DTO a
--    expunha e o frontend nao tinha uma referencia sequer — a interface mostra o icone colorido com
--    um cadeado por cima (.achievement-lock), nao uma versao cinza.
--
--    Considerei derivar o cinza do colorido em vez de apagar, mas o padrao _BW.jpg valia para 6.904
--    das 510.142 linhas: teria corrompido 99%.
--
-- 2) icon_url: as 510.142 linhas comecavam com o mesmo prefixo do CDN da Steam. Passa a guardar so
--    a parte variavel (620/WAKE_UP.jpg); o backend remonta na leitura (comum/IconeConquista).
--
-- ORDEM IMPORTA: o backend que le os dois formatos foi implantado ANTES desta migration
-- (commit 3290af4). Na ordem inversa os icones quebrariam no intervalo entre uma coisa e outra.

ALTER TABLE game_achievements DROP COLUMN IF EXISTS icon_gray_url;

-- O prefixo tem 66 caracteres contando a barra final. A primeira versao usou 66 como inicio do
-- substring (em vez de 67) e deixou a barra sobrando no valor guardado; nada quebrou para o
-- usuario, porque a CDN aceita a barra dupla, mas ficava inconsistente com o que o backend grava.
UPDATE game_achievements
SET icon_url = substring(icon_url from 67)
WHERE icon_url LIKE 'https://steamcdn-a.akamaihd.net/steamcommunity/public/images/apps/%';

-- Espaco so volta pro disco com VACUUM FULL; VACUUM comum apenas marca reuso interno.
-- Toma lock exclusivo: rodar fora de horario de pico.
VACUUM FULL game_achievements;
