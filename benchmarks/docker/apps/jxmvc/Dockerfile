# Contexto de build = raíz del repo (bench.sh lo invoca así), para compilar JxMVC.Core.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY JxMVC.Core /src/JxMVC.Core
RUN cd /src/JxMVC.Core && mvn -q -DskipTests install
COPY benchmarks/docker/apps/jxmvc/app /src/app
RUN cd /src/app && mvn -q -DskipTests package

FROM tomcat:10.1-jre17
RUN rm -rf /usr/local/tomcat/webapps/*
COPY --from=build /src/app/target/ROOT.war /usr/local/tomcat/webapps/ROOT.war
EXPOSE 8080
