# Copyright DataStax, Inc.
# Please see the included license file for details.
FROM --platform=${BUILDPLATFORM} maven:3.9-eclipse-temurin-11 as builder

COPY . .

RUN ./gradlew copyDockerBuildCtx


#############################################################

# The datastax base image is not multiarch presently, so we use the openjdk
# image as our base for arm builds instead.
FROM eclipse-temurin:11-jdk as cass-config-builder

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

#############################################################

FROM redhat/ubi10-minimal:10.0 AS builder-ubi

# Update the builder packages, install Java, and create user
RUN microdnf clean all && \
    microdnf update -y && \
    rm -rf /var/cache/yum && \
    microdnf install -y shadow-utils findutils && \
    microdnf clean all && \
    useradd -r -s /bin/false -U -G root cassandra

COPY --from=builder --chown=cassandra:root build/docker/definitions /definitions

RUN find /definitions -type d -exec chmod 0775 {} \;

#############################################################

# Build the UBI image
FROM redhat/ubi10-minimal:10.0 AS cass-config-builder-ubi
LABEL maintainer="DataStax, Inc <info@datastax.com>"
LABEL name="cass-config-builder"
LABEL vendor="DataStax, Inc"
LABEL release="1.0.0"
LABEL summary="Configuration templating engine for Apache Cassandra®."
LABEL description="Configuration templating engine for Apache Cassandra®. Powers the configuration of containers deployed via the DataStax Kubernetes Operator for Apache Cassandra."

# Install shadow-utils for user management and create user and group for security
RUN microdnf update -y && \
    microdnf install -y shadow-utils && \
    microdnf clean all && \
    groupadd -r cassandra && useradd -r -g root -G cassandra cassandra

# Install the uber jar
COPY --from=builder --chown=cassandra:root build/docker/*.jar /usr/local/bin/

# Install definition files
COPY --from=builder-ubi --chown=cassandra:root /definitions /definitions

# Install scripts
COPY --from=builder --chown=cassandra:root build/docker/bin/* /usr/local/bin/

# Install licenses
COPY --from=builder --chown=cassandra:root build/docker/LICENSE /licenses/

USER cassandra:root

ENV PATH=$PATH:/usr/local/bin

ENTRYPOINT ["/usr/local/bin/entrypoint"]
