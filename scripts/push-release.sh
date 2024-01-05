#!/usr/bin/env bash

# Expected ENV variable inputs:
#
# GITHUB_REPO_URL - the GitHub repository url (i.e. https://github.com/datastax/cass-operator)
# GITHUB_REF - the git ref of the tag
# GITHUB_SHA - the git SHA of the current checkout
#

VERSION_NUMBER="$(cat version.txt | tr -d '[:space:]')"
RELEASE_VERSION="${VERSION_NUMBER}"
RELEASE_MINOR_VERSION="$(echo ${RELEASE_VERSION} | cut -d "." -f 1-2)"

DOCKERHUB_REPOSITORY="datastax/cass-config-builder"

# Make sure the version number of the project aligns with
# the tag that we have to prevent confusion.
GIT_TAG="${GITHUB_REF##*/}"
if ! [ "v${VERSION_NUMBER}" = "${GIT_TAG}" ]; then
  echo "Git tag $GIT_TAG does not align with version number ${VERSION_NUMBER} in version.txt"
  exit 1
fi

DOCKERHUB_TAGS=(--tag "${DOCKERHUB_REPOSITORY}:${RELEASE_VERSION}" --tag "${DOCKERHUB_REPOSITORY}:${RELEASE_MINOR_VERSION}")
DOCKERHUB_UBI_TAGS=(--tag "${DOCKERHUB_REPOSITORY}:${RELEASE_VERSION}-ubi7" --tag "${DOCKERHUB_REPOSITORY}:${RELEASE_MINOR_VERSION}-ubi7")
DOCKERHUB_UBI8_TAGS=(--tag "${DOCKERHUB_REPOSITORY}:${RELEASE_VERSION}-ubi8" --tag "${DOCKERHUB_REPOSITORY}:${RELEASE_MINOR_VERSION}-ubi8")

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

docker buildx build --push \
  "${DOCKERHUB_UBI_TAGS[@]}" \
  "${UBI_ARGS[@]}" \
  --platform linux/amd64 .

docker buildx build --push \
  "${DOCKERHUB_UBI8_TAGS[@]}" \
  "${UBI_ARGS[@]}" \
  --platform linux/amd64,linux/arm64 .

docker buildx build --push \
  "${DOCKERHUB_TAGS[@]}" \
  "${STANDARD_ARGS[@]}" \
  --platform linux/amd64,linux/arm64 .
