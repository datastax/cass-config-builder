# Copyright DataStax, Inc.
# Please see the included license file for details.

FROM datastax/ds-base-debian-openjdk-8:8u252-jdk-slim-buster-20200602

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
