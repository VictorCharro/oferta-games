package com.ofertagames.backend.favoritosperfil;

import java.util.List;

public record RequisicaoOrdemFavoritos(List<ItemOrdemFavorito> itens) {
  public record ItemOrdemFavorito(String slug, Integer steamAppId) {}
}
