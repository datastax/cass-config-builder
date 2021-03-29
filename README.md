# Introduction

The cass-config-builder can be consumed as either a jar artifact or inside of a Docker image.

# Testing

./gradlew test

This task, without additional parameters, will test the cass-config-definitions submodule. 
However, this can be inconvenient if you want to make changes in your own fork of the 
cass-config-definitions repo. The following -P option allows you to point to a different
directory for the definition files to test with:

./gradlew test -Pdefinitions.location=~/cass-config-definitions/resources

# Build all artifacts

./gradlew assemble

# Push the jar artifact to your local Maven repository

./gradlew publishToMavenLocal

# Publish the jar artifact to your remote Maven repository

./gradlew publish

This task uses the following gradle settings:


Setting|Description
---|---
mavenRepositoryUrl|       Maven repository to publish to
mavenRepositoryUsername|  Username for publishing
mavenRepositoryPassword|  Password for publishing

# Build the Docker image

Docker images are built automatically and pushed to GitHub packages for pushes to master and PRs against master. When creating a release tag (e.g. v1.2.0) an image is produced and pushed to DockerHub.

# Using the cass-config-builder docker image

The cass-config-builder receives input via the following environment variables:

Variable|Description
---|---
CONFIG_FILE_DATA|See below for explanation
CONFIG_OUTPUT_DIRECTORY|Filesystem location to place rendered files.  Defaults to /config
DEFINITIONS_LOCATION|Filesystem location of the Definition Files.  Defaults to /definitions
POD_IP|The IP of the Kubernetes Pod
HOST_IP|The IP of the Kubernetes worker hosting the Pod
PRODUCT_NAME|Either "cassandra" or "dse"
PRODUCT_VERSION|The version number for Cassandra or DSE
RACK_NAME|The Cassandra rack name for this Pod

# CONFIG_FILE_DATA

This is a JSON-encoded string representation of a nested dictionary.  At the top-level of this data structure there are three special keys and an optional number of additional keys.

The three required top-level keys are "cluster-info", "datacenter-info", and "node-info".

## cluster-info key

The value for cluster-info key is a dictionary with two required fields:

Key|Description
---|---
name:Cluster name
seeds:A comma separated list of IP addresses of Cassandra seed nodes

## datacenter-info key

The value for datacenter-info key is a dictionary with four required fields:

Key|Description
---|---
name:Datacenter name
graph-enabled:Enable DSE graph workload
solr-enabled:Enable DSE Solr workload
spark-enabled:Enable DSE Spark workload

Note: Graph, Solr, and Spark workloads are not currently supported in the Cass-Operator.

## node-info key

The value for node-info key is a dictionary with eight required fields:

Key|Description
---|---
name:Node name
rack:Rack name
listen_address:IP for listen address
native_transport_address:IP for native_transport address
native_transport_broadcast_address:IP for native_transport broadcast address
initial_token:The initial token
auto_bootstrap:The auto_bootstrap value
agent_version:The version of Datastax Agent to support

### IP Address defaulting

The POD_IP will be used as the default value for the listen_address and native_transport_broadcast_address if they are not specified.

Cassandra refers to native_transport address as rpc_address and native_transport_broadcast_address as broadcast_rpc_address.

native_transport_address will be defaulted to "0.0.0.0".

## Additional Top-Level CONFIG_FILE_DATA keys

The list of supported additional fields is dependent upon the exact version of DSE or Cassandra that is being targetted.  Each key is called a "config-file-id" and corresponds to a specific configuration file in that version of DSE or Cassandra.  The exact details of the config-file-ids and their supported values are defined in the cass-config-builder Definition Files.

For more details on the Definition Files, see the Github repository:

https://github.com/datastax/cass-config-definitions
