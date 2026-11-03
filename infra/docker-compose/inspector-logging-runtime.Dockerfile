# Only a verified original sealed distribution is accepted by log-worker-image.py.
FROM eclipse-temurin:25.0.5_8-jre@sha256:ecdc0adb37bde1a281c4d245a3cd535004b1fc8322d560f5b09463b248c81df0
WORKDIR /app
COPY --chown=10001:10001 distribution/ /app/
USER 10001:10001
EXPOSE 8443
ENTRYPOINT ["/app/bin/structure-inspector"]
