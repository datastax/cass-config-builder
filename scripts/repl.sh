#!/bin/bash

# Runs `gradlew clojureRepl` to start an nrepl server followed by
# `lein repl :connect` to connect to the nrepl server using REPL-y. Will
# automatically close nrepl server after exiting the REPL-y session.

set -e

NREPL_PORT=7888

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
PROJECT_ROOT_DIR=$(dirname $SCRIPT_DIR)

# Ensure we clean up our child processes
trap "trap - SIGTERM && kill -- -$$" SIGINT SIGTERM EXIT

# Ensure the port we need is not already in use
if lsof -i -P -n | grep TCP | grep "$NREPL_PORT"; then
    echo "Port $NREPL_PORT already in use"
    exit 1
fi

# We need to be in the project root directory in order to run the gradle task
cd "$PROJECT_ROOT_DIR"

# Start the nrepl server
./gradlew --console plain --no-daemon clojureRepl &
CHILD_PID=$!
echo "Spawned nrepl server process $CHILD_PID"

# Wait for the nrepl server to become ready for client connections
current_time="$(date +%s)"
until_time=$(expr $(date +%s) + 120)
while [[ "$(date +%s)" -lt "$until_time" ]]; do
    if lsof -i -P -n -p "$CHILD_PID" | grep TCP | grep "$NREPL_PORT"; then
        break
    fi
done

# Connect to the nrepl server
lein repl :connect localhost:$NREPL_PORT
