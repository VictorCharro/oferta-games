package com.ofertagames.backend.perfis;

import static org.junit.jupiter.api.Assertions.*;
import com.ofertagames.backend.conexoes.ServicoConexoesSteam.JogoBibliotecaSteam;
import com.ofertagames.backend.favoritosperfil.FavoritoPerfilJogo;
import org.junit.jupiter.api.Test;

class PrivacidadePerfilTest {
  @Test void ocultaDadosPorJogoSemAlterarOriginal() {
    var jogo = new JogoBibliotecaSteam(1, "Jogo", 600, null, 10, 10, null, "jogo", 1, "xbox");
    var publico = ServicoPerfis.protegerJogo(jogo, false, false);
    assertNull(publico.minutosJogadas());
    assertNull(publico.conquistasDesbloqueadas());
    assertNull(publico.conquistasTotal());
    assertNull(publico.platinumPosition());
    assertEquals(600, jogo.minutosJogadas());
    assertEquals(jogo, ServicoPerfis.protegerJogo(jogo, true, true));
  }

  @Test void protegeFavoritosEColecoesComTogglesIndependentes() {
    var jogo = new FavoritoPerfilJogo("jogo", 1, "Jogo", null, null, false, 600, 5, 10, null, null, null);
    var semHoras = ServicoPerfis.protegerFavorito(jogo, false, true);
    assertNull(semHoras.minutosJogadas());
    assertEquals(5, semHoras.conquistasDesbloqueadas());
    var semConquistas = ServicoPerfis.protegerFavorito(jogo, true, false);
    assertEquals(600, semConquistas.minutosJogadas());
    assertNull(semConquistas.conquistasTotal());
  }
}
