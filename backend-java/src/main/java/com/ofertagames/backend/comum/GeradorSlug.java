package com.ofertagames.backend.comum;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Converte titulo de jogo em slug de URL (`games.slug`, usado em `/jogo/{slug}`).
 *
 * <p>Serve tambem como normalizacao de titulo no casamento da Instant Gaming
 * (`instant_gaming_catalog.titulo normalizado`): os dois lados precisam passar por aqui pra
 * baterem. Mudar a regra de normalizacao invalida os matches ja gravados.
 */
public final class GeradorSlug {
  private GeradorSlug() {}

  /**
   * Remove acentos, passa pra minusculo e troca cada sequencia de caracteres nao alfanumericos
   * por um unico hifen, sem hifen nas pontas.
   *
   * <p>Apostrofo nao e removido, e sim tratado como separador: "Baldur's Gate 3" vira
   * "baldur-s-gate-3" (e nao "baldurs-gate-3").
   *
   * @param titulo nao pode ser nulo — lanca {@link NullPointerException}, diferente das demais
   *     classes deste pacote, que sao null-safe
   * @return slug, ou string vazia se o titulo nao tiver nenhum caractere alfanumerico
   */
  public static String porTitulo(String titulo) {
    String normalizado = Normalizer.normalize(titulo, Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .toLowerCase(Locale.ROOT);
    return normalizado.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
  }
}
