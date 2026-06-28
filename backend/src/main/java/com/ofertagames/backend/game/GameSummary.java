package com.ofertagames.backend.game;

import java.math.BigDecimal;

public record GameSummary(
        String slug,
        String title,
        String coverUrl,
        BigDecimal minPrice
) {}
