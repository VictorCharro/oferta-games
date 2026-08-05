package com.ofertagames.backend.conexoes;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

// Fluxo "Xbox App" do OpenXBL (xbl.io): diferente da API key pessoal, esse app_key e usado
// pra logar QUALQUER usuario com a conta Microsoft dele (nao so a nossa) - o usuario e
// redirecionado pra https://api.xbl.io/app/auth/{app_key}, volta com um "code" na URL, e a
// gente troca esse code por xuid/gamertag/token nesse endpoint /app/claim.
@Service
class ClienteXbl {
  private static final String URL_BASE = "https://api.xbl.io";

  private final RestClient restClient;
  private final String appKey;

  ClienteXbl(RestClient.Builder restClientBuilder, @Value("${app.xbox.app-key}") String appKey) {
    this.restClient = restClientBuilder.baseUrl(URL_BASE).build();
    this.appKey = appKey;
  }

  boolean configurada() {
    return appKey != null && !appKey.isBlank();
  }

  String appKey() {
    return appKey;
  }

  RespostaClaimXbl trocarCodigo(String code) {
    return restClient.post()
        .uri("/app/claim")
        .body(Map.of("code", code, "app_key", appKey))
        .retrieve()
        .body(RespostaClaimXbl.class);
  }

  // titleHistory devolve a biblioteca inteira (todos os jogos ja jogados) numa unica chamada,
  // ja com o progresso de conquistas por jogo - sem precisar de uma chamada por jogo, o que
  // seria inviavel dado o rate limit apertado da OpenXBL (60 req/5min no free tier).
  List<TituloXbl> buscarBiblioteca(String tokenUsuario) {
    RespostaTitleHistoryXbl resposta = restClient.get()
        .uri("/v2/player/titleHistory")
        .header("X-Authorization", tokenUsuario)
        .retrieve()
        .body(RespostaTitleHistoryXbl.class);
    if (resposta == null || resposta.content() == null || resposta.content().titles() == null) return List.of();
    return resposta.content().titles();
  }

  // MinutesPlayed nao vem no titleHistory - precisa desse endpoint separado de stats em lote.
  // Da pra pedir varios titleIds numa unica chamada (POST /v2/player/stats), entao processa em
  // blocos de 200 pra nao mandar um payload gigante de uma vez so, mas ainda economizando MUITO
  // rate limit comparado a uma chamada por jogo.
  Map<String, Integer> buscarMinutosJogados(String tokenUsuario, String xuid, List<String> titleIds) {
    Map<String, Integer> resultado = new HashMap<>();
    int tamanhoBloco = 200;
    for (int inicio = 0; inicio < titleIds.size(); inicio += tamanhoBloco) {
      List<String> bloco = titleIds.subList(inicio, Math.min(inicio + tamanhoBloco, titleIds.size()));
      List<Map<String, String>> stats = bloco.stream()
          .map(titleId -> Map.of("name", "MinutesPlayed", "titleId", titleId))
          .toList();
      RespostaStatsXbl resposta = restClient.post()
          .uri("/v2/player/stats")
          .header("X-Authorization", tokenUsuario)
          .body(Map.of("xuids", List.of(xuid), "stats", stats))
          .retrieve()
          .body(RespostaStatsXbl.class);
      if (resposta == null || resposta.content() == null || resposta.content().statlistscollection() == null) continue;
      for (GrupoStatsXbl grupo : resposta.content().statlistscollection()) {
        if (grupo == null || grupo.stats() == null) continue;
        for (StatXbl stat : grupo.stats()) {
          if (stat == null || stat.titleid() == null || stat.value() == null) continue;
          try {
            resultado.put(stat.titleid(), Integer.parseInt(stat.value()));
          } catch (NumberFormatException erro) {
            // Ignora valor que nao vem como numero.
          }
        }
      }
    }
    return resultado;
  }

  // "app_key" aqui e o token por usuario devolvido pelo /app/claim (mesmo nome de campo da
  // chave do app, mas com outro significado) - usado pra chamar a API em nome desse usuario.
  @JsonIgnoreProperties(ignoreUnknown = true)
  record RespostaClaimXbl(
      String xuid,
      String gamertag,
      String avatar,
      Integer gamerscore,
      @JsonProperty("app_key") String tokenUsuario,
      String message
  ) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record RespostaTitleHistoryXbl(ConteudoTitleHistoryXbl content) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record ConteudoTitleHistoryXbl(String xuid, List<TituloXbl> titles) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record TituloXbl(String titleId, String name, String displayImage, AchievementXbl achievement, TitleHistoryDetalheXbl titleHistory) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record AchievementXbl(int currentAchievements, int totalAchievements, int currentGamerscore, int totalGamerscore) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record TitleHistoryDetalheXbl(String lastTimePlayed) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record RespostaStatsXbl(ConteudoStatsXbl content) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record ConteudoStatsXbl(List<GrupoStatsXbl> statlistscollection) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record GrupoStatsXbl(List<StatXbl> stats) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record StatXbl(String titleid, String name, String value) {}
}
