package com.ofertagames.backend.jogos;

import java.util.List;

public record RespostaConquistas(
    int total, int desbloqueadas, int percentualConcluido, ConquistaComProgresso proxima, List<ConquistaComProgresso> conquistas) {}
