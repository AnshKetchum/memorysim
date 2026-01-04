#!/usr/bin/env bash
set -euo pipefail

# Define image and tags
IMAGE_NAME="eyeamansh/chipyard-dev"
GIT_SHA=$(git rev-parse --short HEAD)

echo "[info] Building Docker image for chipyard-dev..."
docker build --network=host -t chipyard-dev -f .devcontainer/Dockerfile .

echo "[info] Tagging image with commit SHA and latest..."
docker tag chipyard-dev "${IMAGE_NAME}:${GIT_SHA}"
docker tag chipyard-dev "${IMAGE_NAME}:latest"

echo "[info] Pushing image to Docker Hub..."
docker push "${IMAGE_NAME}:${GIT_SHA}"
docker push "${IMAGE_NAME}:latest"

echo "[success] Pushed:"
echo "  - ${IMAGE_NAME}:${GIT_SHA}"
echo "  - ${IMAGE_NAME}:latest"
