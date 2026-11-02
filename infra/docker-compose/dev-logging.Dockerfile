FROM eclipse-temurin:25.0.5_8-jdk@sha256:83f4a7fc27df94dcc7b8de04d14cbbba8da14406f921e966969c3b09000aa507 AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.gradle,sharing=locked ./gradlew --no-daemon :services:dev-logging:bootstrap:installDist

FROM eclipse-temurin:25.0.5_8-jdk@sha256:83f4a7fc27df94dcc7b8de04d14cbbba8da14406f921e966969c3b09000aa507
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/services/dev-logging/bootstrap/build/install/dev-logging/ /app/
USER 10001:10001
EXPOSE 18087
ENTRYPOINT ["/app/bin/dev-logging"]
