FROM python:3.14.10-trixie@sha256:faf7953db05ee8e060aa9fda8a389e5779cdba26a22e1363379861023e910830
COPY tools/ci/python-runtime-requirements.txt /opt/dkp/requirements.txt
RUN python -m pip install --no-cache-dir --disable-pip-version-check --only-binary=:all: --require-hashes -r /opt/dkp/requirements.txt
WORKDIR /workspace
COPY --chmod=0444 tools/ci/verify.py tools/ci/bp03-aggregate-linux.py tools/ci/bp03-aggregate.Dockerfile tools/ci/test_bp03_aggregate_linux.py tools/ci/bp03-aggregate-selftest.sh tools/ci/
COPY --chmod=0444 .github/workflows/bp03-aggregate.yml .github/workflows/foundation.yml .github/workflows/
RUN find /workspace -type d -exec chmod 0755 {} +
ENV HOME=/tmp PYTHONDONTWRITEBYTECODE=1 COVERAGE_CORE=ctrace COVERAGE_FILE=/tmp/dkp222-controller.coverage
USER 10001:10001
CMD ["bash", "tools/ci/bp03-aggregate-selftest.sh"]
