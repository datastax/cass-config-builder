FROM openjdk:8u212-jre-slim-stretch

ENV USER_UID=1001 \
    USER_NAME=dse-operator \
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
