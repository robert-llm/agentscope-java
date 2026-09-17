/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.documentation2.quickstart;

import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.aistio.Aistio;
import io.agentscope.extensions.aistio.AistioConfig;
import io.agentscope.extensions.aistio.SessionBridge;
import io.agentscope.extensions.aistio.adapter.AgentScopeAdapter;
import io.agentscope.harness.agent.HarnessAgent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Paths;

/**
 * BasicChatWithServiceExample - Interactive chat registered with AgentScope Service.
 *
 * <p>Demonstrates:
 * <ul>
 *   <li>Creating a {@link HarnessAgent} with workspace, memory and aistio middleware</li>
 *   <li>Registering the agent to the Service control plane via HTTP self-registration</li>
 *   <li>Interactive streaming chat visible in the Service console</li>
 * </ul>
 *
 * <p><b>Prerequisites:</b>
 * <ol>
 *   <li>Start the Service (source docker-compose):
 *       {@code cd agentscope-service && docker compose up -d}</li>
 *   <li>Set {@code DASHSCOPE_API_KEY} environment variable</li>
 * </ol>
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.quickstart.BasicChatWithServiceExample
 * </pre>
 *
 * <p><b>Environment variables:</b>
 * <ul>
 *   <li>{@code DASHSCOPE_API_KEY} (required) - DashScope API key for the model</li>
 *   <li>{@code AISTIO_CONTROL_PLANE_HTTP} (default: http://localhost:8081) - Control plane URL</li>
 *   <li>{@code BUILDER_INTERNAL_TOKEN} (default: compose-local-internal-token-at-least-32chars)
 *       - Internal token matching the Service's configuration</li>
 *   <li>{@code AISTIO_CONTRACT_PORT} (default: 18090) - Port for the contract HTTP server</li>
 * </ul>
 *
 * <p>After starting, open the Service console at {@code http://localhost:8080},
 * navigate to <b>Managed Agents</b>, and you should see the registered agent.
 * Create a session to chat with it through the console UI.
 */
public class BasicChatWithServiceExample {

    /** Default internal token matching the source docker-compose default. */
    private static final String DEFAULT_INTERNAL_TOKEN =
            "compose-local-internal-token-at-least-32chars";

    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Error: DASHSCOPE_API_KEY environment variable not set.");
            System.err.println("Get your API key from: https://dashscope.aliyun.com");
            System.err.println("Then set it with: export DASHSCOPE_API_KEY=your_api_key");
            System.exit(1);
        }

        // Service connection settings
        String controlPlaneHttp = envOr("AISTIO_CONTROL_PLANE_HTTP", "http://localhost:8081");
        String internalToken = envOr("BUILDER_INTERNAL_TOKEN", DEFAULT_INTERNAL_TOKEN);
        int contractPort = Integer.parseInt(envOr("AISTIO_CONTRACT_PORT", "18090"));
        String agentName = "basic-chat-agent";

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Basic Chat with Service Example");
        System.out.println("=".repeat(60));
        System.out.println("Agent name   : " + agentName);
        System.out.println("Control plane: " + controlPlaneHttp);
        System.out.println("Contract port: " + contractPort);
        System.out.println("-".repeat(60));
        System.out.println("This agent registers with the Service control plane.");
        System.out.println("Open console at http://localhost:8080 to interact.");
        System.out.println("Type 'exit' to quit.\n");

        // Step 1: Create the adapter (provides observation middleware)
        AgentScopeAdapter adapter = new AgentScopeAdapter();

        // Step 2: Build a HarnessAgent with workspace + aistio middleware
        //   HarnessAgent wraps ReActAgent and adds workspace management,
        //   memory (flush + consolidation), skills, and session persistence.
        //   State is auto-persisted to ~/.agentscope/state/<agentId>/.
        String workspaceDir =
                Paths.get(System.getProperty("user.home"), ".agentscope", "basic-chat", "workspace")
                        .toString();

        System.out.println("Workspace: " + workspaceDir);
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("Assistant")
                        .sysPrompt("You are a helpful AI assistant. Be friendly and concise.")
                        .model("dashscope:qwen-plus")
                        .toolkit(new Toolkit())
                        .workspace(workspaceDir)
                        .middleware(adapter.middleware())
                        .build();

        // Step 3: Configure and start the aistio bridge
        AistioConfig config =
                AistioConfig.builder(agentName)
                        .controlPlaneHttp(controlPlaneHttp)
                        .internalToken(internalToken)
                        .namespace("default")
                        .enableEvents(true)
                        .contractHttpPort(contractPort)
                        .startGrpc(false)
                        .startHttp(true)
                        .build();

        SessionBridge bridge = Aistio.instrument(agent, config, adapter);

        System.out.println("Agent registered! Contract server on port " + bridge.getContractPort());
        System.out.println("Waiting for sessions from the console...\n");

        // Step 4: Interactive chat loop (also accessible via the console UI)
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        try {
            while (true) {
                System.out.print("You: ");
                String input = reader.readLine();

                if (input == null || input.trim().equalsIgnoreCase("exit")) {
                    System.out.println("\nGoodbye!");
                    break;
                }
                if (input.isBlank()) {
                    continue;
                }

                Msg userMsg = new UserMessage(input.trim());

                System.out.print("\nAssistant: ");
                agent.streamEvents(userMsg)
                        .doOnNext(
                                event -> {
                                    if (event instanceof TextBlockDeltaEvent e) {
                                        System.out.print(e.getDelta());
                                    }
                                })
                        .blockLast();
                System.out.println("\n");
            }
        } finally {
            // Step 5: Clean up
            bridge.close();
            System.out.println("Bridge closed.");
        }
    }

    private static String envOr(String name, String defaultValue) {
        String v = System.getenv(name);
        return (v != null && !v.isBlank()) ? v : defaultValue;
    }
}
