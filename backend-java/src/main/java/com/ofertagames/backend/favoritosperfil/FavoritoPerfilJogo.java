package com.ofertagames.backend.favoritosperfil;

import java.math.BigDecimal;

public record FavoritoPerfilJogo(
    String slug,
    Integer steamAppId,
    String titulo,
    String capaUrl,
    String iconeHash,
    Boolean ehDlc,
    Integer minutosJogadas,
    Integer conquistasDesbloqueadas,
    Integer conquistasTotal,
    BigDecimal precoMinimo,
    BigDecimal precoRegular,
    String favoritadoEm
) {}
