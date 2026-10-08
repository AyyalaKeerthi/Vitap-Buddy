# Stage 1: Build the Maven application
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app

# Copy Maven configuration
COPY pom.xml .

# Copy source code
COPY src ./src

# Build the JAR
RUN mvn clean package


# Stage 2: Run the application
FROM eclipse-temurin:17-jre

WORKDIR /app

# Copy the JAR from the build stage
COPY --from=build /app/target/vitap-buddy-1.0.0.jar app.jar

# Application uses port 8080 by default
EXPOSE 8090

# Start the application
ENTRYPOINT ["java", "-jar", "app.jar"]