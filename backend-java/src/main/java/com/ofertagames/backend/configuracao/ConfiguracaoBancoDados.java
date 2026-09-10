package com.ofertagames.backend.configuracao;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Dois bancos desde a migracao do catalogo pra fora do Supabase (cota free de 500MB estourando -
 * ver docs/superpowers/plans, se existir, ou o historico do chat): {@code DATABASE_URL} continua
 * sendo o Supabase (auth-adjacent: favoritos, perfil, avaliacoes, notificacoes, conexoes) e
 * {@code CATALOG_DATABASE_URL} e o Postgres novo, self-hosted na VM Oracle (games/game_details/
 * game_achievements/offers/price_history/instant_gaming_catalog - dados de catalogo, sem FK com
 * usuario nenhum).
 *
 * <p>O DataSource do Supabase continua {@code @Primary}: e o que a maioria dos repositorios usa
 * (todo mundo que injeta {@code JdbcClient}/{@code JdbcTemplate} sem qualifier). Só quem mexe em
 * catalogo usa os beans qualificados com {@code "catalogo"}.
 */
@Configuration
public class ConfiguracaoBancoDados {
  @Bean
  @Primary
  DataSource fonteDados() {
    return criarDataSource("DATABASE_URL", "oferta-games-pool");
  }

  @Bean
  @Qualifier("catalogo")
  DataSource fonteDadosCatalogo() {
    return criarDataSource("CATALOG_DATABASE_URL", "oferta-games-catalogo-pool");
  }

  @Bean
  @Qualifier("catalogo")
  JdbcClient jdbcClientCatalogo(@Qualifier("catalogo") DataSource fonteDadosCatalogo) {
    return JdbcClient.create(fonteDadosCatalogo);
  }

  @Bean
  @Qualifier("catalogo")
  JdbcTemplate jdbcTemplateCatalogo(@Qualifier("catalogo") DataSource fonteDadosCatalogo) {
    return new JdbcTemplate(fonteDadosCatalogo);
  }

  @Bean
  @Qualifier("catalogo")
  PlatformTransactionManager transactionManagerCatalogo(@Qualifier("catalogo") DataSource fonteDadosCatalogo) {
    return new DataSourceTransactionManager(fonteDadosCatalogo);
  }

  private static DataSource criarDataSource(String variavelAmbiente, String nomePool) {
    String urlBanco = System.getenv(variavelAmbiente);
    if (urlBanco == null || urlBanco.isBlank()) {
      throw new IllegalStateException(variavelAmbiente + " nao configurada");
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
    // O pooler Supavisor em modo transaction (porta 6543) não suporta prepared statements. O
    // Postgres direto da VM (catalogo) nao usa pooler nenhum, mas manter a mesma flag não tem
    // custo nenhum nele.
    config.addDataSourceProperty("prepareThreshold", "0");
    config.setPoolName(nomePool);
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
