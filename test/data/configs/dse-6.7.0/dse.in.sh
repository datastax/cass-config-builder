#!/bin/sh

get_partitioner() {
    echo "$("$BIN"/dse-client-tool cassandra partitioner)"
}

contains() {
    string="$1"
    substring="$2"
    if test "${string#*$substring}" != "$string"
    then
        return 0    # $substring is in $string
    else
        return 1    # $substring is not in $string
    fi
}

remove_duplicates() {
    result=$(echo "$1" | awk -v RS=':' -v ORS=":" '!a[$1]++{if (NR > 1) printf ORS; printf $a[$1]}')
    echo $result
}

if [ -n "$2" ]; then
for PARAM in "$@"; do
   if [ "$PARAM" = "$1" ]; then
      continue
   fi

   if [ "$PARAM" = "-t" ]; then
      DSE_HADOOP_MODE="1"
   elif [ "$PARAM" = "-s" ]; then
      DSE_SOLR_MODE="1"
   elif contains "$PARAM" "cassandra.username" ; then
      DSE_CREDENTIALS_SUPPLIED="1"
   elif contains "$PARAM" "dse.sasl.protocol" ; then
      DSE_CREDENTIALS_SUPPLIED="1"
   fi
done
fi

DSE_CMD="$1"

# Use JAVA_HOME if set, otherwise look for java in PATH
if [ "x$JAVA_HOME" != "x" -a -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
elif [ "`uname`" = "Darwin" ]; then
    export JAVA_HOME=$(/usr/libexec/java_home)
    if [ -x "$JAVA_HOME/bin/java" ]; then
      JAVA="$JAVA_HOME/bin/java"
    fi
else
    JAVA="`which java`"
    if [ "x$JAVA" = "x" -a -x "/usr/lib/jvm/default-java/bin/java" ]; then
        # Use the default java installation
        JAVA="/usr/lib/jvm/default-java/bin/java"
    fi
    if [ "x$JAVA" != "x" ]; then
        JAVA=$(readlink -f "$JAVA")
        export JAVA_HOME=$(echo "$JAVA" | sed "s:bin/java::")
    fi
fi
export JAVA

if [ "x$JAVA" = "x" ]; then
    echo "Java executable not found (hint: set JAVA_HOME)" >&2
    exit 1
fi

# Helper functions
filematch () { case "$2" in $1) return 0 ;; *) return 1 ;; esac ; }

#########################################
# Setup DSE env
#########################################

if [ -z "$TMPDIR" ]; then
    # TMPDIR env variable is not set. DSE will use /tmp as a temporary files location
    export TMPDIR="/tmp"
fi

if [ ! -d "$TMPDIR" ]; then
    mkdir -m 1777 -p "$TMPDIR"
    if [ ! -d "$TMPDIR" ]; then
        echo "Error: Temporary location $TMPDIR does not exist and could not be created" 1>&2
        exit 1
    fi
fi

if [ ! -w "$TMPDIR" ]; then
    echo "Error: Temporary location $TMPDIR is not writable" 1>&2
    exit 1
fi

if [ -z "$DSE_HOME" ]; then
    abspath=$(cd "$(dirname $0)" && pwd -P)
    DSE_HOME="`dirname "$abspath"`"
    if [ -x "$DSE_HOME/bin/dse" ]; then
        export DSE_HOME
    elif [ -x "/usr/bin/dse" ]; then
        export DSE_HOME="/usr"
    elif [ -x "/usr/share/dse/cassandra" ]; then
        export DSE_HOME="/usr/share/dse"
    elif [ -x "/usr/share/dse/bin/dse" ]; then
        export DSE_HOME="/usr/share/dse"
    elif [ -x "/opt/dse/bin/dse" ]; then
        export DSE_HOME="/opt/dse"
    else
        DIR="`dirname $0`"
        for i in 1 2 3 4 5 6; do
            if [ -x "$DIR/bin/dse" ]; then
                export DSE_HOME="$DIR"
                break
            fi
            DIR="$DIR/.."
        done
        if [ ! -x "$DSE_HOME/bin/dse" ]; then
            echo "Cannot determine DSE_HOME."
            exit 1
        fi
    fi
fi

if [ -z "$DSE_CONF" ]; then
    for dir in "$DSE_HOME/resources/dse/conf" \
               "$DSE_HOME/conf" \
               "/etc/dse" \
               "/usr/share/dse" \
               "/usr/share/dse/conf" \
               "/usr/local/share/dse" \
               "/usr/local/share/dse/conf" \
               "/opt/dse/conf"; do
        if [ -r "$dir/dse.yaml" ]; then
            export DSE_CONF="$dir"
            break
        fi
    done
    if [ -z "$DSE_CONF" ]; then
        echo "Cannot determine DSE_CONF."
        exit 1
    fi
fi

#include the DSERC environment script (pulls in credentials for basic authentication)
if [ -r "$DSE_HOME/bin/dserc-env.sh" ]; then
    . "$DSE_HOME/bin/dserc-env.sh"
elif [ -r "$DSE_CONF/dserc-env.sh" ]; then
    . "$DSE_CONF/dserc-env.sh"
else
    echo "Location pointed by DSERC_ENV not readable: $DSERC_ENV"
    exit 1
fi


if [ -z "$DSE_LOG_ROOT" ]; then
    DSE_LOG_ROOT_DEFAULT="/var/log"
    if [ -w "$DSE_HOME/logs" ]; then
        export DSE_LOG_ROOT="$DSE_HOME/logs"
    else
        export DSE_LOG_ROOT="$DSE_LOG_ROOT_DEFAULT"
    fi
fi

if [ -z "$DSE_LIB" ]; then
    for dir in "$DSE_HOME/build" \
               "$DSE_HOME/lib" \
               "$DSE_HOME/resources/dse/lib" \
               "/usr/share/dse" \
               "/usr/share/dse/common" \
               "/opt/dse" \
               "/opt/dse/common"; do

        if [ -r "$dir" ]; then
            export DSE_LIB="$DSE_LIB
                            $dir"
        fi
    done
fi

if [ -z "$DSE_COMPONENTS_ROOT" ]; then
    for dir in $DSE_HOME/resources \
               $DSE_HOME \
               /usr/share/dse \
               /usr/local/share/dse \
               /opt/dse; do

        if [ -r "$dir/cassandra" ] && [ -r "$dir/driver" ]; then
            export DSE_COMPONENTS_ROOT="$dir"
            break
        fi
    done
fi

if [ -z "$DSE_COMPONENTS_ROOT" ]; then
    echo "Cannot determine DSE_COMPONENTS_ROOT."
    exit 1
fi

#
# Add dse jars to the classpath
#
for dir in $DSE_LIB; do
    for jar in "$dir"/*.jar; do
        if [ -r "$jar" ]; then
            DSE_CLASSPATH="$DSE_CLASSPATH:$jar"
        fi
    done

    for jar in "$dir"/dse*.jar; do
        if [ -r "$jar" ]; then
            found_dse_jars="$found_dse_jars:$jar"
        fi
    done
done

export DSE_JARS="$found_dse_jars"

# check if there are jars from older/other DSE versions in the classpath
DSE_JARS_ERROR_CHECK=$(echo $DSE_JARS | \
    tr ':' '\n' | \
    ( while read jar; do basename "$jar" ; done ) | \
    awk -v dirs="$(echo "$DSE_LIB" | tr '\n' ' ' )" '
        BEGIN { old_dse = 0; dse_core = 0 }
        /^dse-[0-9]/ { old_dse++ }
        /^dse\.jar/ { old_dse++ }
        /^dse-core-[0-9]/ { dse_core++ }
        END {
            if (old_dse != 0) {
                printf "Found DSE jar from an older DSE version in %s. Please remove it.", dirs
            } else if (dse_core == 0) {
                printf "Found no DSE core jar files in %s. Please make sure there is one.", dirs
            } else if (dse_core > 1) {
                printf "Found multiple DSE core jar files in %s. Please make sure there is only one.", dirs
            }
        }
    ' )

if [ ! -z "$DSE_JARS_ERROR_CHECK" ]; then
    echo $DSE_JARS_ERROR_CHECK
    exit 1
fi

if [ -r $DSE_HOME/build/classes ]; then
    DSE_CLASSPATH="$DSE_HOME/build/classes:$DSE_CLASSPATH"
fi

if [ -r $DSE_HOME/build/classes/main ]; then
    DSE_CLASSPATH="$DSE_HOME/build/classes/main:$DSE_CLASSPATH"
fi

#
# Add dse conf
#
DSE_CLASSPATH=$DSE_CLASSPATH:$DSE_CONF

export DSE_CLASSPATH=$(remove_duplicates "$DSE_CLASSPATH")

#########################################
# Setup Cassandra env
#########################################

# the default location for commitlogs, sstables, and saved caches
# if not set in cassandra.yaml
if [ -z "$cassandra_storagedir" ]; then
    export cassandra_storagedir="$DSE_HOME/data"
fi

if [ -z "$CASSANDRA_LOG_DIR" ]; then
    if [ -w "$DSE_LOG_ROOT" ] || [ -w "$DSE_LOG_ROOT/cassandra" ]; then
        export CASSANDRA_LOG_DIR="$DSE_LOG_ROOT/cassandra"
    else
        export CASSANDRA_LOG_DIR="/var/log/cassandra"
    fi
fi

if [ -z "$CASSANDRA_HOME" -o ! -d "$CASSANDRA_HOME"/tools/lib ]; then
    for dir in $DSE_HOME/resources/cassandra \
               $DSE_HOME/cassandra \
               /usr/share/dse/cassandra \
               /usr/local/share/dse/cassandra \
               /opt/cassandra; do

        if [ -r "$dir" ]; then
            export CASSANDRA_HOME="$dir"
            # Resetting java agent... otherwise it's likely to point
            # to an invalid file
            export JAVA_AGENT=
            break
        fi
    done
    if [ -z "$CASSANDRA_HOME" ]; then
        echo "Cannot determine CASSANDRA_HOME."
        exit 1
    fi
fi

if [ -z "$CASSANDRA_BIN" -o ! -x "$CASSANDRA_BIN"/cassandra ]; then
    for dir in $CASSANDRA_HOME/bin /usr/bin /usr/sbin; do
        if [ -x "$dir/cassandra" ]; then
            export CASSANDRA_BIN="$dir"
            break
        fi
    done
    if [ -z "$CASSANDRA_BIN" ]; then
        echo "Cannot determine CASSANDRA_BIN."
        exit 1
    fi
fi

if [ -z "$CASSANDRA_CONF" -o ! -r "$CASSANDRA_CONF"/cassandra.yaml ]; then
    for dir in $CASSANDRA_HOME/conf \
               /etc/dse/cassandra \
               /etc/dse/ \
               /etc/cassandra; do
        if [ -r "$dir/cassandra.yaml" ]; then
            export CASSANDRA_CONF="$dir"
            break
        fi
    done
    if [ -z "$CASSANDRA_CONF" ]; then
        echo "Cannot determine CASSANDRA_CONF."
        exit 1
    fi
fi

if [ -z "$CASSANDRA_DRIVER_CLASSPATH" ]; then
    for jar in "$CASSANDRA_HOME"/../driver/lib/*.jar; do
        CASSANDRA_DRIVER_CLASSPATH="$CASSANDRA_DRIVER_CLASSPATH:$jar"
    done
fi

export CASSANDRA_DRIVER_CLASSPATH

if [ -z "$CASSANDRA_CLASSPATH" ]; then
    CASSANDRA_STRESS_CLASSPATH=""
    for jar in "$CASSANDRA_HOME"/tools/lib/*.jar; do
        CASSANDRA_STRESS_CLASSPATH="$CASSANDRA_STRESS_CLASSPATH:$jar"
    done
    CASSANDRA_CLASSPATH="$CASSANDRA_CONF"
    FOUND_CASSANDRA_JAR=0
    for jar in "$CASSANDRA_HOME"/lib/*.jar; do
        if filematch "*/lib/dse-db-all-*" "$jar" ; then
            if [ "$FOUND_CASSANDRA_JAR" != "0" ]; then
                echo "Found multiple DSE DB jar files in $(dirname "$jar"). Please make sure there is only one."
                exit 1
            fi
            FOUND_CASSANDRA_JAR=1
        fi
        if filematch "*/lib/cassandra-all-*" "$jar" ; then
            if [ "$FOUND_CASSANDRA_JAR" != "0" ]; then
                echo "Found multiple Cassandra jar files in $(dirname "$jar"). Please make sure there is only one."
                exit 1
            fi
            FOUND_CASSANDRA_JAR=1
        fi
        CASSANDRA_CLASSPATH="$CASSANDRA_CLASSPATH:$jar"
    done
    CASSANDRA_CLASSPATH="$CASSANDRA_CLASSPATH:$CASSANDRA_DRIVER_CLASSPATH"
    for dir in $DSE_LIB; do
        for jar in $dir/slf4j*; do
            if [ -r "$jar" ]; then
                CASSANDRA_CLASSPATH="$CASSANDRA_CLASSPATH:$jar"
            fi
        done
    done
fi

export JAVA_LIBRARY_PATH="$CASSANDRA_HOME/lib/sigar-bin:$JAVA_LIBRARY_PATH"

export CASSANDRA_CLASSPATH=$(remove_duplicates "$CASSANDRA_CLASSPATH")
export CASSANDRA_STRESS_CLASSPATH

# Set JAVA_AGENT option like we do in cassandra.in.sh
# as some tools (nodetool/dsetool) don't call that
export JAVA_AGENT="$JAVA_AGENT -javaagent:$CASSANDRA_HOME/lib/jamm-0.3.2.jar"

case "`uname`" in
    Linux)
        system_memory_in_mb=`free -m | awk '/:/ {print $2;exit}'`
        system_cpu_cores=`egrep -c 'processor([[:space:]]+):.*' /proc/cpuinfo`
    ;;
    FreeBSD)
        system_memory_in_bytes=`sysctl hw.physmem | awk '{print $2}'`
        system_memory_in_mb=`expr $system_memory_in_bytes / 1024 / 1024`
        system_cpu_cores=`sysctl hw.ncpu | awk '{print $2}'`
    ;;
    SunOS)
        system_memory_in_mb=`prtconf | awk '/Memory size:/ {print $3}'`
        system_cpu_cores=`psrinfo | wc -l`
    ;;
    Darwin)
        system_memory_in_bytes=`sysctl hw.memsize | awk '{print $2}'`
        system_memory_in_mb=`expr $system_memory_in_bytes / 1024 / 1024`
        system_cpu_cores=`sysctl hw.ncpu | awk '{print $2}'`
    ;;
    *)
        # assume reasonable defaults for e.g. a modern desktop or
        # cheap server
        system_memory_in_mb="2048"
        system_cpu_cores="2"
    ;;
esac

# Turn guice stack traces off. Graph uses child injectors and generating stacktraces when creating injectors is slow.
DSE_OPTS="$DSE_OPTS -Dguice_include_stack_traces=OFF"

# Include DSE's custom configuration loader
export DSE_OPTS="$DSE_OPTS -Ddse.system_memory_in_mb=$system_memory_in_mb -Dcassandra.config.loader=com.datastax.bdp.config.DseConfigurationLoader"

#########################################
# Setup Hadoop env
#########################################

hadoop2_dir_name="hadoop2-client"

#select hadoop distro

export HADOOP_HOME_WARN_SUPPRESS=true

if [ -z "$HADOOP2_HOME" ]; then
    if [ -n "$DSE_COMPONENTS_ROOT" ]; then
        export HADOOP2_HOME="$DSE_COMPONENTS_ROOT/$hadoop2_dir_name"
    else
        echo "Cannot determine HADOOP2_HOME."
        exit 1
    fi
fi

if [ -z "$HADOOP_BIN" ]; then
    export HADOOP_BIN="$HADOOP2_HOME/bin"
fi

if [ -z "$HADOOP2_CONF_DIR" ]; then
    if [ -r "$HADOOP2_HOME/conf" ]; then
        export HADOOP2_CONF_DIR="$HADOOP2_HOME/conf"
    elif [ -r "/etc/dse/$HADOOP2_NAME" ]; then
        export HADOOP2_CONF_DIR="/etc/dse/$hadoop2_dir_name"
    else
        echo "Cannot determine HADOOP2_CONF_DIR."
        exit 1
    fi
fi

if [ -z "$HADOOP_LOG_DIR" ]; then
    if [ -w "$DSE_LOG_ROOT" ] || [ -w "$DSE_LOG_ROOT/hadoop" ]; then
        export HADOOP_LOG_DIR="$DSE_LOG_ROOT/hadoop"
    else
        export HADOOP_LOG_DIR="$HOME/hadoop"
    fi
fi

if [ "$DSE_TOOL" != "1" ]; then
    #
    # Add hadoop native libs
    #
    JAVA_PLATFORM=`$HADOOP_BIN/hadoop org.apache.hadoop.util.PlatformName | sed -e 's/ /_/g'`
    if [ "$JAVA_PLATFORM" = "Linux-amd64-64" ]; then
        HADOOP2_JAVA_LIBRARY_PATH_LOCAL="$HADOOP2_HOME/lib/native"
        export HADOOP2_JAVA_LIBRARY_PATH="$HADOOP2_JAVA_LIBRARY_PATH_LOCAL:$JAVA_LIBRARY_PATH"
    fi

    export JAVA_LIBRARY_PATH="$HADOOP2_JAVA_LIBRARY_PATH_LOCAL:$JAVA_LIBRARY_PATH"

    #
    # Optional for things like lzo compression libs
    #
    if [ -n "$OTHER_HADOOP_NATIVE_ROOT" ]; then
        for jar in "$OTHER_HADOOP_NATIVE_ROOT"/*.jar; do
            HADOOP_CLASSPATH="$HADOOP_CLASSPATH:$jar"
        done

        export JAVA_LIBRARY_PATH="$JAVA_LIBRARY_PATH:$OTHER_HADOOP_NATIVE_ROOT/lib/native/${JAVA_PLATFORM}/"
    fi
fi


export HADOOP_HOME="$HADOOP2_HOME"
export HADOOP_CONF_DIR="$HADOOP2_CONF_DIR"

# needed for webapps

HADOOP_CLASSPATH="$HADOOP_CLASSPATH:$HADOOP_CONF_DIR:$HADOOP_HOME/lib/*"
HADOOP_CLASSPATH="$DSE_CLASSPATH:$HADOOP_CLASSPATH:$CASSANDRA_DRIVER_CLASSPATH"

# We need to add antlr runtime to the Hadoop classpath because
# the initializer for CassandraFileSystemThriftStore ends up calling
# down into CFMetaData.compile which requires it.

for jar in $CASSANDRA_HOME/lib/antlr-runtime-*.jar; do
  HADOOP_CLASSPATH="$HADOOP_CLASSPATH:$jar"
done

export HADOOP_CLASSPATH=$(remove_duplicates "$HADOOP_CLASSPATH")

#########################################
# Setup Solr env
#########################################

if [ -z "$SOLR_HOME" ]; then
    if [ -n "$DSE_COMPONENTS_ROOT" ]; then
        export SOLR_HOME="$DSE_COMPONENTS_ROOT/solr"
    else
        echo "Cannot determine SOLR_HOME."
        exit 1
    fi
fi

#
# Add solr jars
#
export SOLR_CLASSPATH="$SOLR_CLASSPATH:$SOLR_HOME/conf:$SOLR_HOME/lib/*"

#only set these things when starting cassandra
if [ "$DSE_CMD" = "cassandra" -o "$(basename $0)" = "sstablescrub" ]; then

    #
    # Initialize Tomcat env
    #
    if [ -z "$TOMCAT_HOME" ]; then
        if [ -r "$DSE_COMPONENTS_ROOT/tomcat" ]; then
            export TOMCAT_HOME="$DSE_COMPONENTS_ROOT/tomcat"
        else
            echo "Cannot determine TOMCAT_HOME."
            exit 1
        fi
    fi

    if [ -z "$TOMCAT_CONF_DIR" ]; then
        if [ -r "$TOMCAT_HOME/conf" ]; then
            export TOMCAT_CONF_DIR="$TOMCAT_HOME/conf"
        elif [ -r "/etc/dse/tomcat/conf" ]; then
            export TOMCAT_CONF_DIR="/etc/dse/tomcat/conf"
        else
            echo "Cannot determine TOMCAT_CONF_DIR."
            exit 1
        fi
    fi

    if ! [ -r "$TOMCAT_CONF_DIR/catalina.properties" ]; then
        echo "catalina.properties is missing from $TOMCAT_CONF_DIR."
        exit 1
    fi
    
    if ! [ -r "$TOMCAT_CONF_DIR/context.xml" ]; then
        echo "context.xml is missing from $TOMCAT_CONF_DIR. The default context may induce excessive JAR scanning and therefore long startup times."
        exit 1
    fi

    if [ -z "$TOMCAT_LOGS" ]; then
        if [ -w "$DSE_LOG_ROOT/tomcat" ]; then
            export TOMCAT_LOGS="$DSE_LOG_ROOT/tomcat"
        else
            export TOMCAT_LOGS="$HOME/tomcat"
        fi
    fi

    export TOMCAT_BIN="$TOMCAT_HOME/bin"
    if [ -z $CATALINA_BASE ]; then
        export CATALINA_BASE="$TOMCAT_HOME"
    fi
    export CATALINA_HOME="$TOMCAT_HOME"

    #
    # Add Tomcat jars
    #
    export TOMCAT_CLASSPATH="$TOMCAT_CLASSPATH:$TOMCAT_CONF_DIR:$TOMCAT_HOME/lib/*"

fi

#########################################
# Setup Spark env
#########################################

if [ -z "$SPARK_HOME" ]; then
    if [ -n "$DSE_COMPONENTS_ROOT" ]; then
        export SPARK_HOME="$DSE_COMPONENTS_ROOT/spark"
    else
        echo "Cannot determine SPARK_HOME."
        exit 1
    fi
fi

if [ -z "$SPARK_BIN" ]; then
    export SPARK_BIN="$SPARK_HOME/bin"
fi

if [ -z "$SPARK_SBIN" ]; then
    export SPARK_SBIN="$SPARK_HOME/sbin"
fi

if [ -z "$SPARK_CONF_DIR" ]; then
    if [ -r "$SPARK_HOME/conf" ]; then
        export SPARK_CONF_DIR="$SPARK_HOME/conf"
    elif [ -r "/etc/dse/spark" ]; then
        export SPARK_CONF_DIR="/etc/dse/spark"
    else
        echo "Cannot determine SPARK_CONF_DIR."
        exit 1
    fi
fi

if [ -z "$SPARK_PYTHON_LIB_DIR" ]; then
    if [ -r "$SPARK_HOME/python/lib" ]; then
        export SPARK_PYTHON_LIB_DIR="$SPARK_HOME/python/lib"
    elif  [ -r "/usr/share/dse/spark/python/lib" ]; then
        export SPARK_PYTHON_LIB_DIR="/usr/share/dse/spark/python/lib"
    else
        echo "Cannot determine SPARK_PYTHON_LIB_DIR."
        exit 1
    fi
fi


if [ -z "$SPARK_JOBSERVER_HOME" ]; then
    if [ -n "$DSE_COMPONENTS_ROOT" ]; then
        export SPARK_JOBSERVER_HOME="$DSE_COMPONENTS_ROOT/spark/spark-jobserver"
    else
        echo "Cannot determine SPARK_JOBSERVER_HOME."
        exit 1
    fi
fi


if [ -z "$SCALA_HOME" ]; then
    if [ -n "$DSE_COMPONENTS_ROOT" ]; then
        export SCALA_HOME="$DSE_COMPONENTS_ROOT/scala"
    else
        echo "Cannot determine SCALA_HOME."
        exit 1
    fi
fi

ANALYTICS_CORE_HOME="$DSE_COMPONENTS_ROOT/analytics-core"
SPARK_LIB_CLASSPATH="$SPARK_LIB_CLASSPATH:$SPARK_CONF_DIR"
export SPARK_LIB_CLASSPATH="$SPARK_LIB_CLASSPATH:$SPARK_HOME/lib/*:$ANALYTICS_CORE_HOME/lib/*"

# This function basically runs the provided command
# One thing which is done before actually executing the command is setting CLASSPATH env variable. If the framework
# name is "dse" the classpath remains without changes (original DSE classpath). In other cases, the CLASSPATH variable
# is set to the classpath of a certain framework.
run_with_framework() {
    case "$DSE_CLIENT_FRAMEWORK" in
        dse)
            cp="$(remove_duplicates "$DSE_JARS:$SPARK_LIB_CLASSPATH:$SPARK_CLIENT_CLASSPATH:$HADOOP_CLASSPATH:$DSE_CLASSPATH:$CASSANDRA_CLASSPATH:$GRAPH_CLASSPATH:$SEARCH_CLASSPATH")"
            ;;
        spark-2.2)
            cp="$SPARK_CONF_DIR:$HADOOP2_CONF_DIR:$DSE_CONF:$SPARK_HOME/client-lib/*:"
            ;;
        *)
            echo "Invalid framework: $DSE_CLIENT_FRAMEWORK"
            echo "Valid Options: dse, spark-2.2"
            exit 1
            ;;
    esac

    export CLASSPATH="$cp"
    if [ "$1" == "classpath" ]; then
        echo "$CLASSPATH"
    else
        exec "$@"
    fi
}

#########################################
# Setup Graph env
#########################################

if [ -z "$GRAPH_HOME" ]; then
    if [ -n "$DSE_COMPONENTS_ROOT" ]; then
        export GRAPH_HOME="$DSE_COMPONENTS_ROOT/graph"
    else
        echo "Cannot determine GRAPH_HOME."
        exit 1
    fi
fi

if [ -z "$GRAPH_CONF_DIR" ]; then
    if [ -r "$GRAPH_HOME/conf" ]; then
        export GRAPH_CONF_DIR="$GRAPH_HOME/conf"
    elif [ -r "/etc/dse/graph" ]; then
        export GRAPH_CONF_DIR="/etc/dse/graph"
    else
        echo "Cannot determine GRAPH_CONF_DIR."
        exit 1
    fi
fi

export GREMLIN_SERVER_LOGBACK_CONF_FILE="$GRAPH_CONF_DIR/logback-gremlin-server.xml"
export GREMLIN_LOG_DIR="$CASSANDRA_LOG_DIR"

# No graph-specific binaries exist right now
#if [ -z "$GRAPH_BIN" ]; then
#    export GRAPH_BIN="$GRAPH_HOME/bin"
#fi

export GRAPH_CLASSPATH="$GRAPH_CLASSPATH:$GRAPH_HOME/lib/*"

#########################################
# Add all components classpaths
# to global CLASSPATH
#########################################

export CLASSPATH="$DSE_CLASSPATH:$CASSANDRA_CLASSPATH:$SOLR_CLASSPATH:$TOMCAT_CLASSPATH:$HADOOP_CLASSPATH:$SPARK_LIB_CLASSPATH:$GRAPH_CLASSPATH"
