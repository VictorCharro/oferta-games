-- Fecha a API publica do Supabase (PostgREST) em todas as tabelas do schema public.
--
-- PROBLEMA (verificado em producao antes da correcao)
--
-- A chave anon do Supabase e publica por design: vai no bundle do frontend e qualquer visitante a
-- extrai. RLS e a UNICA barreira, e nao estava ligado em nenhuma das 28 tabelas. Consequencia
-- medida com a propria chave do bundle:
--
--   leitura anonima  -> profiles, favorites, steam_connections, xbox_connections,
--                       steam_library_games, price_notifications, profile_collections
--   escrita anonima  -> UPDATE e DELETE aceitos em favorites, profiles e price_history
--
-- Ou seja: um DELETE sem filtro apagaria os jogos monitorados de todos os usuarios, ou o historico
-- de preco dos 110k jogos.
--
-- POR QUE SEM POLICY NENHUMA
--
-- O frontend nunca acessa tabela direto - so `supabase.auth.*` (sessao) e `supabase.storage.*`
-- (avatares). Todo dado passa pelo backend Java. Nao existe caso legitimo de acesso via API
-- publica, entao negar tudo e o comportamento correto; escrever policy por tabela seria trabalho
-- grande, sujeito a erro, e sem ganho.
--
-- POR QUE O BACKEND NAO QUEBRA
--
-- Ele conecta por JDBC como `postgres`, dono das 28 tabelas, e o dono ignora RLS.
-- NAO usar FORCE ROW LEVEL SECURITY: isso passaria a valer tambem pro dono e derrubaria a API.
--
-- Verificado depois de aplicar: leitura anonima devolve [] em todas; UPDATE e DELETE anonimos
-- contra uma linha real devolvem [] com `Prefer: return=representation` e a linha sobrevive
-- intacta; e as 8 rotas principais do backend seguem respondendo 200.
--
-- (Atencao ao ler resposta de escrita do PostgREST: um 204 NAO prova que a escrita passou - ele
-- responde 204 mesmo afetando 0 linhas. So `Prefer: return=representation` e conclusivo.)
--
-- O storage ja estava correto desde antes: `storage.objects` tem policies de leitura publica e de
-- escrita restrita a pasta do proprio usuario.
--
-- Se um dia o frontend precisar ler alguma tabela direto, a policy vem junto - nao remova o RLS.

ALTER TABLE public.coleta_status ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.favorites ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.game_achievements ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.game_details ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.game_review_votes ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.game_reviews ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.games ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.instant_gaming_catalog ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.instant_gaming_scan_cursor ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.offers ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.price_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.price_notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.profile_activities ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.profile_blocks ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.profile_collection_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.profile_collections ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.profile_favorites ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.profile_platinum_order ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.profile_steam_favorites ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.steam_auth_states ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.steam_connections ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.steam_game_achievements ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.steam_library_games ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.steam_user_achievements ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.sync_locks ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.xbox_connections ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.xbox_library_games ENABLE ROW LEVEL SECURITY;
