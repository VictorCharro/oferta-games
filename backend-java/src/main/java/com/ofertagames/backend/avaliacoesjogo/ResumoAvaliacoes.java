package com.ofertagames.backend.avaliacoesjogo;

import java.util.Map;

public record ResumoAvaliacoes(int total, Double media, Map<Integer, Integer> distribuicao, Integer percentualRecomenda) {}
