#!/bin/bash


export JAVA_HOME="/d/software/jdk-17.0.12"
export PATH=$JAVA_HOME/bin:$PATH

export DASHSCOPE_API_KEY=sk-ce9f83a5aa404184a0d2b1b2e31f3352

mvn exec:java -pl agentscope-examples/documentation -Dexec.mainClass=io.agentscope.examples.documentation2.quickstart.BasicChatExample
