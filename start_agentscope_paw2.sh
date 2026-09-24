#!/bin/bash



# 终端 1 — 控制面（示例）
export AISTIO_TRANSCRIPT_FS_ROOT="$HOME/.agentscope/claw/transcripts"
# 启动 aistiod，HTTP 默认 :8081

# 终端 2 — paw BYO 数据面
export DASHSCOPE_API_KEY=sk-ce9f83a5aa404184a0d2b1b2e31f3352
export CLAW_AISTIO_ENABLED=true
export AISTIO_CONTROL_HTTP=http://localhost:8081
export BUILDER_INTERNAL_TOKEN=local-dev-internal-token-at-least-32chars
# 必须与 agentscope.json 里 main 对应的 agent id 一致（自动生成配置为 default）
export CLAW_AISTIO_AGENT_NAME=default
export CLAW_AISTIO_NAMESPACE=default
export CLAW_TRANSCRIPT_ROOT="$HOME/.agentscope/claw/transcripts"   # 可省略，默认即此路径
export CLAW_PORT=8090

#第一次需要执行，后续不需要了，除非改代码了
#mvn -pl agentscope-examples/agents/agentscope-paw -am clean package -DskipTests

java -jar agentscope-examples/agents/agentscope-paw/target/agentscope-paw-*.jar


