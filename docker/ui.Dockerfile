# syntax=docker/dockerfile:1
FROM node:22-alpine AS build
WORKDIR /ui
COPY aauth-full-java-demo/supply-chain-ui/package.json aauth-full-java-demo/supply-chain-ui/package-lock.json ./
RUN npm ci --ignore-scripts
COPY aauth-full-java-demo/supply-chain-ui .
ARG VITE_API_BASE_URL=http://portal.uma.lab:8000
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
RUN npm run build

FROM nginx:alpine
COPY --from=build /ui/dist /usr/share/nginx/html
COPY aauth-full-java-demo/docker/nginx.conf /etc/nginx/conf.d/default.conf
