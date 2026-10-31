FROM postgres:18.6@sha256:979bccfde35445db618c65107d27d08c6c21339d8dc3ad4c830c6633947be0e4
COPY --chmod=0444 infra/docker-compose/dm-postgres-init.sql /docker-entrypoint-initdb.d/010-dm.sql
COPY --chmod=0555 infra/docker-compose/dm-postgres-probe.sh /usr/local/bin/dkp-dm-probe
# Official Debian PostgreSQL image's postgres account; no root/chown capability.
USER 999:999
HEALTHCHECK --interval=5s --timeout=5s --start-period=15s --retries=20 CMD ["/usr/local/bin/dkp-dm-probe"]
