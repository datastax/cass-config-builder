FROM registry.access.redhat.com/ubi7/ubi-minimal:7.8 AS builder

# Update the builder packages and create user
RUN microdnf update && rm -rf /var/cache/yum && \
    microdnf install shadow-utils && microdnf clean all && \
    useradd -r -s /bin/false -U -G root cassandra

#############################################################

FROM registry.access.redhat.com/ubi7/ubi-minimal:7.8

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
COPY --from=builder /etc/passwd /etc/passwd
COPY --from=builder /etc/shadow /etc/shadow
COPY --from=builder /etc/group /etc/group
COPY --from=builder /etc/gshadow /etc/gshadow

# Install the uber jar
COPY *.jar /usr/local/bin/

# Install definition files
COPY definitions /definitions

COPY bin/* /usr/local/bin/

COPY LICENSE /licenses/

# Fix permissions
RUN chown cassandra:root -Rv /usr/local/bin/* && \
    chmod -Rv g+x /usr/local/bin

USER cassandra:root

ENV PATH=$PATH:/usr/local/bin

ENTRYPOINT ["/usr/local/bin/entrypoint"]
