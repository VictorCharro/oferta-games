package com.ofertagames.backend.saude;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/actuator")
public class ControladorSaude {
  @GetMapping("/health")
  Map<String, String> verificar() {
    return Map.of("status", "UP");
  }
}
