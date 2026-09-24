#!/bin/bash


export JAVA_HOME="/d/software/jdk-17.0.12"
export PATH=$JAVA_HOME/bin:$PATH

export DASHSCOPE_API_KEY=sk-ce9f83a5aa404184a0d2b1b2e31f3352
export BUILDER_INTERNAL_TOKEN=local-dev-internal-token-at-least-32chars


# 先编译
#mvn -pl agentscope-examples/agents/agentscope-codingagent -am install -DskipTests
mvn compile -pl agentscope-examples/documentation -am install -DskipTests


$env:USER="robert2"
# 再运行（-f 指向目标模块的 pom.xml）
#mvn -f agentscope-examples/agents/agentscope-codingagent/pom.xml exec:java \
#    -Dexec.mainClass=io.agentscope.harness.coding.CodingChatCli \
#    -Dexec.args="--user robert"

# 3. 运行示例
mvn exec:java -pl agentscope-examples/documentation \
    -Dexec.mainClass=io.agentscope.examples.documentation2.quickstart.BasicChatWithServiceExample
