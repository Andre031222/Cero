# jxmvc-core no se compila aquí: el código fuente no está en este repositorio, así que se
# instala en el repositorio local de Maven el artefacto ya publicado que hay en lib/.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY lib /src/lib
RUN mvn -q -B install:install-file \
      -Dfile=/src/lib/jxmvc-core-3.4.0.jar \
      -DpomFile=/src/lib/jxmvc-core-3.4.0.pom \
      -DgroupId=jxmvc -DartifactId=jxmvc-core -Dversion=3.4.0 -Dpackaging=jar
COPY app /src/app
RUN cd /src/app && mvn -q -B -DskipTests package

FROM tomcat:10.1-jre21
RUN rm -rf /usr/local/tomcat/webapps/*
COPY --from=build /src/app/target/ROOT.war /usr/local/tomcat/webapps/ROOT.war
EXPOSE 8080
