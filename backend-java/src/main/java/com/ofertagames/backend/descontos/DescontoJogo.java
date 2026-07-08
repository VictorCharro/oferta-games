package com.ofertagames.backend.descontos;

import java.math.BigDecimal;

public record DescontoJogo(
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
