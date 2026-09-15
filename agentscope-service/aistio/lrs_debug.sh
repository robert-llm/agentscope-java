#!/bin/bash

export AISTIO_ENABLE_KUBERNETES=false
export AISTIO_PRODUCT_DSN="postgres://builder:builder@localhost:5432/builder?sslmode=disable"
export AISTIO_HTTP_BIND=":8081"
export BUILDER_JWT_SECRET="builder-default-dev-secret-change-in-production-32chars"
export BUILDER_INTERNAL_TOKEN="local-dev-internal-token-at-least-32chars"
export BUILDER_DATA_URL="http://localhost:8082"
export AISTIO_WORKSPACE_ROOT="../.dev-stack/workspaces"
export AISTIO_STATIC_DIR="./ui"

./bin/aistiod \
    --storage-driver=postgres \
    --storage-dsn="postgres://builder:builder@localhost:5432/builder?sslmode=disable&search_path=rt" \
    --log-format=console
