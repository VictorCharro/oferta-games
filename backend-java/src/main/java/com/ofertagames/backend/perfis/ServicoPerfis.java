package com.ofertagames.backend.perfis;

import com.ofertagames.backend.conexoes.ServicoConexoesSteam;
import com.ofertagames.backend.conexoes.ServicoConexoesXbox;
import com.ofertagames.backend.atividadesperfil.AtividadePerfil;
import com.ofertagames.backend.atividadesperfil.RepositorioAtividadesPerfil;
import com.ofertagames.backend.colecoesperfil.ColecaoPerfil;
import com.ofertagames.backend.colecoesperfil.RepositorioColecoesPerfil;
import com.ofertagames.backend.favoritosperfil.FavoritoPerfilJogo;
import com.ofertagames.backend.favoritosperfil.RepositorioFavoritosPerfil;
import com.ofertagames.backend.jogos.RepositorioJogos;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class ServicoPerfis {
  private static final Set<String> IDENTIFICADORES_RESERVADOS = Set.of(
      "admin", "api", "busca", "catalogo", "configuracoes", "favoritos", "gratuitos",
      "jogo", "login", "mais-vendidos", "monitorados", "perfil", "promocoes", "u");
  private final RepositorioPerfis perfis;
  private final ServicoConexoesSteam steam;
  private final ServicoConexoesXbox xbox;
  private final RepositorioFavoritosPerfil favoritosPerfil;
  private final RepositorioColecoesPerfil colecoesPerfil;
  private final RepositorioAtividadesPerfil atividades;
  private final RepositorioBlocosPerfil blocos;
  private final RepositorioJogos jogos;

  ServicoPerfis(RepositorioPerfis perfis, ServicoConexoesSteam steam, ServicoConexoesXbox xbox, RepositorioFavoritosPerfil favoritosPerfil, RepositorioColecoesPerfil colecoesPerfil, RepositorioAtividadesPerfil atividades, RepositorioBlocosPerfil blocos, RepositorioJogos jogos) { this.perfis = perfis; this.steam = steam; this.xbox = xbox; this.favoritosPerfil = favoritosPerfil; this.colecoesPerfil = colecoesPerfil; this.atividades = atividades; this.blocos = blocos; this.jogos = jogos; }

  RepositorioPerfis.Perfil proprio(String usuarioId) { return perfis.buscarPorUsuario(usuarioId).orElse(null); }

  void salvar(String usuarioId, EntradaPerfil entrada) {
    String handle = normalizarHandle(entrada.handle());
    if (entrada.publico() && handle == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Defina a URL do perfil antes de torna-lo publico");
    String avatarUrl = perfis.buscarPorUsuario(usuarioId).map(RepositorioPerfis.Perfil::avatarUrl).orElse(null);
    try { perfis.salvar(usuarioId, new RepositorioPerfis.DadosPerfil(handle, limitar(entrada.nomeExibicao(), 60), limitar(entrada.bio(), 180), avatarUrl, entrada.publico(), entrada.mostrarHoras(), entrada.mostrarConquistas(), entrada.mostrarBiblioteca(), entrada.mostrarFavoritos(), entrada.mostrarAtividades(), entrada.mostrarColecoes())); }
    catch (RuntimeException erro) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta URL de perfil ja esta em uso"); }
  }

  void atualizarAvatar(String usuarioId, EntradaAvatar entrada) {
    String avatarUrl = validarUrlPropriaPasta(usuarioId, entrada.avatarUrl(), "URL de avatar invalida");
    double zoom = Math.max(1, Math.min(3, entrada.zoom()));
    int posicaoX = Math.max(0, Math.min(100, entrada.posicaoX()));
    int posicaoY = Math.max(0, Math.min(100, entrada.posicaoY()));
    perfis.atualizarAvatar(usuarioId, avatarUrl, zoom, posicaoX, posicaoY);
  }

  void atualizarBanner(String usuarioId, EntradaBanner entrada) {
    String bannerUrl = validarUrlPropriaPasta(usuarioId, entrada.bannerUrl(), "URL de banner invalida");
    double zoom = Math.max(1, Math.min(3, entrada.zoom()));
    int posicaoX = Math.max(0, Math.min(100, entrada.posicaoX()));
    int posicaoY = Math.max(0, Math.min(100, entrada.posicaoY()));
    perfis.atualizarBanner(usuarioId, bannerUrl, zoom, posicaoX, posicaoY);
  }

  private static String validarUrlPropriaPasta(String usuarioId, String url, String mensagemErro) {
    String valor = url == null ? "" : url.trim();
    String padrao = "^https://[a-z0-9-]+\\.supabase\\.co/storage/v1/object/public/avatars/" + java.util.regex.Pattern.quote(usuarioId) + "/[^\\s]{1,900}$";
    if (!valor.matches(padrao)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, mensagemErro);
    return valor;
  }

  List<RepositorioBlocosPerfil.BlocoPerfil> blocos(String usuarioId) { return blocos.listar(usuarioId); }

  void atualizarMostrarWishlistSteam(String usuarioId, boolean mostrar) {
    perfis.atualizarMostrarWishlistSteam(usuarioId, mostrar);
  }

  void salvarBlocos(String usuarioId, List<RepositorioBlocosPerfil.BlocoPerfil> entrada) {
    if (entrada == null || entrada.size() > 20) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quantidade de blocos invalida");
    for (RepositorioBlocosPerfil.BlocoPerfil bloco : entrada) {
      if (bloco == null || bloco.id() == null || bloco.tipo() == null || bloco.tamanho() == null || bloco.tipoFundo() == null || !bloco.id().matches("^[a-z0-9-]{3,60}$") || !Set.of("favoritos", "biblioteca", "atividade", "platinados", "texto", "imagem", "links").contains(bloco.tipo()) || !Set.of("pequeno", "medio", "largo", "completo").contains(bloco.tamanho()) || !Set.of("padrao", "cor", "imagem", "gradiente").contains(bloco.tipoFundo()) || bloco.opacidade() < 0 || bloco.opacidade() > 85 || (bloco.tipoFundo().equals("cor") && (bloco.valorFundo() == null || !bloco.valorFundo().matches("^#[0-9a-fA-F]{6}$"))) || (bloco.corTexto() != null && !bloco.corTexto().matches("^#[0-9a-fA-F]{6}$"))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bloco de perfil invalido");
    }
    blocos.substituir(usuarioId, entrada);
  }

  PerfilPublico publico(String handle, String visitanteId) {
    RepositorioPerfis.Perfil perfil = perfis.buscarPorHandle(normalizarHandleObrigatorio(handle)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (!perfil.publico() && !perfil.usuarioId().equals(visitanteId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    ServicoConexoesSteam.StatusConexaoSteam status = steam.status(perfil.usuarioId());
    ServicoConexoesXbox.StatusConexaoXbox statusXbox = xbox.status(perfil.usuarioId());
    boolean dono = perfil.usuarioId().equals(visitanteId);
    boolean mostrarBiblioteca = dono || perfil.mostrarBiblioteca();
    List<ServicoConexoesSteam.JogoBibliotecaSteam> jogosSteam = mostrarBiblioteca ? enriquecerComCatalogSlug(steam.biblioteca(perfil.usuarioId())) : List.of();
    List<ServicoConexoesSteam.JogoBibliotecaSteam> jogosXbox = mostrarBiblioteca ? xbox.biblioteca(perfil.usuarioId()) : List.of();
    List<ServicoConexoesSteam.JogoBibliotecaSteam> jogos = java.util.stream.Stream.concat(jogosSteam.stream(), jogosXbox.stream()).toList();
    List<FavoritoPerfilJogo> favoritos = dono || perfil.mostrarFavoritos() ? favoritosPerfil.listarPorUsuario(perfil.usuarioId()) : List.of();
    List<ColecaoPerfil> colecoesBrutas = dono || perfil.mostrarColecoes() ? colecoesPerfil.listarPorUsuario(perfil.usuarioId()) : List.of();
    // A wishlist da Steam tem toggle proprio (dentro do Organizar da aba Colecoes, nao em
    // Privacidade). Desmarcado, some pra todo mundo, inclusive o dono - ele ve exatamente o que
    // um visitante veria. O checkbox continua visivel de qualquer forma (fica no cabecalho do
    // painel, nao dentro do card da colecao) usando temColecaoWishlistSteam, calculado ANTES
    // desse filtro, pra saber se existe uma wishlist mesmo com ela escondida.
    boolean temColecaoWishlistSteam = colecoesBrutas.stream().anyMatch(ColecaoPerfil::origemSistema);
    List<ColecaoPerfil> colecoes = colecoesBrutas.stream()
        .filter(c -> !c.origemSistema() || perfil.mostrarWishlistSteam())
        .toList();
    boolean mostrarAtividades = dono || perfil.mostrarAtividades();
    List<AtividadePerfil> atividadeRecente = mostrarAtividades ? carregarAtividades(perfil.usuarioId()) : List.of();
    boolean mostrarPlataforma = dono || perfil.mostrarHoras() || perfil.mostrarConquistas() || perfil.mostrarBiblioteca();
    List<String> plataformasConectadas = new java.util.ArrayList<>();
    if (status.conectada() && mostrarPlataforma) plataformasConectadas.add("steam");
    if (statusXbox.conectada() && mostrarPlataforma) plataformasConectadas.add("xbox");
    return new PerfilPublico(perfil.handle(), perfil.nomeExibicao(), perfil.bio(), perfil.avatarUrl(), perfil.avatarZoom(), perfil.avatarPosicaoX(), perfil.avatarPosicaoY(),
        perfil.bannerUrl(), perfil.bannerZoom(), perfil.bannerPosicaoX(), perfil.bannerPosicaoY(),
        dono || perfil.mostrarHoras() ? status.totalMinutos() : null,
        dono || perfil.mostrarConquistas() ? status.conquistasDesbloqueadas() : null,
        dono || perfil.mostrarConquistas() ? status.conquistasTotal() : null,
        dono || perfil.mostrarConquistas() ? status.jogosPlatinados() : null,
        mostrarBiblioteca ? status.totalJogos() + jogosXbox.size() : null,
        plataformasConectadas,
        jogos,
        favoritos,
        colecoes,
        atividadeRecente,
        perfil.mostrarAtividades(), blocosPublicos(perfil, dono), perfil.mostrarWishlistSteam(), temColecaoWishlistSteam);
  }

  ResultadoAtualizacao solicitarAtualizacao(String handle, String visitanteId) {
    RepositorioPerfis.Perfil perfil = perfis.buscarPorHandle(normalizarHandleObrigatorio(handle))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    boolean dono = perfil.usuarioId().equals(visitanteId);
    if (!perfil.publico() && !dono) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    if (!steam.status(perfil.usuarioId()).conectada()) return new ResultadoAtualizacao("sem_conexao", null);
    if (!steam.reservarAtualizacaoPublica(perfil.usuarioId())) return new ResultadoAtualizacao("aguarde", null);
    return new ResultadoAtualizacao("agendada", perfil.usuarioId());
  }

  private static String normalizarHandle(String valor) { if (valor == null || valor.isBlank()) return null; return normalizarHandleObrigatorio(valor); }
  private static String normalizarHandleObrigatorio(String valor) { String handle = valor.trim().toLowerCase(Locale.ROOT); if (!handle.matches("^[a-z0-9][a-z0-9-]{2,29}$")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use de 3 a 30 caracteres: letras minusculas, numeros e hifen"); if (IDENTIFICADORES_RESERVADOS.contains(handle)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Esta URL e reservada pelo sistema"); return handle; }
  private static String limitar(String valor, int maximo) { String resultado = valor == null ? "" : valor.trim(); return resultado.length() > maximo ? resultado.substring(0, maximo) : resultado; }

  // Cruza os jogos da biblioteca Steam com o catalogo (por steam_app_id) pra oferecer
  // "Ver no catalogo" nos cards do perfil, quando existir uma entrada correspondente.
  private List<ServicoConexoesSteam.JogoBibliotecaSteam> enriquecerComCatalogSlug(List<ServicoConexoesSteam.JogoBibliotecaSteam> lista) {
    if (lista.isEmpty()) return lista;
    Map<Integer, String> slugs = jogos.buscarSlugsPorSteamAppIds(lista.stream().map(ServicoConexoesSteam.JogoBibliotecaSteam::appId).toList());
    if (slugs.isEmpty()) return lista;
    return lista.stream().map(jogo -> jogo.comCatalogSlug(slugs.get(jogo.appId()))).toList();
  }

  // A atividade e complementar: uma migration pendente nao pode tornar o perfil indisponivel.
  private List<AtividadePerfil> carregarAtividades(String usuarioId) {
    try {
      return atividades.listarPorUsuario(usuarioId, 20);
    } catch (DataAccessException erro) {
      return List.of();
    }
  }

  private List<RepositorioBlocosPerfil.BlocoPerfil> blocosPublicos(RepositorioPerfis.Perfil perfil, boolean dono) {
    return blocos.listar(perfil.usuarioId()).stream()
        .filter(bloco -> !Set.of("resumo_favoritos", "horas", "conquistas").contains(bloco.tipo()))
        .filter(bloco -> dono || !"favoritos".equals(bloco.tipo()) || perfil.mostrarFavoritos())
        .filter(bloco -> dono || !"biblioteca".equals(bloco.tipo()) || perfil.mostrarBiblioteca())
        .filter(bloco -> dono || !"atividade".equals(bloco.tipo()) || perfil.mostrarAtividades())
        // Platinados e derivado da biblioteca (conquistasDesbloqueadas/Total de cada jogo Steam),
        // entao depende dos mesmos dados dela, nao so do toggle de conquistas.
        .filter(bloco -> dono || !"platinados".equals(bloco.tipo()) || perfil.mostrarBiblioteca())
        .toList();
  }

  record EntradaPerfil(String handle, String nomeExibicao, String bio, boolean publico, boolean mostrarHoras, boolean mostrarConquistas, boolean mostrarBiblioteca, boolean mostrarFavoritos, boolean mostrarAtividades, boolean mostrarColecoes) {}
  record EntradaAvatar(String avatarUrl, double zoom, int posicaoX, int posicaoY) {}
  record EntradaBanner(String bannerUrl, double zoom, int posicaoX, int posicaoY) {}
  record ResultadoAtualizacao(String status, String usuarioId) {}
  record PerfilPublico(String handle, String nomeExibicao, String bio, String avatarUrl, double avatarZoom, int avatarPosicaoX, int avatarPosicaoY,
      String bannerUrl, double bannerZoom, int bannerPosicaoX, int bannerPosicaoY,
      Long totalMinutos, Long conquistasDesbloqueadas, Long conquistasTotal, Long jogosPlatinados, Long totalJogosBiblioteca, List<String> plataformasConectadas, List<ServicoConexoesSteam.JogoBibliotecaSteam> biblioteca, List<FavoritoPerfilJogo> favoritos, List<ColecaoPerfil> colecoes, List<AtividadePerfil> atividades, boolean mostrarAtividades, List<RepositorioBlocosPerfil.BlocoPerfil> blocos,
      // mostrarWishlistSteam: esconde a colecao "Lista de Desejos (Steam)" de TODO MUNDO quando
      // false, inclusive o dono (ele ve exatamente o que um visitante veria). O dono le esse
      // valor pra saber o estado atual do proprio checkbox no Organizar.
      // temColecaoWishlistSteam: existe uma colecao de wishlist pra esse usuario, independente
      // do toggle acima - o frontend usa isso pra decidir se mostra o checkbox (que precisa
      // continuar visivel mesmo com a colecao escondida, senao ninguem reativaria).
      boolean mostrarWishlistSteam, boolean temColecaoWishlistSteam) {}
}
