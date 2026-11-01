FROM eclipse-temurin:25.0.5_8-jdk@sha256:83f4a7fc27df94dcc7b8de04d14cbbba8da14406f921e966969c3b09000aa507 AS jdk
FROM python:3.14.10-trixie@sha256:faf7953db05ee8e060aa9fda8a389e5779cdba26a22e1363379861023e910830
COPY --from=jdk /opt/java/openjdk /opt/java/openjdk
COPY python-runtime-requirements.txt /opt/dkp/requirements.txt
RUN python -m pip install --no-cache-dir --disable-pip-version-check --only-binary=:all: --require-hashes -r /opt/dkp/requirements.txt
COPY --chmod=0444 python_runtime_inside.py verify.py test_verify.py python-runtime-pins.json python-runtime.Dockerfile python-runtime-requirements.txt /opt/dkp/
ENV JAVA_HOME=/opt/java/openjdk PATH=/opt/java/openjdk/bin:$PATH HOME=/tmp PYTHONDONTWRITEBYTECODE=1 COVERAGE_CORE=ctrace COVERAGE_FILE=/tmp/dkp.coverage
WORKDIR /workspace
USER 10001:10001
ENTRYPOINT ["python", "/opt/dkp/python_runtime_inside.py"]
