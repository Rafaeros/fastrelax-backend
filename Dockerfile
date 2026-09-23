# syntax=docker/dockerfile:1

# ---- build ----
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

# Camada de dependências separada da de código: só reexecuta o download
# quando pom.xml muda, não a cada alteração de fonte.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src ./src
RUN ./mvnw -B -q package -DskipTests

# ---- runtime ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S fastrelax && adduser -S fastrelax -G fastrelax
COPY --from=build /app/target/fastrelax-backend-*.jar app.jar
USER fastrelax

# Migrações Flyway rodam no boot; nenhuma etapa extra necessária ao subir o
# container além de ter DB_URL/DB_USER/DB_PASS apontando para um Postgres
# acessível.
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "app.jar"]
