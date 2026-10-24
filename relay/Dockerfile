# Aster Relay without a Cloudflare account: the same Worker under workerd,
# with Durable Object state on /data. Put a TLS proxy in front of it.
FROM node:22-slim
WORKDIR /relay
COPY package.json package-lock.json ./
RUN npm ci
COPY wrangler.jsonc tsconfig.json worker-configuration.d.ts ./
COPY src ./src
COPY public ./public
ENV RELAY_HOST=""
EXPOSE 8787
VOLUME /data
CMD ["sh", "-c", "exec npx wrangler dev --ip 0.0.0.0 --port 8787 --persist-to /data --var RELAY_HOST:\"$RELAY_HOST\""]
