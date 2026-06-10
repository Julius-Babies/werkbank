FROM oven/bun:alpine AS bun-env
FROM caddy:2-alpine AS caddy-source

FROM eclipse-temurin:26-jre-alpine

RUN apk add --no-cache bash jq

RUN apk add --no-cache libstdc++
COPY --from=bun-env /usr/local/bin/bun /usr/local/bin/bun
COPY --from=caddy-source /usr/bin/caddy /usr/bin/caddy

WORKDIR /app
COPY web_build /app/web
COPY server.jar /app/server.jar
COPY deploy /app/deploy

EXPOSE 80

ENTRYPOINT ["bash", "/app/deploy/entrypoint.bash"]