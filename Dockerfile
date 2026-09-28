# ---------- Stage 1: build the JAR with Maven ----------
FROM maven:3.9-amazoncorretto-21 AS build
WORKDIR /build

# Copy the POM first so dependency downloads are cached between builds
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests are run separately (mvn verify); skipping them keeps the image build fast and deterministic
RUN mvn -B -q package -DskipTests

# ---------- Stage 2: runtime image (Amazon Corretto 21) ----------
FROM amazoncorretto:21
WORKDIR /app

COPY --from=build /build/target/webframework.jar app.jar

# Do not run as root inside the container (numeric UID: the base image has no useradd)
USER 1000:1000

# Secure defaults for the image. All can be overridden with `docker run -e ...`.
# APP_ENV=production keeps the /shutdown route disabled even if the platform forgets to set it.
ENV PORT=8080 \
    APP_ENV=production \
    WORKER_THREADS=16 \
    SHUTDOWN_TIMEOUT_SECONDS=10

EXPOSE 8080

# Exec form: java is PID 1 and receives SIGTERM from `docker stop`, which triggers the
# framework's graceful shutdown hook.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
