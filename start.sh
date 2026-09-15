#!/bin

#注意，启动服务前，得先启动Docker镜像服务

export JAVA_HOME="/d/software/jdk-17.0.12"
export PATH=$JAVA_HOME/bin:$PATH

#echo $PATH

# 开启这两个参数后，控制面板Managed Agents下创建的agent也会注册到Dashboard下
#export BUILDER_DATAPLANE_REGISTER="true"
#export BUILDER_DATAPLANE_PUBLIC_URL="http://localhost:8082"

#agentscope-service/scripts/dev-down.sh && BUILDER_REBUILD=1 agentscope-service/scripts/dev-up.sh
agentscope-service/scripts/dev-down.sh && agentscope-service/scripts/dev-up.sh


