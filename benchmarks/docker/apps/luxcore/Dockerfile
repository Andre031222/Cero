# Contexto de build = raíz del repo (bench.sh lo invoca así), para compilar los módulos de LuxCore.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY java /src/java
RUN cd /src/java && mvn -q -DskipTests -pl lux-http,lux-core -am install
COPY benchmarks/docker/apps/luxcore/app /src/app
# La versión sale de java/pom.xml, no del pom del banco: así una subida de versión
# del framework no deja a LuxCore fuera de la comparación por no resolver la dependencia.
RUN LUX=$(mvn -q -f /src/java/pom.xml help:evaluate -Dexpression=project.version -DforceStdout) \
    && cd /src/app && mvn -q -DskipTests -Dlux.version="$LUX" package

FROM eclipse-temurin:21-jre
COPY --from=build /src/app/target/app.jar /app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app.jar"]
