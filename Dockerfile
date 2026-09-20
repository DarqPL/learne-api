FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x ./mvnw && ./mvnw -B dependency:go-offline

COPY src src
RUN ./mvnw -B clean package -DskipTests
RUN cp target/*.jar /app/backend.jar

FROM eclipse-temurin:21-jre

WORKDIR /app
ENV JAVA_TOOL_OPTIONS="-Xmx768m"

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system appgroup \
    && useradd --system --gid appgroup --home-dir /app --shell /usr/sbin/nologin appuser
COPY --from=build --chown=appuser:appgroup /app/backend.jar /app/backend.jar

EXPOSE 8080

USER appuser
ENTRYPOINT ["java", "-jar", "/app/backend.jar"]
