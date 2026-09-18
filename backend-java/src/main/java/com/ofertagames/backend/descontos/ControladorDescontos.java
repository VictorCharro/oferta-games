package com.ofertagames.backend.descontos;

import com.ofertagames.backend.comum.CacheHttp;
import com.ofertagames.backend.comum.ParametrosPublicos;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/deals")
public class ControladorDescontos {
  private final RepositorioDescontos descontos;

  ControladorDescontos(RepositorioDescontos descontos) {
    this.descontos = descontos;
  }

  /**
   * Publico e igual pra todo mundo — a Home dispara tres variacoes disso a cada carregamento.
   *
   * <p>O {@code size} <b>nao</b> chega no repositorio: o topo e sempre calculado inteiro (e cacheado
   * por ordenacao/tipo) e fatiado aqui. Ver {@link RepositorioDescontos#listarTopo}.
   */
  @GetMapping("/top")
  ResponseEntity<List<DescontoJogo>> listarMelhores(
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "discount") String sort,
      @RequestParam(defaultValue = "all") String type
  ) {
    List<DescontoJogo> topo = descontos.listarTopo(
        ParametrosPublicos.ordenacaoDescontos(sort), ParametrosPublicos.tipo(type));
    int tamanho = Math.min(ParametrosPublicos.tamanhoDescontos(size), topo.size());
    return ResponseEntity.ok()
        .cacheControl(CacheHttp.publico())
        .body(topo.subList(0, tamanho));
  }

  /** Lancamentos e pre-vendas populares, com o menor preco atual (secao "Lancamentos em alta" da Home). */
  @GetMapping("/lancamentos")
  ResponseEntity<List<DescontoJogo>> listarLancamentos() {
    return ResponseEntity.ok()
        .cacheControl(CacheHttp.publico())
        .body(descontos.listarLancamentos());
  }
}
