# Reuse the already-selected Python/coverage pins for runner tests in both local and CI builds.
FROM python:3.14.10-trixie@sha256:faf7953db05ee8e060aa9fda8a389e5779cdba26a22e1363379861023e910830 AS runner-tests
COPY tools/ci/python-runtime-requirements.txt /opt/dkp/requirements.txt
RUN python -m pip install --no-cache-dir --disable-pip-version-check --only-binary=:all: --require-hashes -r /opt/dkp/requirements.txt
WORKDIR /workspace
COPY --chown=10001:0 . .
ENV PYTHONDONTWRITEBYTECODE=1 COVERAGE_CORE=ctrace COVERAGE_FILE=/tmp/java-dm-runner.coverage
USER 10001:0
RUN python -m coverage run --branch -m unittest discover -s tools/ci -p test_java_dm_linux.py && python -m coverage json -o /tmp/java-dm-runner-coverage.json && python -c 'import json; s=json.load(open("/tmp/java-dm-runner-coverage.json"))["files"]["tools/ci/java-dm-linux.py"]["summary"]; print(s); assert s["covered_lines"]/s["num_statements"]>=.75 and s["covered_branches"]/s["num_branches"]>=.75'
FROM eclipse-temurin:25.0.5_8-jdk@sha256:83f4a7fc27df94dcc7b8de04d14cbbba8da14406f921e966969c3b09000aa507
COPY --from=runner-tests /tmp/java-dm-runner-coverage.json /opt/dkp/java-dm-runner-coverage.json
WORKDIR /workspace
COPY --chown=10001:0 . .
RUN find /workspace -type d -exec chmod 0755 {} + && chown 10001:0 /workspace && mkdir -m 0700 /tmp/dkp-java-gradle && chown 10001:0 /tmp/dkp-java-gradle
ENV GRADLE_USER_HOME=/tmp/dkp-java-gradle
USER 10001:0
RUN ./gradlew --no-daemon --max-workers=2 :services:document-management:bootstrap:testClasses :services:document-management:adapters-out:testClasses
CMD ["bash", "-c", "java -version && bash tools/ci/test-java-dm-check.sh && bash tools/ci/java-dm-check.sh"]
