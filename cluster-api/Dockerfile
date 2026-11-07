FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
# Cache ~/.m2 across builds and retry downloads; Docker Desktop's link to Maven Central drops TLS handshakes now and then.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B package -DskipTests -Daether.connector.http.retryHandler.count=5

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/target/cluster-api-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
