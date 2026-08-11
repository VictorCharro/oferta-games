package com.ofertagames.backend.configuracao;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// Headers de seguranca basicos em toda resposta. Sem CSP: a API so serve JSON (sem HTML/scripts
// pra proteger), e um CSP mal calibrado quebraria integracoes sem beneficio real aqui.
@Component
class FiltroCabecalhosSeguranca extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta, FilterChain cadeia)
      throws ServletException, IOException {
    resposta.setHeader("X-Content-Type-Options", "nosniff");
    resposta.setHeader("X-Frame-Options", "DENY");
    resposta.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
    resposta.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
    // HSTS: seguro aqui porque o Caddy na frente sempre serve por HTTPS (ver doc.md "Oracle").
    resposta.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
    cadeia.doFilter(requisicao, resposta);
  }
}
