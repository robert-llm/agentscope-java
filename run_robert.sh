#!/bin

#注意，启动服务前，得先启动Docker镜像服务

export JAVA_HOME="/d/software/jdk-17.0.12"
export PATH=$JAVA_HOME/bin:$PATH

#mvn -pl agentscope-robert clean spring-boot:run -X
mvn -pl agentscope-robert clean spring-boot:run 



