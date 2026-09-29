# Independently deployable backend. Needs PostgreSQL and Kafka. Runs with the "prod" profile, so
# ProductionReadinessCheck refuses unsafe configuration at startup (see PRODUCTION_GUARDRAILS.md).
#   docker build -t account-upgrade-backend .
#   docker run -p 8080:8080 \
#     -e SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/account_upgrade -e SPRING_DATASOURCE_PASSWORD=... \
#     -e SPRING_KAFKA_BOOTSTRAP_SERVERS=b1:9092,b2:9092,b3:9092 \
#     -e UPGRADE_MESSAGING_KAFKA_REPLICATION_FACTOR=3 -e UPGRADE_MESSAGING_KAFKA_MIN_INSYNC_REPLICAS=2 \
#     account-upgrade-backend
# Amazon MSK with TLS: -e SPRING_KAFKA_SECURITY_PROTOCOL=SSL
# Browser access from another origin: -e UPGRADE_WEB_CORS_ALLOWED_ORIGINS=https://app.example.com

FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
# Tests and the coverage gate run in CI (mvn verify); the image build only packages.
RUN ./mvnw -B -q package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build --chown=root:root /app/target/account-upgrade-service-*.jar app.jar
USER app
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health/liveness | grep -q '"UP"' || exit 1
# Bounded heap, crash (and let the orchestrator restart) on OOM instead of limping on.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
