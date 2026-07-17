# Oferta Games - Contexto do Projeto

> Este arquivo e a fonte de verdade do projeto. Atualize-o sempre que uma decisao de arquitetura, schema, deploy, contrato de API ou fluxo relevante mudar. Nao registre segredos, tokens ou senhas aqui.

## Produto

O Oferta Games e um comparador de precos de jogos. Ele nao vende jogos: coleta ofertas de lojas parceiras por meio da API IsThereAnyDeal (ITAD), mostra o menor preco em BRL e encaminha o usuario para a loja.

O produto tambem tem contas, Jogos Monitorados para alertas de preco, perfis publicos personalizaveis e conexao Steam para biblioteca, horas e conquistas.

## Arquitetura Atual

| Camada | Tecnologia | Hospedagem atual |
|---|---|---|
| Frontend | Angular 21 + TypeScript | Vercel |
| Backend | Java 21 + Spring Boot 3 | Oracle Always Free via Docker |
| Banco e Auth | PostgreSQL + Supabase Auth + Storage | Supabase |
| Precos | ITAD API | Consumida pelo backend |
| Perfil gamer | Steam OpenID + Steam Web API | Consumida pelo backend |

URLs de producao atuais:

- Frontend: `https://ofertagames.vercel.app`
- Backend Oracle temporario: `https://api.163.176.220.243.sslip.io`
- Health check Oracle: `https://api.163.176.220.243.sslip.io/actuator/health`

O banco usa o transaction pooler do Supabase. A conversao da `DATABASE_URL` para JDBC e feita pelo backend, com `prepareThreshold=0`, pois prepared statements persistentes nao sao compativeis com esse modo do Supavisor.

## Deploy e Operacao

O `Dockerfile` faz o build Maven em imagem Java 21 e inicia o JAR com limite de heap `-Xmx384m`. O Render esta desligado e nao deve receber novos deploys. Nao existe workflow de sincronizacao recorrente no GitHub Actions.

### Variaveis do backend

| Variavel | Obrigatoria | Uso |
|---|---:|---|
| `DATABASE_URL` | sim | PostgreSQL Supabase pelo pooler |
| `SUPABASE_URL` | sim | Validacao de tokens Supabase |
| `SUPABASE_ANON_KEY` | sim | Validacao de tokens Supabase |
| `ITAD_API_KEY` | sim | Coleta e refresh de ofertas |
| `STEAM_WEB_API_KEY` | sim para Steam | Biblioteca, horas e conquistas Steam |
| `PUBLIC_BACKEND_URL` | sim para Steam | URL publica do backend para retorno OpenID |
| `FRONTEND_URL` | sim para Steam | URL do frontend para redirecionamentos |
| `CORS_ALLOWED_ORIGINS` | sim | Separar por virgula; incluir Vercel e `http://localhost:4200` |
| `APP_SYNC_SCHEDULER_ENABLED` | sim | `true` em producao |
| `APP_SYNC_SCHEDULER_PRICE_DELAY_MS` | nao | Padrao `600000` (10 min) |
| `APP_SYNC_SCHEDULER_STEAM_DELAY_MS` | nao | Padrao `900000` (15 min) |
| `SYNC_SECRET_KEY` | sim para endpoint legado | Protege `POST /api/sync` |
| `PORT` | nao | Fornecida pelo Render; padrao `8080` |

Nunca colocar essas variaveis no Git ou em arquivos do frontend.

### Oracle

A VM Oracle Always Free em Sao Paulo foi criada com Ubuntu 24.04 ARM, `1 OCPU` e `6 GB`. Ela atende a producao. O compose da VM fica em `deploy/oracle/compose.yml`, com o arquivo secreto `deploy/oracle/.env` criado somente no servidor. Ele inclui as chaves de banco/Supabase/ITAD/Steam, CORS, `FRONTEND_URL`, `API_DOMAIN` e `PUBLIC_BACKEND_URL`.

O Caddy e executado no mesmo compose e entrega HTTPS na frente do backend, que nao expoe mais a porta 8080 fora da rede Docker. Enquanto nao houver dominio proprio, pode ser usado temporariamente `api.163.176.220.243.sslip.io`; ele aponta para o IP publico da VM. O frontend so deve trocar do Render para a Oracle depois que `https://<API_DOMAIN>/actuator/health` responder `UP`.

O workflow `.github/workflows/deploy-oracle.yml` atualiza o backend na VM por SSH em cada push relevante para `master`; exige os segredos `ORACLE_HOST` e `ORACLE_SSH_PRIVATE_KEY` no GitHub. No estado atual, os GitHub Actions da conta estao bloqueados por cobranca/limite da conta e nao devem ser considerados para deploy ate a normalizacao no GitHub. O deploy manual oficial e:

```powershell
.\scripts\deploy-oracle.ps1 -ChaveSsh "C:\Users\VFulls\Downloads\oferta-games.key"
```

O script conecta na VM, atualiza o `master`, recria somente o container do backend e valida o health check. A chave da VM usada para baixar o repositorio continua somente leitura.

## Coletas e Atualizacao de Catalogo

### Precos ITAD

O Spring executa a coleta internamente quando `APP_SYNC_SCHEDULER_ENABLED=true`.

- Intervalo padrao: 10 minutos.
- Cada rodada seleciona 5.000 jogos: 200 relevantes (top 2.000 por `rank`) e 4.800 da fila geral.
- A fila e dada por `games.last_price_sync_at`: o item mais antigo e atualizado primeiro e volta naturalmente ao fim da fila.
- A ITAD recebe lotes de ate 200 IDs, em ate 25 chamadas sequenciais por rodada.
- Cada lote e tentado ate tres vezes. Uma falha nao cancela os demais lotes; o jogo permanece prioritario na proxima rodada.
- A coleta atualiza **todas as ofertas retornadas pela ITAD** para cada jogo, como o botao manual `Atualizar precos`. Ofertas ITAD antigas que nao retornam mais sao removidas.
- Antes e depois da troca de ofertas, o backend compara o menor preco para criar notificacoes de queda para quem monitora o jogo.
- `sync_locks` impede jobs simultaneos, inclusive em deploys com duas instancias temporarias.

O endpoint `POST /api/sync?page=0` e legado: serve para diagnostico/manual e exige `X-Sync-Key`. Nao usar GitHub Actions para processar o catalogo completo.

### Metadados Steam de catalogo

Job separado, em geral a cada 15 minutos:

- Completa capa oficial quando ela esta ausente.
- Ajuda a identificar DLCs quando existe uma oferta Steam correspondente.
- Processa um lote pequeno de jogos pendentes com `games.last_steam_sync_at`.
- Compartilha a mesma trava da coleta de preco.

## Fonte de Dados e Regras de Catalogo

### ITAD

ITAD e a fonte principal de catalogo e ofertas. O sistema trabalha somente com BRL por enquanto.

- Menor preco e calculado em query com `MIN(price)`, nao armazenado.
- `rank` vem da ITAD: menor rank significa jogo mais relevante.
- A busca consulta o banco primeiro; se nao encontrar, consulta ITAD e persiste os resultados.
- A pagina de detalhe pode atualizar um jogo individualmente via ITAD.

### Lojas e conteudos bloqueados

As regras ficam no backend em classes de dominio, nao no frontend:

- `LojasBloqueadas.java`: GreenManGaming, AllYouPay/AllYouPlay, PlanetPlay, PlayerLand, JoyBuggy, WinGameStore, MacGameStore e Humble Store/Humble Bundle, porque os links nao abriam corretamente.
- `JogosBloqueados.java`: itens especificos com link quebrado, incluindo `tell-me-why-chapter-1` e a URL ITAD `019e8518-404a-709b-ae47-a4ef949552ea`.
- `ConteudosNaoJogos.java`: cursos, bundles educacionais e musicais nao aparecem como jogos. Exemplos de termos filtrados: certification, e-learning, Kali Linux, programming bundle, cybersecurity, phonk e masterclass.

Ao adicionar uma nova loja ou excecao, garantir que ela seja filtrada em catalogo, home, busca, detalhe, favoritos e fila de coleta.

### Plataformas e DLCs

- O catalogo recebe `platform=all|pc|xbox`.
- Como a plataforma ainda nao e persistida diretamente da ITAD, o frontend infere PC/Xbox pelo nome e URL da loja. PlayStation permanece oculto ate haver ofertas confiaveis.
- DLCs sao separados de jogos base por `games.is_dlc` e heuristicas de titulo. A Steam ajuda a preencher esse campo; enquanto nulo, a heuristica permanece como fallback.
- A home deve manter uma secao de maiores descontos de DLC separada quando houver itens elegiveis.

## Schema Relevante

### Catalogo

```text
games
  id, itad_id, title, slug, cover_url, rank, is_dlc
  last_price_sync_at, last_steam_sync_at, created_at

offers
  id, game_id, source, store_name, price, regular_price
  currency, url, updated_at
  UNIQUE (game_id, source, store_name)

sync_locks
  name, locked_until
```

### Conta, monitoramento e notificacoes

```text
favorites
  user_id, game_id, created_at
  -- representa Jogos Monitorados, nunca favoritos pessoais

price_notifications
  user_id, game_id, previous_price, current_price, store_name
  read_at, created_at
```

### Perfis e Steam

```text
profiles
  user_id, handle, display_name, bio, avatar_url, is_public
  show_game_hours, show_achievements, show_library
  show_favorite_games, show_recent_activity
  avatar_zoom, avatar_position_x, avatar_position_y
  banner_url, banner_zoom, banner_position_x, banner_position_y

steam_connections
  user_id, steam_id, persona_name, avatar_url
  connected_at, last_library_sync_at, last_achievement_sync_at, last_error

steam_library_games
  user_id, app_id, title, playtime_minutes, icon_hash, last_synced_at

steam_game_achievements
  user_id, app_id, unlocked_count, total_count, last_synced_at

profile_favorites
  user_id, game_id, created_at, position

profile_steam_favorites
  user_id, app_id, created_at, position
  -- usado para jogos da biblioteca Steam ausentes do catalogo
  -- position: sequencia manual unica por usuario, combinando as duas tabelas

profile_activities
  user_id, type, game_id, detail, created_at

profile_blocks
  user_id, block_id, block_type, title, content, position, size
  visible, background_type, background_value, overlay_opacity, text_color
```

Apesar da coluna `profile_blocks.visible` existir por compatibilidade, nao ha privacidade por secao: a privacidade e sempre do perfil como um todo e dos controles gerais de dados.

## Migrations Supabase

Arquivos em `backend-java/sql/`:

1. `20260711_coleta_agendada.sql`
2. `20260711_conexoes_steam.sql`
3. `20260711_perfis_publicos.sql`
4. `20260712_atividades_perfil.sql`
5. `20260712_atividades_steam_detalhadas.sql`
6. `20260712_atualizacao_publica_perfil.sql`
7. `20260712_avatars_perfil.sql`
8. `20260712_favoritos_pessoais.sql`
9. `20260712_perfis_publicos_por_padrao.sql`
10. `20260712_visibilidade_atividade_perfil.sql`
11. `20260713_editor_de_perfil.sql`
12. `20260713_favoritos_steam_perfil.sql`
13. `20260713_notificacoes_preco.sql`
14. `20260713_cor_texto_blocos_perfil.sql`
15. `20260716_banner_perfil.sql`
16. `20260716_ordem_favoritos_perfil.sql`

Essas migrations ja foram aplicadas ao projeto Supabase de producao. Em outro ambiente, executa-las em ordem antes de publicar o backend. Em especial, a coluna `profile_activities.detail` e obrigatoria para atividade recente detalhada; se ela estiver ausente, a rota de perfil pode retornar HTTP 500. A migration do banner (15) precisa ser aplicada antes do deploy do backend que a usa: o backend seleciona `banner_url`/`banner_zoom`/`banner_position_x`/`banner_position_y` em toda consulta de perfil, entao sem essas colunas qualquer pagina de perfil (propria ou publica) quebra com erro 500. A migration 16 adiciona `position` aos favoritos e tambem precisa ser aplicada antes do deploy do backend que ordena por essa coluna.

O bucket publico `avatars` do Supabase Storage guarda avatar, imagens dos blocos e seus fundos. Cada usuario so pode gravar na propria pasta. As politicas RLS de `SELECT`, `INSERT`, `UPDATE` e `DELETE` foram aplicadas ao projeto novo em 15/07/2026; sem elas o Storage retorna HTTP 400 nos uploads. Limite de upload de imagem no frontend: 2 MB, JPG/PNG/WebP. Blocos de imagem e fundos tambem aceitam URL externa `http(s)`.

## API HTTP

### Catalogo

- `GET /api/games?page=&size=&sort=&type=&platform=&minPrice=&maxPrice=&minDiscount=&q=`
  - `sort`: `rank`, `discount`, `price_asc`, `price_desc`.
  - `type`: `all`, `game`, `dlc`.
  - Retorna a oferta minima, incluindo loja e URL quando disponiveis.
- `GET /api/games/search?q=nome`
- `GET /api/games/{slug}`
- `POST /api/games/{slug}/refresh`
- `GET /api/deals/top?size=&sort=discount|rank`
- `POST /api/sync?page=` (legado, exige `X-Sync-Key`)

### Jogos Monitorados e notificacoes

- `GET|POST /api/favorites`
- `DELETE /api/favorites/{slug}`
- `GET /api/notifications`
- `GET /api/notifications/unread-count`
- `PATCH /api/notifications/{id}/read`
- `PATCH /api/notifications/read-all`
- `DELETE /api/notifications/{id}`

### Perfil e favoritos pessoais

- `GET /api/perfis/me`
- `PUT /api/perfis/me`
- `PUT /api/perfis/me/avatar`
- `PUT /api/perfis/me/banner`
- `GET|PUT /api/perfis/me/blocos`
- `GET /api/perfis/{handle}`
- `POST /api/perfis/{handle}/atualizar`
  - qualquer visitante pode pedir atualizacao Steam publica; cada perfil aceita uma solicitacao a cada 10 minutos.
- `GET|POST /api/profile-favorites`
- `DELETE /api/profile-favorites/{slug}`
- `POST /api/profile-favorites/steam`
- `DELETE /api/profile-favorites/steam/{appId}`
- `PUT /api/profile-favorites/ordem`
  - recebe `{ itens: [{ slug?, steamAppId? }] }` na ordem desejada e grava `position` 0,1,2... Novos favoritos entram no fim da fila.

### Steam e administracao

- `POST /api/conexoes/steam/iniciar`
- `GET /api/conexoes/steam/retorno`
- `GET|DELETE /api/conexoes/steam`
- `GET /api/conexoes/steam/biblioteca`
- `POST /api/conexoes/steam/sincronizar`
- `GET /api/admin/coleta`
- `POST /api/admin/coleta/precos`
- `POST /api/admin/coleta/steam`
- `GET /actuator/health`

Os endpoints autenticados recebem token Bearer do Supabase. A administracao exige o UID autorizado no backend: `0a6eb06b-756e-4434-899b-33420bed8609`.

## Frontend e UX

### Rotas e navegacao

- `/` home.
- `/catalogo` usa scroll infinito e filtros.
- `/jogo/:slug` mostra ofertas e atualizacao individual.
- `/monitorados` mostra a lista de precos acompanhados. `/favoritos` redireciona por compatibilidade.
- `/perfil` e uma ponte autenticada: resolve/cria o handle e redireciona para a URL canonica.
- `/:handle` e a pagina publica do perfil. As rotas de produto sao reservadas e nao podem ser handles.
- Toda mudanca de rota deve iniciar no topo. A pagina publica observa mudancas de `handle` e descarta respostas de requisicoes antigas para nao manter o perfil anterior na tela.

### Mapa de paginas

| Pagina/rota | Finalidade | Comportamentos e dados importantes |
|---|---|---|
| **Inicio** (`/`) | Apresentar ofertas de interesse imediato. | Banner de melhor oferta, carrosseis de Jogos Monitorados, plataforma favorita quando definida, descontos em jogos, descontos em DLCs, mais vendidos e gratuitos. O conteudo geral nunca deve desaparecer ao selecionar uma plataforma favorita. |
| **Catalogo** (`/catalogo`) | Explorar todo o catalogo. | Scroll infinito; filtros de tipo, plataforma, desconto minimo e faixa de preco; ordenacao por dropdown customizado. As preferencias preenchem os filtros iniciais, mas o usuario pode mudar tudo manualmente. |
| **Detalhe do jogo** (`/jogo/:slug`) | Comparar todas as lojas de um jogo. | Hero com capa, melhor preco, botao `Atualizar precos`, acao de Jogos Monitorados e tabela de ofertas. A tabela mostra loja, plataforma, desconto, preco regular, preco atual e link externo. |
| **Busca** | Encontrar jogos, lojas e categorias pela topbar. | Sugestoes devem navegar diretamente para o jogo escolhido; a mudanca de URL precisa recarregar o detalhe mesmo quando o usuario ja esta em outro detalhe. |
| **Jogos Monitorados** (`/monitorados`) | Listar jogos acompanhados por preco. | Usa a tabela `favorites` e os endpoints `/api/favorites`. E diferente de favoritos pessoais do perfil. A rota antiga `/favoritos` somente redireciona para aqui. |
| **Mais vendidos** | Mostrar jogos relevantes/populares. | Usa rank ITAD e deve respeitar as mesmas regras de filtro de conteudo nao-jogo, DLC e lojas bloqueadas. |
| **Gratuitos** | Mostrar ofertas com preco zero. | Itens com link invalido ou filtrados por `JogosBloqueados` nao devem aparecer. |
| **Login** | Autenticar por Supabase Auth. | Nao usa sidebar nem topbar. Depois do login, a navegacao volta ao fluxo normal do aplicativo. |
| **Configuracoes** (`/configuracoes`) | Centralizar opcoes da conta. | Abas separadas: Conta, Conexoes, Preferencias e Privacidade. Nao misturar assuntos entre abas. Preferencias afetam home/catalogo; Privacidade afeta o perfil publico; Conexoes concentra Steam e futura Xbox. |
| **Perfil proprio** (`/perfil` -> `/:handle`) | Personalizar e visualizar o perfil do usuario. | `/perfil` redireciona para o handle canonico. O dono pode editar bio, foto, layout, blocos e pedir atualizacao Steam. A pagina canonica e a mesma que visitantes veem, com controles extras apenas para o dono. |
| **Perfil publico** (`/:handle`) | Compartilhar biblioteca e perfil gamer. | Respeita privacidade geral e dos dados escolhidos. Mostra uma faixa fixa com biblioteca, horas, conquistas desbloqueadas e icones das plataformas conectadas; abaixo, mostra Resumo, Jogos favoritos e Biblioteca quando liberados. Nunca mostra e-mail, UUID, Jogos Monitorados ou controles de edicao a visitantes. |
| **Administracao de coleta** (`/admin/coleta`) | Acompanhar e disparar jobs internos. | Exclusiva do UID administrador. Exibe status das filas de preco/Steam e permite disparar coleta manual em segundo plano; nao substitui o scheduler. |

### Componentes globais

- **Sidebar:** navegacao principal. O item de monitoramento deve se chamar **Jogos Monitorados** e usar o icone correspondente, nunca o de favoritos pessoais.
- **Topbar:** busca global, alternancia de tema, notificacoes e menu da conta. Ao clicar em Perfil, deve resolver o handle do usuario e navegar diretamente para `/:handle`, nunca permanecer em `/perfil`.
- **Notificacoes:** sino da topbar; lista quedas de preco de Jogos Monitorados, permite marcar como lida ou remover e mostra badge de nao lidas.
- **Card de jogo:** exibe capa, desconto, preco, loja e plataforma quando conhecidos. O icone de acao nele monitora preco, nao adiciona aos favoritos pessoais.

### Home, catalogo e monitoramento

- A home mantem conteudo geral misturado. Se houver plataforma preferida, cria uma secao adicional **Jogos da sua plataforma favorita** abaixo de Jogos Monitorados, sem esconder o restante.
- Preferencias de plataforma, ocultar DLC, desconto minimo e preco maximo preenchem inicialmente os filtros do catalogo; o usuario ainda pode altera-los.
- Cards e detalhe usam o icone `jogos-monitorados.png` para monitoramento de preco. Nunca usar o icone de favorito pessoal nesse fluxo.
- Favoritos pessoais sao outra funcionalidade, mostrada no perfil e biblioteca Steam. Na aba **Jogos favoritos**, o dono entra no modo **Organizar** (botao no cabecalho da aba) para reordenar por arrastar e soltar e remover itens; fora desse modo os controles ficam escondidos e os cards navegam normalmente. A ordem e salva automaticamente via `PUT /api/profile-favorites/ordem`; visitantes so visualizam. A ordem manual e unica por usuario e vale para favoritos de catalogo e Steam juntos.

### Perfil publico e privado

- Perfis novos sao publicos por padrao. O dono pode tornar o perfil privado em Configuracoes > Privacidade.
- E-mail, UUID, Jogos Monitorados e dados de autenticacao nunca sao publicos.
- O dono escolhe a exposicao de horas, conquistas, biblioteca, favoritos pessoais e atividade recente. O backend filtra a resposta publica.
- O dono na propria URL canonica ve controles de avatar, banner, bio, atualizacao e modo de edicao; visitantes nao veem esses comandos.
- O avatar e o banner do topo do perfil sao salvos no Storage com zoom e posicao persistidos para todos verem o mesmo enquadramento. O ajuste usa o mesmo editor em modal dos blocos de imagem: arrastar com mouse/touch para posicionar e scroll/pinca para zoom, com folga minima de 115% para sempre permitir arrastar em qualquer direcao, calculado em pixels reais (imagem x quadro) tanto no modal quanto na exibicao final. Os botoes "Trocar foto" e "Trocar banner" só aparecem no modo de edicao do perfil.
- A primeira sincronizacao Steam gera somente os resumos. Nas posteriores, novos jogos e conquistas viram atividades individuais.
- A atividade recente e publica por padrao, salvo escolha do dono na privacidade.

### Editor de perfil

O modo de edicao permite reorganizar blocos por arrastar e soltar, mudar tamanho, remover e adicionar blocos. Ele existe somente na aba **Resumo**: ao abrir, as abas Jogos favoritos e Biblioteca ficam indisponiveis e os comandos internos Gerenciar/Ver biblioteca somem para evitar navegacao acidental. A faixa de estatisticas logo abaixo do cabecalho e fixa, portanto nao entra no editor: mostra jogos na biblioteca, horas jogadas, somente conquistas desbloqueadas e icones das plataformas conectadas. A barra "Modo de edicao" fica fixa na parte inferior da tela (nao rola com a pagina), com os botoes Cancelar/Salvar visualmente destacados a direita, separados dos botoes de adicionar bloco. Tipos suportados:

- paineis de favoritos pessoais, biblioteca e atividade;
- blocos personalizados de texto, imagem e links.

Cada bloco pode usar fundo padrao, cor solida, gradiente ou imagem, alem de cor de texto hexadecimal livre. Cor solida e texto aceitam seletor visual e digitacao direta de `#RRGGBB`; o gradiente e montado visualmente por duas cores, sem exigir CSS. A opcao de texto fica dentro do menu de fundo e altera somente o conteudo do card, nunca os controles do editor. A imagem de fundo e escolhida por um comando explicito e enviada ao bucket `avatars`; nao existe privacidade por bloco. Blocos personalizados de imagem abrem um editor de enquadramento antes de salvar, exibido como modal grande sobre um fundo escurecido (nao mais embutido na lista de blocos), com pre-visualizacao ampla; o ajuste e feito arrastando a imagem com o mouse/touch para posicionar e girando o scroll (ou pinca no mobile) para dar zoom, sem sliders. O zoom minimo aplica uma pequena folga (115%) sobre o enquadramento padrao para garantir espaco de arraste em qualquer direcao, independente da proporcao da imagem enviada. O enquadramento (zoom e posicao) e calculado com base no tamanho real da imagem e do quadro (nao mais via `object-position` + `transform: scale`, que ficava preso na mesma janela de corte e podia travar um dos eixos do arraste); o mesmo calculo e usado tanto no editor quanto na exibicao final do bloco no perfil, garantindo que o resultado salvo seja igual ao que o dono ajustou. Esses dados sao persistidos junto da URL em formato compativel com blocos antigos que guardavam somente a URL. O campo "Usar URL" sempre abre vazio, mesmo quando o bloco ja tem uma imagem: ele nunca preenche com a URL interna do Supabase Storage, que nao deve ser exposta ao usuario. O editor de links separa titulo e lista de URLs no mesmo padrao visual dos demais campos.

Blocos de texto e links respeitam limites conforme o tamanho escolhido: pequeno (`42` caracteres de titulo e `180` de conteudo), medio (`64` e `420`), largo (`88` e `800`) e completo (`120` e `1400`). O conteudo aplica quebra de palavras longas para nunca vazar horizontalmente do card.

Os tamanhos sao composicoes diferentes, e nao apenas escala. Nos blocos de jogos, o pequeno mostra 1 card por linha, o medio 2, o largo 3 e o completo 4; os cards crescem verticalmente quando houver mais itens. A quantidade de itens exibida no bloco vem sempre do tamanho escolhido (pequeno 1, medio 4, largo 6, completo 8) e nao e configuravel separadamente. Favoritos pessoais e biblioteca usam cards visuais com capa, titulo e dados relevantes. Favoritos Steam usam a capa horizontal oficial `header.jpg`, igual aos cards da biblioteca, e exibem plataforma, horas jogadas e percentual de conquistas; favoritos do catalogo exibem a capa e o menor preco conhecido. O bloco de Biblioteca tem o comando **Ver biblioteca** no canto superior direito e abre a aba completa, que permite busca, ordenacao e favoritar jogos Steam mesmo quando eles nao existem no catalogo.

Os dropdowns de tamanho e fundo devem seguir o mesmo padrao visual do filtro de ordenacao do catalogo, nao usar `select` nativo. Links personalizados usam uma linha por item no formato `Nome - endereco.com` ou `Nome - https://url`; enderecos sem protocolo recebem `https://` automaticamente e devem abrir como links reais. No perfil publico, cada link renderiza como um card empilhado (nao mais uma pill inline), com o nome a esquerda e um icone de link externo a direita.

### Steam e Xbox

- Steam esta funcional por OpenID: nao solicitar Steam ID ou URL manualmente.
- Sincroniza biblioteca, horas totais/por jogo e conquistas gradualmente via Steam Web API.
- A aba Biblioteca busca, ordena por tempo/nome/conquistas e permite favoritar itens Steam ausentes do catalogo.
- Wishlist Steam nao e coletada.
- Xbox permanece planejado. Nao usar scraping nem API nao oficial: nao ha um fluxo publico equivalente ao Steam OpenID + Web API para importar a biblioteca de qualquer conta.

## Estrutura de Codigo

```text
backend-java/
  Dockerfile
  pom.xml
  sql/
  src/main/java/com/ofertagames/backend/
    administracao/       status e disparo manual de coleta
    autenticacao/        Bearer token Supabase
    configuracao/        CORS e banco
    conexoes/            Steam OpenID e sincronizacao
    descontos/           home e melhores descontos
    favoritos/           Jogos Monitorados
    favoritosperfil/     favoritos pessoais
    jogos/               catalogo, busca, detalhe e refresh
    notificacoes/        alertas de preco
    perfis/              perfil publico, avatar e blocos
    sincronizacao/       scheduler, ITAD e locks
    steam/               metadados Steam de catalogo

frontend/src/app/
  components/            sidebar, topbar, cards e carrosseis
  guards/                auth.guard
  pages/                 home, catalog, game-detail, profile, settings,
                         favorites/monitorados, login, search e outras
  services/              API, Auth, Supabase, tema, preferencias,
                         favoritos, favoritos pessoais e perfis
```

Convencao obrigatoria no backend: classes, pacotes, metodos e variaveis em portugues. Marcas e contratos JSON podem manter termos externos, por exemplo ITAD, Steam, Bearer, `coverUrl` e `minPrice`.

## Assets e Tema

- Cor principal: `#29A8E0`.
- PNGs de perfil em `frontend/public/`: `horas-jogadas.png`, `conquistas.png`, `biblioteca.png`, `jogos-favoritos.png` e `jogos-monitorados.png`, com variantes por tema quando existentes.
- Logos de lojas/plataformas em `frontend/public/store-logos/` e `frontend/public/platform-logos/`.
- `store-brand.ts` resolve icones de loja e plataforma no frontend.

## Estado e Proximos Passos

### Implementado

- Catalogo, busca, detalhe, refresh individual, descontos, DLCs e filtros.
- Scheduler interno de precos ITAD e metadados Steam com fila e lock no PostgreSQL.
- Jogos Monitorados, notificacoes de queda de preco e sino na topbar.
- Login Supabase, perfis compartilhaveis, avatar persistente, bio e privacidade geral.
- Steam OpenID, biblioteca, horas, conquistas, favoritos pessoais e atividade recente.
- Pagina admin de coleta protegida por UID.
- Editor de perfil persistido com blocos (favoritos, biblioteca, atividade, texto, links, imagem), upload de imagens JPG/PNG/WebP de ate 2 MB e imagens externas por URL `http(s)`, validado em desktop. O enquadramento de imagens de bloco aceita zoom por scroll/pinca e reposicionamento por arrasto direto na previa, em mouse ou toque.
- Faixa fixa de estatisticas no perfil e galerias responsivas para favoritos pessoais e biblioteca Steam. Cards da Steam tentam a capa horizontal e depois uma capsula alternativa; se nenhuma existir, usam um fallback visual sem imagem quebrada.
- Favoritos pessoais com ordenacao manual (arrastar e soltar, persistida) dentro do modo Organizar da aba. Colecoes de favoritos ainda nao implementadas.
- PostgreSQL, Auth/OAuth e Storage foram migrados para o Supabase em Sao Paulo. A Oracle usa o novo banco, executa o scheduler e expoe a API por Caddy/HTTPS. O frontend publicado na Vercel usa o mesmo projeto Supabase. O Render esta desligado; o Supabase antigo permanece somente como rollback temporario.
- Validacao pos-migracao concluida: login Google/Discord, catalogo, perfil, avatares, blocos e scheduler confirmados funcionando na Oracle com o Supabase novo.

### Em validacao

- O layout mobile do editor de perfil ainda nao e responsivo e nao esta sendo trabalhado por enquanto (prioridade e desktop).

### Planejado

1. Evoluir favoritos pessoais com colecoes/grupos e filtro por grupo (ordenacao manual ja implementada). Filtro por ano de lancamento depende de uma coluna nova em `games` com backfill via ITAD/Steam; hoje o catalogo nao guarda data de lancamento nem genero.
2. Melhorar a pagina de administracao/observabilidade de coletas e erros ITAD/Steam.
3. Implementar Xbox somente com um caminho oficial suportado (pausado ate acesso ao Azure).
4. Integrar Eneba depois de aprovar afiliacao; Instant Gaming aguarda aprovacao.
5. Melhorar observabilidade operacional da Oracle: uso de memoria, erros do scheduler e status da API.
6. Depois de alguns dias de estabilidade, exportar um ultimo backup e excluir o projeto Supabase antigo.

## Checklist antes de Publicar

1. Conferir `git status --short` e nunca reverter mudancas do usuario.
2. Para frontend: `cd frontend; npm.cmd run build`.
3. Para backend: `cd backend-java; mvn -q -DskipTests package` quando Maven estiver disponivel.
4. Conferir se uma migration nova precisa ser aplicada no Supabase antes do deploy.
5. Conferir CORS quando uma rota `PUT`, `PATCH` ou `POST` nova for adicionada.
6. Validar em producao: catalogo, detalhes, monitorados, perfil proprio, perfil anonimo e conexao Steam quando afetados.
7. O deploy manual `scripts/deploy-oracle.ps1` valida a saude pela URL HTTPS publica do Caddy; a porta `8080` nao e exposta diretamente na VM.

## Regras de Seguranca e Produto

- Sem scraping de lojas ou plataformas.
- Sem multi-moeda funcional por enquanto.
- Sem exibir dados privados de perfil na rota publica.
- Nao reintroduzir sincronizacao recorrente pelo GitHub Actions.
- Nao colocar chaves do backend no frontend.
- Nao transformar favoritos pessoais em Jogos Monitorados nem o contrario.
