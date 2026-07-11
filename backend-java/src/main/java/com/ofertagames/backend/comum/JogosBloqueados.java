package com.ofertagames.backend.comum;

import java.util.Set;

public final class JogosBloqueados {
  private static final Set<String> IDS_ITAD = Set.of(
      "019e8518-404a-709b-ae47-a4ef949552ea"
  );

  private JogosBloqueados() {}

  public static boolean contemIdItad(String idItad) {
    return idItad != null && IDS_ITAD.contains(idItad);
  }

  public static String filtroSql(String alias) {
    return "AND (" + alias + ".itad_id IS NULL OR " + alias
        + ".itad_id::text NOT IN ('019e8518-404a-709b-ae47-a4ef949552ea'))";
  }
}
