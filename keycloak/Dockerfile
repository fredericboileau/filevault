FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
COPY spi/pom.xml .
RUN mvn dependency:go-offline -q
COPY spi/src ./src
RUN mvn package -DskipTests

FROM quay.io/keycloak/keycloak:25.0.1
COPY --from=builder /build/target/filevault-keycloak-spi-1.0.0-SNAPSHOT.jar /opt/keycloak/providers/
ENV KC_DB=postgres
RUN /opt/keycloak/bin/kc.sh build
