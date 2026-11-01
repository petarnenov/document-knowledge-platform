FROM nginxinc/nginx-unprivileged:1.30.2-alpine@sha256:de3e40ec8b7debd7194fc798d4bbfb102c7f8b012b2c73032816b5f72393acdd
COPY infra/docker-compose/kb-dm-sharing.conf /etc/nginx/conf.d/default.conf
USER 101:101
HEALTHCHECK --interval=5s --timeout=3s --start-period=10s --retries=10 CMD wget -q -O /dev/null http://127.0.0.1:8080/health
