package com.xiaozhi.websocket.llm.providers;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xiaozhi.utils.JsonUtil;
import com.xiaozhi.websocket.llm.api.AbstractOpenAiLlmService;
import com.xiaozhi.websocket.llm.api.ToolCallInfo;
import com.xiaozhi.websocket.llm.memory.ModelContext;
import com.xiaozhi.websocket.llm.tool.function.FunctionSessionHolder;
import com.xiaozhi.websocket.llm.tool.function.bean.FunctionLlmDescription;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 讯飞星火 LLM服务实现
 */
public class SparkService extends AbstractOpenAiLlmService {

    /**
     * 构造函数
     * 
     * @param endpoint API端点 (host url)
     * @param apiKey   API密钥 (apiKey)
     * @param model    模型名称
     */
    public SparkService(String endpoint, String appId, String apiKey, String apiSecret, String model) {
        super(endpoint, appId, apiKey != null?apiKey : apiSecret, apiSecret, model);

    }

    @Override
    protected String chat(List<Map<String, Object>> messages) throws IOException {
        try {
            // 构建请求体
            Map<String, Object> requestBody = new HashMap<>();

            Map<String, Object> chat = new HashMap<>();
            chat.put("model", model);

            Map<String, Object> requestPayload = new HashMap<>();

            // 转换消息格式为讯飞要求的格式
            List<Map<String, Object>> sparkMessages = new ArrayList<>();
            for (Map<String, Object> message : messages) {
                Map<String, Object> sparkMessage = new HashMap<>();
                sparkMessage.put("role", message.get("role"));
                sparkMessage.put("content", message.get("content"));
                sparkMessages.add(sparkMessage);
            }

            requestPayload.put("message", sparkMessages);

            requestBody.put("payload", requestPayload);

            // 转换为JSON
            String jsonBody = objectMapper.writeValueAsString(requestBody);

            // 构建请求 - 使用简单的Bearer token认证
            Request request = new Request.Builder()
                    .url(endpoint + "/chat/completions")
                    .post(RequestBody.create(jsonBody, JSON))
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Authorization", "Bearer " + apiSecret) // 使用apiSecret作为Bearer token
                    .build();

            // 发送请求
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("请求失败: " + response);
                }

                String responseBody = response.body().string();
                Map<String, Object> responseMap = objectMapper.readValue(responseBody,
                        new TypeReference<Map<String, Object>>() {
                        });

                // 解析星火API响应
                Map<String, Object> responsePayload = (Map<String, Object>) responseMap.get("payload");
                if (responsePayload != null) {
                    List<Map<String, Object>> choices = (List<Map<String, Object>>) responsePayload.get("choices");
                    if (choices != null && !choices.isEmpty()) {
                        Map<String, Object> choice = choices.get(0);
                        if (choice.containsKey("content")) {
                            return (String) choice.get("content");
                        }
                    }
                }

                throw new IOException("无法解析星火响应");
            }
        } catch (Exception e) {
            throw new IOException("星火API请求失败: " + e.getMessage(), e);
        }
    }

    @NotNull
    protected String buildRequestJson(List<Map<String, Object>> messages, ModelContext modelContext) {
        // 构建请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("stream", true);
        requestBody.put("messages", messages);


        if(modelContext.isUseFunctionCall() && supportFunctionCall) {
            FunctionSessionHolder functionSessionHolder = modelContext.getFunctionSessionHolder();
            if(functionSessionHolder != null){
                List<FunctionLlmDescription> tools = functionSessionHolder.getAllFunctionLlmDescription();
                if(!tools.isEmpty()){
                    requestBody.put("tools", tools);
                }
            }
        }

        // 转换为JSON
        return JsonUtil.toJson(requestBody);
    }

    /**
     * 从toolCallsData中抽取工具调用信息
     * @param toolCallInfo
     * @param toolCallsData
     */
    protected ToolCallInfo getToolCallInfo(ToolCallInfo toolCallInfo, Object toolCallsData) {
        if(toolCallInfo == null){
            toolCallInfo = new ToolCallInfo();
        }
        Map<String, Object> toolInfo = (Map<String, Object>) toolCallsData;//讯飞星火模型返回的不是数组，这里做个适配
        Map<String, String> toolCall = (Map<String, String>)toolInfo.get("function");
        if(toolCall.get("name") != null){
            toolCallInfo.setName(toolCall.get("name"));
        }
        String arguments = toolCall.get("arguments");
        if (arguments != null && !arguments.isEmpty()) {
            toolCallInfo.appendArgumentsJson(arguments);
        }
        return toolCallInfo;
    }

    @Override
    protected void testFunctionCall() throws Exception {
        //星火只有个别模型支持tools，这里做个判断，否则会导致模型调用报错
        if(model.equalsIgnoreCase("4.0Ultra") || model.equalsIgnoreCase("generalv3.5")){
            supportFunctionCall = true;
        }
    }

    @Override
    public String getProviderName() {
        return "spark";
    }
}