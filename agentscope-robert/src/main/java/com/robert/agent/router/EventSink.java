package com.robert.agent.router;

public interface EventSink {
    /**
     * @param event  序列化后的事件 JSON 字符串
     * @param module 0=EIP / 1=learning；单 Agent 直通时传 -1 表示剥除
     */
    void send(String event, int module);
}
