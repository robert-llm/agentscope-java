package com.robert.agent.higress.mcptest.provider;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 员工手册问答请求体。
 *
 * @Author: luorenshu
 * @Date: 2026/10/21
 */
@Schema(description = "员工手册问答请求")
public class HandbookChatRequest {

    @Schema(description = "用户提问内容", requiredMode = Schema.RequiredMode.REQUIRED, example = "公司的年假政策是什么？")
    private String message;

    public HandbookChatRequest() {
    }

    public HandbookChatRequest(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
