FROM eclipse-temurin:25.0.5_8-jdk@sha256:83f4a7fc27df94dcc7b8de04d14cbbba8da14406f921e966969c3b09000aa507
WORKDIR /workspace
COPY --chown=10001:0 . .
RUN find /workspace -type d -exec chmod 0755 {} + && chown 10001:0 /workspace && mkdir -m 0700 /tmp/dkp-aggregate-gradle && chown 10001:0 /tmp/dkp-aggregate-gradle
ENV GRADLE_USER_HOME=/tmp/dkp-aggregate-gradle
USER 10001:0
# Resolve the exact locked compilation/test inputs before the network-free test run.
RUN ./gradlew --no-daemon --max-workers=2 :services:browser-bff:bootstrap:prepareAggregateVerification
CMD ["bash", "tools/ci/bp03-aggregate-check.sh"]
