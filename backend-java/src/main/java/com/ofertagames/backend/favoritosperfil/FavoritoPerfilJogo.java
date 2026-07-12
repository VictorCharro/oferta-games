package com.ofertagames.backend.favoritosperfil;

import java.math.BigDecimal;

public record FavoritoPerfilJogo(
    String slug,
    String titulo,
    String capaUrl,
    Boolean ehDlc,
    BigDecimal precoMinimo,
    BigDecimal precoRegular,
    String favoritadoEm
) {}
