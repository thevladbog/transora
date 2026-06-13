FROM golang:1.22-bookworm AS build
WORKDIR /src
COPY station-agent/go.mod station-agent/go.sum ./
RUN go mod download
COPY station-agent/ ./
RUN CGO_ENABLED=1 go build -o /station-agent ./cmd/agent

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl libsqlite3-0 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /station-agent /app/station-agent
COPY station-agent/config.yaml /app/config.yaml
COPY station-agent/docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh && mkdir -p /app/data
EXPOSE 8081
ENTRYPOINT ["/app/docker-entrypoint.sh"]
