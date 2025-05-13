package com.xiaozhi.websocket.llm.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaozhi.entity.SysMessage;
import com.xiaozhi.websocket.llm.memory.ModelContext;
import com.xiaozhi.websocket.llm.tool.ActionType;
import com.xiaozhi.websocket.llm.tool.ToolResponse;
import com.xiaozhi.websocket.llm.tool.function.FunctionSessionHolder;
import com.xiaozhi.websocket.llm.tool.function.bean.FunctionCallTool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * LLM服务抽象类
 * 实现一些通用功能
 */
public abstract class AbstractLlmService implements LlmService {
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    protected static final ObjectMapper objectMapper = new ObjectMapper();
    protected static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    protected static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    // 设备历史记录缓存，键为deviceId，值为该设备的历史消息
    protected Map<String, List<SysMessage>> deviceHistoryCache = new ConcurrentHashMap<>();

    // 历史记录默认限制数量
    protected static final int DEFAULT_HISTORY_LIMIT = 10;

    protected final String endpoint;
    protected final String apiKey;
    protected final String model;
    protected final String appId;
    protected final String apiSecret;

    protected boolean supportFunctionCall = false;

    /**
     * 构造函数
     * 
     * @param endpoint API端点
     * @param apiKey   API密钥
     * @param model    模型名称
     */
    public AbstractLlmService(String endpoint, String appId, String apiKey, String apiSecret, String model) {
        this.endpoint = endpoint;
        this.appId = appId;
        this.apiSecret = apiSecret;
        this.apiKey = apiKey;
        this.model = model;

    }

    /**
     * 初始化设备的历史记录缓存
     * 
     * @param modelContext 模型上下文
     */
    protected void initializeHistory(ModelContext modelContext) {
        String deviceId = modelContext.getDeviceId();
        if (!deviceHistoryCache.containsKey(deviceId)) {
            // 从数据库加载历史记录
            List<SysMessage> history = modelContext.getMessages(SysMessage.MESSAGE_TYPE_NORMAL, DEFAULT_HISTORY_LIMIT); // 这里后期可以设置 limit，来自定义历史记录条数
            deviceHistoryCache.put(deviceId, history);
            logger.info("已初始化设备 {} 的历史记录缓存，共 {} 条消息", deviceId, history.size());
        }
    }

    /**
     * 获取格式化的消息历史，适合发送给LLM API
     * 
     * @param modelContext 模型上下文
     * @param userMessage  当前用户消息
     * @return 格式化的消息历史列表
     */
    protected List<Map<String, Object>> getFormattedHistory(ModelContext modelContext, String userMessage) {
        String deviceId = modelContext.getDeviceId();
        String systemMessage = modelContext.getSystemMessage();

        // 初始化历史记录缓存（如果需要）
        if (!deviceHistoryCache.containsKey(deviceId)) {
            initializeHistory(modelContext);
        }

        List<SysMessage> historyMessages = deviceHistoryCache.get(deviceId);
        List<Map<String, Object>> formattedMessages = new ArrayList<>();

        // 添加系统消息（如果有）
        if (systemMessage != null && !systemMessage.isEmpty()) {
            Map<String, Object> systemMsg = new HashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content", systemMessage);
            formattedMessages.add(systemMsg);
        }

        // 添加历史消息
        for (SysMessage msg : historyMessages) {
            String role = msg.getSender();
            role = role.equals("assistant") ? "assistant" : "user";
            Map<String, Object> formattedMsg = new HashMap<>();
            formattedMsg.put("messageId", msg.getMessageId());
            formattedMsg.put("role", role);
            formattedMsg.put("content", msg.getMessage());
            formattedMsg.put("messageType", msg.getMessageType());
            formattedMessages.add(formattedMsg);
        }

        // 添加当前用户消息
        Map<String, Object> currentUserMsg = new HashMap<>();
        currentUserMsg.put("role", "user");
        currentUserMsg.put("content", userMessage);
        currentUserMsg.put("messageType", "NORMAL");//默认为普通消息
        formattedMessages.add(currentUserMsg);

        return formattedMessages;
    }

    /**
     * 更新设备的历史记录缓存
     *
     * @param modelContext     模型上下文
     * @param message      消息
     */
    public void updateHistoryCache(ModelContext modelContext, Map<String, Object> message) {
        String messageContent = message.get("content") == null? "" : String.valueOf(message.get("content"));
        if(messageContent.isEmpty()){
            return;
        }
        String messageRole = String.valueOf(message.get("role"));

        String deviceId = modelContext.getDeviceId();
        String sessionId = modelContext.getSessionId();
        Integer roleId = modelContext.getRoleId();

        // 获取当前缓存，如果没有则从数据库加载
        List<SysMessage> history = deviceHistoryCache.computeIfAbsent(deviceId,
                k -> modelContext.getMessages(SysMessage.MESSAGE_TYPE_NORMAL, DEFAULT_HISTORY_LIMIT));

        // 创建新的用户消息对象
        SysMessage sysMessage = new SysMessage();
        sysMessage.setMessageId((Integer) message.get("messageId"));
        sysMessage.setMessageType((String)message.get("messageType"));
        sysMessage.setDeviceId(deviceId);
        sysMessage.setSessionId(sessionId);
        sysMessage.setSender(messageRole);
        sysMessage.setMessage(messageContent);
        sysMessage.setRoleId(roleId);

        // 添加新消息
        history.add(sysMessage);

        // 如果历史记录过长，移除最旧的消息（保持偶数，确保user/assistant对保持完整）
        while (history.size() > DEFAULT_HISTORY_LIMIT) {
            history.remove(0);
            if (history.size() > DEFAULT_HISTORY_LIMIT) {
                history.remove(0);
            }
        }
        // 更新缓存
        deviceHistoryCache.put(deviceId, history);
    }

    /**
     * 更新设备的历史记录缓存
     * 
     * @param modelContext     模型上下文
     * @param userMessage      用户消息
     * @param assistantMessage AI消息
     */
    protected void updateHistoryCache(ModelContext modelContext, String userMessage, String assistantMessage) {
        String deviceId = modelContext.getDeviceId();
        String sessionId = modelContext.getSessionId();
        Integer roleId = modelContext.getRoleId();

        // 获取当前缓存，如果没有则从数据库加载
        List<SysMessage> history = deviceHistoryCache.computeIfAbsent(deviceId,
                k -> modelContext.getMessages(SysMessage.MESSAGE_TYPE_NORMAL, DEFAULT_HISTORY_LIMIT));

        // 创建新的用户消息对象
        SysMessage userMsg = new SysMessage();
        userMsg.setDeviceId(deviceId);
        userMsg.setSessionId(sessionId);
        userMsg.setSender("user");
        userMsg.setMessage(userMessage);
        userMsg.setRoleId(roleId);

        // 创建新的助手消息对象
        SysMessage assistantMsg = new SysMessage();
        assistantMsg.setDeviceId(deviceId);
        assistantMsg.setSessionId(sessionId);
        assistantMsg.setSender("assistant");
        assistantMsg.setMessage(assistantMessage);
        assistantMsg.setRoleId(roleId);

        // 添加新消息
        history.add(userMsg);
        history.add(assistantMsg);

        // 如果历史记录过长，移除最旧的消息（保持偶数，确保user/assistant对保持完整）
        while (history.size() > DEFAULT_HISTORY_LIMIT) {
            history.remove(0);
            if (history.size() > DEFAULT_HISTORY_LIMIT) {
                history.remove(0);
            }
        }

        // 更新缓存
        deviceHistoryCache.put(deviceId, history);
    }

    @Override
    public String chat(String userMessage, ModelContext modelContext) throws IOException {
        // 初始化历史记录缓存
        initializeHistory(modelContext);
        // 保存用户消息
        modelContext.addUserMessage(userMessage);

        // 获取格式化的历史记录（包含当前用户消息）
        List<Map<String, Object>> formattedMessages = getFormattedHistory(modelContext, userMessage);

        // 调用实际的聊天方法
        String response = chat(formattedMessages);

        // 保存AI消息
        modelContext.addAssistantMessage(response);

        // 更新缓存
        updateHistoryCache(modelContext, userMessage, response);

        return response;
    }

    @Override
    public void chatStream(String userMessage, ModelContext modelContext, StreamResponseListener streamListener)
            throws IOException {

        // 初始化历史记录缓存
        initializeHistory(modelContext);

        // 获取格式化的历史记录（包含当前用户消息）
        List<Map<String, Object>> formattedMessages = getFormattedHistory(modelContext, userMessage);

        // 调用实际的流式聊天方法
        chatStream(formattedMessages, streamListener, modelContext);
    }

    @Override
    public String getModelName() {
        return model;
    }

    /**
     * 执行实际的聊天请求
     * 
     * @param messages 格式化的消息列表（包含系统消息、历史对话和当前用户消息）
     * @return 模型回复
     * @throws IOException 如果请求失败
     */
    protected abstract String chat(List<Map<String, Object>> messages) throws IOException;

    /**
     * 执行实际的流式聊天请求
     *
     * @param messages       格式化的消息列表（包含系统消息、历史对话和当前用户消息）
     * @param streamListener 流式响应监听器
     * @param modelContext 上下文
     * @throws IOException 如果请求失败
     */
    protected abstract void chatStream(List<Map<String, Object>> messages, StreamResponseListener streamListener, ModelContext modelContext)
            throws IOException;

    /**
     * 执行函数调用，返回函数调用后的消息结果
     * @param modelContext 执行上下文
     * @param toolCallInfo 工具信息
     * @param streamListener 数据流监听器
     * @param messages 历史消息记录
     * @return
     * @throws Exception
     */
    protected ToolResponse doFunctionCall(ModelContext modelContext, ToolCallInfo toolCallInfo, StreamResponseListener streamListener,
                                  List<Map<String, Object>> messages) {
        FunctionSessionHolder functionSessionHolder = modelContext.getFunctionSessionHolder();
        if(functionSessionHolder != null){
            FunctionCallTool functionCallTool = functionSessionHolder.getFunction(toolCallInfo.getName());
            if(functionCallTool!=null){
                FunctionCallTool.FunctionParams functionParams = new FunctionCallTool.FunctionParams(modelContext, toolCallInfo.getArguments());
                ToolResponse toolResponse = functionCallTool.getFunction().apply(functionParams);
                logger.debug("Function call: Llm: {} deviceId: {} roleId: {} function: {} with arguments: {} result： {}", model,
                        modelContext.getDeviceId(), modelContext.getRoleId(), toolCallInfo.getName(), toolCallInfo.getArguments(), toolResponse);
                if(ActionType.REQLLM.equals(toolResponse.getActionType())){
                    try{
                        submitFunctionResultToLlm(modelContext, toolCallInfo, streamListener, messages, toolResponse);
                    }catch (UnsupportedOperationException e){
                        logger.error("Function call: Llm: {} 不支持function总结， 对需总结的function：{} 不进行总结，直接返回", model, toolCallInfo.getName());
                        streamListener.onToken(toolResponse.getResponse());
                    }
                }else if(ActionType.RESPONSE.equals(toolResponse.getActionType())) {
                    streamListener.onToken(toolResponse.getResponse());
                }else if(ActionType.ERROR.equals(toolResponse.getActionType())) {
                    streamListener.onToken(toolResponse.getResponse());
                }
                return toolResponse;
            }else{
                logger.error("Function call: Llm: {} 回调未找到函数: 函数名: {} with arguments: {} toolId: {} ", model, toolCallInfo.getName(), toolCallInfo.getArguments(), toolCallInfo.getTool_call_id());
            }
        }
        return null;
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
                                                      ToolResponse toolResponse) throws UnsupportedOperationException{
        throw new UnsupportedOperationException("当前模型不支持函数调用结果提交到LLM进行总结回复");
    }
}