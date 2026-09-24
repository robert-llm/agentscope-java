#!/bin


export JAVA_HOME="/d/software/jdk-17.0.12"
export PATH=$JAVA_HOME/bin:$PATH

#echo $PATH

#export BUILDER_DATAPLANE_PUBLIC_URL="http://localhost:8082"

#agentscope-service/scripts/dev-down.sh && BUILDER_REBUILD=1 agentscope-service/scripts/dev-up.sh
agentscope-service/scripts/dev-down.sh && BUILDER_REBUILD=3 agentscope-service/scripts/dev-up.sh

#agentscope-service/scripts/dev-down.sh && agentscope-service/scripts/dev-up.sh

