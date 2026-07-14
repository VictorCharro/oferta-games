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

CMD ["sh", "-c", "java ${JAVA_OPTS} -Dserver.port=${PORT} -jar app.jar"]
