package com.ofertagames.backend.itad;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LojaItad(Integer id, String name) {}
