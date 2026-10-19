# FileCloud all-in-one image: PostgreSQL 17 + FileCloud server in one container.
FROM node:24-alpine
ARG APP_VERSION=
LABEL org.opencontainers.image.title="FileCloud" org.opencontainers.image.description="Self-hosted files, sharing, calendar and contacts"

RUN apk add --no-cache postgresql17 postgresql17-client tini su-exec

WORKDIR /app
ENV NODE_ENV=production APP_VERSION=$APP_VERSION DATA_DIR=/data DB_DIR=/var/lib/filecloud/db
COPY package.json package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force
COPY src ./src
COPY public ./public
COPY docker/entrypoint.sh /usr/local/bin/filecloud-entrypoint
COPY docker/pg_hba.conf /etc/filecloud/pg_hba.conf
RUN mkdir -p /data /var/lib/filecloud/db /run/filecloud && chown node:node /data /var/lib/filecloud/db /run/filecloud

VOLUME ["/data", "/var/lib/filecloud/db"]
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s CMD wget -qO- http://127.0.0.1:8080/status || exit 1
ENTRYPOINT ["/sbin/tini", "--", "filecloud-entrypoint"]
