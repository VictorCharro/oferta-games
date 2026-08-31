package com.ofertagames.backend.descontos;

import com.ofertagames.backend.comum.CacheHttp;
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

  /** Publico e igual pra todo mundo — a Home dispara tres variacoes disso a cada carregamento. */
  @GetMapping("/top")
  ResponseEntity<List<DescontoJogo>> listarMelhores(
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "discount") String sort,
      @RequestParam(defaultValue = "all") String type
  ) {
    int tamanhoSeguro = Math.min(200, Math.max(1, size));
    return ResponseEntity.ok()
        .cacheControl(CacheHttp.publico())
        .body(descontos.listarMelhores(tamanhoSeguro, sort, type));
  }
}
