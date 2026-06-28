package com.ofertagames.backend.game;

import java.math.BigDecimal;

public interface GameSummaryProjection {
    String getSlug();
    String getTitle();
    String getCoverUrl();
    BigDecimal getMinPrice();
}
