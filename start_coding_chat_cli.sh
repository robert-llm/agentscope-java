#!/bin/bash


export JAVA_HOME="/d/software/jdk-17.0.12"
export PATH=$JAVA_HOME/bin:$PATH

export DASHSCOPE_API_KEY=sk-ce9f83a5aa404184a0d2b1b2e31f3352

#mvn -pl agentscope-examples/agents/agentscope-codingagent -am compile \
#    org.codehaus.mojo:exec-maven-plugin:3.6.3:java \
#    -Dexec.mainClass=io.agentscope.harness.coding.CodingChatCli
#

# 先编译
mvn -pl agentscope-examples/agents/agentscope-codingagent -am install -DskipTests


$env:USER="robert"
# 再运行（-f 指向目标模块的 pom.xml）
mvn -f agentscope-examples/agents/agentscope-codingagent/pom.xml exec:java \
    -Dexec.mainClass=io.agentscope.harness.coding.CodingChatCli \
    -Dexec.args="--user robert"
