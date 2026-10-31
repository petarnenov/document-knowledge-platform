FROM quay.io/keycloak/keycloak:26.8.1@sha256:3f3c5f8e03c614774b4bd9173b88391fefa4153bfc010d8012d59ad825377332 AS runtime
# Prebuild before using a read-only runtime and the local non-root owner UID.
RUN /opt/keycloak/bin/kc.sh build --db=dev-file --health-enabled=true
COPY --chmod=0555 infra/docker-compose/keycloak-probe.sh /opt/keycloak/probe.sh
USER 1000:1000
HEALTHCHECK --interval=5s --timeout=3s --start-period=40s --retries=20 CMD ["/bin/bash", "/opt/keycloak/probe.sh"]
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
