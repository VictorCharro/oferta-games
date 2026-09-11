package com.ofertagames.backend.conexoes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
class ClienteSteamWeb {
  private final RestClient restClient;
  private final String chaveApi;

  ClienteSteamWeb(RestClient.Builder restClientBuilder, @Value("${app.steam.api-key}") String chaveApi) {
    this.restClient = restClientBuilder.baseUrl("https://api.steampowered.com").build();
    this.chaveApi = chaveApi;
  }

  boolean configurada() {
    return chaveApi != null && !chaveApi.isBlank();
  }

  PerfilSteam buscarPerfil(String steamId) {
    validarChave();
    Map<String, Object> resposta = restClient.get()
        .uri(uri -> uri.path("/ISteamUser/GetPlayerSummaries/v0002/")
            .queryParam("key", chaveApi)
            .queryParam("steamids", steamId)
            .build())
        .retrieve()
        .body(new ParameterizedTypeReference<Map<String, Object>>() {});
    Map<?, ?> response = resposta == null ? null : comoMapa(resposta.get("response"));
    List<?> jogadores = response == null ? List.of() : comoLista(response.get("players"));
    Map<?, ?> jogador = jogadores.isEmpty() ? null : comoMapa(jogadores.get(0));
    return new PerfilSteam(
        jogador == null ? null : comoTexto(jogador.get("personaname")),
        jogador == null ? null : comoTexto(jogador.get("avatarfull")));
  }

  List<RepositorioConexoesSteam.JogoBibliotecaSteam> buscarBiblioteca(String steamId) {
    validarChave();
    Map<String, Object> resposta = restClient.get()
        .uri(uri -> uri.path("/IPlayerService/GetOwnedGames/v0001/")
            .queryParam("key", chaveApi)
            .queryParam("steamid", steamId)
            .queryParam("include_appinfo", true)
            .queryParam("include_played_free_games", true)
            .build())
        .retrieve()
        .body(new ParameterizedTypeReference<Map<String, Object>>() {});
    Map<?, ?> response = resposta == null ? null : comoMapa(resposta.get("response"));
    if (response == null || !response.containsKey("games")) {
      throw new BibliotecaSteamPrivadaException();
    }

    List<RepositorioConexoesSteam.JogoBibliotecaSteam> jogos = new ArrayList<>();
    for (Object item : comoLista(response.get("games"))) {
      Map<?, ?> jogo = comoMapa(item);
      if (jogo == null) continue;
      Integer appId = comoInteiro(jogo.get("appid"));
      String titulo = comoTexto(jogo.get("name"));
      if (appId == null || titulo == null || titulo.isBlank()) continue;
      jogos.add(new RepositorioConexoesSteam.JogoBibliotecaSteam(
          appId,
          titulo,
          comoInteiro(jogo.get("playtime_forever"), 0),
          comoTexto(jogo.get("img_icon_url"))));
    }
    return jogos;
  }

  /**
   * Wishlist do usuario, appIds na ordem "adicionado mais recente primeiro".
   *
   * <p>Sem chave (usada em {@code validarChave} nas outras chamadas) — {@code IWishlistService}
   * funciona sem key. Perfil com wishlist privada (configuracao separada da privacidade geral do
   * perfil Steam) devolve {@code response} vazio, sem erro — tratado igual a lista vazia, nunca
   * lanca excecao (mesmo padrao das outras chamadas de sincronizacao desse cliente).
   */
  List<Integer> buscarWishlist(String steamId) {
    Map<String, Object> resposta = restClient.get()
        .uri(uri -> uri.path("/IWishlistService/GetWishlist/v1/")
            .queryParam("steamid", steamId)
            .build())
        .retrieve()
        .body(new ParameterizedTypeReference<Map<String, Object>>() {});
    Map<?, ?> response = resposta == null ? null : comoMapa(resposta.get("response"));
    List<?> itens = response == null ? List.of() : comoLista(response.get("items"));

    List<ItemWishlist> ordenados = new ArrayList<>();
    for (Object item : itens) {
      Map<?, ?> mapa = comoMapa(item);
      if (mapa == null) continue;
      Integer appId = comoInteiro(mapa.get("appid"));
      if (appId == null) continue;
      ordenados.add(new ItemWishlist(appId, comoInteiro(mapa.get("date_added"), 0)));
    }
    ordenados.sort((a, b) -> Integer.compare(b.dataAdicionado(), a.dataAdicionado()));
    return ordenados.stream().map(ItemWishlist::appId).toList();
  }

  private record ItemWishlist(int appId, int dataAdicionado) {}

  ConquistasSteam buscarConquistas(String steamId, int appId) {
    validarChave();
    try {
      Map<String, Object> resposta = restClient.get()
          .uri(uri -> uri.path("/ISteamUserStats/GetPlayerAchievements/v0001/")
              .queryParam("key", chaveApi)
              .queryParam("steamid", steamId)
              .queryParam("appid", appId)
              .queryParam("l", "brazilian")
              .build())
          .retrieve()
          .body(new ParameterizedTypeReference<Map<String, Object>>() {});
      Map<?, ?> playerstats = resposta == null ? null : comoMapa(resposta.get("playerstats"));
      List<?> conquistas = playerstats == null ? List.of() : comoLista(playerstats.get("achievements"));
      List<ConquistaSteam> detalhes = new ArrayList<>();
      for (Object item : conquistas) {
        Map<?, ?> conquista = comoMapa(item);
        if (conquista == null || !Integer.valueOf(1).equals(comoInteiro(conquista.get("achieved")))) continue;
        String identificador = comoTexto(conquista.get("apiname"));
        if (identificador == null || identificador.isBlank()) continue;
        detalhes.add(new ConquistaSteam(identificador, formatarNomeConquista(identificador), comoInteiro(conquista.get("unlocktime"), 0)));
      }
      return new ConquistasSteam(detalhes, conquistas.size());
    } catch (RuntimeException erro) {
      return null;
    }
  }

  private void validarChave() {
    if (chaveApi == null || chaveApi.isBlank()) throw new ChaveSteamNaoConfiguradaException();
  }

  private static Map<?, ?> comoMapa(Object valor) {
    return valor instanceof Map<?, ?> mapa ? mapa : null;
  }

  private static List<?> comoLista(Object valor) {
    return valor instanceof List<?> lista ? lista : List.of();
  }

  private static String comoTexto(Object valor) {
    return valor instanceof String texto ? texto : null;
  }

  private static Integer comoInteiro(Object valor) {
    return comoInteiro(valor, null);
  }

  private static Integer comoInteiro(Object valor, Integer padrao) {
    return valor instanceof Number numero ? numero.intValue() : padrao;
  }

  private static String formatarNomeConquista(String valor) {
    String texto = valor.replace('_', ' ').trim().toLowerCase(java.util.Locale.ROOT);
    if (texto.isBlank()) return valor;
    return Character.toUpperCase(texto.charAt(0)) + texto.substring(1);
  }

  record PerfilSteam(String nome, String avatarUrl) {}
  record ConquistasSteam(List<ConquistaSteam> desbloqueadas, int total) {}
  record ConquistaSteam(String identificador, String titulo, int desbloqueadaEm) {}
  static class ChaveSteamNaoConfiguradaException extends RuntimeException {}
  static class BibliotecaSteamPrivadaException extends RuntimeException {}
}
