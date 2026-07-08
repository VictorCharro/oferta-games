package com.ofertagames.backend.configuracao;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ConfiguracaoBancoDados {
  @Bean
  DataSource fonteDados() {
    String urlBanco = System.getenv("DATABASE_URL");
    if (urlBanco == null || urlBanco.isBlank()) {
      throw new IllegalStateException("DATABASE_URL nao configurada");
    }

    URI uri = URI.create(urlBanco);
    String[] credenciais = extrairCredenciais(uri);
    String urlJdbc = "jdbc:postgresql://" + uri.getHost() + ":" + uri.getPort() + uri.getPath();
    urlJdbc += uri.getQuery() == null || uri.getQuery().isBlank() ? "?sslmode=require" : "?" + uri.getQuery();

    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(urlJdbc);
    config.setUsername(credenciais[0]);
    config.setPassword(credenciais[1]);
    config.setMaximumPoolSize(5);
    config.setMinimumIdle(0);
    config.setPoolName("oferta-games-pool");
    return new HikariDataSource(config);
  }

  private static String[] extrairCredenciais(URI uri) {
    String usuarioSenha = uri.getRawUserInfo();
    if (usuarioSenha == null || usuarioSenha.isBlank()) {
      return new String[] {"", ""};
    }

    String[] partes = usuarioSenha.split(":", 2);
    String usuario = decodificar(partes[0]);
    String senha = partes.length > 1 ? decodificar(partes[1]) : "";
    return new String[] {usuario, senha};
  }

  private static String decodificar(String valor) {
    return URLDecoder.decode(valor, StandardCharsets.UTF_8);
  }
}
