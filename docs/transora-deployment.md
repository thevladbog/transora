# Transora — Deployment
## Схема развёртывания: Kubernetes, Docker Compose, сетевая топология

> Версия: 1.0 | Статус: Черновик | Модуль: `deployment`

---

## Содержание

1. [Обзор инфраструктуры](#1-обзор-инфраструктуры)
2. [Сетевая топология](#2-сетевая-топология)
3. [Docker Compose — локальная разработка](#3-docker-compose--локальная-разработка)
4. [Kubernetes — продакшн](#4-kubernetes--продакшн)
5. [Манифесты сервисов ядра](#5-манифесты-сервисов-ядра)
6. [Конфигурация Kong API Gateway](#6-конфигурация-kong-api-gateway)
7. [Базы данных и брокер сообщений](#7-базы-данных-и-брокер-сообщений)
8. [Порядок запуска и зависимости](#8-порядок-запуска-и-зависимости)
9. [Переменные окружения](#9-переменные-окружения)
10. [Мониторинг и алертинг](#10-мониторинг-и-алертинг)
11. [CI/CD Pipeline](#11-cicd-pipeline)
12. [Процедуры обслуживания](#12-процедуры-обслуживания)

---

## 1. Обзор инфраструктуры

### 1.1 Компоненты системы

```
┌─────────────────────────────────────────────────────────────────────┐
│                         ЯДРО СИСТЕМЫ                                │
│                    (Kubernetes Cluster)                             │
│                                                                     │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────────┐  │
│  │ Kong Gateway │  │ iam-service  │  │   Core Services          │  │
│  │  (Ingress)   │  │  (Kotlin)    │  │                          │  │
│  └──────────────┘  └──────────────┘  │  scheduling-service      │  │
│                                      │  inventory-service       │  │
│  ┌──────────────────────────────┐    │  sales-service           │  │
│  │     Infrastructure           │    │  document-service        │  │
│  │                              │    │  notification-service    │  │
│  │  PostgreSQL (HA)             │    └──────────────────────────┘  │
│  │  NATS JetStream (cluster)    │                                   │
│  │  Redis Sentinel              │    ┌──────────────────────────┐  │
│  │  MinIO (distributed)         │    │  Support Services        │  │
│  └──────────────────────────────┘    │                          │  │
│                                      │  Prometheus + Grafana    │  │
│                                      │  Loki (logs)             │  │
│                                      │  Jaeger (traces)         │  │
│                                      └──────────────────────────┘  │
└────────────────────────────┬────────────────────────────────────────┘
                             │  WSS / HTTPS
          ┌──────────────────┼──────────────────┐
          │                  │                  │
   ┌──────▼──────┐    ┌──────▼──────┐    ┌──────▼──────┐
   │  Вокзал А   │    │  Вокзал Б   │    │  Вокзал N   │
   │             │    │             │    │             │
   │ station-    │    │ station-    │    │ station-    │
   │ agent       │    │ agent       │    │ agent       │
   │             │    │             │    │             │
   │ Кассы       │    │ Кассы       │    │ Кассы       │
   │ Табло       │    │ Табло       │    │ Табло       │
   │ ТСД         │    │ ТСД         │    │ ТСД         │
   └─────────────┘    └─────────────┘    └─────────────┘
```

### 1.2 Характеристики кластера (минимум для продакшна)

| Компонент | Количество | CPU | RAM | Диск |
|-----------|-----------|-----|-----|------|
| K8s Master nodes | 3 | 2 vCPU | 4 GB | 50 GB SSD |
| K8s Worker nodes | 3 | 4 vCPU | 8 GB | 100 GB SSD |
| PostgreSQL HA | 3 | 4 vCPU | 8 GB | 200 GB SSD |
| NATS cluster | 3 | 2 vCPU | 4 GB | 50 GB SSD |
| Redis Sentinel | 3 | 1 vCPU | 2 GB | 20 GB SSD |
| MinIO | 4 | 2 vCPU | 4 GB | 500 GB HDD |

---

## 2. Сетевая топология

### 2.1 Полная схема сети

```
ИНТЕРНЕТ
    │
    │ 443/TCP (HTTPS, WSS)
    ▼
┌───────────────────────────────────────────────────────────────────┐
│  Load Balancer (внешний IP)                                       │
│  HAProxy / Cloud LB                                               │
└───────────────────────────────┬───────────────────────────────────┘
                                │
                                │
┌───────────────────────────────▼───────────────────────────────────┐
│                    DMZ / Ingress Zone                             │
│                                                                   │
│  ┌─────────────────────────────────────────────────────────────┐  │
│  │              Kong API Gateway (NodePort 443)                │  │
│  │                                                             │  │
│  │  Routes:                                                    │  │
│  │  /auth/*          → iam-service:8080                        │  │
│  │  /api/schedule/*  → scheduling-service:8080                 │  │
│  │  /api/inventory/* → inventory-service:8080                  │  │
│  │  /api/sales/*     → sales-service:8080                      │  │
│  │  /api/documents/* → document-service:8080                   │  │
│  │  /api/boarding/*  → boarding handler (via sales)            │  │
│  │  /ws/stations     → notification-service:8082 (WebSocket)   │  │
│  └─────────────────────────────────────────────────────────────┘  │
└───────────────────────────────┬───────────────────────────────────┘
                                │
┌───────────────────────────────▼───────────────────────────────────┐
│                    Services Zone (K8s internal)                   │
│                    Namespace: transora-core                       │
│                                                                   │
│  iam-service          :8080  ←──── JWT issuer, user mgmt         │
│  scheduling-service   :8080  ←──── Маршруты, рейсы               │
│  inventory-service    :8080  ←──── Инвентарь мест                │
│  sales-service        :8080  ←──── Продажи, возвраты             │
│  document-service     :8080  ←──── Генерация PDF                 │
│  notification-service :8082  ←──── Табло, аудио, WebSocket       │
└───────────────────────────────┬───────────────────────────────────┘
                                │
┌───────────────────────────────▼───────────────────────────────────┐
│                    Data Zone (K8s internal)                       │
│                    Namespace: transora-data                       │
│                                                                   │
│  postgresql-primary   :5432  ←──── Основная БД                   │
│  postgresql-replica   :5433  ←──── Read-only replica             │
│  nats-cluster         :4222  ←──── JetStream брокер              │
│  redis-master         :6379  ←──── Кеш, blacklist, очереди       │
│  minio                :9000  ←──── Объектное хранилище           │
└───────────────────────────────────────────────────────────────────┘

═══════════════════ WAN / VPN туннель ════════════════════════

┌──────────────────────────────────────────────────────────┐
│  Вокзал (LAN 192.168.X.0/24)                            │
│                                                          │
│  station-agent :8080  ─── WSS ──► Kong :443             │
│                                                          │
│  Кассовые ПК:                                           │
│    hardware-agent    :9090  (localhost only)             │
│    cashier-app (Tauri) ──HTTP──► station-agent :8080     │
│                                                          │
│  Прочие устройства:                                     │
│    Табло (браузер) ──HTTP──► station-agent :8080         │
│    ТСД (Android)  ──HTTP──► station-agent :8080          │
└──────────────────────────────────────────────────────────┘
```

### 2.2 Kubernetes Namespaces

| Namespace | Назначение |
|-----------|-----------|
| `transora-core` | Бизнес-сервисы |
| `transora-data` | PostgreSQL, NATS, Redis, MinIO |
| `transora-infra` | Kong, Prometheus, Grafana, Loki, Jaeger |
| `transora-ops` | Служебные задачи (миграции, cron) |

---

## 3. Docker Compose — локальная разработка

### 3.1 `docker-compose.yml`

```yaml
version: "3.9"

# ── Общие настройки ──────────────────────────────────────────────────
x-common-env: &common-env
  TZ: Asia/Krasnoyarsk
  JAVA_OPTS: "-Xms256m -Xmx512m"

x-kotlin-service: &kotlin-service
  restart: unless-stopped
  networks:
    - transora-net
  depends_on:
    postgres:
      condition: service_healthy
    nats:
      condition: service_healthy
    redis:
      condition: service_healthy

# ── Сервисы ──────────────────────────────────────────────────────────
services:

  # ── Инфраструктура ────────────────────────────────────────────────

  postgres:
    image: postgres:15-alpine
    environment:
      POSTGRES_USER: transora
      POSTGRES_PASSWORD: transora_dev
      POSTGRES_DB: transora
    volumes:
      - postgres_data:/var/lib/postgresql/data
      - ./infra/postgres/init:/docker-entrypoint-initdb.d
    ports:
      - "5432:5432"
    networks:
      - transora-net
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U transora"]
      interval: 5s
      timeout: 3s
      retries: 10

  nats:
    image: nats:2.10-alpine
    command: >
      -js
      -sd /data
      -m 8222
      --cluster_name transora-dev
    volumes:
      - nats_data:/data
    ports:
      - "4222:4222"
      - "8222:8222"   # Management UI
    networks:
      - transora-net
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8222/healthz"]
      interval: 5s
      timeout: 3s
      retries: 10

  redis:
    image: redis:7-alpine
    command: redis-server --appendonly yes
    volumes:
      - redis_data:/data
    ports:
      - "6379:6379"
    networks:
      - transora-net
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 10

  minio:
    image: minio/minio:latest
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: transora
      MINIO_ROOT_PASSWORD: transora_dev
    volumes:
      - minio_data:/data
    ports:
      - "9000:9000"
      - "9001:9001"   # Console
    networks:
      - transora-net
    healthcheck:
      test: ["CMD", "mc", "ready", "local"]
      interval: 10s
      timeout: 5s
      retries: 5

  # ── Бизнес-сервисы ────────────────────────────────────────────────

  iam-service:
    <<: *kotlin-service
    build:
      context: ./iam-service
      dockerfile: Dockerfile
    environment:
      <<: *common-env
      DB_URL: jdbc:postgresql://postgres:5432/transora
      DB_SCHEMA: iam
      DB_USERNAME: transora
      DB_PASSWORD: transora_dev
      REDIS_HOST: redis
      REDIS_PORT: 6379
      NATS_URL: nats://nats:4222
      JWT_PRIVATE_KEY_PATH: /secrets/private.pem
      JWT_PUBLIC_KEY_PATH: /secrets/public.pem
      SERVER_PORT: 8080
    volumes:
      - ./infra/keys:/secrets:ro
    ports:
      - "8081:8080"

  scheduling-service:
    <<: *kotlin-service
    build:
      context: ./scheduling-service
    environment:
      <<: *common-env
      DB_URL: jdbc:postgresql://postgres:5432/transora
      DB_SCHEMA: scheduling
      DB_USERNAME: transora
      DB_PASSWORD: transora_dev
      NATS_URL: nats://nats:4222
      IAM_JWKS_URL: http://iam-service:8080/auth/jwks.json
      SERVER_PORT: 8080
    ports:
      - "8082:8080"
    depends_on:
      iam-service:
        condition: service_started

  inventory-service:
    <<: *kotlin-service
    build:
      context: ./inventory-service
    environment:
      <<: *common-env
      DB_URL: jdbc:postgresql://postgres:5432/transora
      DB_SCHEMA: inventory
      DB_USERNAME: transora
      DB_PASSWORD: transora_dev
      REDIS_HOST: redis
      REDIS_PORT: 6379
      NATS_URL: nats://nats:4222
      IAM_JWKS_URL: http://iam-service:8080/auth/jwks.json
      SERVER_PORT: 8080
    ports:
      - "8083:8080"

  sales-service:
    <<: *kotlin-service
    build:
      context: ./sales-service
    environment:
      <<: *common-env
      DB_URL: jdbc:postgresql://postgres:5432/transora
      DB_SCHEMA: sales
      DB_USERNAME: transora
      DB_PASSWORD: transora_dev
      NATS_URL: nats://nats:4222
      IAM_JWKS_URL: http://iam-service:8080/auth/jwks.json
      INVENTORY_URL: http://inventory-service:8080
      SERVER_PORT: 8080
    ports:
      - "8084:8080"

  document-service:
    <<: *kotlin-service
    build:
      context: ./document-service
    environment:
      <<: *common-env
      DB_URL: jdbc:postgresql://postgres:5432/transora
      DB_SCHEMA: documents
      DB_USERNAME: transora
      DB_PASSWORD: transora_dev
      NATS_URL: nats://nats:4222
      IAM_JWKS_URL: http://iam-service:8080/auth/jwks.json
      MINIO_ENDPOINT: http://minio:9000
      MINIO_ACCESS_KEY: transora
      MINIO_SECRET_KEY: transora_dev
      MINIO_BUCKET_DOCS: transora-documents
      MINIO_BUCKET_TMPL: transora-templates
      SCHEDULING_URL: http://scheduling-service:8080
      SALES_URL: http://sales-service:8080
      SERVER_PORT: 8080
    ports:
      - "8085:8080"

  notification-service:
    build:
      context: ./notification-service
      dockerfile: Dockerfile.go
    restart: unless-stopped
    environment:
      TZ: Asia/Krasnoyarsk
      DB_DSN: "postgres://transora:transora_dev@postgres:5432/transora?search_path=notifications"
      REDIS_ADDR: redis:6379
      NATS_URL: nats://nats:4222
      MINIO_ENDPOINT: minio:9000
      MINIO_ACCESS_KEY: transora
      MINIO_SECRET_KEY: transora_dev
      MINIO_BUCKET_AUDIO: transora-audio
      YANDEX_TTS_API_KEY: "${YANDEX_TTS_API_KEY}"
      YANDEX_TTS_FOLDER_ID: "${YANDEX_TTS_FOLDER_ID}"
      SCHEDULING_URL: http://scheduling-service:8080
      LISTEN_ADDR: ":8082"
    ports:
      - "8086:8082"
    networks:
      - transora-net
    depends_on:
      postgres:
        condition: service_healthy
      nats:
        condition: service_healthy

  # ── Kong API Gateway ──────────────────────────────────────────────

  kong-db:
    image: postgres:15-alpine
    environment:
      POSTGRES_USER: kong
      POSTGRES_PASSWORD: kong_dev
      POSTGRES_DB: kong
    volumes:
      - kong_db_data:/var/lib/postgresql/data
    networks:
      - transora-net
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U kong"]
      interval: 5s
      retries: 10

  kong-migration:
    image: kong:3.6
    command: kong migrations bootstrap
    environment:
      KONG_DATABASE: postgres
      KONG_PG_HOST: kong-db
      KONG_PG_USER: kong
      KONG_PG_PASSWORD: kong_dev
      KONG_PG_DATABASE: kong
    networks:
      - transora-net
    depends_on:
      kong-db:
        condition: service_healthy
    restart: on-failure

  kong:
    image: kong:3.6
    environment:
      KONG_DATABASE: postgres
      KONG_PG_HOST: kong-db
      KONG_PG_USER: kong
      KONG_PG_PASSWORD: kong_dev
      KONG_PG_DATABASE: kong
      KONG_PROXY_LISTEN: "0.0.0.0:8000, 0.0.0.0:8443 ssl"
      KONG_ADMIN_LISTEN: "0.0.0.0:8001"
      KONG_PROXY_ACCESS_LOG: /dev/stdout
      KONG_ADMIN_ACCESS_LOG: /dev/stdout
    ports:
      - "8000:8000"   # HTTP proxy
      - "8443:8443"   # HTTPS proxy
      - "8001:8001"   # Admin API
    networks:
      - transora-net
    depends_on:
      kong-migration:
        condition: service_completed_successfully
    healthcheck:
      test: ["CMD", "kong", "health"]
      interval: 10s
      retries: 5

  # ── Observability ─────────────────────────────────────────────────

  prometheus:
    image: prom/prometheus:latest
    volumes:
      - ./infra/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - prometheus_data:/prometheus
    ports:
      - "9090:9090"
    networks:
      - transora-net

  grafana:
    image: grafana/grafana:latest
    environment:
      GF_SECURITY_ADMIN_PASSWORD: admin
    volumes:
      - grafana_data:/var/lib/grafana
      - ./infra/grafana/dashboards:/etc/grafana/provisioning/dashboards:ro
      - ./infra/grafana/datasources:/etc/grafana/provisioning/datasources:ro
    ports:
      - "3000:3000"
    networks:
      - transora-net

# ── Сети и тома ──────────────────────────────────────────────────────

networks:
  transora-net:
    driver: bridge

volumes:
  postgres_data:
  nats_data:
  redis_data:
  minio_data:
  kong_db_data:
  prometheus_data:
  grafana_data:
```

### 3.2 Инициализация БД при старте

```bash
# infra/postgres/init/01-schemas.sql
-- Создать схемы для всех сервисов
CREATE SCHEMA IF NOT EXISTS iam;
CREATE SCHEMA IF NOT EXISTS scheduling;
CREATE SCHEMA IF NOT EXISTS inventory;
CREATE SCHEMA IF NOT EXISTS sales;
CREATE SCHEMA IF NOT EXISTS documents;
CREATE SCHEMA IF NOT EXISTS notifications;

-- Создать пользователей с минимальными правами
CREATE USER iam_svc      WITH PASSWORD 'iam_svc_pass';
CREATE USER sched_svc    WITH PASSWORD 'sched_svc_pass';
CREATE USER inv_svc      WITH PASSWORD 'inv_svc_pass';
CREATE USER sales_svc    WITH PASSWORD 'sales_svc_pass';
CREATE USER docs_svc     WITH PASSWORD 'docs_svc_pass';
CREATE USER notif_svc    WITH PASSWORD 'notif_svc_pass';

GRANT ALL ON SCHEMA iam           TO iam_svc;
GRANT ALL ON SCHEMA scheduling    TO sched_svc;
GRANT ALL ON SCHEMA inventory     TO inv_svc;
GRANT ALL ON SCHEMA sales         TO sales_svc;
GRANT ALL ON SCHEMA documents     TO docs_svc;
GRANT ALL ON SCHEMA notifications TO notif_svc;
```

### 3.3 Скрипт быстрого старта

```bash
#!/bin/bash
# scripts/dev-start.sh

set -e

echo "=== Transora Dev Environment ==="

# 1. Генерация RSA ключей если нет
if [ ! -f infra/keys/private.pem ]; then
  echo "Generating RSA keys..."
  mkdir -p infra/keys
  openssl genrsa -out infra/keys/private.pem 2048
  openssl rsa -in infra/keys/private.pem -pubout -out infra/keys/public.pem
  echo "Keys generated."
fi

# 2. Запустить инфраструктуру
echo "Starting infrastructure..."
docker compose up -d postgres nats redis minio
echo "Waiting for infrastructure..."
docker compose wait postgres nats redis minio

# 3. Инициализировать MinIO бакеты
echo "Initializing MinIO buckets..."
docker compose run --rm minio mc alias set local http://minio:9000 transora transora_dev
docker compose run --rm minio mc mb local/transora-documents --ignore-existing
docker compose run --rm minio mc mb local/transora-templates --ignore-existing
docker compose run --rm minio mc mb local/transora-audio --ignore-existing

# 4. Запустить Kong
echo "Starting Kong..."
docker compose up -d kong-db
docker compose wait kong-db
docker compose up -d kong-migration
docker compose wait kong-migration
docker compose up -d kong

# 5. Запустить сервисы
echo "Starting services..."
docker compose up -d iam-service
sleep 5   # IAM должен быть готов раньше остальных
docker compose up -d scheduling-service inventory-service \
  sales-service document-service notification-service

# 6. Применить конфигурацию Kong
echo "Configuring Kong..."
./scripts/kong-configure.sh

echo "=== Dev environment ready ==="
echo "Kong proxy:    http://localhost:8000"
echo "Kong admin:    http://localhost:8001"
echo "Grafana:       http://localhost:3000"
echo "MinIO console: http://localhost:9001"
echo "NATS monitor:  http://localhost:8222"
```

---

## 4. Kubernetes — продакшн

### 4.1 Структура директорий

```
k8s/
├── namespaces.yaml
├── data/
│   ├── postgres/
│   │   ├── statefulset.yaml
│   │   ├── service.yaml
│   │   └── pvc.yaml
│   ├── nats/
│   │   ├── statefulset.yaml
│   │   └── service.yaml
│   ├── redis/
│   │   ├── statefulset.yaml
│   │   └── service.yaml
│   └── minio/
│       ├── statefulset.yaml
│       └── service.yaml
├── infra/
│   ├── kong/
│   │   ├── deployment.yaml
│   │   ├── service.yaml
│   │   └── ingress.yaml
│   └── monitoring/
│       ├── prometheus.yaml
│       ├── grafana.yaml
│       └── loki.yaml
├── core/
│   ├── iam-service/
│   │   ├── deployment.yaml
│   │   ├── service.yaml
│   │   ├── hpa.yaml
│   │   └── configmap.yaml
│   ├── scheduling-service/
│   ├── inventory-service/
│   ├── sales-service/
│   ├── document-service/
│   └── notification-service/
├── ops/
│   ├── db-migrations/
│   │   └── job.yaml
│   └── minio-init/
│       └── job.yaml
└── secrets/
    └── README.md   ← Секреты управляются через Vault / Sealed Secrets
```

### 4.2 `namespaces.yaml`

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: transora-core
  labels:
    app.kubernetes.io/part-of: transora
---
apiVersion: v1
kind: Namespace
metadata:
  name: transora-data
---
apiVersion: v1
kind: Namespace
metadata:
  name: transora-infra
---
apiVersion: v1
kind: Namespace
metadata:
  name: transora-ops
```

---

## 5. Манифесты сервисов ядра

### 5.1 Шаблон Deployment (iam-service как пример)

```yaml
# k8s/core/iam-service/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: iam-service
  namespace: transora-core
  labels:
    app: iam-service
    app.kubernetes.io/part-of: transora
    app.kubernetes.io/version: "1.0.0"
spec:
  replicas: 2
  selector:
    matchLabels:
      app: iam-service
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0        # Zero-downtime deploy
  template:
    metadata:
      labels:
        app: iam-service
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port:   "8080"
        prometheus.io/path:   "/actuator/prometheus"
    spec:
      serviceAccountName: iam-service
      terminationGracePeriodSeconds: 30

      # Ожидать готовности зависимостей
      initContainers:
        - name: wait-for-postgres
          image: busybox:1.35
          command: ['sh', '-c',
            'until nc -z postgres.transora-data 5432; do echo waiting; sleep 2; done']
        - name: wait-for-redis
          image: busybox:1.35
          command: ['sh', '-c',
            'until nc -z redis.transora-data 6379; do echo waiting; sleep 2; done']

      containers:
        - name: iam-service
          image: registry.transora.internal/iam-service:1.0.0
          imagePullPolicy: IfNotPresent
          ports:
            - containerPort: 8080
              name: http
          env:
            - name: SERVER_PORT
              value: "8080"
            - name: DB_URL
              valueFrom:
                secretKeyRef:
                  name: iam-service-secrets
                  key: db-url
            - name: DB_USERNAME
              valueFrom:
                secretKeyRef:
                  name: iam-service-secrets
                  key: db-username
            - name: DB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: iam-service-secrets
                  key: db-password
            - name: REDIS_HOST
              value: "redis.transora-data"
            - name: REDIS_PORT
              value: "6379"
            - name: NATS_URL
              value: "nats://nats.transora-data:4222"
          envFrom:
            - configMapRef:
                name: iam-service-config
          volumeMounts:
            - name: jwt-keys
              mountPath: /secrets
              readOnly: true

          # Health checks
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: 15
            periodSeconds: 10
            failureThreshold: 3

          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 15
            failureThreshold: 3

          # Ресурсы
          resources:
            requests:
              cpu: 200m
              memory: 512Mi
            limits:
              cpu: 1000m
              memory: 1Gi

      volumes:
        - name: jwt-keys
          secret:
            secretName: jwt-rsa-keys

      # Разместить поды на разных нодах
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
            - weight: 100
              podAffinityTerm:
                labelSelector:
                  matchExpressions:
                    - key: app
                      operator: In
                      values: [iam-service]
                topologyKey: kubernetes.io/hostname
```

### 5.2 Service и HPA

```yaml
# k8s/core/iam-service/service.yaml
apiVersion: v1
kind: Service
metadata:
  name: iam-service
  namespace: transora-core
spec:
  selector:
    app: iam-service
  ports:
    - port: 8080
      targetPort: 8080
      name: http
  type: ClusterIP
---
# k8s/core/iam-service/hpa.yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: iam-service
  namespace: transora-core
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: iam-service
  minReplicas: 2
  maxReplicas: 5
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: 80
```

### 5.3 Ресурсы всех сервисов

| Сервис | Replicas | CPU req/limit | RAM req/limit | Примечание |
|--------|----------|--------------|--------------|-----------|
| `iam-service` | 2–5 | 200m / 1000m | 512Mi / 1Gi | |
| `scheduling-service` | 2–4 | 200m / 800m | 512Mi / 1Gi | |
| `inventory-service` | 2–6 | 300m / 1000m | 512Mi / 1Gi | Много Redis-операций |
| `sales-service` | 2–6 | 300m / 1000m | 512Mi / 1Gi | Критичный сервис |
| `document-service` | 1–3 | 500m / 2000m | 1Gi / 2Gi | JasperReports CPU-intensive |
| `notification-service` | 2–4 | 200m / 600m | 256Mi / 512Mi | Go, эффективен |

---

## 6. Конфигурация Kong API Gateway

### 6.1 Декларативная конфигурация (deck)

```yaml
# infra/kong/kong.yaml  (применяется через: deck sync)

_format_version: "3.0"
_info:
  select_tags:
    - transora

services:

  - name: iam-service
    url: http://iam-service.transora-core:8080
    tags: [transora]
    routes:
      - name: auth-public
        paths: [/auth/login, /auth/refresh, /auth/jwks.json]
        methods: [GET, POST]
        strip_path: false
      - name: auth-protected
        paths: [/auth]
        methods: [GET, POST, PUT, DELETE]
        strip_path: false
        plugins:
          - name: jwt
            config:
              key_claim_name: kid
              claims_to_verify: [exp]

  - name: scheduling-service
    url: http://scheduling-service.transora-core:8080
    tags: [transora]
    routes:
      - name: scheduling-api
        paths: [/api/schedule]
        strip_path: false
        plugins:
          - name: jwt
            config:
              key_claim_name: kid
              claims_to_verify: [exp]

  - name: inventory-service
    url: http://inventory-service.transora-core:8080
    tags: [transora]
    routes:
      - name: inventory-api
        paths: [/api/inventory]
        strip_path: false
        plugins:
          - name: jwt

  - name: sales-service
    url: http://sales-service.transora-core:8080
    tags: [transora]
    routes:
      - name: sales-api
        paths: [/api/sales, /api/boarding]
        strip_path: false
        plugins:
          - name: jwt

  - name: document-service
    url: http://document-service.transora-core:8080
    tags: [transora]
    routes:
      - name: documents-api
        paths: [/api/documents]
        strip_path: false
        plugins:
          - name: jwt

  - name: notification-ws
    url: http://notification-service.transora-core:8082
    tags: [transora]
    routes:
      - name: ws-stations
        paths: [/ws/stations]
        protocols: [http, https, ws, wss]
        strip_path: false
        plugins:
          - name: jwt

# Глобальные плагины
plugins:
  - name: cors
    config:
      origins: ["*"]
      methods: [GET, POST, PUT, DELETE, OPTIONS]
      headers: [Authorization, Content-Type, X-Station-ID]
      max_age: 3600

  - name: request-transformer
    config:
      add:
        headers:
          - "X-Forwarded-By: kong"

  - name: rate-limiting
    config:
      minute: 600
      policy: local

  - name: response-transformer
    config:
      remove:
        headers:
          - X-Powered-By
          - Server

# JWT Consumer (публичный ключ IAM)
consumers:
  - username: transora-jwt
    jwt_secrets:
      - algorithm: RS256
        key: transora-2025-01         # kid из JWT header
        rsa_public_key: |
          -----BEGIN PUBLIC KEY-----
          MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA...
          -----END PUBLIC KEY-----
```

### 6.2 Скрипт настройки Kong

```bash
#!/bin/bash
# scripts/kong-configure.sh

KONG_ADMIN="http://localhost:8001"

echo "Applying Kong configuration..."
deck sync --kong-addr $KONG_ADMIN --state infra/kong/kong.yaml

echo "Kong configuration applied."
```

---

## 7. Базы данных и брокер сообщений

### 7.1 PostgreSQL HA (StatefulSet)

```yaml
# k8s/data/postgres/statefulset.yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: postgres
  namespace: transora-data
spec:
  serviceName: postgres
  replicas: 3                    # 1 primary + 2 replica
  selector:
    matchLabels:
      app: postgres
  template:
    metadata:
      labels:
        app: postgres
    spec:
      containers:
        - name: postgres
          image: postgres:15
          env:
            - name: POSTGRES_USER
              valueFrom:
                secretKeyRef:
                  name: postgres-secrets
                  key: username
            - name: POSTGRES_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: postgres-secrets
                  key: password
            - name: PGDATA
              value: /var/lib/postgresql/data/pgdata
          resources:
            requests:
              cpu: 1000m
              memory: 2Gi
            limits:
              cpu: 4000m
              memory: 8Gi
          volumeMounts:
            - name: postgres-data
              mountPath: /var/lib/postgresql/data
  volumeClaimTemplates:
    - metadata:
        name: postgres-data
      spec:
        accessModes: [ReadWriteOnce]
        storageClassName: fast-ssd
        resources:
          requests:
            storage: 200Gi
```

### 7.2 NATS JetStream Cluster

```yaml
# k8s/data/nats/statefulset.yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: nats
  namespace: transora-data
spec:
  serviceName: nats
  replicas: 3
  selector:
    matchLabels:
      app: nats
  template:
    spec:
      containers:
        - name: nats
          image: nats:2.10
          args:
            - -js
            - -sd=/data
            - -cluster=nats://0.0.0.0:6222
            - -routes=nats://nats-0.nats:6222,nats://nats-1.nats:6222,nats://nats-2.nats:6222
            - --cluster_name=transora
            - -m=8222
          ports:
            - containerPort: 4222   # Client
            - containerPort: 6222   # Clustering
            - containerPort: 8222   # Monitoring
          volumeMounts:
            - name: nats-data
              mountPath: /data
          resources:
            requests:
              cpu: 500m
              memory: 1Gi
            limits:
              cpu: 2000m
              memory: 4Gi
  volumeClaimTemplates:
    - metadata:
        name: nats-data
      spec:
        accessModes: [ReadWriteOnce]
        storageClassName: fast-ssd
        resources:
          requests:
            storage: 50Gi
```

### 7.3 NATS JetStream — конфигурация стримов

```bash
# ops/nats-init/init-streams.sh
# Создаётся при первом запуске через K8s Job

NATS_URL="nats://nats.transora-data:4222"

# Создать стримы для каждого сервиса
for stream in scheduling inventory sales documents notifications iam; do
  nats stream add transora.$stream \
    --server $NATS_URL \
    --subjects "transora.$stream.>" \
    --storage file \
    --replicas 3 \
    --retention limits \
    --max-age 7d \
    --max-msgs 10000000 \
    --discard old \
    --ack
  echo "Stream transora.$stream created"
done
```

---

## 8. Порядок запуска и зависимости

### 8.1 Граф зависимостей

```
Уровень 0 (инфраструктура — запускается первой):
  PostgreSQL ──┐
  NATS        ──┼──► Все сервисы ядра зависят от этих трёх
  Redis       ──┘

Уровень 1 (IAM — должен быть готов перед сервисами):
  iam-service
    · Зависит от: PostgreSQL, Redis, NATS
    · Предоставляет: JWKS endpoint для остальных сервисов

Уровень 2 (сервисы ядра — параллельный запуск):
  scheduling-service    → Зависит от: PostgreSQL, NATS, IAM(JWKS)
  inventory-service     → Зависит от: PostgreSQL, Redis, NATS, IAM(JWKS)
  sales-service         → Зависит от: PostgreSQL, NATS, IAM(JWKS), inventory-service
  document-service      → Зависит от: PostgreSQL, NATS, MinIO, IAM(JWKS)
  notification-service  → Зависит от: PostgreSQL, Redis, NATS, MinIO, IAM(JWKS)

Уровень 3 (Kong — настраивается после готовности сервисов):
  kong
    · Зависит от: kong-db, все сервисы уровня 1-2
    · deck sync применяет конфигурацию маршрутизации

Уровень 4 (вокзал — запускается после ядра):
  station-agent
    · Зависит от: Kong (WSS endpoint доступен)
    · При старте: первичная синхронизация кеша расписания
```

### 8.2 K8s Job: миграции БД

```yaml
# k8s/ops/db-migrations/job.yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: db-migrations
  namespace: transora-ops
spec:
  ttlSecondsAfterFinished: 3600
  template:
    spec:
      restartPolicy: OnFailure
      initContainers:
        - name: wait-postgres
          image: busybox:1.35
          command: ['sh', '-c',
            'until nc -z postgres.transora-data 5432; do sleep 2; done']
      containers:
        - name: flyway
          image: flyway/flyway:10
          args:
            - -url=jdbc:postgresql://postgres.transora-data:5432/transora
            - -user=$(DB_USER)
            - -password=$(DB_PASSWORD)
            - -locations=filesystem:/migrations
            - migrate
          env:
            - name: DB_USER
              valueFrom:
                secretKeyRef:
                  name: postgres-secrets
                  key: username
            - name: DB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: postgres-secrets
                  key: password
          volumeMounts:
            - name: migrations
              mountPath: /migrations
      volumes:
        - name: migrations
          configMap:
            name: db-migrations-scripts
```

---

## 9. Переменные окружения

### 9.1 Сводная таблица переменных

| Переменная | Сервис | Описание | Пример |
|-----------|--------|---------|--------|
| `DB_URL` | все Kotlin | JDBC URL PostgreSQL | `jdbc:postgresql://postgres:5432/transora` |
| `DB_SCHEMA` | все Kotlin | Схема БД сервиса | `iam`, `scheduling`, ... |
| `DB_USERNAME` | все Kotlin | Пользователь БД | `iam_svc` |
| `DB_PASSWORD` | все Kotlin | Пароль БД | — (из Secret) |
| `NATS_URL` | все | URL NATS | `nats://nats:4222` |
| `REDIS_HOST` | iam, inventory, notification | Хост Redis | `redis` |
| `REDIS_PORT` | iam, inventory, notification | Порт Redis | `6379` |
| `IAM_JWKS_URL` | все кроме iam | URL JWKS для верификации JWT | `http://iam-service:8080/auth/jwks.json` |
| `JWT_PRIVATE_KEY_PATH` | iam | Путь к RSA private key | `/secrets/private.pem` |
| `JWT_PUBLIC_KEY_PATH` | iam | Путь к RSA public key | `/secrets/public.pem` |
| `MINIO_ENDPOINT` | document, notification | URL MinIO | `http://minio:9000` |
| `MINIO_ACCESS_KEY` | document, notification | MinIO access key | — (из Secret) |
| `MINIO_SECRET_KEY` | document, notification | MinIO secret key | — (из Secret) |
| `YANDEX_TTS_API_KEY` | notification | Ключ Yandex SpeechKit | — (из Secret) |
| `SCHEDULING_URL` | sales, document | URL scheduling-service | `http://scheduling-service:8080` |
| `INVENTORY_URL` | sales | URL inventory-service | `http://inventory-service:8080` |
| `SERVER_PORT` | все | HTTP порт сервиса | `8080` |
| `TZ` | все | Временная зона | `Asia/Krasnoyarsk` |

### 9.2 Управление секретами

```bash
# Создать Kubernetes Secrets (пример для iam-service)
kubectl create secret generic iam-service-secrets \
  --namespace transora-core \
  --from-literal=db-url="jdbc:postgresql://postgres.transora-data:5432/transora" \
  --from-literal=db-username="iam_svc" \
  --from-literal=db-password="$(cat secrets/iam_svc_password)" \
  --dry-run=client -o yaml | kubeseal > k8s/secrets/iam-service-sealed.yaml

# RSA ключи
kubectl create secret generic jwt-rsa-keys \
  --namespace transora-core \
  --from-file=private.pem=infra/keys/private.pem \
  --from-file=public.pem=infra/keys/public.pem \
  --dry-run=client -o yaml | kubeseal > k8s/secrets/jwt-rsa-sealed.yaml
```

> В продакшне используется **Sealed Secrets** (Bitnami) или **HashiCorp Vault**.
> Секреты не хранятся в git-репозитории ни в каком виде.

---

## 10. Мониторинг и алертинг

### 10.1 Prometheus — конфигурация сбора метрик

```yaml
# infra/prometheus/prometheus.yml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  # Kubernetes service discovery
  - job_name: transora-core
    kubernetes_sd_configs:
      - role: pod
        namespaces:
          names: [transora-core]
    relabel_configs:
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_scrape]
        action: keep
        regex: "true"
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_path]
        target_label: __metrics_path__
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_port]
        target_label: __address__
        regex: (.+)
        replacement: "${1}"
      - source_labels: [__meta_kubernetes_pod_label_app]
        target_label: service

  - job_name: nats
    static_configs:
      - targets: ["nats.transora-data:8222"]
    metrics_path: /varz

  - job_name: postgres
    static_configs:
      - targets: ["postgres-exporter.transora-data:9187"]

  - job_name: station-agents
    # Station agents регистрируются динамически через file_sd
    file_sd_configs:
      - files: [/etc/prometheus/station-agents/*.json]
        refresh_interval: 1m
```

### 10.2 Ключевые метрики и алерты

```yaml
# infra/prometheus/alerts.yaml
groups:
  - name: transora-core
    rules:

      # Сервис недоступен
      - alert: ServiceDown
        expr: up{job="transora-core"} == 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Сервис {{ $labels.service }} недоступен"
          description: "Под {{ $labels.pod }} не отвечает более 1 минуты"

      # Высокая латентность API
      - alert: HighAPILatency
        expr: |
          histogram_quantile(0.95,
            rate(http_server_requests_seconds_bucket{job="transora-core"}[5m])
          ) > 2.0
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Высокая латентность {{ $labels.service }}"
          description: "P95 = {{ $value | humanizeDuration }}"

      # Высокий процент ошибок
      - alert: HighErrorRate
        expr: |
          rate(http_server_requests_seconds_count{
            status=~"5..",
            job="transora-core"
          }[5m]) > 0.05
        for: 3m
        labels:
          severity: critical
        annotations:
          summary: "Высокий процент ошибок {{ $labels.service }}"

      # Вокзал потерял связь с ядром
      - alert: StationAgentOffline
        expr: station_agent_core_connected == 0
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "Вокзал {{ $labels.station_id }} потерял связь с ядром"

      # Несинхронизированный буфер посадки
      - alert: BoardingBufferStale
        expr: station_agent_boarding_buffer_pending > 50
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "Большой буфер посадки на вокзале {{ $labels.station_id }}"
          description: "{{ $value }} событий не синхронизированы"

      # Проблемы с ККТ
      - alert: FiscalRegistrarError
        expr: hardware_agent_device_status{device_type="FISCAL_REGISTRAR"} == 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "ККТ недоступна на рабочем месте {{ $labels.pos_id }}"

      # NATS JetStream — недоставленные сообщения
      - alert: NATSStreamLag
        expr: nats_consumer_num_pending > 10000
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Большой лаг в NATS stream {{ $labels.stream_name }}"
```

### 10.3 Grafana дашборды

| Дашборд | Содержание |
|---------|-----------|
| **Transora Overview** | Статус всех сервисов, RPS, латентность, ошибки |
| **Sales Dashboard** | Продажи в реальном времени, выручка, возвраты |
| **Station Status** | Состояние всех вокзалов, режим подключения |
| **Infrastructure** | PostgreSQL, NATS, Redis, MinIO метрики |
| **Hardware Agents** | Статусы ККТ и терминалов по вокзалам |

---

## 11. CI/CD Pipeline

### 11.1 GitHub Actions — сборка и деплой

```yaml
# .github/workflows/deploy.yml
name: Build and Deploy

on:
  push:
    branches: [main]
    tags: ['v*']

env:
  REGISTRY: registry.transora.internal
  K8S_NAMESPACE: transora-core

jobs:

  # ── Тесты ─────────────────────────────────────────────────────────
  test:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:15
        env:
          POSTGRES_PASSWORD: test
        options: --health-cmd pg_isready
      nats:
        image: nats:2.10
        options: --health-cmd "wget -qO- http://localhost:8222/healthz"

    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: gradle

      - name: Run tests
        run: |
          for service in iam-service scheduling-service inventory-service \
                         sales-service document-service; do
            cd $service
            ./gradlew test --no-daemon
            cd ..
          done

      - name: Run Go tests
        run: |
          cd notification-service && go test ./...
          cd station-agent && go test ./...
          cd hardware-agent && go test ./...

  # ── Сборка Docker образов ─────────────────────────────────────────
  build:
    needs: test
    runs-on: ubuntu-latest
    strategy:
      matrix:
        service:
          - iam-service
          - scheduling-service
          - inventory-service
          - sales-service
          - document-service
          - notification-service
          - station-agent
          - hardware-agent

    steps:
      - uses: actions/checkout@v4

      - name: Extract version
        id: version
        run: echo "VERSION=${GITHUB_REF_NAME}" >> $GITHUB_OUTPUT

      - name: Build and push Docker image
        uses: docker/build-push-action@v5
        with:
          context: ./${{ matrix.service }}
          push: true
          tags: |
            ${{ env.REGISTRY }}/${{ matrix.service }}:${{ steps.version.outputs.VERSION }}
            ${{ env.REGISTRY }}/${{ matrix.service }}:latest

  # ── Деплой в Kubernetes ───────────────────────────────────────────
  deploy:
    needs: build
    runs-on: ubuntu-latest
    environment: production

    steps:
      - uses: actions/checkout@v4

      - name: Set up kubectl
        uses: azure/setup-kubectl@v3

      - name: Configure kubeconfig
        run: |
          echo "${{ secrets.KUBECONFIG }}" | base64 -d > kubeconfig
          export KUBECONFIG=kubeconfig

      - name: Run DB migrations
        run: |
          kubectl apply -f k8s/ops/db-migrations/job.yaml
          kubectl wait job/db-migrations \
            -n transora-ops --for=condition=complete --timeout=120s

      - name: Deploy services
        run: |
          VERSION=${GITHUB_REF_NAME}
          for service in iam-service scheduling-service inventory-service \
                         sales-service document-service notification-service; do
            kubectl set image deployment/$service \
              $service=${{ env.REGISTRY }}/$service:$VERSION \
              -n ${{ env.K8S_NAMESPACE }}
          done

      - name: Wait for rollout
        run: |
          for service in iam-service scheduling-service inventory-service \
                         sales-service document-service notification-service; do
            kubectl rollout status deployment/$service \
              -n ${{ env.K8S_NAMESPACE }} --timeout=300s
          done

      - name: Apply Kong config
        run: |
          deck sync --kong-addr ${{ secrets.KONG_ADMIN_URL }} \
            --state infra/kong/kong.yaml
```

### 11.2 Dockerfile для Kotlin-сервисов

```dockerfile
# Dockerfile (общий для всех Kotlin-сервисов)
FROM gradle:8.5-jdk21-alpine AS build
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
# Кешировать зависимости отдельным слоем
RUN gradle dependencies --no-daemon
COPY src ./src
RUN gradle bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S transora && adduser -S transora -G transora
USER transora

COPY --from=build /app/build/libs/*.jar app.jar

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

EXPOSE 8080
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]
```

### 11.3 Dockerfile для Go-сервисов

```dockerfile
# Dockerfile.go (для notification-service, station-agent, hardware-agent)
FROM golang:1.22-alpine AS build
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build \
  -ldflags="-s -w" \
  -o service ./cmd/...

FROM alpine:3.19
RUN apk add --no-cache ca-certificates tzdata ffmpeg
WORKDIR /app
RUN addgroup -S transora && adduser -S transora -G transora
USER transora

COPY --from=build /app/service .

HEALTHCHECK --interval=30s --timeout=3s \
  CMD wget -qO- http://localhost:8082/health || exit 1

EXPOSE 8082
ENTRYPOINT ["./service"]
```

---

## 12. Процедуры обслуживания

### 12.1 Резервное копирование

```bash
# scripts/backup.sh — запускается как K8s CronJob ежедневно в 03:00

#!/bin/bash
set -e

DATE=$(date +%Y%m%d_%H%M%S)
BACKUP_DIR="/backups/$DATE"
mkdir -p $BACKUP_DIR

# 1. Дамп PostgreSQL
echo "Backing up PostgreSQL..."
pg_dumpall \
  -h postgres.transora-data \
  -U $PGUSER \
  --globals-only > $BACKUP_DIR/postgres_globals.sql

for schema in iam scheduling inventory sales documents notifications; do
  pg_dump \
    -h postgres.transora-data \
    -U $PGUSER \
    -n $schema \
    -Fc transora > $BACKUP_DIR/postgres_${schema}.dump
  echo "  Schema $schema backed up"
done

# 2. Snapshot MinIO (через mc mirror)
echo "Backing up MinIO..."
mc mirror \
  minio/transora-documents \
  backups/minio-docs/$DATE/
mc mirror \
  minio/transora-audio \
  backups/minio-audio/$DATE/

# 3. Загрузить в S3-совместимое хранилище
echo "Uploading to S3..."
aws s3 sync $BACKUP_DIR s3://transora-backups/$DATE/ \
  --storage-class STANDARD_IA

# 4. Удалить локальные файлы старше 7 дней
find /backups -mtime +7 -exec rm -rf {} \;

echo "Backup completed: $DATE"
```

### 12.2 Процедура обновления (Zero-downtime)

```bash
# Обновление одного сервиса без остановки
VERSION="1.1.0"
SERVICE="sales-service"

# 1. Собрать и опубликовать новый образ
docker build -t registry.transora.internal/$SERVICE:$VERSION ./$SERVICE
docker push registry.transora.internal/$SERVICE:$VERSION

# 2. Обновить deployment (RollingUpdate)
kubectl set image deployment/$SERVICE \
  $SERVICE=registry.transora.internal/$SERVICE:$VERSION \
  -n transora-core

# 3. Следить за rollout
kubectl rollout status deployment/$SERVICE -n transora-core

# 4. При проблемах — откат
# kubectl rollout undo deployment/$SERVICE -n transora-core
```

### 12.3 Чеклист подключения нового вокзала

```
□ 1.  Суперадминка: создать филиал (ServiceStation) + сгенерировать код TR-XXXX-XXXX
□ 2.  На LAN вокзала: установить station-agent, указать core.http_url и registration_code
□ 3.  Запустить agent — автоматический POST /api/stations/provision → provisioned.yaml
□ 4.  Проверить в суперадминке: agent online; локально GET :8081/agent/status
□ 5.  Создать учётные записи сотрудников и assignments на филиал (суперадминка / station admin)
□ 6.  Задеплоить station admin static (VITE_APP_TIER=station, VITE_API_URL) при необходимости
□ 7.  Зарегистрировать табло (POST /api/display-boards/register)
□ 8.  Проверить cache.trips_count > 0 после sync
□ 9.  Тестовая продажа / посадка / объявления
□ 10. Добавить филиал в мониторинг Grafana
```

Docker Compose (dev/staging):

```bash
REGISTRATION_CODE=TR-XXXX-XXXX docker compose --profile station-site up -d station-agent
```

См. также [frontend-multi-host.md](frontend-multi-host.md) и [station-agent/README.md](../station-agent/README.md).

### 12.4 Диагностика типовых проблем

| Симптом | Где смотреть | Вероятная причина |
|---------|-------------|------------------|
| Касса не может войти | `iam-service` logs, Redis | Блокировка по IP, истёкший токен |
| Табло не обновляется | `station-agent` /agent/status | Потеря WSS-соединения с ядром |
| Продажа зависает | `sales-service` → `inventory-service` → Redis | Зависшая Redis-блокировка |
| Нет аудио-объявлений | `notification-service` queue API | Пустой файл кеша, проблема с ffplay |
| ККТ не печатает чек | `hardware-agent` /status, journal.db | USB отключён, нет бумаги, кончилась смена |
| Медленная генерация документов | `document-service` logs, CPU | JVM cold start, сложный шаблон JasperReports |
| NATS лаг растёт | NATS monitor :8222/connz | Потребитель не читает, сеть |
| Миграция зависла | K8s Job logs | Конфликт блокировок в PostgreSQL |

---

*Документация Transora завершена. Все модули описаны.*

## Итоговый реестр документов

| Документ | Модуль | Стек |
|----------|--------|------|
| `bus-station-system-concept.md` | Концепция системы | — |
| `transora-scheduling-service.md` | Расписание и рейсы | Kotlin + PostgreSQL + NATS |
| `transora-inventory-service.md` | Инвентарь мест | Kotlin + PostgreSQL + Redis + NATS |
| `transora-sales-service.md` | Продажи и возвраты | Kotlin + PostgreSQL + NATS |
| `transora-document-service.md` | Генерация документов | Kotlin + PostgreSQL + JasperReports + MinIO |
| `transora-notification-service.md` | Табло и озвучивание | Go + PostgreSQL + Redis + NATS + MinIO |
| `transora-hardware-agent.md` | ККТ, терминал, принтер | Go + SQLite |
| `transora-station-agent.md` | Агент вокзала | Go + SQLite |
| `transora-boarding-app.md` | Приложение посадки | Android (Kotlin) + Room |
| `transora-iam-service.md` | Авторизация и роли | Kotlin + PostgreSQL + Redis + NATS |
| `transora-deployment.md` | Развёртывание | Kubernetes + Docker Compose |
