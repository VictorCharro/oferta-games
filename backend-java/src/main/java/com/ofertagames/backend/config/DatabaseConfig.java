package com.ofertagames.backend.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DatabaseConfig {
  @Bean
  DataSource dataSource() {
    String databaseUrl = System.getenv("DATABASE_URL");
    if (databaseUrl == null || databaseUrl.isBlank()) {
      throw new IllegalStateException("DATABASE_URL nao configurada");
    }

    URI uri = URI.create(databaseUrl);
    String[] credentials = parseCredentials(uri);
    String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + uri.getPort() + uri.getPath();
    if (uri.getQuery() != null && !uri.getQuery().isBlank()) {
      jdbcUrl += "?" + uri.getQuery();
    } else {
      jdbcUrl += "?sslmode=require";
    }

    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(jdbcUrl);
    config.setUsername(credentials[0]);
    config.setPassword(credentials[1]);
    config.setMaximumPoolSize(5);
    config.setMinimumIdle(0);
    config.setPoolName("oferta-games-pool");
    return new HikariDataSource(config);
  }

  private static String[] parseCredentials(URI uri) {
    String userInfo = uri.getRawUserInfo();
    if (userInfo == null || userInfo.isBlank()) {
      return new String[] {"", ""};
    }

    String[] parts = userInfo.split(":", 2);
    String username = decode(parts[0]);
    String password = parts.length > 1 ? decode(parts[1]) : "";
    return new String[] {username, password};
  }

  private static String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }
}
