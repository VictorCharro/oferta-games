package com.ofertagames.backend.favorites;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record FavoriteDto(
    String slug,
    String title,
    String coverUrl,
    BigDecimal minPrice,
    BigDecimal regularPrice,
    OffsetDateTime favoritedAt
) {}
