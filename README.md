# Introduction

The cass-config-builder can be consumed as either a jar artifact or inside of a Docker image.

# Build all artifacts

./gradlew assemble

# Push the jar artifact to your local Maven repository

./gradlew publishToMavenLocal

# Publish the jar artifact to your remote Maven repository

./gradlew publish

# Build the Docker image

./gradlew dockerImage

# Publish the Docker image to a remote docker repository

./gradlew pushDockerImage
