# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY retrieva-core/pom.xml retrieva-core/
COPY retrieva-arrow/pom.xml retrieva-arrow/
COPY retrieva-server/pom.xml retrieva-server/
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests dependency:go-offline || true
COPY retrieva-core retrieva-core
COPY retrieva-arrow retrieva-arrow
COPY retrieva-server retrieva-server
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

FROM tomcat:10.1-jre21-temurin
RUN rm -rf /usr/local/tomcat/webapps/* \
 && groupadd --system retrieva && useradd --system --gid retrieva --home-dir /nonexistent --shell /usr/sbin/nologin retrieva \
 && mkdir -p /var/lib/retrieva && chown retrieva:retrieva /var/lib/retrieva \
 && chown -R retrieva:retrieva /usr/local/tomcat/logs /usr/local/tomcat/temp /usr/local/tomcat/work
COPY docker/server.xml /usr/local/tomcat/conf/server.xml
COPY --from=build /src/retrieva-server/target/ROOT.war /usr/local/tomcat/webapps/ROOT.war
# Arrow needs java.nio access on JDK 17+; the heap leaves room for Arrow's off-heap buffers.
ENV CATALINA_OPTS="--add-opens=java.base/java.nio=ALL-UNNAMED -XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError" \
    RETRIEVA_MEMORY_DIR=/var/lib/retrieva
USER retrieva
VOLUME /var/lib/retrieva
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s CMD curl -fsS http://127.0.0.1:8080/api/health || exit 1
