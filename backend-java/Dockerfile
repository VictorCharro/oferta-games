FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /app
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=build /app/target/backend-java-0.0.1-SNAPSHOT.jar app.jar

ENV PORT=8080
ENV JAVA_OPTS="-Xms128m -Xmx384m"
EXPOSE 8080

# O 'exec' faz o java SUBSTITUIR o shell e virar o PID 1. Sem ele o PID 1 e o sh, que nao repassa
# SIGTERM: o Docker pedia parada, o Java nunca era avisado e so morria no SIGKILL do fim do prazo.
# Efeito pratico - shutdown gracioso e @PreDestroy (que devolve a trava de coleta) nunca rodavam,
# e requisicao em andamento era cortada no meio. Confirmado no log, que mostrava "INFO 8 ---":
# o java estava no PID 8, nao no 1.
CMD ["sh", "-c", "exec java ${JAVA_OPTS} -Dserver.port=${PORT} -jar app.jar"]
