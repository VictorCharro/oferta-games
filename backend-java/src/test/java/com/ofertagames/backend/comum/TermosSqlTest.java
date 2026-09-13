package com.ofertagames.backend.comum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Os filtros de titulo e de loja trocaram regex por lista (issue #16). Estes testes fixam que o
 * resultado continua o MESMO do regex antigo, caso a caso — a troca foi por desempenho, nao pra
 * mudar o que aparece no catalogo.
 */
class TermosSqlTest {
  // Copias literais dos regex que existiam antes da troca.
  private static final Pattern DLC_ANTIGO = Pattern.compile(
      "(dlc|season pass|soundtrack|art book|skin set|skin pack|booster pack|expansion|add-on|monk decipher|demonic weapon pack|foundation boost|arcane boost|lion heart pack|ancient labyrinth)",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern NAO_JOGO_ANTIGO = Pattern.compile(
      "(certification|e[- ]?learning|online business|programming bundle|cybersecurity|masterclass|tutorial|training course|course bundle|kali linux|phonk|hip hop|music bundle)",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern LOJA_ANTIGA = Pattern.compile(
      "^(greenmangaming|allyoupay|allyouplay|planetplay|playerland|joybuggy|wingamestore|macgamestore|humblestore|humblebundle|gamesplanetus|gamesplanetfr|gamesplanetde|gamesplanetuk|gog)$");

  private static final List<String> TITULOS = List.of(
      "Baldur's Gate 3", "Cyberpunk 2077: Phantom Liberty DLC", "The Witcher 3 - Season Pass",
      "Original Soundtrack", "Deluxe Expansion Pack", "Skin Set Vol. 2", "Booster Pack", "Weapon ADD-ON",
      "Monk Decipher", "Demonic Weapon Pack", "Foundation Boost", "Arcane Boost", "Lion Heart Pack",
      "Ancient Labyrinth", "Art Book", "dlcx", "Addon Pack", "Soundtracks Collection",
      "AWS Certification Prep", "E-Learning Bundle", "E Learning 101", "eLearning Pro", "Elearning",
      "e--learning", "Online Business Starter", "Complete Python Programming Bundle", "Cybersecurity Masterclass",
      "Unity Tutorial", "Training Course", "Course Bundle", "Kali Linux Guide", "PHONK Mix", "Hip Hop Beats",
      "Music Bundle", "Hiphop", "Cult of the Lamb", "", "   ", "Tutorials of Doom", "MASTERCLASS");

  private static final List<String> LOJAS = List.of(
      "GOG", "gog.com", "Green Man Gaming", "GreenManGaming", "AllYouPlay", "Humble Store", "Humble Bundle",
      "humble-store", "GamesPlanet US", "gamesplanet-uk", "Steam", "Nuuvem", "Epic Game Store",
      "Gogolplex Games", "WinGameStore", "MacGameStore", "PlayerLand", "JoyBuggy", "PlanetPlay", "AllYouPay");

  @Test
  void classificadorDlcDaOMesmoResultadoDoRegexAntigo() {
    for (String titulo : TITULOS) {
      assertEquals(DLC_ANTIGO.matcher(titulo).find(), ClassificadorDlc.pareceDlc(titulo), titulo);
    }
  }

  @Test
  void conteudosNaoJogosDaOMesmoResultadoDoRegexAntigo() {
    for (String titulo : TITULOS) {
      boolean antigo = NAO_JOGO_ANTIGO.matcher(titulo.toLowerCase(Locale.ROOT)).find();
      assertEquals(antigo, ConteudosNaoJogos.contem(titulo), titulo);
    }
  }

  @Test
  void lojasBloqueadasDaOMesmoResultadoDoRegexAntigo() {
    for (String loja : LOJAS) {
      boolean antigo = LOJA_ANTIGA.matcher(loja.toLowerCase().replaceAll("[^a-z0-9]", "")).find();
      assertEquals(antigo, LojasBloqueadas.contem(loja), loja);
    }
  }

  @Test
  void sqlNaoUsaMaisRegexNosFiltrosDoCatalogo() {
    assertFalse(ClassificadorDlc.condicaoDlcSql("g").contains("~"));
    assertFalse(ConteudosNaoJogos.filtroSql("g").contains("~"));
    assertFalse(LojasBloqueadas.filtroSql("o").contains("~"));
  }

  @Test
  void sqlDeTermosUsaLikeAnyComCadaTermoEntrePorcentos() {
    String sql = TermosSql.contemAlgumSql("g.title", List.of("dlc", "season pass"));
    assertEquals("lower(coalesce(g.title, '')) LIKE ANY (ARRAY['%dlc%','%season pass%'])", sql);
  }

  @Test
  void filtroDeLojaComparaPorIgualdadeContraALista() {
    String sql = LojasBloqueadas.filtroSql("o");
    assertTrue(sql.startsWith("AND regexp_replace(lower(o.store_name), '[^a-z0-9]', '', 'g') <> ALL (ARRAY['"));
    for (String nome : LojasBloqueadas.nomesNormalizados()) {
      assertTrue(nome.matches("[a-z0-9]+"), "nome de loja precisa ser normalizado e seguro pra literal SQL: " + nome);
    }
  }

  @Test
  void recusaTermoQueQuebrariaOLiteralSql() {
    assertThrows(IllegalStateException.class, () -> TermosSql.validar(List.of("it's")));
    assertThrows(IllegalStateException.class, () -> TermosSql.validar(List.of("100%")));
    assertThrows(IllegalStateException.class, () -> TermosSql.validar(List.of("a_b")));
    assertThrows(IllegalStateException.class, () -> TermosSql.validar(List.of("Maiuscula")));
    assertThrows(IllegalStateException.class, () -> TermosSql.validar(List.of(" ")));
  }
}
