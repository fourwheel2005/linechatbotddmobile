# Use Eclipse Temurin (More stable than the official 'gradle' image)
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /app

# Copy wrapper files first
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./

# specific permission fix for the wrapper
RUN chmod +x ./gradlew

# Download dependencies (cached layer)
RUN ./gradlew build -x test --no-daemon || return 0

# Copy source and build
COPY src ./src
RUN ./gradlew clean bootJar --no-daemon

# --- Stage 2: Runtime ---
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
ARG APP_BUILD_COMMIT=unknown
ENV APP_BUILD_COMMIT=${APP_BUILD_COMMIT}
LABEL org.opencontainers.image.revision=${APP_BUILD_COMMIT}
RUN useradd -ms /bin/sh javauser
USER javauser
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
