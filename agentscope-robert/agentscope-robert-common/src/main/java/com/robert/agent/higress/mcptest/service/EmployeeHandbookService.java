package com.robert.agent.higress.mcptest.service;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.reader.ReaderInput;
import io.agentscope.core.rag.reader.SplitStrategy;
import io.agentscope.core.rag.reader.WordReader;
import io.agentscope.core.rag.store.InMemoryStore;
import io.agentscope.core.embedding.dashscope.DashScopeTextEmbedding;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import jakarta.annotation.PostConstruct;
import java.util.List;

/**
 * 员工手册知识库服务。
 *
 * <p>封装了基于 RAG（检索增强生成）的员工手册问答核心逻辑：
 * <ol>
 *   <li>启动时加载 .docx 员工手册文档，解析、分块、Embedding 后存入内存向量库</li>
 *   <li>接收用户提问，通过 ReActAgent + GENERIC RAG 模式自动检索知识库并生成回答</li>
 *   <li>提供流式输出接口，返回 Flux&lt;AgentEvent&gt; 供 Controller 推送 SSE</li>
 * </ol>
 *
 * @Author: luorenshu
 * @Date: 2026/10/21
 */
@SuppressWarnings({"deprecation", "removal"})
@Service
public class EmployeeHandbookService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeHandbookService.class);

    /**
     * 员工手册 .docx 文件路径，通过配置文件指定。
     * 默认值为本机开发环境路径，生产环境请通过 application.yml 覆盖。
     */
    @Value("${employee.handbook.doc-path:E:\\books\\AI\\知识库\\弘金地\\弘金地员工手册202107版本.docx}")
    private String docPath;

    /**
     * DashScope API Key，从环境变量或配置文件读取。
     */
    @Value("${employee.handbook.dashscope-api-key:${DASHSCOPE_API_KEY:}}")
    private String dashscopeApiKey;

    /**
     * 使用的 LLM 模型名称。
     */
    @Value("${employee.handbook.model-name:qwen-plus}")
    private String modelName;

    /**
     * Embedding 模型名称。
     */
    @Value("${employee.handbook.embedding-model-name:text-embedding-v3}")
    private String embeddingModelName;

    /**
     * Embedding 向量维度。
     */
    @Value("${employee.handbook.embedding-dimensions:1024}")
    private int embeddingDimensions;

    /**
     * 文档分块大小（字符数）。
     */
    @Value("${employee.handbook.chunk-size:512}")
    private int chunkSize;

    private ReActAgent agent;

    @PostConstruct
    public void init() {
        if (dashscopeApiKey == null || dashscopeApiKey.isBlank()) {
            log.warn("DashScope API Key 未配置，员工手册知识库服务不会初始化。"
                    + "请设置环境变量 DASHSCOPE_API_KEY 或在配置文件中指定 employee.handbook.dashscope-api-key");
            return;
        }

        try {
            log.info("正在初始化员工手册知识库，文档路径: {}", docPath);

            // 1. 用 WordReader 解析 .docx 文档
            WordReader reader = new WordReader(
                    chunkSize,
                    SplitStrategy.PARAGRAPH,
                    50,     // chunk 间重叠 50 字符
                    false,  // 不提取图片（员工手册以文字为主）
                    false,  // 不单独分离表格
                    io.agentscope.core.rag.reader.TableFormat.MARKDOWN
            );
            ReaderInput input = ReaderInput.fromPath(docPath);
            List<Document> documents = reader.read(input).block();
            log.info("文档解析完成，共生成 {} 个文档块", documents != null ? documents.size() : 0);

            if (documents == null || documents.isEmpty()) {
                log.error("员工手册文档解析结果为空，请检查文件路径和内容");
                return;
            }

            // 2. 创建 Embedding 模型
            EmbeddingModel embeddingModel = DashScopeTextEmbedding.builder()
                    .apiKey(dashscopeApiKey)
                    .modelName(embeddingModelName)
                    .dimensions(embeddingDimensions)
                    .build();

            // 3. 创建内存向量存储
            var vectorStore = InMemoryStore.builder()
                    .dimensions(embeddingDimensions)
                    .build();

            // 4. 组装知识库
            Knowledge knowledge = SimpleKnowledge.builder()
                    .embeddingModel(embeddingModel)
                    .embeddingStore(vectorStore)
                    .build();

            // 5. 将文档块写入知识库（自动 Embedding + 存储）
            knowledge.addDocuments(documents).block();
            log.info("员工手册知识库构建完成，共存储 {} 个文档向量", documents.size());

            // 6. 创建 LLM 对话模型
            DashScopeChatModel chatModel = DashScopeChatModel.builder()
                    .apiKey(dashscopeApiKey)
                    .modelName(modelName)
                    .stream(true)
                    .build();

            // 7. 构建 RAG Agent（GENERIC 模式：每次提问自动检索知识库注入上下文）
            agent = ReActAgent.builder()
                    .name("弘金地员工助手")
                    .sysPrompt("你是弘金地公司的员工手册智能助手。"
                            + "请严格根据知识库中的员工手册内容回答用户的问题。"
                            + "如果知识库中没有相关信息，请如实告知用户你无法确认该信息，建议咨询 HR 部门。"
                            + "回答时请条理清晰、引用具体条款。")
                    .model(chatModel)
                    .knowledge(knowledge)
                    .ragMode(io.agentscope.core.rag.RAGMode.GENERIC)
                    .build();

            log.info("员工手册 RAG Agent 初始化完成，模型: {}", modelName);

        } catch (Exception e) {
            log.error("员工手册知识库初始化失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 流式对话：接收用户提问，返回 AgentEvent 事件流。
     *
     * <p>前端可通过 SSE 接收 {@link TextBlockDeltaEvent} 中的增量文本，
     * 实现打字机效果。
     *
     * @param message 用户提问内容
     * @return AgentEvent 事件流（包含文本增量、工具调用、完成等事件）
     */
    public Flux<AgentEvent> streamChat(String message) {
        if (agent == null) {
            return Flux.error(new IllegalStateException(
                    "员工手册知识库服务未初始化，请检查配置（API Key、文档路径）"));
        }

        Msg userMsg = Msg.builder()
                .role(MsgRole.USER)
                .textContent(message)
                .build();

        return agent.streamEvents(userMsg)
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 同步对话：接收用户提问，返回完整回答。
     *
     * @param message 用户提问内容
     * @return Agent 的完整回答
     */
    public Msg chat(String message) {
        if (agent == null) {
            throw new IllegalStateException(
                    "员工手册知识库服务未初始化，请检查配置（API Key、文档路径）");
        }

        Msg userMsg = Msg.builder()
                .role(MsgRole.USER)
                .textContent(message)
                .build();

        return agent.call(userMsg).block();
    }

    /**
     * 检查服务是否已就绪。
     */
    public boolean isReady() {
        return agent != null;
    }
}
