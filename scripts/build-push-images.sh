#!/usr/bin/env bash

# Expected ENV variable inputs:
#
# GITHUB_REPO_URL - the GitHub repository url (i.e. https://github.com/datastax/cass-operator)
# GITHUB_REPO_OWNER - the owner of the repository (i.e. datastax), this is useful for forks
# GITHUB_SHA - the git SHA of the current checkout
#

set -e

VERSION_NUMBER="$(cat version.txt | tr -d '[:space:]')"
VERSION_DATE="$(date -u +%Y%m%d)"
RELEASE_VERSION="${VERSION_NUMBER}-${VERSION_DATE}"

GH_REPOSITORY="ghcr.io/${GITHUB_REPO_OWNER}/cass-config-builder/cass-config-builder"

GH_TAGS=(--tag "${GH_REPOSITORY}:${RELEASE_VERSION}")
GH_UBI_TAGS=(--tag "${GH_REPOSITORY}:${RELEASE_VERSION}-ubi10" --tag "${GH_REPOSITORY}:${RELEASE_VERSION}-ubi")
GH_ARM64_TAGS=(--tag "${GH_REPOSITORY}:${RELEASE_VERSION}-arm64")

LABELS=(
  --label "release=$RELEASE_VERSION"
  --label "org.label-schema.schema-version=1.0"
  --label "org.label-schema.vcs-ref=$GITHUB_SHA"
  --label "org.label-schema.vcs-url=$GITHUB_REPO_URL"
  --label "org.label-schema.version=$RELEASE_VERSION"
)

COMMON_ARGS=(
  "${LABELS[@]}"
  --file docker/Dockerfile
  --cache-from "type=local,src=/tmp/.buildx-cache"
  --cache-to "type=local,dest=/tmp/.buildx-cache"
)

STANDARD_ARGS=(
  "${COMMON_ARGS[@]}"
  --target cass-config-builder
)

UBI_ARGS=(
  "${COMMON_ARGS[@]}"
  --target cass-config-builder-ubi
)

# GitHub packages does not presently support multiarch images, so we
# will have to create independent tags for each arch. This feature is
# coming though:
#
#    https://github.community/t/handle-multi-arch-docker-images-on-github-package-registry/14314/16
#

docker buildx build --load \
  "${GH_UBI_TAGS[@]}" \
  "${UBI_ARGS[@]}" \
  --platform linux/arm64 \
  .

docker buildx build --load \
  "${GH_UBI_TAGS[@]}" \
  "${UBI_ARGS[@]}" \
  --platform linux/amd64 \
  .

docker buildx build --load \
  "${GH_ARM64_TAGS[@]}" \
  "${STANDARD_ARGS[@]}" \
  --platform linux/arm64 \
  .

docker buildx build --load \
  "${GH_TAGS[@]}" \
  "${STANDARD_ARGS[@]}" \
  --platform linux/amd64 \
  .

TAGS_TO_PUSH=("${GH_ARM64_TAGS[@]}" "${GH_TAGS[@]}" "${GH_UBI_TAGS[@]}")
echo "Pushing tags: " "${TAGS_TO_PUSH[@]}"

# Note: Every even index of TAGS_TO_PUSH will be the string '--tag'
#       so we skip over those while looping.

for ((x=1; x<${#TAGS_TO_PUSH[@]}; x=x+2)); do
  docker push "${TAGS_TO_PUSH[x]}"
done
