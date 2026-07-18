package com.ofertagames.backend.jogos;

import java.time.Instant;

public record ConquistaComProgresso(
    String nome, String descricao, String iconeUrl, Double percentualGlobal, boolean desbloqueada, Instant desbloqueadaEm) {}
