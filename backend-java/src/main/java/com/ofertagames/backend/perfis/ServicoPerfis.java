package com.ofertagames.backend.perfis;

import com.ofertagames.backend.conexoes.ServicoConexoesSteam;
import com.ofertagames.backend.atividadesperfil.AtividadePerfil;
import com.ofertagames.backend.atividadesperfil.RepositorioAtividadesPerfil;
import com.ofertagames.backend.favoritosperfil.FavoritoPerfilJogo;
import com.ofertagames.backend.favoritosperfil.RepositorioFavoritosPerfil;
import java.util.List;
import java.util.Locale;
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
  private final RepositorioFavoritosPerfil favoritosPerfil;
  private final RepositorioAtividadesPerfil atividades;
  private final RepositorioBlocosPerfil blocos;

  ServicoPerfis(RepositorioPerfis perfis, ServicoConexoesSteam steam, RepositorioFavoritosPerfil favoritosPerfil, RepositorioAtividadesPerfil atividades, RepositorioBlocosPerfil blocos) { this.perfis = perfis; this.steam = steam; this.favoritosPerfil = favoritosPerfil; this.atividades = atividades; this.blocos = blocos; }

  RepositorioPerfis.Perfil proprio(String usuarioId) { return perfis.buscarPorUsuario(usuarioId).orElse(null); }

  void salvar(String usuarioId, EntradaPerfil entrada) {
    String handle = normalizarHandle(entrada.handle());
    if (entrada.publico() && handle == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Defina a URL do perfil antes de torna-lo publico");
    String avatarUrl = perfis.buscarPorUsuario(usuarioId).map(RepositorioPerfis.Perfil::avatarUrl).orElse(null);
    try { perfis.salvar(usuarioId, new RepositorioPerfis.DadosPerfil(handle, limitar(entrada.nomeExibicao(), 60), limitar(entrada.bio(), 180), avatarUrl, entrada.publico(), entrada.mostrarHoras(), entrada.mostrarConquistas(), entrada.mostrarBiblioteca(), entrada.mostrarFavoritos(), entrada.mostrarAtividades())); }
    catch (RuntimeException erro) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta URL de perfil ja esta em uso"); }
  }

  void atualizarAvatar(String usuarioId, EntradaAvatar entrada) {
    String avatarUrl = entrada.avatarUrl() == null ? "" : entrada.avatarUrl().trim();
    String padrao = "^https://[a-z0-9-]+\\.supabase\\.co/storage/v1/object/public/avatars/" + java.util.regex.Pattern.quote(usuarioId) + "/[^\\s]{1,900}$";
    if (!avatarUrl.matches(padrao)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL de avatar invalida");
    double zoom = Math.max(1, Math.min(3, entrada.zoom()));
    int posicaoX = Math.max(0, Math.min(100, entrada.posicaoX()));
    int posicaoY = Math.max(0, Math.min(100, entrada.posicaoY()));
    perfis.atualizarAvatar(usuarioId, avatarUrl, zoom, posicaoX, posicaoY);
  }

  List<RepositorioBlocosPerfil.BlocoPerfil> blocos(String usuarioId) { return blocos.listar(usuarioId); }

  void salvarBlocos(String usuarioId, List<RepositorioBlocosPerfil.BlocoPerfil> entrada) {
    if (entrada == null || entrada.size() > 20) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quantidade de blocos invalida");
    for (RepositorioBlocosPerfil.BlocoPerfil bloco : entrada) {
      if (bloco == null || bloco.id() == null || bloco.tipo() == null || bloco.tamanho() == null || bloco.tipoFundo() == null || !bloco.id().matches("^[a-z0-9-]{3,60}$") || !Set.of("resumo_favoritos", "horas", "conquistas", "favoritos", "biblioteca", "atividade", "texto", "imagem", "links").contains(bloco.tipo()) || !Set.of("pequeno", "medio", "largo", "completo").contains(bloco.tamanho()) || !Set.of("padrao", "cor", "imagem", "gradiente").contains(bloco.tipoFundo()) || bloco.opacidade() < 0 || bloco.opacidade() > 85 || (bloco.tipoFundo().equals("cor") && (bloco.valorFundo() == null || !bloco.valorFundo().matches("^#[0-9a-fA-F]{6}$"))) || (bloco.corTexto() != null && !bloco.corTexto().matches("^#[0-9a-fA-F]{6}$"))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bloco de perfil invalido");
    }
    blocos.substituir(usuarioId, entrada);
  }

  PerfilPublico publico(String handle, String visitanteId) {
    RepositorioPerfis.Perfil perfil = perfis.buscarPorHandle(normalizarHandleObrigatorio(handle)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (!perfil.publico() && !perfil.usuarioId().equals(visitanteId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    ServicoConexoesSteam.StatusConexaoSteam status = steam.status(perfil.usuarioId());
    boolean dono = perfil.usuarioId().equals(visitanteId);
    List<ServicoConexoesSteam.JogoBibliotecaSteam> jogos = dono || perfil.mostrarBiblioteca() ? steam.biblioteca(perfil.usuarioId()) : List.of();
    List<FavoritoPerfilJogo> favoritos = dono || perfil.mostrarFavoritos() ? favoritosPerfil.listarPorUsuario(perfil.usuarioId()) : List.of();
    boolean mostrarAtividades = dono || perfil.mostrarAtividades();
    List<AtividadePerfil> atividadeRecente = mostrarAtividades ? carregarAtividades(perfil.usuarioId()) : List.of();
    return new PerfilPublico(perfil.handle(), perfil.nomeExibicao(), perfil.bio(), perfil.avatarUrl(), perfil.avatarZoom(), perfil.avatarPosicaoX(), perfil.avatarPosicaoY(),
        perfil.mostrarHoras() ? status.totalMinutos() : null,
        perfil.mostrarConquistas() ? status.conquistasDesbloqueadas() : null,
        perfil.mostrarConquistas() ? status.conquistasTotal() : null,
        dono || perfil.mostrarBiblioteca() ? status.totalJogos() : null,
        jogos,
        favoritos,
        atividadeRecente,
        perfil.mostrarAtividades(), blocosPublicos(perfil, dono));
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

  // A atividade e complementar: uma migration pendente nao pode tornar o perfil indisponivel.
  private List<AtividadePerfil> carregarAtividades(String usuarioId) {
    try {
      return atividades.listarPorUsuario(usuarioId, 8);
    } catch (DataAccessException erro) {
      return List.of();
    }
  }

  private List<RepositorioBlocosPerfil.BlocoPerfil> blocosPublicos(RepositorioPerfis.Perfil perfil, boolean dono) {
    return blocos.listar(perfil.usuarioId()).stream()
        .filter(bloco -> dono || !"favoritos".equals(bloco.tipo()) || perfil.mostrarFavoritos())
        .filter(bloco -> dono || !"biblioteca".equals(bloco.tipo()) || perfil.mostrarBiblioteca())
        .filter(bloco -> dono || !"atividade".equals(bloco.tipo()) || perfil.mostrarAtividades())
        .toList();
  }

  record EntradaPerfil(String handle, String nomeExibicao, String bio, boolean publico, boolean mostrarHoras, boolean mostrarConquistas, boolean mostrarBiblioteca, boolean mostrarFavoritos, boolean mostrarAtividades) {}
  record EntradaAvatar(String avatarUrl, double zoom, int posicaoX, int posicaoY) {}
  record ResultadoAtualizacao(String status, String usuarioId) {}
  record PerfilPublico(String handle, String nomeExibicao, String bio, String avatarUrl, double avatarZoom, int avatarPosicaoX, int avatarPosicaoY, Long totalMinutos, Long conquistasDesbloqueadas, Long conquistasTotal, Long totalJogosBiblioteca, List<ServicoConexoesSteam.JogoBibliotecaSteam> biblioteca, List<FavoritoPerfilJogo> favoritos, List<AtividadePerfil> atividades, boolean mostrarAtividades, List<RepositorioBlocosPerfil.BlocoPerfil> blocos) {}
}
