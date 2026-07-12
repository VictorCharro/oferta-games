package com.ofertagames.backend.perfis;

import com.ofertagames.backend.conexoes.ServicoConexoesSteam;
import com.ofertagames.backend.favoritosperfil.FavoritoPerfilJogo;
import com.ofertagames.backend.favoritosperfil.RepositorioFavoritosPerfil;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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

  ServicoPerfis(RepositorioPerfis perfis, ServicoConexoesSteam steam, RepositorioFavoritosPerfil favoritosPerfil) { this.perfis = perfis; this.steam = steam; this.favoritosPerfil = favoritosPerfil; }

  RepositorioPerfis.Perfil proprio(String usuarioId) { return perfis.buscarPorUsuario(usuarioId).orElse(null); }

  void salvar(String usuarioId, EntradaPerfil entrada) {
    String handle = normalizarHandle(entrada.handle());
    if (entrada.publico() && handle == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Defina a URL do perfil antes de torna-lo publico");
    String avatarUrl = perfis.buscarPorUsuario(usuarioId).map(RepositorioPerfis.Perfil::avatarUrl).orElse(null);
    try { perfis.salvar(usuarioId, new RepositorioPerfis.DadosPerfil(handle, limitar(entrada.nomeExibicao(), 60), limitar(entrada.bio(), 180), avatarUrl, entrada.publico(), entrada.mostrarHoras(), entrada.mostrarConquistas(), entrada.mostrarBiblioteca(), entrada.mostrarFavoritos())); }
    catch (RuntimeException erro) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta URL de perfil ja esta em uso"); }
  }

  void atualizarAvatar(String usuarioId, EntradaAvatar entrada) {
    String avatarUrl = entrada.avatarUrl() == null ? "" : entrada.avatarUrl().trim();
    String padrao = "^https://[a-z0-9-]+\\.supabase\\.co/storage/v1/object/public/avatars/" + java.util.regex.Pattern.quote(usuarioId) + "/[^\\s]{1,900}$";
    if (!avatarUrl.matches(padrao)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL de avatar invalida");
    perfis.atualizarAvatar(usuarioId, avatarUrl);
  }

  PerfilPublico publico(String handle, String visitanteId) {
    RepositorioPerfis.Perfil perfil = perfis.buscarPorHandle(normalizarHandleObrigatorio(handle)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (!perfil.publico() && !perfil.usuarioId().equals(visitanteId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    ServicoConexoesSteam.StatusConexaoSteam status = steam.status(perfil.usuarioId());
    boolean dono = perfil.usuarioId().equals(visitanteId);
    List<ServicoConexoesSteam.JogoBibliotecaSteam> jogos = dono || perfil.mostrarBiblioteca() ? steam.biblioteca(perfil.usuarioId()) : List.of();
    List<FavoritoPerfilJogo> favoritos = dono || perfil.mostrarFavoritos() ? favoritosPerfil.listarPorUsuario(perfil.usuarioId()) : List.of();
    return new PerfilPublico(perfil.handle(), perfil.nomeExibicao(), perfil.bio(), perfil.avatarUrl(),
        perfil.mostrarHoras() ? status.totalMinutos() : null,
        perfil.mostrarConquistas() ? status.conquistasDesbloqueadas() : null,
        perfil.mostrarConquistas() ? status.conquistasTotal() : null,
        jogos,
        favoritos);
  }

  private static String normalizarHandle(String valor) { if (valor == null || valor.isBlank()) return null; return normalizarHandleObrigatorio(valor); }
  private static String normalizarHandleObrigatorio(String valor) { String handle = valor.trim().toLowerCase(Locale.ROOT); if (!handle.matches("^[a-z0-9][a-z0-9-]{2,29}$")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use de 3 a 30 caracteres: letras minusculas, numeros e hifen"); if (IDENTIFICADORES_RESERVADOS.contains(handle)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Esta URL e reservada pelo sistema"); return handle; }
  private static String limitar(String valor, int maximo) { String resultado = valor == null ? "" : valor.trim(); return resultado.length() > maximo ? resultado.substring(0, maximo) : resultado; }

  record EntradaPerfil(String handle, String nomeExibicao, String bio, boolean publico, boolean mostrarHoras, boolean mostrarConquistas, boolean mostrarBiblioteca, boolean mostrarFavoritos) {}
  record EntradaAvatar(String avatarUrl) {}
  record PerfilPublico(String handle, String nomeExibicao, String bio, String avatarUrl, Long totalMinutos, Long conquistasDesbloqueadas, Long conquistasTotal, List<ServicoConexoesSteam.JogoBibliotecaSteam> biblioteca, List<FavoritoPerfilJogo> favoritos) {}
}
