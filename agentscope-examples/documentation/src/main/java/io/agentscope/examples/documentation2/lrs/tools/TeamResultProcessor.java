/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.documentation2.lrs.tools;

/**
 * Processes the raw result returned by a team before it becomes the tool return value.
 *
 * <p>This is always applied, regardless of the {@code stream_mode} parameter:
 * <ul>
 *   <li>{@code transparent} mode: team events are forwarded to the user in real-time,
 *       then the final result is processed by this processor.
 *   <li>{@code summary} mode: team events are suppressed, the final result is still
 *       processed by this processor before being returned to the LLM.
 * </ul>
 *
 * <p>Implementations are registered at construction time on {@link RoutingTools},
 * so different deployments can customize the output format without changing tool code.
 *
 * <h3>Example</h3>
 *
 * <pre>{@code
 * TeamResultProcessor processor = (teamName, subject, rawResult) ->
 *     "团队: " + teamName + "\n任务: " + subject + "\n结果:\n" + rawResult;
 *
 * RoutingTools tools = new RoutingTools(httpClient, namespace, processor);
 * }</pre>
 */
@FunctionalInterface
public interface TeamResultProcessor {

    /**
     * Transform the raw team result into the value returned to the Router LLM.
     *
     * @param teamName  the team that processed the task
     * @param subject   the original task subject
     * @param rawResult the raw result text from the team's task
     * @return the processed result string that becomes the tool observation
     */
    String process(String teamName, String subject, String rawResult);
}
