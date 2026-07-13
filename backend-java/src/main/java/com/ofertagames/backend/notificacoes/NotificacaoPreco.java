package com.ofertagames.backend.notificacoes;

import java.math.BigDecimal;

public record NotificacaoPreco(
    long id,
    String slug,
    String titulo,
    BigDecimal precoAnterior,
    BigDecimal precoAtual,
    String loja,
    boolean lida,
    String criadaEm
) {}
