//package com.robert.agent;
//
//import com.robert.agent.model.AgentResult;
//import com.robert.agent.model.SubAgentRequest;
//
//import java.util.concurrent.CompletableFuture;
//import java.util.concurrent.ExecutorService;
//
//public class EIPSubAgent implements SubAgent {
//
//    private final ReActSmartAgent delegate;
//    private final ExecutorService agentExecutor;
//
//    public EIPSubAgent(ReActSmartAgent delegate, ExecutorService agentExecutor) {
//        this.delegate = delegate;
//        this.agentExecutor = agentExecutor;
//    }
//
//    @Override
//    public String agentId() {
//        return "eip-agent";
//    }
//
//    @Override
//    public String description() {
//        return "EIP 平台制度/新闻/公告/流程/组织/HR/IT 问答";
//    }
//
//    @Override
//    public CompletableFuture<AgentResult> execute(SubAgentRequest req, AgentEventListener listener) {
//        return CompletableFuture.supplyAsync(() -> {
//            AgentChatContext ctx = req.getContext();
//            ctx.setEventSink((eventJson, module) -> {
//                dispatchEvent(eventJson, listener, req.getModule());
//            });
//            return delegate.execute(ctx);
//        }, agentExecutor);
//    }
//
//    private void dispatchEvent(String eventJson, AgentEventListener listener, int module) {
//        // TODO: 解析 eventJson 并分发到 listener 对应方法
//    }
//}
