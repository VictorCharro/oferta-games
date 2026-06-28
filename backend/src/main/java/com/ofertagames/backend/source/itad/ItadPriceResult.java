package com.ofertagames.backend.source.itad;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

public record ItadPriceResult(
        String id,
        List<Deal> deals
) {
    public record Deal(
            @JsonProperty("shop") Shop shop,
            @JsonProperty("price") Price price,
            @JsonProperty("regular") Price regular,
            String url
    ) {}

    public record Shop(int id, String name) {}

    public record Price(BigDecimal amount, String currency) {}
}
