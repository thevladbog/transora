FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY hardware-agent ./hardware-agent
RUN chmod +x gradlew && ./gradlew :hardware-agent:bootJar -x test

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/hardware-agent/build/libs/hardware-agent-*.jar app.jar
EXPOSE 9090
ENTRYPOINT ["java", "-jar", "app.jar"]
