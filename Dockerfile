# Build stage
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /app

# Copy maven wrapper and pom.xml
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Grant execution rights to the wrapper and convert line endings if needed
RUN sed -i 's/\r$//' mvnw
RUN chmod +x mvnw
# Run a dependency check/fetch to cache dependencies (optional but recommended)
# Using package skipping tests to build the artifact
COPY src ./src
RUN ./mvnw clean package -DskipTests

# Run stage
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Copy the built jar from the build stage
# The jar name is based on the artifactId and version in pom.xml
COPY --from=build /app/target/sistema-hotelero-0.0.1-SNAPSHOT.jar app.jar

# Expose the port defined in application.properties
EXPOSE 8084

# Command to run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
