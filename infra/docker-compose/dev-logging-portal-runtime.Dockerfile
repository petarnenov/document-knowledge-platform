FROM nginxinc/nginx-unprivileged:1.30.2-alpine@sha256:de3e40ec8b7debd7194fc798d4bbfb102c7f8b012b2c73032816b5f72393acdd AS runtime
# Public assets are verified original compiler outputs; never recompile here.
COPY public/ /usr/share/nginx/html/
COPY nginx.conf /etc/nginx/nginx.conf
USER 101:101
EXPOSE 8443 8444
ENTRYPOINT ["nginx", "-g", "daemon off;"]
