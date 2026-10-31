FROM eclipse-temurin:25.0.5_8-jre@sha256:ecdc0adb37bde1a281c4d245a3cd535004b1fc8322d560f5b09463b248c81df0
# The trusted namespace owner holds NET_ADMIN; the parser container holds no capabilities.
RUN apt-get update && apt-get install -y --no-install-recommends iptables=1.8.11-2ubuntu3 && rm -rf /var/lib/apt/lists/*
COPY --chmod=0555 infra/docker-compose/inspector-network.sh /usr/local/bin/inspector-network
ENTRYPOINT ["/usr/local/bin/inspector-network"]
HEALTHCHECK --interval=2s --timeout=1s --retries=5 CMD ["test", "-f", "/tmp/firewall-ready"]
