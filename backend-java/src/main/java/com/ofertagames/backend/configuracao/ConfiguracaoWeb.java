package com.ofertagames.backend.configuracao;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ConfiguracaoWeb implements WebMvcConfigurer {
  private final String[] origensPermitidas;

  ConfiguracaoWeb(@Value("${app.cors.allowed-origins:*}") String origensPermitidas) {
    this.origensPermitidas = Arrays.stream(origensPermitidas.split(","))
        .map(String::trim)
        .filter(origem -> !origem.isBlank())
        .toArray(String[]::new);
  }

  @Override
  public void addCorsMappings(CorsRegistry registro) {
    registro.addMapping("/api/**")
        .allowedOrigins(origensPermitidas)
        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        .allowedHeaders("*");
  }
}
