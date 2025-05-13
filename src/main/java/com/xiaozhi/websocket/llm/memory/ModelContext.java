package com.xiaozhi.websocket.llm.memory;

import java.util.List;

import com.xiaozhi.entity.SysMessage;
import com.xiaozhi.websocket.llm.tool.function.FunctionSessionHolder;

/**
 * 模型上下文
 * 包含模型配置和记忆管理
 */
public class ModelContext {
    private final String deviceId;
    private final String sessionId;
    private final Integer roleId;
    private final String systemMessage;
    private final boolean useFunctionCall;// 是否启用函数调用
    private final ChatMemory chatMemory;
    private final FunctionSessionHolder functionSessionHolder;

    /**
     * 构造函数
     *
     * @param deviceId   设备ID
     * @param sessionId  会话ID
     * @param roleId     角色ID
     * @param useFunctionCall     是否启用函数调用
     * @param chatMemory 聊天记忆
     */
    public ModelContext(String deviceId, String sessionId, Integer roleId, boolean useFunctionCall, ChatMemory chatMemory) {
        this.deviceId = deviceId;
        this.sessionId = sessionId;
        this.roleId = roleId;
        this.chatMemory = chatMemory;
        this.useFunctionCall = useFunctionCall;
        this.systemMessage = chatMemory.getSystemMessage(deviceId, roleId);
        this.functionSessionHolder = null;
    }

    /**
     * 构造函数
     *
     * @param deviceId   设备ID
     * @param sessionId  会话ID
     * @param roleId     角色ID
     * @param chatMemory 聊天记忆
     * @param functionSessionHolder session绑定的function控制器
     */
    public ModelContext(String deviceId, String sessionId, Integer roleId, boolean useFunctionCall, ChatMemory chatMemory, FunctionSessionHolder functionSessionHolder) {
        this.deviceId = deviceId;
        this.sessionId = sessionId;
        this.roleId = roleId;
        this.chatMemory = chatMemory;
        this.useFunctionCall = useFunctionCall;
        this.systemMessage = chatMemory.getSystemMessage(deviceId, roleId);
        this.functionSessionHolder = functionSessionHolder;
    }

    /**
     * 获取设备ID
     * 
     * @return 设备ID
     */
    public String getDeviceId() {
        return deviceId;
    }

    /**
     * 获取会话ID
     * 
     * @return 会话ID
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 获取角色ID
     * 
     * @return 角色ID
     */
    public Integer getRoleId() {
        return roleId;
    }

    public boolean isUseFunctionCall() {
        return useFunctionCall;
    }

    /**
     * 获取系统消息
     * 
     * @return 系统消息
     */
    public String getSystemMessage() {
        return systemMessage;
    }

    /**
     * 添加用户消息
     * 
     * @param message 用户消息
     */
    public void addUserMessage(String message) {
        chatMemory.addMessage(deviceId, sessionId, "user", message, roleId, "NORMAL", null);
    }

    /**
     * 添加AI消息
     * 
     * @param message AI消息
     */
    public void addAssistantMessage(String message) {
        chatMemory.addMessage(deviceId, sessionId, "assistant", message, roleId, "NORMAL", null);
    }

    /**
     * 通用添加消息
     *
     * @param message 消息内容
     * @param role 角色名称
     * @param messageType 消息类型
     */
    public void addMessage(String message, String role, String messageType, String audioPath) {
        chatMemory.addMessage(deviceId, sessionId, role, message, roleId, messageType, audioPath);
    }

    /**
     * 获取历史消息
     *
     * @param messageType 指定查询的消息类型 - 传null查所有消息
     * @param limit 消息数量限制
     * @return 历史消息列表
     */
    public List<SysMessage> getMessages(String messageType, Integer limit) {
        return chatMemory.getMessages(deviceId, messageType, limit);
    }

    /**
     * 获取会话的函数控制器s
     *
     * @return 函数描述列表
     */
    public FunctionSessionHolder getFunctionSessionHolder() {
        return functionSessionHolder;
    }
}