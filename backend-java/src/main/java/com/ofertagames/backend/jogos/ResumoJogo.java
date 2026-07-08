package com.ofertagames.backend.jogos;

import java.math.BigDecimal;

public record ResumoJogo(
    String slug,
    String title,
    String coverUrl,
    Boolean isDlc,
    BigDecimal minPrice,
    BigDecimal regularPrice
) {}
