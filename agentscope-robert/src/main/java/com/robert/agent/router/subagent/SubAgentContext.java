package com.robert.agent.router.subagent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SubAgent 流转上下文 — 在 Agent 链路中层层传递的共享状态容器。
 *
 * <p>RouterAgent 在串行调度多个 SubAgent 时创建一个 SubAgentContext 实例，
 * 每个 SubAgent 都可以从中读取上游数据、也可以往里写入中间结果供下游消费。</p>
 *
 * <h3>基本用法</h3>
 * <pre>{@code
 * // SubAgent A：写入中间数据
 * ctx.put("extracted_entities", entityList);
 * ctx.setOutput("Agent A 的完整回答文本");
 *
 * // SubAgent B：读取上游数据
 * List<Entity> entities = ctx.get("extracted_entities");
 * String prevOutput = ctx.getOutput();
 * }</pre>
 *
 * <h3>自定义扩展</h3>
 * <p>如果业务需要强类型的上下文，可以继承本类添加业务字段：</p>
 * <pre>{@code
 * public class BizContext extends SubAgentContext {
 *     private String tenantId;
 *     private List<String> approvedRoles;
 *     // getter/setter ...
 * }
 * }</pre>
 * <p>然后在 RouterAgent 创建时传入自定义子类实例即可。</p>
 */
public class SubAgentContext {

    /** 通用数据容器：SubAgent 之间传递任意中间数据 */
    private final Map<String, Object> data = new LinkedHashMap<>();

    /** 累积的文本输出：每个 SubAgent 处理完后追加自己的输出 */
    private final StringBuilder outputBuffer = new StringBuilder();

    /** 当前正在处理的 SubAgent id（由 RouterAgent 在每次调度前设置） */
    private String currentAgentId;

    /** 当前 SubAgent 在链路中的序号（0-based） */
    private int currentIndex = -1;

    /** 路由目标总数（供 SubAgent 判断自己是否是最后一个） */
    private int totalTargets = 0;

    // ==================== 通用数据读写 ====================

    /**
     * 写入数据到上下文，供下游 SubAgent 消费。
     *
     * @param key   数据标识
     * @param value 数据值（任意类型）
     */
    public void put(String key, Object value) {
        data.put(key, value);
    }

    /**
     * 读取上下文中指定 key 的数据。
     *
     * @param key  数据标识
     * @param type 期望的返回类型（自动转型）
     * @return 数据值，不存在或类型不匹配时返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Object val = data.get(key);
        if (val != null && type.isInstance(val)) {
            return (T) val;
        }
        return null;
    }

    /**
     * 读取上下文中指定 key 的数据（Object 类型，调用方自行转型）。
     */
    public Object get(String key) {
        return data.get(key);
    }

    /** 是否包含指定 key */
    public boolean contains(String key) {
        return data.containsKey(key);
    }

    /** 获取所有数据的只读视图 */
    public Map<String, Object> getAllData() {
        return Map.copyOf(data);
    }

    // ==================== 文本输出累积 ====================

    /**
     * 追加当前 SubAgent 的文本输出。
     * RouterAgent 在转发 MESSAGE 事件时自动调用。
     */
    public void appendOutput(String fragment) {
        if (fragment != null) {
            outputBuffer.append(fragment);
        }
    }

    /**
     * 获取上游所有 SubAgent 的累积文本输出。
     * 当前 SubAgent 可据此做上下文参考。
     */
    public String getOutput() {
        return outputBuffer.toString();
    }

    /** 是否有上游文本输出 */
    public boolean hasOutput() {
        return outputBuffer.length() > 0;
    }

    // ==================== 链路位置信息 ====================

    public String getCurrentAgentId() { return currentAgentId; }
    public void setCurrentAgentId(String currentAgentId) { this.currentAgentId = currentAgentId; }

    public int getCurrentIndex() { return currentIndex; }
    public void setCurrentIndex(int currentIndex) { this.currentIndex = currentIndex; }

    public int getTotalTargets() { return totalTargets; }
    public void setTotalTargets(int totalTargets) { this.totalTargets = totalTargets; }

    /** 当前是否是第一个 Agent */
    public boolean isFirst() { return currentIndex == 0; }

    /** 当前是否是最后一个 Agent */
    public boolean isLast() { return currentIndex == totalTargets - 1; }
}
