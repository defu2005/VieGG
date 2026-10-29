# Build context là thư mục backend/ (xem docker-compose.yml ở thư mục gốc).
# Multi-stage build: Maven → eclipse-temurin:21-jre, non-root user (System Design §17.1)
# Cache của Maven dùng BuildKit (mặc định trong Docker hiện nay).
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 rentgg
WORKDIR /app
COPY --from=build /src/target/rentgg-backend-*.jar /app/app.jar
USER rentgg
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
