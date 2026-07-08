package com.ofertagames.backend.jogos;

import java.math.BigDecimal;

public record OfertaJogo(
    String storeName,
    BigDecimal price,
    BigDecimal regularPrice,
    String currency,
    String url
) {}
