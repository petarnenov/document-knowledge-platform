FROM eclipse-temurin:25.0.5_8-jdk@sha256:83f4a7fc27df94dcc7b8de04d14cbbba8da14406f921e966969c3b09000aa507 AS build
WORKDIR /workspace
COPY . .
ARG SERVICE
RUN case "$SERVICE" in document-management|knowledge-base|knowledge-search|browser-bff) ;; *) exit 1 ;; esac
# Gradle's writable cache/daemon registry cannot coordinate across build containers.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked ./gradlew --no-daemon ":services:${SERVICE}:bootstrap:installDist"

FROM eclipse-temurin:25.0.5_8-jre@sha256:ecdc0adb37bde1a281c4d245a3cd535004b1fc8322d560f5b09463b248c81df0 AS runtime
ARG SERVICE
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/services/${SERVICE}/bootstrap/build/install/${SERVICE}/ /app/
COPY --chmod=0555 infra/docker-compose/probe.sh /app/probe.sh
ENV SERVICE_NAME=${SERVICE}
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=30s --retries=12 CMD ["/app/probe.sh"]
ENTRYPOINT ["/bin/sh", "-c", "exec /app/bin/\"$SERVICE_NAME\""]
