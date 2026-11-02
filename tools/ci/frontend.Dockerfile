FROM node:24.22.1-bookworm-slim@sha256:be8204d4bd327363357f532187b752ff62759c31bedf98e9e18fc08c9cbd605c AS node-toolchain
FROM mcr.microsoft.com/playwright:v1.64.1-noble@sha256:47b510dd9230bf0b9bfc9b20102241e577ea7a99334ab4c3ca19f19770e9b8ea
# Disposable frontend verification only. Toolchain changes affect this image alone.
COPY --from=node-toolchain /usr/local/bin/node /usr/local/bin/node
RUN npm install --global pnpm@12.16.1 \
    && mkdir -p /workspace /evidence /home/frontend \
    && chown -R 10001:10001 /workspace /evidence /home/frontend
WORKDIR /workspace
COPY --chown=10001:10001 . .
ENV HOME=/home/frontend CI=true PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
USER 10001:10001
ENTRYPOINT ["node", "tools/ci/frontend-check.mjs"]
