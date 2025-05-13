package com.xiaozhi.websocket.llm.providers;

import com.xiaozhi.websocket.llm.api.AbstractOpenAiLlmService;

/**
 * OpenAI LLM服务实现
 */
public class OpenAiService extends AbstractOpenAiLlmService {

    /**
     * 构造函数
     * 
     * @param endpoint API端点
     * @param apiKey   API密钥
     * @param model    模型名称
     */
    public OpenAiService(String endpoint, String appId, String apiKey, String apiSecret, String model) {
        super(endpoint, appId, apiKey, apiSecret, model);
    }


    @Override
    public String getProviderName() {
        return "openai";
    }
}