package com.ofertagames.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AplicacaoOfertaGames {
  public static void main(String[] args) {
    SpringApplication.run(AplicacaoOfertaGames.class, args);
  }
}
