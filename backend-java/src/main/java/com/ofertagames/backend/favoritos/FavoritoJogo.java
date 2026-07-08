package com.ofertagames.backend.favoritos;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record FavoritoJogo(
    String slug,
    String title,
    String coverUrl,
    BigDecimal minPrice,
    BigDecimal regularPrice,
    OffsetDateTime favoritedAt
) {}
