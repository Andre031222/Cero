# Contexto de build = raíz del repo (bench.sh lo invoca así), para compilar JxMVC.Core.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY legacy/jxmvc-core /src/jxmvc-core
RUN cd /src/jxmvc-core && mvn -q -DskipTests install
COPY benchmarks/docker/apps/jxmvc/app /src/app
RUN cd /src/app && mvn -q -DskipTests package

FROM tomcat:10.1-jre21
RUN rm -rf /usr/local/tomcat/webapps/*
COPY --from=build /src/app/target/ROOT.war /usr/local/tomcat/webapps/ROOT.war
EXPOSE 8080
