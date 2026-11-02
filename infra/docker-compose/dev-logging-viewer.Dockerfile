FROM python:3.14.10-trixie@sha256:faf7953db05ee8e060aa9fda8a389e5779cdba26a22e1363379861023e910830
WORKDIR /app
COPY tools/runtime/dev-log-viewer.py /app/dev-log-viewer.py
USER 10001:10001
EXPOSE 18088
ENTRYPOINT ["python", "-I", "-B", "/app/dev-log-viewer.py"]
