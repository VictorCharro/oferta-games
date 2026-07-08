package com.ofertagames.backend.deals;

import java.math.BigDecimal;

public record DealDto(
    String slug,
    String title,
    String coverUrl,
    Boolean isDlc,
    Integer rank,
    String storeName,
    BigDecimal price,
    BigDecimal regularPrice,
    String url,
    Integer discountPct
) {}
