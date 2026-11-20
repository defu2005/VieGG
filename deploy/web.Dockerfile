# Build web (Vite) rồi đóng gói cùng Caddy. Build context là thư mục gốc repo.
FROM node:22-alpine AS web
WORKDIR /src
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM caddy:2.10-alpine
COPY --from=web /src/dist /srv/web
