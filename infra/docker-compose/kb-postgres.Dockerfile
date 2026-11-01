FROM postgres:18.6@sha256:979bccfde35445db618c65107d27d08c6c21339d8dc3ad4c830c6633947be0e4
COPY --chmod=0444 infra/docker-compose/kb-postgres-init.sql /docker-entrypoint-initdb.d/010-kb.sql
COPY --chmod=0555 infra/docker-compose/kb-postgres-probe.sh /usr/local/bin/dkp-kb-probe
COPY --chmod=0444 infra/docker-compose/kb-database-check.sql /usr/local/share/dkp-kb-database-check.sql
USER 999:999
HEALTHCHECK --interval=5s --timeout=5s --start-period=15s --retries=20 CMD ["/usr/local/bin/dkp-kb-probe"]
