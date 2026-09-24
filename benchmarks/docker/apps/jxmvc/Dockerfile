# Contexto de build = un directorio temporal que arma bench.sh con dos cosas: el código de
# JxMVC (JXMVC_SRC, que vive fuera de este repositorio) y la aplicación del banco.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY jxmvc-core /src/jxmvc-core
RUN cd /src/jxmvc-core && mvn -q -DskipTests install
COPY app /src/app
RUN cd /src/app && mvn -q -DskipTests package

FROM tomcat:10.1-jre21
RUN rm -rf /usr/local/tomcat/webapps/*
COPY --from=build /src/app/target/ROOT.war /usr/local/tomcat/webapps/ROOT.war
EXPOSE 8080
