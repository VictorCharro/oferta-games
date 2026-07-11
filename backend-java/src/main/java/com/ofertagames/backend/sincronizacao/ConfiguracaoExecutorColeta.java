package com.ofertagames.backend.sincronizacao;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
class ConfiguracaoExecutorColeta {
  @Bean("executorColetaManual")
  ThreadPoolTaskExecutor executorColetaManual() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(1);
    executor.setThreadNamePrefix("coleta-manual-");
    executor.initialize();
    return executor;
  }
}
