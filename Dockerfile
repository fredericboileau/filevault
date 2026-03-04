FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY ./restapp/pom.xml .
RUN mvn dependency:go-offline -q
COPY ./restapp/src ./src
RUN mvn package -DskipTests -o

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/filevault-0.0.1-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
