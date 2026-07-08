package com.ofertagames.backend.comum;

import java.text.Normalizer;
import java.util.Locale;

public final class GeradorSlug {
  private GeradorSlug() {}

  public static String porTitulo(String titulo) {
    String normalizado = Normalizer.normalize(titulo, Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .toLowerCase(Locale.ROOT);
    return normalizado.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
  }
}
