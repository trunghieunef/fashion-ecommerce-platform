# Local S1 image. Build the JAR on the host first: ./mvnw -pl services/user-service -am package -DskipTests
FROM eclipse-temurin:21.0.12.1_1-jre-noble@sha256:817f192b914584dac49595ae662461250b21aaa31df8698feac767d08defb3d0
USER 10001:10001
COPY target/user-service.jar /app/app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
