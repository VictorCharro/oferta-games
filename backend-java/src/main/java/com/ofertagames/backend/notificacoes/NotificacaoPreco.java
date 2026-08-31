package com.ofertagames.backend.notificacoes;

import java.math.BigDecimal;

/**
 * @param tipo {@code "queda"} (alerta comum) ou {@code "meta_atingida"} (o preco cruzou a meta que
 *     o usuario definiu). Ver {@code RepositorioNotificacoes.registrarQueda}
 */
public record NotificacaoPreco(
    long id,
    String slug,
    String titulo,
    BigDecimal precoAnterior,
    BigDecimal precoAtual,
    String loja,
    String tipo,
    boolean lida,
    String criadaEm
) {}
