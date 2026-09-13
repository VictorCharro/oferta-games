package com.ofertagames.backend.conexoes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

class ValidacaoOpenIdSteamTest {
  private static final String BACKEND = "https://api.exemplo.com";
  private static final UUID ESTADO = UUID.fromString("5b8f2c1e-3a4d-4e6f-8a9b-0c1d2e3f4a5b");
  private static final String STEAM_ID = "https://steamcommunity.com/openid/id/76561198000000000";

  /** Formato da resposta que a Steam devolve no retorno do login (campos relevantes). */
  private static MultiValueMap<String, String> respostaSteam() {
    MultiValueMap<String, String> p = new LinkedMultiValueMap<>();
    p.add("openid.mode", "id_res");
    p.add("openid.op_endpoint", "https://steamcommunity.com/openid/login");
    p.add("openid.return_to", BACKEND + "/api/conexoes/steam/retorno?state=" + ESTADO);
    p.add("openid.claimed_id", STEAM_ID);
    p.add("openid.identity", STEAM_ID);
    return p;
  }

  @Test
  void respostaLegitimaDaSteamPassa() {
    assertTrue(ServicoConexoesSteam.camposOpenIdConferem(BACKEND, ESTADO, respostaSteam()));
  }

  @Test
  void assercaoEmitidaPraOutroSiteNaoVale() {
    var p = respostaSteam();
    p.set("openid.return_to", "https://site-do-atacante.example/login?state=" + ESTADO);
    assertFalse(ServicoConexoesSteam.camposOpenIdConferem(BACKEND, ESTADO, p));
  }

  @Test
  void assercaoDeOutraTentativaDeLoginNaoVale() {
    var p = respostaSteam();
    p.set("openid.return_to", BACKEND + "/api/conexoes/steam/retorno?state=" + UUID.randomUUID());
    assertFalse(ServicoConexoesSteam.camposOpenIdConferem(BACKEND, ESTADO, p));
  }

  @Test
  void outroProvedorOuIdentidadesDiferentesNaoValem() {
    var outroProvedor = respostaSteam();
    outroProvedor.set("openid.op_endpoint", "https://provedor-falso.example/openid");
    assertFalse(ServicoConexoesSteam.camposOpenIdConferem(BACKEND, ESTADO, outroProvedor));

    var identidadeTrocada = respostaSteam();
    identidadeTrocada.set("openid.identity", "https://steamcommunity.com/openid/id/76561198999999999");
    assertFalse(ServicoConexoesSteam.camposOpenIdConferem(BACKEND, ESTADO, identidadeTrocada));
  }
}
