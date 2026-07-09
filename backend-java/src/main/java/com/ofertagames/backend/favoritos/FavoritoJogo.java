package com.ofertagames.backend.favoritos;

import java.math.BigDecimal;

public record FavoritoJogo(
    String slug,
    String title,
    String coverUrl,
    Boolean isDlc,
    BigDecimal minPrice,
    BigDecimal regularPrice,
    String favoritedAt
) {}
