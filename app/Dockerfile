FROM node:24-alpine
ARG APP_VERSION=
LABEL org.opencontainers.image.title="FileCloud" org.opencontainers.image.description="Self-hosted files, sharing, calendar and contacts"
WORKDIR /app
ENV NODE_ENV=production APP_VERSION=$APP_VERSION
COPY package.json package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force
COPY src ./src
COPY public ./public
RUN mkdir -p /data && chown node:node /data
USER node
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s CMD wget -qO- http://127.0.0.1:8080/status || exit 1
CMD ["node", "--disable-warning=ExperimentalWarning", "src/server.ts"]
