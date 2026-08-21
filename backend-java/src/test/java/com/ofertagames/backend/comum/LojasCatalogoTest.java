package com.ofertagames.backend.comum;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class LojasCatalogoTest {

  @Test
  void semChavesNaoFiltra() {
    assertNull(LojasCatalogo.regexParaChaves(null));
    assertNull(LojasCatalogo.regexParaChaves(List.of()));
  }

  @Test
  void chaveDesconhecidaEIgnorada() {
    assertNull(LojasCatalogo.regexParaChaves(List.of("loja-que-nao-existe")));
  }

  @Test
  void combinaRegexDasChavesValidasEBateComONomeDaLoja() {
    String regex = LojasCatalogo.regexParaChaves(List.of("steam", "epic"));
    assertTrue(regex.contains("steam"));
    assertTrue(regex.contains("epic"));

    Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    assertTrue(pattern.matcher("Steam").find());
    assertTrue(pattern.matcher("Epic Games Store").find());
    assertFalse(pattern.matcher("nuuvem").find());
  }
}
