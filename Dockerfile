# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-26 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q -DskipTests -Djacoco.skip=true package \
 && java -Djarmode=tools -jar target/rag-eval-gateway-*.jar extract --destination /app

FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 gateway \
 && useradd --system --uid 10001 --gid gateway --no-create-home gateway
WORKDIR /app
COPY --from=build --chown=10001:10001 /app/lib ./lib
COPY --from=build --chown=10001:10001 /app/rag-eval-gateway-*.jar ./app.jar
USER 10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
