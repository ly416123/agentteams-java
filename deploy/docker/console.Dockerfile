FROM node:22-alpine AS build
WORKDIR /workspace/console
COPY console/package*.json ./
ARG NPM_REGISTRY=https://registry.npmjs.org/
RUN npm ci --registry="$NPM_REGISTRY"
COPY console/ ./
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY deploy/docker/console-nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/console/dist /usr/share/nginx/html
USER 101
EXPOSE 8080
