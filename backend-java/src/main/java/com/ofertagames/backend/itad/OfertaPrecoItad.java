package com.ofertagames.backend.itad;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OfertaPrecoItad(LojaItad shop, DinheiroItad price, DinheiroItad regular, String url, String voucher) {}
