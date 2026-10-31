# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
# spring-security-jwt-starter comes from GitHub Packages, which needs a token. Pass a Maven
# settings file with a server named github-entra-security, and the token if that file reads it
# from ${env.GITHUB_TOKEN}:
#   docker build --secret id=maven-settings,src=$HOME/.m2/settings.xml \
#                --secret id=github-token,env=GITHUB_TOKEN .
RUN --mount=type=cache,target=/root/.m2/repository \
    --mount=type=secret,id=maven-settings,target=/root/.m2/settings.xml \
    --mount=type=secret,id=github-token,env=GITHUB_TOKEN \
    mvn -B -ntp -q -DskipTests -Djacoco.skip=true package \
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
