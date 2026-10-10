# syntax=docker/dockerfile:1
# One image definition for every Spring Boot service; pick the module with --build-arg SERVICE=<module dir>.
# The whole reactor is needed to build any one service (root pom + paymesh-common).

FROM maven:3.9-eclipse-temurin-21 AS build
ARG SERVICE
WORKDIR /src
COPY . .
# The cache mount keeps ~/.m2 between builds; "locked" because compose builds all services in parallel
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    test -n "$SERVICE" || { echo "SERVICE build arg is required" >&2; exit 1; } \
 && mvn -B -q -pl "$SERVICE" -am package -DskipTests \
 && cp "$SERVICE"/target/"$SERVICE"-*-SNAPSHOT.jar /app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 paymesh
USER paymesh
WORKDIR /app
COPY --from=build /app.jar app.jar
# Size the heap from the container's memory limit, not the host's
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
