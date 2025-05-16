package com.xiaozhi.websocket.llm.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xiaozhi.entity.SysMessage;
import com.xiaozhi.utils.JsonUtil;
import com.xiaozhi.websocket.llm.memory.ModelContext;
import com.xiaozhi.websocket.llm.tool.ActionType;
import com.xiaozhi.websocket.llm.tool.ToolResponse;
import com.xiaozhi.websocket.llm.tool.function.FunctionSessionHolder;
import com.xiaozhi.websocket.llm.tool.function.bean.FunctionLlmDescription;
import com.xiaozhi.websocket.llm.tool.function.bean.description.FunctionDesc;
import okhttp3.*;
import okio.BufferedSource;
import org.jetbrains.annotations.NotNull;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.*;

/**
 * 基于OpenAI标准协议接口的的 LLM服务实现
 * 实现一些通用功能
 */
public abstract class AbstractOpenAiLlmService extends AbstractLlmService {
    /**
     * 构造函数
     *
     * @param endpoint  API端点
     * @param appId
     * @param apiKey    API密钥
     * @param apiSecret
     * @param model     模型名称
     */
    public AbstractOpenAiLlmService(String endpoint, String appId, String apiKey, String apiSecret, String model) {
        super(endpoint, appId, apiKey, apiSecret, model);
        Mono.fromCallable(() -> {
            try {
                testFunctionCall();
            } catch (Exception e) {
                logger.error("初始化LlmService服务时testFunctionCall发生错误，看来该模型不支持function_call，这条异常信息可以忽略", e);
            }
            return null;
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();

    }

    @Override
    protected String chat(List<Map<String, Object>> messages) throws IOException {
        // 构建请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", messages);

        // 转换为JSON
        String jsonBody = objectMapper.writeValueAsString(requestBody);

        // 构建请求
        Request request = new Request.Builder()
                .url(endpoint + "/chat/completions")
                .post(RequestBody.create(jsonBody, JSON))
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
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

            List<Map<String, Object>> choices = (List<Map<String, Object>>) responseMap.get("choices");
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                if (message != null) {
                    return (String) message.get("content");
                }
            }
            throw new IOException("无法解析响应");
        }
    }

    @Override
    protected void chatStream(List<Map<String, Object>> messages, StreamResponseListener streamListener, ModelContext modelContext){
        String jsonBody = buildRequestJson(messages, modelContext);

        Request request = buildRequest(jsonBody);
        // 通知开始
        streamListener.onStart();
        // 发送请求
        client.newCall(request).enqueue(new OpenAiResponseCallBack(messages, streamListener, modelContext));
    }

    @NotNull
    protected String buildRequestJson(List<Map<String, Object>> messages, ModelContext modelContext) {
        // 构建请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("stream", true);
        requestBody.put("messages", messages);

        if(modelContext.isUseFunctionCall() && supportFunctionCall){
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

    protected Request buildRequest(String jsonBody) {
        // 构建请求
        return new Request.Builder()
                .url(endpoint + "/chat/completions")
                .post(RequestBody.create(jsonBody, JSON))
                .addHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    protected class OpenAiResponseCallBack implements Callback {
        private final List<Map<String, Object>> messages;
        private final StreamResponseListener streamListener;
        private final ModelContext modelContext;

        public OpenAiResponseCallBack(List<Map<String, Object>> messages, StreamResponseListener streamListener, ModelContext modelContext) {
            this.messages = messages;
            this.streamListener = streamListener;
            this.modelContext = modelContext;
        }

        @Override
        public void onFailure(Call call, IOException e) {
            logger.error("流式请求失败: {}", e.getMessage(), e);
            streamListener.onError(e);
        }

        @Override
        public void onResponse(Call call, Response response) throws IOException {
            if (!response.isSuccessful()) {
                String errorMsg = "流式请求响应失败: " + response;
                logger.error(errorMsg);
                streamListener.onError(new IOException(errorMsg));
                return;
            }
            ToolCallInfo toolCallInfo = null;

            try (ResponseBody responseBody = response.body()) {
                if (responseBody == null) {
                    String errorMsg = "响应体为空";
                    logger.error(errorMsg);
                    streamListener.onError(new IOException(errorMsg));
                    return;
                }

                BufferedSource source = responseBody.source();
                StringBuilder fullResponse = new StringBuilder();

                while (!source.exhausted()) {
                    String line = source.readUtf8Line();
                    if (line == null) {
                        break;
                    }
                    if (line.isEmpty() || line.equals("data: [DONE]")) {
                        continue;
                    }
                    if (line.startsWith("data: ")) {
                        String jsonData = line.substring(6);
                        try {
                            Map<String, Object> data = objectMapper.readValue(jsonData, new TypeReference<Map<String, Object>>() {});
                            List<Map<String, Object>> choices = (List<Map<String, Object>>) data.get("choices");
                            if (choices != null && !choices.isEmpty()) {
                                Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
                                if (delta != null) {
                                    //处理function_call
                                    if(delta.containsKey("tool_calls") && delta.get("tool_calls") != null){
                                        //tool_calls不为空，则说明是一个function调用信息
                                        Object toolCallsData = delta.get("tool_calls");
                                        toolCallInfo = getToolCallInfo(toolCallInfo, toolCallsData);
                                    }
                                    //处理普通消息内容(有的ai，比如腾讯hunyuan-lite，工具调消息里还给了总结思考文本，这里就丢弃不要了)
                                    if(toolCallInfo == null && delta.containsKey("content")){
                                        String content = (String) delta.get("content");
                                        if (content != null && !content.isEmpty()) {
                                            streamListener.onToken(content);
                                            fullResponse.append(content);
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            logger.error("解析流式响应失败: {}", e.getMessage(), e);
                            streamListener.onError(e);
                        }
                    }
                }

                boolean isFunctionResultReqLlm = false;//是否存在REQLLM的函数调用
                String messageType = SysMessage.MESSAGE_TYPE_NORMAL;
                // 处理函数调用
                if(toolCallInfo != null){
                    try{
                        ToolResponse toolResponse = doFunctionCall(modelContext, toolCallInfo, streamListener, messages);
                        if(toolResponse != null){
                            if(ActionType.REQLLM.equals(toolResponse.getActionType())){
                                isFunctionResultReqLlm = true;
                            }else{
                                //非REQLLM函数，则将消息添加到消息列表，并设置完整内容为工具的response内容
                                fullResponse.append(toolResponse.getResponse());
                                messageType = SysMessage.MESSAGE_TYPE_FUNCTION_CALL;
                            }
                        }
                    }catch (Exception e){
                        logger.error("函数调用失败: {}", e.getMessage(), e);
                        streamListener.onError(e);
                    }
                }

                if(!isFunctionResultReqLlm){
                    // 通知完成
                    streamListener.onComplete(fullResponse.toString(), messages, AbstractOpenAiLlmService.this, messageType);
                }
            }
        }
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
        Map<String, Object> toolInfo;
        if(toolCallsData instanceof List && !((List)toolCallsData).isEmpty()){
            toolInfo = ((List<Map<String, Object>>)toolCallsData).get(0);
        }else{
            return null;
        }
        if(toolInfo.get("id") != null && !((String)toolInfo.get("id")).isEmpty()){
            //如果原ToolCallInfo有tool_call_id，则当前流回复里，有多function调用，暂时只处理最后一个调用
            if(toolCallInfo.getTool_call_id() != null){
                toolCallInfo.clearArgumentsJson();//重置下参数字符串，避免把多函数的参数都拼一起去了
            }
            toolCallInfo.setTool_call_id((String)toolInfo.get("id"));
        }
        Map<String, String> toolCall = (Map<String, String>)toolInfo.get("function");
        if(toolCall.get("name") != null && !(toolCall.get("name")).isEmpty()){
            toolCallInfo.setName(toolCall.get("name"));
        }
        String arguments = toolCall.get("arguments");
        if (arguments != null && !arguments.isEmpty()) {
            toolCallInfo.appendArgumentsJson(arguments);
        }
        return toolCallInfo;
    }

    /**
     * 提交函数结果到LLM
     *
     * @param modelContext 模型上下文
     * @param toolCallInfo 工具调用信息
     * @param streamListener 流式响应监听器
     * @param messages 历史消息列表
     * @param toolResponse 工具响应
     * @throws Exception 如果请求失败
     */
    protected void submitFunctionResultToLlm(ModelContext modelContext, ToolCallInfo toolCallInfo,
                                             StreamResponseListener streamListener, List<Map<String, Object>> messages,
                                             ToolResponse toolResponse){
        Map<String, Object> assistantMessage = createLlAssistantMessage(toolCallInfo.getTool_call_id(), toolCallInfo.getName(),
                toolCallInfo.getArguments());
        Map<String, Object> toolMessage = createLlmToolMessage(toolResponse.getResponse(), toolCallInfo.getTool_call_id());
        messages.add(assistantMessage);
        messages.add(toolMessage);
        //继续把工具消息传给llm，让大模型总结输出
        chatStream(messages, streamListener, modelContext);
    }

    /**
     * 当function调用后，需要发起llm进行总结，则需要创建特殊的助手消息
     * @param tool_call_id
     * @param functionName
     * @param arguments
     * @return
     */
    protected Map<String, Object> createLlAssistantMessage(String tool_call_id, String functionName, Map<String, Object> arguments)
            throws UnsupportedOperationException {
        Map<String, Object> message = new HashMap<>();

        Map<String, Object> function = new HashMap<>();
        function.put("arguments", JsonUtil.toJson(arguments));
        function.put("name", functionName);

        Map<String, Object> tool_call = new HashMap<>();
        tool_call.put("id", tool_call_id);
        tool_call.put("function", function);
        tool_call.put("type", "function");
        tool_call.put("index", 0);
        List<Map<String, Object>> tool_calls = Collections.singletonList(tool_call);

        message.put("role", "assistant");
        message.put("content", "");
        message.put("tool_calls", tool_calls);
        //平台自行标记，非接口字段
        message.put("messageType", "FUNCTION_CALL");

        return message;
    }

    /**
     * 当function调用后，需要发起llm进行总结，则需要创建特殊的工具消息
     * @param content
     * @param toolId
     * @return
     */
    protected Map<String, Object> createLlmToolMessage(String content, String toolId)
            throws UnsupportedOperationException {
        Map<String, Object> message = new HashMap<>();
        message.put("role", "tool");
        message.put("content", content);
        message.put("tool_call_id", toolId);
        //平台自行标记，非接口字段
        message.put("messageType", "FUNCTION_CALL");
        return message;
    }

    protected void testFunctionCall() throws Exception {
        List<Map<String, Object>> formattedMessages = new ArrayList<>();
        // 添加提示词信息
        Map<String, Object> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", "你是一个工具助手，能够调用函数来完成任务。目前只有一个function: test_function");
        formattedMessages.add(systemMsg);

        // 添加当前用户消息
        Map<String, Object> currentUserMsg = new HashMap<>();
        currentUserMsg.put("role", "user");
        currentUserMsg.put("content", "帮我调用function: test_function");
        formattedMessages.add(currentUserMsg);

        // 创建函数名称，格式：get_{属性名称}
        String funcName = "test_function";
        // 创建函数对象
        FunctionDesc function = new FunctionDesc(funcName, "这是函数：test_function");
        FunctionLlmDescription functionLlmDescription = new FunctionLlmDescription(function);

        // 构建请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", formattedMessages);
        requestBody.put("tools", Collections.singletonList(functionLlmDescription));

        // 转换为JSON
        String jsonBody = objectMapper.writeValueAsString(requestBody);

        // 构建请求
        Request request = new Request.Builder()
                .url(endpoint + "/chat/completions")
                .post(RequestBody.create(jsonBody, JSON))
                .addHeader("Authorization", "Bearer " + apiKey)
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
            List<Map<String, Object>> choices = (List<Map<String, Object>>) responseMap.get("choices");
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                if (message != null) {
                    supportFunctionCall = message.containsKey("tool_calls");
                }
            }
        }
    }
}