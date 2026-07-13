package com.ofertagames.backend.favoritosperfil;

import java.math.BigDecimal;

public record FavoritoPerfilJogo(
    String slug,
    Integer steamAppId,
    String titulo,
    String capaUrl,
    String iconeHash,
    Boolean ehDlc,
    BigDecimal precoMinimo,
    BigDecimal precoRegular,
    String favoritadoEm
) {}
