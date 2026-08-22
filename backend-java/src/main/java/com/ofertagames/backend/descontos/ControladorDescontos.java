package com.ofertagames.backend.descontos;

import java.util.List;
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

  @GetMapping("/top")
  List<DescontoJogo> listarMelhores(
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "discount") String sort,
      @RequestParam(defaultValue = "all") String type
  ) {
    int tamanhoSeguro = Math.min(200, Math.max(1, size));
    return descontos.listarMelhores(tamanhoSeguro, sort, type);
  }
}
