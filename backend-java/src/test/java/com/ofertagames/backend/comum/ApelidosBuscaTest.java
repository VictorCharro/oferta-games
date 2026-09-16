package com.ofertagames.backend.comum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ApelidosBuscaTest {

  @Test
  void expandeSiglaMantendoOComplemento() {
    assertEquals(List.of("gta", "grand theft auto"), ApelidosBusca.expandir("GTA"));
    assertEquals(List.of("gta 6", "grand theft auto 6"), ApelidosBusca.expandir(" gta 6 "));
    assertEquals(List.of("re 4", "resident evil 4"), ApelidosBusca.expandir("re 4"));
  }

  @Test
  void termoSemApelidoVaiSozinhoESiglaNoMeioNaoExpande() {
    assertEquals(List.of("hades"), ApelidosBusca.expandir("hades"));
    // "codex" nao e "cod": a expansao so vale pra palavra inteira.
    assertEquals(List.of("codex"), ApelidosBusca.expandir("codex"));
  }

  @Test
  void regexCasaInicioDePalavraENaoMeioDePalavra() {
    // \m do Postgres nao existe em Java: aqui o equivalente e \b, so pra provar a regra.
    Pattern gta = Pattern.compile(ApelidosBusca.regexInicioDePalavra("gta").replace("\\m", "\\b"), Pattern.CASE_INSENSITIVE);
    assertTrue(gta.matcher("GTA+").find());
    assertTrue(gta.matcher("GTA Online: Whale Shark Cash Card").find());
    assertTrue(!gta.matcher("RagTag").find());
  }

  @Test
  void escapaMetacaracteresDoTermo() {
    assertEquals("\\m2\\+2", ApelidosBusca.regexInicioDePalavra("2+2"));
  }
}
