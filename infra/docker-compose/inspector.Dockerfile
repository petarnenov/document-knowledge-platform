FROM eclipse-temurin:25.0.5_8-jdk@sha256:83f4a7fc27df94dcc7b8de04d14cbbba8da14406f921e966969c3b09000aa507 AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon --max-workers=2 :workers:structure-inspector:installDist

FROM eclipse-temurin:25.0.5_8-jre@sha256:ecdc0adb37bde1a281c4d245a3cd535004b1fc8322d560f5b09463b248c81df0
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/workers/structure-inspector/build/install/structure-inspector/ /app/
USER 10001:10001
EXPOSE 8443
ENTRYPOINT ["/app/bin/structure-inspector"]
