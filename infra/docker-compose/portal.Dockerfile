FROM node:24.22.1-bookworm-slim@sha256:be8204d4bd327363357f532187b752ff62759c31bedf98e9e18fc08c9cbd605c AS build
WORKDIR /workspace
RUN npm install --global pnpm@12.16.1
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml .npmrc ./
COPY patches/ patches/
COPY apps/admin-portal/package.json apps/admin-portal/package.json
RUN pnpm install --frozen-lockfile
COPY generated-clients/typescript generated-clients/typescript
COPY apps/admin-portal apps/admin-portal
RUN pnpm --filter admin-portal build

FROM nginxinc/nginx-unprivileged:1.30.2-alpine@sha256:de3e40ec8b7debd7194fc798d4bbfb102c7f8b012b2c73032816b5f72393acdd AS runtime
COPY --from=build /workspace/apps/admin-portal/dist /usr/share/nginx/html
COPY --chmod=0444 infra/docker-compose/nginx.conf /etc/nginx/conf.d/default.conf
USER 101:101
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=10s --retries=6 CMD wget -q -O /dev/null http://127.0.0.1:8080/
