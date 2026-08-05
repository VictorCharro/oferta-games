package com.ofertagames.backend.jogos;

import java.math.BigDecimal;

public record OfertaParaSalvar(
    long jogoId,
    String fonte,
    String loja,
    BigDecimal preco,
    BigDecimal precoNormal,
    String moeda,
    String url,
    String cupom
) {}
