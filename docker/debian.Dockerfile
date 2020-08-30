# Copyright DataStax, Inc.
# Please see the included license file for details.
FROM --platform=linux/amd64 maven:3.6.3-adoptopenjdk-8 as builder

COPY . .

RUN ./gradlew copyDockerBuildCtx

# The datastax base image is not multiarch presently, so we use the openjdk
# image as our base for arm builds instead.
FROM --platform=linux/arm64 openjdk:8u171-jdk-slim-stretch as base-arm64

FROM --platform=linux/amd64 datastax/ds-base-debian-openjdk-8:8u252-jdk-slim-buster-20200602 as base-amd64

FROM base-${TARGETARCH} as cass-config-builder

ENV USER_UID=1001 \
    USER_NAME=cass-operator \
    HOME=/home/cass-operator

# Install the uber jar
COPY --from=builder build/docker/*.jar /usr/local/bin/

# Install definition files
COPY --from=builder build/docker/definitions /definitions

COPY --from=builder build/docker/bin/* /usr/local/bin/

RUN  /usr/local/bin/user_setup

ENV PATH=$PATH:/usr/local/bin

ENTRYPOINT ["/usr/local/bin/entrypoint"]

USER ${USER_UID}
