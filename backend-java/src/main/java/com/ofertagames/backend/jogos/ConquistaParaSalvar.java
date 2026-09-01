package com.ofertagames.backend.jogos;

// Sem o icone cinza que a Steam devolve: era gravado e nunca lido (a interface usa o icone
// colorido com um cadeado por cima), custando 57 MB. Ver migration de 01/09/2026.
public record ConquistaParaSalvar(
    String apiName, String displayName, String descricao, String iconeUrl, Double percentualGlobal) {}
