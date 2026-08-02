FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /a
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
COPY --from=build /a/target/quarkus-app/ /app/
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/quarkus-run.jar"]
