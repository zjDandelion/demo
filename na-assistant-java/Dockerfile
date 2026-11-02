# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-8 AS build
WORKDIR /build
COPY pom.xml .
COPY na-payment-contract/pom.xml na-payment-contract/pom.xml
COPY na-order-service/pom.xml na-order-service/pom.xml
COPY na-payment-service/pom.xml na-payment-service/pom.xml
COPY na-assistant-java/pom.xml na-assistant-java/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl na-assistant-java -am dependency:go-offline || true
COPY na-assistant-java/src na-assistant-java/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -pl na-assistant-java package

FROM eclipse-temurin:8-jre-jammy
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --system --create-home --uid 10001 assistant
COPY --from=build /build/na-assistant-java/target/na-assistant-java.jar app.jar
USER assistant
EXPOSE 8093
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
