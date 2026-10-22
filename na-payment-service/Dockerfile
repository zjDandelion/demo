# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-8 AS build
WORKDIR /build
COPY pom.xml .
COPY na-payment-contract/pom.xml na-payment-contract/pom.xml
COPY na-order-service/pom.xml na-order-service/pom.xml
COPY na-payment-service/pom.xml na-payment-service/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl na-payment-service -am dependency:go-offline || true
COPY na-payment-contract/src na-payment-contract/src
COPY na-payment-service/src na-payment-service/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -pl na-payment-service -am package

FROM eclipse-temurin:8-jre-jammy
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /build/na-payment-service/target/na-payment-service.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
