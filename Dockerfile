# ---------- Stage 1: build the JAR with Maven ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Copy the POM first so dependency downloads are cached between builds
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests are run separately (mvn verify); skipping them keeps the image build fast and deterministic
RUN mvn -B -q package -DskipTests

# ---------- Stage 2: minimal runtime image ----------
FROM eclipse-temurin:17-jre
WORKDIR /app

# Do not run as root inside the container
RUN useradd --system --no-create-home appuser
COPY --from=build /build/target/webframework.jar app.jar
USER appuser

# Secure defaults for the image. Both can be overridden with `docker run -e ...`.
# APP_ENV=production keeps the /shutdown route disabled even if the platform forgets to set it.
ENV PORT=8080 \
    APP_ENV=production

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
