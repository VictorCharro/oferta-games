package com.ofertagames.backend.games;

import java.math.BigDecimal;

public record OfferDto(
    String storeName,
    BigDecimal price,
    BigDecimal regularPrice,
    String currency,
    String url
) {}
