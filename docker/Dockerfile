# Copyright DataStax, Inc.
# Please see the included license file for details.

# FROM openjdk:8u212-jre-slim-stretch
FROM registry.access.redhat.com/ubi8/ubi-minimal:latest

RUN microdnf update && rm -rf /var/cache/yum
RUN microdnf install java-1.8.0-openjdk && microdnf clean all

ENV USER_UID=1001 \
    USER_NAME=cass-operator \
    HOME=/home/$USER_NAME

# Install the uber jar
COPY *.jar /usr/local/bin/

# Install definition files
COPY definitions /definitions

COPY bin/* /usr/local/bin/

RUN  /usr/local/bin/user_setup

ENV PATH=$PATH:/usr/local/bin

ENTRYPOINT ["/usr/local/bin/entrypoint"]

USER ${USER_UID}
