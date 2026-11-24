# ── Build ────────────────────────────────────────────────────────────
#
# `npm ci` and not `npm install`: it installs exactly what
# package-lock.json says, so the image is built from the versions that
# were tested rather than whatever the registry offers today.
FROM node:24-alpine AS build

WORKDIR /app

# The lockfile and manifest first, on their own layer. They change far
# less often than the source, so the install is cached across every build
# that only touched a component.
COPY package.json package-lock.json ./
RUN npm ci

COPY tsconfig*.json vite.config.ts index.html ./
COPY src ./src

# `tsc -b && vite build`: the type check is part of the build on purpose.
# An image that builds from code that does not type-check is an image
# that fails later and further away.
RUN npm run build

# ── Serve ────────────────────────────────────────────────────────────
#
# nginx and not `vite preview`, and not a static bucket either: this
# container also proxies the API so the browser sees one origin, which is
# what keeps the session cookie working. See nginx.conf.
FROM nginx:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80

# Asks for the app's own HTML rather than for the API: this container is
# healthy when it can serve the SPA, and whether the API is up is the
# API's healthcheck to answer.
HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -qO /dev/null http://localhost/index.html || exit 1
