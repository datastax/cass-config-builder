# Copyright DataStax, Inc.
# Please see the included license file for details.
FROM --platform=${BUILDPLATFORM} maven:3.6.3-adoptopenjdk-8 as builder

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

FROM registry.access.redhat.com/ubi7/ubi-minimal:7.8 AS builder-ubi

# Update the builder packages and create user
RUN microdnf update && rm -rf /var/cache/yum && \
    microdnf install shadow-utils && microdnf clean all && \
    useradd -r -s /bin/false -U -G root cassandra

#############################################################

FROM registry.access.redhat.com/ubi7/ubi-minimal:7.8 as cass-config-builder-ubi

LABEL maintainer="DataStax, Inc <info@datastax.com>"
LABEL name="cass-config-builder"
LABEL vendor="DataStax, Inc"
LABEL release="1.0.0"
LABEL summary="Configuration templating engine for Apache Cassandra®."
LABEL description="Configuration templating engine for Apache Cassandra®. Powers the configuration of containers deployed via the DataStax Kubernetes Operator for Apache Cassandra."

# Update base packages
RUN microdnf update && \
    rm -rf /var/cache/yum && \
    microdnf install java-1.8.0-openjdk-headless && \
    microdnf clean all

# Copy user accounts information
COPY --from=builder-ubi /etc/passwd /etc/passwd
COPY --from=builder-ubi /etc/shadow /etc/shadow
COPY --from=builder-ubi /etc/group /etc/group
COPY --from=builder-ubi /etc/gshadow /etc/gshadow

# Install the uber jar
COPY --from=builder build/docker/*.jar /usr/local/bin/

# Install definition files
COPY --from=builder build/docker/definitions /definitions

COPY --from=builder build/docker/bin/* /usr/local/bin/

COPY --from=builder build/docker/LICENSE /licenses/

# Fix permissions
RUN chown cassandra:root -Rv /usr/local/bin/* && \
    chmod -Rv g+x /usr/local/bin

USER cassandra:root

ENV PATH=$PATH:/usr/local/bin

ENTRYPOINT ["/usr/local/bin/entrypoint"]
