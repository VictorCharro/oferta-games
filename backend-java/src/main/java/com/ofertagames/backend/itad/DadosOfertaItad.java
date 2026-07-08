package com.ofertagames.backend.itad;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DadosOfertaItad(LojaItad shop, DinheiroItad price, DinheiroItad regular, String url) {}
