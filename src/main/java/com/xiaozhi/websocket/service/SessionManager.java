package com.xiaozhi.websocket.service;

import com.xiaozhi.entity.SysConfig;
import com.xiaozhi.entity.SysDevice;
import com.xiaozhi.websocket.iot.IotDescriptor;
import com.xiaozhi.websocket.llm.tool.function.FunctionSessionHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Sinks;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket会话管理服务
 * 负责管理所有WebSocket连接的会话状态
 */
@Service
public class SessionManager {
    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);

    // 设置不活跃超时时间为60秒
    private static final long INACTIVITY_TIMEOUT_SECONDS = 60;

    // 用于存储所有连接的会话
    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    // 用于存储会话和设备的映射关系
    private final ConcurrentHashMap<String, SysDevice> deviceConfigs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, SysConfig> configCache = new ConcurrentHashMap<>();
    // 用于存储会话的设备上的Iot部件的描述信息
    private final ConcurrentHashMap<String, Map<String, IotDescriptor>> iotDescriptors
            = new ConcurrentHashMap<>();
    // 用于存储会话的function_call tools
    private final ConcurrentHashMap<String, FunctionSessionHolder> functionHolders
            = new ConcurrentHashMap<>();
    // 用于标识对话完成后，是否需要关闭对话
    private final ConcurrentHashMap<String, Boolean> closeAfterChat = new ConcurrentHashMap<>();

    // 用于跟踪会话是否处于监听状态
    private final ConcurrentHashMap<String, Boolean> listeningState = new ConcurrentHashMap<>();

    // 用于存储每个会话的音频数据流
    private final ConcurrentHashMap<String, Sinks.Many<byte[]>> audioSinks = new ConcurrentHashMap<>();

    // 用于跟踪会话是否正在进行流式识别
    private final ConcurrentHashMap<String, Boolean> streamingState = new ConcurrentHashMap<>();

    // 存储验证码生成状态
    private final ConcurrentHashMap<String, Boolean> captchaState = new ConcurrentHashMap<>();

    // 存储每个会话的最后有效活动时间
    private final ConcurrentHashMap<String, Instant> lastActivityTime = new ConcurrentHashMap<>();
    
    // 存储会话属性
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> sessionAttributes = new ConcurrentHashMap<>();

    // 定时任务执行器
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    /**
     * 初始化方法，启动定时检查不活跃会话的任务
     */
    @PostConstruct
    public void init() {
        // 每10秒检查一次不活跃的会话
        scheduler.scheduleAtFixedRate(this::checkInactiveSessions, 10, 10, TimeUnit.SECONDS);
        logger.info("不活跃会话检查任务已启动，超时时间: {}秒", INACTIVITY_TIMEOUT_SECONDS);
    }

    /**
     * 销毁方法，关闭定时任务执行器
     */
    @PreDestroy
    public void destroy() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("不活跃会话检查任务已关闭");
    }

    /**
     * 检查不活跃的会话并关闭它们
     */
    private void checkInactiveSessions() {
        Instant now = Instant.now();
        sessions.keySet().forEach(sessionId -> {
            Instant lastActivity = lastActivityTime.get(sessionId);
            if (lastActivity != null) {
                Duration inactiveDuration = Duration.between(lastActivity, now);
                if (inactiveDuration.getSeconds() > INACTIVITY_TIMEOUT_SECONDS) {
                    logger.info("会话 {} 已经 {} 秒没有有效活动，自动关闭", sessionId, inactiveDuration.getSeconds());
                    closeSession(sessionId);
                }
            }
        });
    }

    /**
     * 更新会话的最后有效活动时间
     * 这个方法应该只在检测到实际的用户活动时调用，如语音输入或明确的交互
     * 
     * @param sessionId 会话ID
     */
    public void updateLastActivity(String sessionId) {
        lastActivityTime.put(sessionId, Instant.now());
    }

    /**
     * 注册新的WebSocket会话
     * 
     * @param sessionId 会话ID
     * @param session   WebSocket会话
     */
    public void registerSession(String sessionId, WebSocketSession session) {
        sessions.put(sessionId, session);
        listeningState.put(sessionId, false);
        streamingState.put(sessionId, false);
        updateLastActivity(sessionId); // 初始化活动时间
        logger.info("WebSocket会话已注册 - SessionId: {}", sessionId);
    }

    /**
     * 关闭并清理WebSocket会话
     * 
     * @param sessionId 会话ID
     */
    public void closeSession(String sessionId) {
        // 关闭会话
        WebSocketSession session = sessions.get(sessionId);
        if (session != null) {
            try {
                session.close().subscribe();
            } catch (Exception e) {
                logger.error("关闭WebSocket会话时发生错误 - SessionId: {}", sessionId, e);
            }
        }
        sessions.remove(sessionId); // 清理session
        deviceConfigs.remove(sessionId); // 清理设备配置
        listeningState.remove(sessionId); // 清理监听状态
        streamingState.remove(sessionId); // 清理播放状态
        lastActivityTime.remove(sessionId); // 清理活动时间记录
        iotDescriptors.remove(sessionId); // 清理Iot部件描述信息
        functionHolders.remove(sessionId);// 清理function_call tools
        closeAfterChat.remove(sessionId);
        sessionAttributes.remove(sessionId); // 清理会话属性
        
        // 清理音频流
        Sinks.Many<byte[]> sink = audioSinks.remove(sessionId);
        if (sink != null) {
            sink.tryEmitComplete();
        }

        logger.info("WebSocket会话已关闭 - SessionId: {}", sessionId);
    }

    /**
     * 注册设备配置
     * 
     * @param sessionId 会话ID
     * @param device    设备信息
     */
    public void registerDevice(String sessionId, SysDevice device) {
        // 先检查是否已存在该sessionId的配置
        SysDevice existingDevice = deviceConfigs.get(sessionId);
        if (existingDevice != null) {
            deviceConfigs.remove(sessionId);
        }
        deviceConfigs.put(sessionId, device);
        updateLastActivity(sessionId); // 更新活动时间
        logger.debug("设备配置已注册 - SessionId: {}, DeviceId: {}", sessionId, device.getDeviceId());
    }

    /**
     * 注册设备iot部件描述信息
     *
     * @param sessionId 会话ID
     * @param iotDescriptor iot部件描述信息
     */
    public void registerIot(String sessionId, IotDescriptor iotDescriptor) {
        // 先检查是否已存在该sessionId的配置
        Map<String, IotDescriptor> existingIotDescriptors = iotDescriptors.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>());
        existingIotDescriptors.put(iotDescriptor.getName(), iotDescriptor);
        logger.debug("设备iot部件已注册 - SessionId: {}, Iot: {}", sessionId, iotDescriptor.getName());
    }

    /**
     * 注册会话的function_call
     *
     * @param sessionId 会话ID
     * @param functionSessionHolder function_call工具
     */
    public void registerFunctionSessionHolder(String sessionId, FunctionSessionHolder functionSessionHolder) {
        functionHolders.putIfAbsent(sessionId, functionSessionHolder);
    }

    /**
     * 设置会话完成后是否关闭
     *
     * @param sessionId 会话ID
     * @param close     是否关闭
     */
    public void setCloseAfterChat(String sessionId, boolean close) {
        closeAfterChat.put(sessionId, close);
    }

    /**
     * 获取会话完成后是否关闭
     *
     * @param sessionId 会话ID
     * @return 是否关闭
     */
    public boolean isCloseAfterChat(String sessionId) {
        return closeAfterChat.getOrDefault(sessionId, false);
    }

    /**
     * 缓存配置信息
     * 
     * @param configId 配置ID
     * @param config   配置信息
     */
    public void cacheConfig(Integer configId, SysConfig config) {
        if (configId != null && config != null) {
            configCache.put(configId, config);
        }
    }

    /**
     * 删除配置
     * 
     * @param configId 配置ID
     */
    public void removeConfig(Integer configId) {
        configCache.remove(configId);
    }

    /**
     * 获取会话
     * 
     * @param sessionId 会话ID
     * @return WebSocket会话
     */
    public WebSocketSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * 获取会话
     * 
     * @param deviceId 设备ID
     * @return 会话ID
     */
    public String getSessionByDeviceId(String deviceId) {
        for (String key : deviceConfigs.keySet()) {
            if (deviceConfigs.get(key).getDeviceId().equals(deviceId)) {
                return key;
            }
        }
        return null;
    }

    /**
     * 获取设备配置
     * 
     * @param sessionId 会话ID
     * @return 设备配置
     */
    public SysDevice getDeviceConfig(String sessionId) {
        return deviceConfigs.get(sessionId);
    }

    /**
     * 获取Iot部件描述信息
     *
     * @param sessionId 会话ID
     * @return 设备配置
     */
    public Map<String, IotDescriptor> getAllIotDescriptor(String sessionId) {
        return iotDescriptors.get(sessionId);
    }

    /**
     * 获取Iot部件描述信息
     *
     * @param sessionId 会话ID
     * @return 设备配置
     */
    public IotDescriptor getIotDescriptor(String sessionId, String iotName) {
        Map<String, IotDescriptor> descriptors = iotDescriptors.get(sessionId);
        if (descriptors != null) {
            return descriptors.get(iotName);
        }
        return null;
    }

    /**
     * 获取会话的function holder
     *
     * @param sessionId 会话ID
     * @return FunctionSessionHolder
     */
    public FunctionSessionHolder getFunctionSessionHolder(String sessionId) {
        return functionHolders.get(sessionId);
    }

    /**
     * 获取缓存的配置
     * 
     * @param configId 配置ID
     * @return 配置信息
     */
    public SysConfig getCachedConfig(Integer configId) {
        return configCache.get(configId);
    }

    /**
     * 设置监听状态
     * 
     * @param sessionId   会话ID
     * @param isListening 是否正在监听
     */
    public void setListeningState(String sessionId, boolean isListening) {
        listeningState.put(sessionId, isListening);
        updateLastActivity(sessionId); // 更新活动时间
    }

    /**
     * 获取监听状态
     * 
     * @param sessionId 会话ID
     * @return 是否正在监听
     */
    public boolean isListening(String sessionId) {
        return listeningState.getOrDefault(sessionId, false);
    }

    /**
     * 设置流式识别状态
     * 
     * @param sessionId   会话ID
     * @param isStreaming 是否正在流式识别
     */
    public void setStreamingState(String sessionId, boolean isStreaming) {
        streamingState.put(sessionId, isStreaming);
        updateLastActivity(sessionId); // 更新活动时间
    }

    /**
     * 获取流式识别状态
     * 
     * @param sessionId 会话ID
     * @return 是否正在流式识别
     */
    public boolean isStreaming(String sessionId) {
        return streamingState.getOrDefault(sessionId, false);
    }

    /**
     * 创建并注册音频数据接收器
     * 
     * @param sessionId 会话ID
     * @return 音频数据接收器
     */
    public Sinks.Many<byte[]> createAudioSink(String sessionId) {
        Sinks.Many<byte[]> sink = Sinks.many().multicast().onBackpressureBuffer();
        audioSinks.put(sessionId, sink);
        return sink;
    }

    /**
     * 获取音频数据接收器
     * 
     * @param sessionId 会话ID
     * @return 音频数据接收器
     */
    public Sinks.Many<byte[]> getAudioSink(String sessionId) {
        return audioSinks.get(sessionId);
    }

    /**
     * 关闭音频数据接收器
     * 
     * @param sessionId 会话ID
     */
    public void closeAudioSink(String sessionId) {
        Sinks.Many<byte[]> sink = audioSinks.get(sessionId);
        if (sink != null) {
            sink.tryEmitComplete();
        }
    }

    /**
     * 标记设备正在生成验证码
     * 
     * @param deviceId 设备ID
     * @return 如果设备之前没有在生成验证码，返回true；否则返回false
     */
    public boolean markCaptchaGeneration(String deviceId) {
        return captchaState.putIfAbsent(deviceId, Boolean.TRUE) == null;
    }

    /**
     * 取消设备验证码生成标记
     * 
     * @param deviceId 设备ID
     */
    public void unmarkCaptchaGeneration(String deviceId) {
        captchaState.remove(deviceId);
    }
    
    /**
     * 设置会话属性
     * 
     * @param sessionId 会话ID
     * @param key 属性键
     * @param value 属性值
     */
    public void setSessionAttribute(String sessionId, String key, Object value) {
        ConcurrentHashMap<String, Object> attributes = sessionAttributes.computeIfAbsent(sessionId, 
                k -> new ConcurrentHashMap<>());
        attributes.put(key, value);
    }
    
    /**
     * 获取会话属性
     * 
     * @param sessionId 会话ID
     * @param key 属性键
     * @return 属性值
     */
    public Object getSessionAttribute(String sessionId, String key) {
        ConcurrentHashMap<String, Object> attributes = sessionAttributes.get(sessionId);
        if (attributes != null) {
            return attributes.get(key);
        }
        return null;
    }
    
    /**
     * 移除会话属性
     * 
     * @param sessionId 会话ID
     * @param key 属性键
     */
    public void removeSessionAttribute(String sessionId, String key) {
        ConcurrentHashMap<String, Object> attributes = sessionAttributes.get(sessionId);
        if (attributes != null) {
            attributes.remove(key);
        }
    }
    
    /**
     * 清理会话属性
     * 
     * @param sessionId 会话ID
     */
    public void clearSessionAttributes(String sessionId) {
        sessionAttributes.remove(sessionId);
    }
}