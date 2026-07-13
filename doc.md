# doc.md

> Este arquivo DEVE ser atualizado sempre que houver uma mudança significativa no projeto: decisão de arquitetura, fonte de dados, tecnologia, schema, deploy ou contrato de API. É a fonte de verdade sobre o que o projeto é e onde ele está.

## Objetivo do projeto

Site de catálogo de promoções de jogos. O usuário entra, vê uma lista de jogos com o menor preço encontrado entre várias lojas, clica no jogo e vê todas as ofertas daquele jogo ordenadas por preço, com link direto para a loja.

Não é uma loja própria. É um agregador/comparador de preços.

## Stack

- **Backend:** Java 21 + Spring Boot 3 em `backend-java`.
- **Frontend:** Angular, hospedado no Vercel.
- **Banco:** PostgreSQL no Supabase. Conexão via transaction pooler (porta 6543), com prepared statements desativados no JDBC (`prepareThreshold=0`) por incompatibilidade do modo transaction do Supavisor.
- **Auth:** Supabase Auth (email/senha e OAuth). Frontend usa `@supabase/supabase-js`; backend valida Bearer token via Supabase Auth.
- **Deploy backend:** Render via Docker.

## Deploy

| Parte | Onde roda |
|---|---|
| Backend | Render |
| Frontend | Vercel |
| Banco/Auth | Supabase |

O backend usa `backend-java/Dockerfile`. O Render pode ser criado manualmente ou via `render.yaml`.

Configuração no Render:

| Campo | Valor |
|---|---|
| Runtime | Docker |
| Root Directory | `backend-java` |
| Health Check Path | `/actuator/health` |
| Plan | Free por enquanto |

Coleta de preços: o próprio backend Spring executa jobs agendados no Render. Não há mais sync por GitHub Actions.

- A cada 10 minutos, atualiza 5.000 jogos: 200 relevantes (top 2.000 por `rank`) e 4.800 da fila geral.
- A seleção usa `games.last_price_sync_at`, do mais antigo para o mais recente. Depois de atualizado, cada jogo vai naturalmente ao fim da fila.
- A ITAD recebe no máximo 200 IDs por chamada; cada rodada faz até 25 chamadas sequenciais.
- Se uma chamada falhar, o lote é tentado três vezes; se continuar falhando, os demais lotes seguem e o lote com falha permanece prioritário na próxima rodada.
- A coleta de metadados Steam é um job separado a cada 15 minutos, com até 25 jogos pendentes por rodada.
- Uma trava compartilhada no PostgreSQL impede sobreposição entre os jobs, inclusive se houver mais de uma instância durante um deploy.

O bot que ja mantem o Render ativo tambem substitui o antigo keep alive do GitHub Actions.

## Variáveis de ambiente do backend

| Variável | Descrição |
|---|---|
| `DATABASE_URL` | URL do pooler do Supabase, formato `postgresql://...` |
| `ITAD_API_KEY` | Chave da API do IsThereAnyDeal |
| `SYNC_SECRET_KEY` | Chave secreta para o endpoint `/api/sync` |
| `APP_SYNC_SCHEDULER_ENABLED` | Ativa a coleta interna no Render. Usar `true` depois de executar a migração SQL. |
| `APP_SYNC_SCHEDULER_PRICE_DELAY_MS` | Intervalo da coleta de preços. Padrão `600000` (10 minutos). |
| `APP_SYNC_SCHEDULER_STEAM_DELAY_MS` | Intervalo da coleta de metadados Steam. Padrão `900000` (15 minutos). |
| `SUPABASE_URL` | URL do projeto Supabase |
| `SUPABASE_ANON_KEY` | Chave anônima do Supabase usada para validar tokens |
| `CORS_ALLOWED_ORIGINS` | Origens permitidas separadas por vírgula |
| `PORT` | Porta HTTP do Spring Boot, padrão `8080` |

## Fontes de dados de preços

| Fonte | Status | Como integra |
|---|---|---|
| **IsThereAnyDeal (ITAD)** | Fonte principal, em uso | API oficial. Lojas configuradas incluem Nuuvem, Fanatical, GamersGate, IndieGala, 2game, Steam, Epic, Blizzard, EA Store, Microsoft Store e Ubisoft Store. Algumas lojas podem estar bloqueadas por regra de produto quando seus links não abrem corretamente. |
| **Steam** | Complementar | Usada para derivar capa oficial e classificar DLC quando há oferta Steam. |
| **Eneba** | Planejada | Feed de afiliados XML/CSV após aprovação no cadastro. |
| **Instant Gaming** | Sem integração automática | Aguardando aprovação no programa de afiliados deles. |

Todas as fontes gravam na tabela `offers`, diferenciadas pela coluna `source`.

## Schema do banco

```sql
games
  id            bigserial PK
  itad_id       uuid UNIQUE NULL
  title         text NOT NULL
  slug          text UNIQUE NOT NULL
  cover_url     text NULL
  rank          integer NULL
  is_dlc        boolean NULL
  last_price_sync_at timestamptz NULL
  last_steam_sync_at timestamptz NULL
  created_at    timestamptz DEFAULT now()

offers
  id            bigserial PK
  game_id       bigint FK -> games.id
  source        text NOT NULL
  store_name    text NOT NULL
  price         numeric(10,2) NOT NULL
  regular_price numeric(10,2) NULL
  currency      text NOT NULL DEFAULT 'BRL'
  url           text NOT NULL
  updated_at    timestamptz NOT NULL
  UNIQUE (game_id, source, store_name)

favorites
  id            bigserial PK
  user_id       uuid NOT NULL
  game_id       bigint FK -> games.id
  created_at    timestamptz DEFAULT now()
  UNIQUE (user_id, game_id)

sync_locks
  name          text PK
  locked_until  timestamptz NOT NULL

price_notifications
  id            bigserial PK
  user_id       uuid FK -> auth.users
  game_id       bigint FK -> games.id
  previous_price numeric(10,2)
  current_price numeric(10,2)
  store_name    text NULL
  read_at       timestamptz NULL
  created_at    timestamptz DEFAULT now()
```

- Menor preço é calculado via query (`MIN(price)`), não armazenado.
- `rank` vem do feed ITAD; menor = mais popular.
- Somente BRL por enquanto.
- A tabela `favorites` representa hoje jogos monitorados/salvos pelo usuário para acompanhar preço. No produto, isso aparece como **Jogos Monitorados**. Favoritos pessoais do perfil serão separados em outra estrutura futura.

## Endpoints da API

- `GET /api/games?page=0&size=20&sort=rank&type=all&platform=all&minPrice=&maxPrice=&minDiscount=&q=`
  - `sort`: `rank`, `discount`, `price_asc`, `price_desc`
  - `type`: `all`, `game`, `dlc`
  - `platform`: `all`, `pc`, `xbox`. PlayStation fica oculto no frontend enquanto não houver ofertas dessa plataforma.
  - `minDiscount`: desconto percentual mínimo calculado a partir do menor preço e do preço regular.
  - Retorna também `storeName` e `url` da oferta usada como menor preço quando disponível, para exibir loja e plataforma nos cards.
  - Ordenação padrão: top 200 por rank com desconto ativo sobem ao topo.
- `GET /api/games/search?q=nome`
  - Busca primeiro no banco.
  - Se não achar, busca na ITAD e insere os jogos encontrados.
- `GET /api/games/{slug}`
  - Retorna detalhe do jogo e ofertas ordenadas por preço.
- `POST /api/games/{slug}/refresh`
  - Atualiza preços do jogo na ITAD.
  - Complementa capa e classificação DLC via Steam quando possível.
- `GET /api/deals/top?size=20&sort=discount`
  - Retorna melhores descontos deduplicados por jogo.
  - `sort=rank` prioriza jogos mais famosos com desconto.
- `POST /api/sync?page=0`
  - Endpoint legado para diagnóstico ou sincronização manual pontual de uma página da ITAD.
  - Exige header `X-Sync-Key`.
  - O job interno é o responsável pela atualização recorrente do catálogo.
- `GET /api/favorites`
  - Lista jogos monitorados/salvos pelo usuário autenticado para acompanhar preço.
- `POST /api/favorites`
  - Adiciona jogo aos monitorados (`{ slug }`).
- `DELETE /api/favorites/{slug}`
  - Remove jogo dos monitorados.
- `GET /api/notifications`
  - Lista os 30 alertas mais recentes de queda de preco do usuario autenticado.
- `PATCH /api/notifications/{id}/read`, `PATCH /api/notifications/read-all` e `DELETE /api/notifications/{id}`
  - Marca alertas como lidos ou remove uma notificacao.
- `GET /actuator/health`
  - Health check usado pelo Render e pelo bot de monitoramento.
- `GET /api/admin/coleta`
  - Retorna status em memória das coletas de preços/Steam e resumo da fila.
  - Exige token Supabase do UID administrador configurado no backend.
- `POST /api/admin/coleta/precos` e `POST /api/admin/coleta/steam`
  - Disparam uma coleta manual em segundo plano.
  - Exigem o mesmo UID administrador; os jobs continuam protegidos pela trava compartilhada.

## Estrutura de pastas

```text
/backend-java
  Dockerfile
  pom.xml
  src/main/java/com/ofertagames/backend/
    AplicacaoOfertaGames.java
    autenticacao/       -> valida Bearer token via Supabase Auth
    comum/              -> utilitários compartilhados, incluindo lojas bloqueadas
    configuracao/       -> CORS e conexão PostgreSQL
    descontos/          -> endpoint /api/deals/top
    favoritos/          -> endpoints /api/favorites
    itad/               -> cliente e modelos da API ITAD
    jogos/              -> catálogo, detalhe, busca e refresh
    saude/              -> endpoint /actuator/health
    sincronizacao/      -> endpoint legado, agendador e trava de coleta
    steam/              -> capa oficial e detecção de DLC

/frontend
  src/app/
    pages/
      home/
      catalog/
      best-sellers/
      game-detail/
      free-games/
      search/
      login/
      profile/
      settings/
      favorites/
    components/
      sidebar/
      topbar/
      game-card/
      deals-carousel/
    services/
      game.ts            -> chamadas para `https://oferta-games.onrender.com/api`
      auth.ts
      favorites.ts       -> favoritos via backend Render
      supabase.ts
      theme.ts
      filters.ts
      store-brand.ts      -> resolve logos locais e plataformas inferidas pelo nome da loja
    guards/
      auth.guard.ts

```

## Padrão de código do backend

- Classes, pacotes, métodos e variáveis em português.
- Exemplos: `ControladorJogos`, `RepositorioJogos`, `ServicoCatalogo`, `ServicoAutenticacao`.
- Marcas, termos externos e contrato JSON podem manter o nome original: `ITAD`, `Steam`, `Spring`, `Bearer`, `coverUrl`, `minPrice`, `regularPrice`, `discountPct`.

## Decisões de design e produto

- **Cor principal:** `#29A8E0` (azul).
- **Ícones:** PNGs em `frontend/public/`.
- **Logos de loja/plataforma:** SVGs locais em `frontend/public/store-logos/` e `frontend/public/platform-logos/`, resolvidos no frontend por `store-brand.ts` a partir de `storeName`.
- **Plataformas:** enquanto o backend não persiste `platforms/drm` do ITAD, o frontend infere PC e Xbox pelo nome/link da loja. PlayStation fica suportado internamente, mas sem botão no catálogo enquanto não houver ofertas.
- **Filtro de plataforma no catálogo:** o backend recebe `platform` em `/api/games` e calcula preço/loja considerando ofertas da plataforma filtrada.
- **Lojas bloqueadas:** lojas com links quebrados são filtradas por `LojasBloqueadas.java` e também ignoradas no salvamento do sync. Lista atual: GreenManGaming, AllYouPay/AllYouPlay, PlanetPlay, PlayerLand, JoyBuggy, WinGameStore, MacGameStore, Humble Store/Humble Bundle.
- **Jogos bloqueados:** itens específicos com oferta/link quebrado são ocultados por slug e URL da oferta em `JogosBloqueados.java`. A lista atual inclui `tell-me-why-chapter-1` e o link `https://itad.link/019e8518-404a-709b-ae47-a4ef949552ea/`.
- **Conteúdo que não é jogo:** cursos, bundles educacionais e musicais da ITAD são filtrados por `ConteudosNaoJogos.java` no catálogo, descontos, busca, detalhe, favoritos e filas de coleta. A regra cobre termos como certification, e-learning, Kali Linux, programming bundle, cybersecurity, phonk e masterclass.
- **DLCs nos descontos:** a home separa jogos base e DLCs a partir de `games.is_dlc` e heurísticas de título. `ClassificadorDlc.java` atende os filtros do catálogo e a mesma lista de pacotes de Lords of the Fallen está no frontend, mantendo Monk Decipher e similares na seção de DLCs.
- **DLC detection:** `games.is_dlc` é preenchido via Steam quando há oferta Steam; enquanto `is_dlc IS NULL`, o frontend usa heurística por título.
- **Deduplicação de deals:** `DISTINCT ON (g.id)` mantém apenas a oferta mais barata por jogo.
- **Catálogo rotativo:** top 200 por rank com desconto ativo sobem ao topo.
- **Coleta de preços:** roda internamente no Spring, em fila baseada em `last_price_sync_at`. Cada rodada atualiza 200 jogos relevantes e 4.800 jogos gerais em lotes de 200 IDs da ITAD. As ofertas ITAD retornadas substituem o conjunto anterior do mesmo jogo, removendo lojas e preços que não existam mais.
- **Coleta Steam:** roda em job separado e preenche capa/DLC dos jogos pendentes. Ela usa `last_steam_sync_at` para evitar repetir o mesmo jogo antes dos demais.
- **Concorrência da coleta:** jobs de preço e Steam não podem rodar juntos; `sync_locks` é uma trava compartilhada no banco que também protege durante deploys com duas instâncias temporárias.
- **Login:** página de login sem sidebar/topbar.
- **Home:** banner com autoplay e seções em carrossel.
- **Perfil:** dashboard gamer com avatar, bio editável, estatísticas de gameplay, resumo de biblioteca e atividade recente. A aba **Jogos favoritos** é isolada e mostra apenas os favoritos pessoais futuros; preferências ficam somente em Configurações. A foto é enviada ao Supabase Storage, persiste entre sessões e é exibida no perfil público.
- **Jogos Monitorados:** a rota `/monitorados` e os endpoints `/api/favorites` representam jogos que o usuário quer acompanhar por preço. A rota antiga `/favoritos` redireciona para `/monitorados` por compatibilidade.
- **Ações de monitoramento:** cards do catálogo e a página de detalhe usam `jogos-monitorados.png`, nunca o ícone de favoritos pessoais, para incluir ou remover um jogo da lista de preços monitorados.
- **Jogos favoritos:** no perfil, este nome é reservado para favoritos pessoais do usuário. Ainda não usa persistência própria; será implementado com estrutura separada dos jogos monitorados.
- **Configurações:** divididas em Conta, Conexões, Preferências e Privacidade. Conta concentra identidade, senha e sessão; Conexões concentra Steam/Xbox; Preferências afetam o conteúdo da home e os filtros iniciais do catálogo; Privacidade controla a exposição futura dos dados sincronizados no perfil.
- **Preferências do usuário:** persistidas localmente no navegador enquanto não houver contrato próprio no backend. A home continua com conteúdo geral misturado e, quando existe uma plataforma preferida, mostra a seção exclusiva **Jogos da sua plataforma favorita** logo abaixo de Jogos Monitorados (ou abaixo do banner quando não houver monitorados). Ocultação de DLCs, desconto mínimo e preço máximo também são aplicados à seção. No catálogo, plataforma, DLCs, desconto mínimo e preço máximo inicializam os filtros sem impedir ajustes manuais.
- **Privacidade do perfil:** persistida localmente por enquanto. As opções já estão preparadas para horas jogadas, conquistas, biblioteca e jogos favoritos, mas só terão efeito público quando existir perfil compartilhável e persistência no backend.
- **Notificações:** o sino da topbar lista alertas de queda de preço para Jogos Monitorados, permite marcar como lidos ou remover e mostra badge de itens não lidos. A coleta cria alerta apenas quando o menor preço passa a ser menor que o valor anterior.
- **Alerta de preço:** antes de substituir o lote de ofertas ITAD, a coleta lê o menor preço atual; depois compara o novo menor preço e cria uma notificação para cada usuário que monitora o jogo quando houver queda. O mesmo preço não gera alerta repetido porque não é uma nova queda.
- **Capa ausente:** fallback visual em `no-cover.svg`; backend tenta preencher capa oficial da Steam quando possível.
- **Catálogo:** scroll infinito via `window:scroll` com throttle por `requestAnimationFrame`.
- **Navegação:** toda mudança de rota inicia no topo da página; o scroll infinito permanece restrito ao comportamento da própria tela de catálogo.
- **Administração de coleta:** a rota `/admin/coleta` mostra status de preços, Steam e fila, além de permitir disparo manual em segundo plano. O frontend limita a rota ao UID administrador e o backend exige o mesmo UID no token Supabase para todos os endpoints `/api/admin/*`; UID permitido: `0a6eb06b-756e-4434-899b-33420bed8609`.

## Implementações futuras planejadas

- **Preferências de alertas:** adicionar limite de preço desejado e canais externos, como e-mail ou push, depois de validar as notificações internas.
- **Favoritos pessoais do perfil:** evoluir a lista já persistida com ordenação manual, limite de exibição pública e, futuramente, coleções.
- **Monitoramento de preço:** manter os favoritos atuais como lista de jogos monitorados. No produto, usar o nome **Jogos Monitorados** para esse conceito.
- **Perfil:** evoluir favoritos pessoais com ordenação manual e coleções quando houver necessidade de mais organização.
- **Conexões de plataformas:** implementar em Configurações, começando por Steam/Xbox quando houver decisão técnica. O perfil apenas consome os dados sincronizados.
- **Persistência de configurações:** migrar preferências e privacidade do `localStorage` para uma tabela vinculada ao usuário no Supabase quando houver perfil público e uso em múltiplos dispositivos.
- **Gameplay real:** substituir placeholders de horas jogadas, conquistas e biblioteca por dados sincronizados das conexões. Estados que dependem de conexão devem usar o padrão `--` + `Conecte uma plataforma`; cards de horas por plataforma só devem aparecer para plataformas realmente conectadas pelo usuário.
- **Atividade recente:** evoluir de eventos locais/derivados para eventos reais, como jogo favoritado no perfil, jogo monitorado, conquista sincronizada ou plataforma conectada.
- **Importação de catálogo:** avaliar uma coleta de descoberta separada para incluir jogos novos da ITAD sem misturar essa responsabilidade com a fila de atualização de preços.

## O que NÃO fazer

## Conexao Steam

- A conexao e feita pelo Steam OpenID; o usuario confirma a propria conta na Steam e o sistema nao solicita Steam ID ou URL manualmente.
- A Steam Web API sincroniza perfil, biblioteca, horas jogadas e conquistas. A biblioteca e carregada apos conectar ou por acao manual; conquistas sao atualizadas gradualmente.
- A aba Biblioteca mostra os 100 jogos Steam com maior tempo jogado. A acao manual de sincronizacao tambem prioriza conquistas dos 50 jogos mais relevantes da conta.
- Lista de desejos nao e coletada.
- Antes do deploy, executar `backend-java/sql/20260711_conexoes_steam.sql` no Supabase e configurar no Render: `STEAM_WEB_API_KEY`, `PUBLIC_BACKEND_URL=https://oferta-games.onrender.com` e a URL publica correta em `FRONTEND_URL`.

## Perfis publicos

- Cada usuario escolhe um identificador unico e compartilhavel na raiz, no formato `/identificador`. Rotas do produto sao reservadas e nao podem ser usadas como identificador.
- A URL canonica do perfil e o proprio endereco compartilhavel do usuario, sem exibir um link duplicado dentro do perfil.
- O perfil publico replica a linguagem visual do perfil privado e mostra somente os blocos autorizados pela privacidade. Quando o proprio dono autenticado abre sua URL canonica, recebe tambem os controles de trocar foto e editar bio; visitantes nunca recebem essas acoes.
- A URL canonica preserva as tres abas do perfil: Resumo, Jogos favoritos e Biblioteca. O resumo contem os cards de favoritos, horas e conquistas, alem dos paineis de favoritos pessoais, biblioteca e atividade recente; a atividade permanece como placeholder ate possuir eventos persistidos.
- A topbar resolve o identificador antes de navegar, evitando renderizar `/perfil` como tela intermediaria. A pagina publica aguarda a resposta da API antes de exibir indisponibilidade.
- A rota `/perfil` e apenas uma ponte autenticada: cria um identificador temporario seguro quando necessario e redireciona para a URL canonica `/<identificador>`. Perfis privados continuam visiveis somente pelo proprio dono autenticado.
- Novos perfis sao publicos por padrao, mas podem ser privados em Configuracoes > Privacidade. E-mail, UUID, jogos monitorados e dados de conexao nunca sao expostos.
- O usuario escolhe se libera horas jogadas, conquistas e biblioteca. O backend filtra os dados antes de responder a rota publica.
- A persistencia fica em `profiles`; executar `backend-java/sql/20260711_perfis_publicos.sql` no Supabase antes do deploy.
- Em bancos ja existentes, executar tambem `backend-java/sql/20260712_perfis_publicos_por_padrao.sql`. A migracao nao altera a visibilidade dos perfis ja criados.
- Avatares publicos usam o bucket `avatars` do Supabase Storage. Executar tambem `backend-java/sql/20260712_avatars_perfil.sql`; o upload aceita JPEG, PNG e WebP de ate 2 MB e cada usuario so pode gravar em sua propria pasta.
- A API de perfis usa `PUT /api/perfis/me` e `PUT /api/perfis/me/avatar`; a politica CORS global permite `PUT` para a origem configurada em `CORS_ALLOWED_ORIGINS`.

## Favoritos pessoais do perfil

- Favoritos pessoais usam a tabela `profile_favorites`, separada de `favorites`, que continua sendo exclusivamente de Jogos Monitorados.
- O usuario adiciona ou remove favoritos pessoais pela pagina de detalhe do jogo. A aba **Jogos favoritos** do proprio perfil lista, remove e direciona para o catalogo; visitantes apenas visualizam a lista quando a privacidade permitir.
- A API autenticada usa `GET`, `POST` e `DELETE /api/profile-favorites`; a URL publica do perfil inclui os favoritos somente quando `show_favorite_games` estiver ativo.
- Executar `backend-java/sql/20260712_favoritos_pessoais.sql` no Supabase antes do deploy do backend.

## Atividade recente do perfil

- Atividades sao registradas em `profile_activities` para adicao/remocao de jogos monitorados, adicao/remocao de favoritos pessoais, conexao Steam e sincronizacoes manuais da Steam.
- A atividade e publica por padrao: visitantes veem os eventos quando o perfil esta publico. Em Privacidade, o dono pode desativar **Mostrar atividade recente**; nesse caso os eventos nao sao enviados pela API para visitantes.
- Executar `backend-java/sql/20260712_atividades_perfil.sql` e `backend-java/sql/20260712_visibilidade_atividade_perfil.sql` no Supabase antes do deploy do backend.
- Qualquer visitante pode usar **Atualizar dados** no perfil publico para solicitar uma sincronizacao completa da Steam (biblioteca, horas e conquistas). A operacao roda em segundo plano e cada perfil aceita uma solicitacao a cada 10 minutos, protegendo a Steam e o backend contra abuso. Executar tambem `backend-java/sql/20260712_atualizacao_publica_perfil.sql`.
- A primeira sincronizacao Steam registra apenas os resumos de biblioteca e conquistas. A partir da linha de base, novos jogos da biblioteca e novas conquistas viram eventos individuais. Executar `backend-java/sql/20260712_atividades_steam_detalhadas.sql` antes do deploy.
- Se o backend ja estiver consultando atividades detalhadas e o perfil retornar erro, aplique imediatamente essa migration no SQL Editor do Supabase: ela cria a coluna `profile_activities.detalhe` usada pela atividade recente.
- A aba Biblioteca permite buscar os jogos sincronizados e ordenar por tempo jogado, nome ou percentual de conquistas.
- **Xbox:** permanece como futura integracao. A documentacao oficial concentra as APIs de conquistas e dados de jogador no GDK/XSAPI para titulos registrados, sem um fluxo publico equivalente ao Steam OpenID + Web API para importar bibliotecas de qualquer conta. Nao usar APIs nao oficiais ou scraping para isso.

## Icones de perfil e biblioteca

- `horas-jogadas.png`: estatisticas de horas jogadas.
- `conquistas.png`: estatisticas de conquistas.
- `biblioteca.png`: resumo e abas da biblioteca sincronizada.
- `biblioteca-modo-escuro.png`: variacao da biblioteca usada no tema escuro.
- `jogos-favoritos.png`: favoritos pessoais do perfil.
- `jogos-favoritos-tema-escuro.png`: variacao usada apenas no tema escuro para melhorar o contraste dos favoritos pessoais.
- `jogos-monitorados.png`: monitoramento de precos e rota `/monitorados`.
- `jogos-monitorados-modo-claro.png` e `jogos-monitorados-modo-escuro.png`: variacoes do icone de monitoramento por tema.
- `steam-modo-claro.png`, `steam-modo-escuro.png`, `xbox-modo-claro.png` e `xbox-modo-escuro.png`: variacoes dos icones de plataforma por tema.

- Sem scraping de sites.
- Sem multi-moeda funcional por enquanto.
- Não disparar mais a sincronização recorrente pelo GitHub Actions.

## Estado atual

- [x] Backend Spring Boot estruturado em `backend-java`
- [x] Endpoints de catálogo, busca, detalhe, refresh, descontos, sync e favoritos implementados
- [x] Integração ITAD implementada
- [x] Complemento Steam para capa/DLC implementado
- [x] Supabase PostgreSQL mantido como banco
- [x] Supabase Auth usado nos favoritos
- [x] Frontend Angular implementado
- [x] Perfil visual implementado com bio editável, avatar persistido e dados Steam sincronizados
- [x] Dockerfile do backend Java configurado para Render
- [x] Coleta interna de preços e metadados Steam agendada no Spring, com fila e trava no PostgreSQL
- [x] Workflows de sync e keep alive do GitHub Actions removidos
- [ ] Executar `backend-java/sql/20260711_coleta_agendada.sql` no Supabase e ativar `APP_SYNC_SCHEDULER_ENABLED=true` no Render
- [x] Backend publicado no Render
- [x] Separar favoritos pessoais do perfil dos jogos monitorados por preço
- [x] Atividade recente real, publica por padrao e configuravel na privacidade do perfil
- [x] Atividade Steam detalhada e Biblioteca pesquisavel com progresso de conquistas
- [x] Persistir foto de perfil no Supabase Storage
- [ ] Conexões de plataformas em Configurações
- [ ] Sincronizar horas jogadas, conquistas e biblioteca
- [ ] Integração com Eneba
- [ ] Integração com Instant Gaming
