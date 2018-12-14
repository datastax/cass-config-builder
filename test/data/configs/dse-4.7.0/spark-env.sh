#!/bin/sh

# This file is sourced when running various Spark programs.
# Copy it as spark-env.sh and edit that to configure Spark for your site.

# Options read when launching programs locally with 
# ./bin/run-example or ./bin/spark-submit
# - SPARK_LOCAL_IP, to set the IP address Spark binds to on this node
# - SPARK_PUBLIC_DNS, to set the public dns name of the driver program
# - SPARK_CLASSPATH, default classpath entries to append

# Options read by executors and drivers running inside the cluster
# - SPARK_LOCAL_IP, to set the IP address Spark binds to on this node
# - SPARK_PUBLIC_DNS, to set the public DNS name of the driver program
# - SPARK_CLASSPATH, default classpath entries to append
# - SPARK_LOCAL_DIRS, storage directories to use on this node for shuffle and RDD data
# - MESOS_NATIVE_LIBRARY, to point to your libmesos.so if you use Mesos

# Options for the daemons used in the standalone deploy mode
# - SPARK_MASTER_IP, to bind the master to a different IP address or hostname
# - SPARK_MASTER_PORT / SPARK_MASTER_WEBUI_PORT, to use non-default ports for the master
# - SPARK_MASTER_OPTS, to set config properties only for the master (e.g. "-Dx=y")
# - SPARK_WORKER_CORES, to set the number of cores to use on this machine
# - SPARK_WORKER_MEMORY, to set how much total memory workers have to give executors (e.g. 1000m, 2g)
# - SPARK_WORKER_PORT / SPARK_WORKER_WEBUI_PORT, to use non-default ports for the worker
# - SPARK_WORKER_INSTANCES, to set the number of worker processes per node
# - SPARK_WORKER_DIR, to set the working directory of worker processes
# - SPARK_WORKER_OPTS, to set config properties only for the worker (e.g. "-Dx=y")
# - SPARK_HISTORY_OPTS, to set config properties only for the history server (e.g. "-Dx=y")
# - SPARK_DAEMON_JAVA_OPTS, to set config properties for all daemons (e.g. "-Dx=y")
# - SPARK_PUBLIC_DNS, to set the public dns name of the master or workers

# Generic options for the daemons used in the standalone deploy mode
# - SPARK_CONF_DIR      Alternate conf dir. (Default: ${SPARK_HOME}/conf)
# - SPARK_LOG_DIR       Where log files are stored.  (Default: ${SPARK_HOME}/logs)
# - SPARK_PID_DIR       Where the pid file is stored. (Default: /tmp)
# - SPARK_IDENT_STRING  A string representing this instance of spark. (Default: $USER)
# - SPARK_NICENESS      The scheduling priority for daemons. (Default: 0)

# Remember to set these ports identically on each node
export SPARK_MASTER_PORT=7077
export SPARK_MASTER_WEBUI_PORT=7080

# The hostname or IP address Cassandra rpc/native protocol is bound to:
# SPARK_CASSANDRA_CONNECTION_HOST="127.0.0.1"

# The hostname or IP address for the driver to listen on. If there is more network interfaces you
# can specify which one is to be used by Spark Shell or other Spark applications.
# export SPARK_DRIVER_HOST="127.0.0.1"

# Set the amount of memory used by Spark Worker - if uncommented, it overrides the setting initial_spark_worker_resources in dse.yaml.
# export SPARK_WORKER_MEMORY=2048m

# Set the number of cores used by Spark Worker - if uncommented, it overrides the setting initial_spark_worker_resources in dse.yaml.
# export SPARK_WORKER_CORES=4

# The number of workers to be started on this node. Each worker will consume the fraction of resources
# defined in dse.yaml:initial_spark_worker_resources at most or the amount of memory and the number of
# cores defined in this file. Therefore, if the number of workers is set to the higher value, you should
# reduce the amount of resources which are granted to a single worker.
export SPARK_WORKER_INSTANCES=1

# The amount of memory used by Spark Driver program
export SPARK_DRIVER_MEMORY="512M"

# Warning: Be careful when changing temporary subdirectories. Make sure they different for different Spark components
# and they are set with spark.local.dir for Spark Master and Spark Worker, and with java.io.tmpdir for Spark executor,
# Spark shell(repl) and Spark applications. Jobs may not finish properly (hang) if temporary directories overlap.
#
# Warning: When changing temporary or logs locations, consider permissions and ownership of files created by particular
# Spark components. Wrongly specified directories here may result in security related errors.

# This is a base directory for Spark Worker work files. The actual files will be placed under
# worker-0/, worker-1/, ... sub-directories - each for a single worker instance.
export SPARK_WORKER_DIR="/var/lib/spark/worker"

export SPARK_LOCAL_DIRS="/var/lib/spark/rdd"

# This is a base directory for Spark Worker logs. The actual log files will be placed under
# worker-0/, worker-1/, ... sub-directories - each for a single worker instance.
export SPARK_WORKER_LOG_DIR="/var/log/spark/worker"

# These Java options will be passed to Spark Worker process
export SPARK_WORKER_OPTS="$SPARK_WORKER_OPTS"

# These Java options will be merged with Executor settings computed by Spark Worker
# that is - they are node specific Java options used by executors which a run on this machine
export SPARK_EXECUTOR_OPTS="$SPARK_EXECUTOR_OPTS"

# These Java options will be passed to Spark submit program
export SPARK_SUBMIT_OPTS="$SPARK_SUBMIT_OPTS"

. "$SPARK_CONF_DIR"/dse-spark-env.sh
