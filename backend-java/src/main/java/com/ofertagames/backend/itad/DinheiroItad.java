package com.ofertagames.backend.itad;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DinheiroItad(BigDecimal amount, String currency) {}
