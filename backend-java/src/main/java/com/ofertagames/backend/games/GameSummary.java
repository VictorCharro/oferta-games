package com.ofertagames.backend.games;

import java.math.BigDecimal;

public record GameSummary(
    String slug,
    String title,
    String coverUrl,
    Boolean isDlc,
    BigDecimal minPrice,
    BigDecimal regularPrice
) {}
