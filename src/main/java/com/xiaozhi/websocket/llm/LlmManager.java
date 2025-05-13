package com.xiaozhi.websocket.llm;

import com.xiaozhi.entity.SysConfig;
import com.xiaozhi.entity.SysDevice;
import com.xiaozhi.service.SysConfigService;
import com.xiaozhi.utils.EmojiUtils;
import com.xiaozhi.websocket.llm.api.LlmService;
import com.xiaozhi.websocket.llm.api.StreamResponseListener;
import com.xiaozhi.websocket.llm.factory.LlmServiceFactory;
import com.xiaozhi.websocket.llm.memory.ChatMemory;
import com.xiaozhi.websocket.llm.memory.ModelContext;
import com.xiaozhi.websocket.llm.tool.function.FunctionSessionHolder;
import com.xiaozhi.websocket.service.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM管理器
 * 负责管理和协调LLM相关功能
 */
@Service
public class LlmManager {
    private static final Logger logger = LoggerFactory.getLogger(LlmManager.class);

    // 句子结束标点符号模式（中英文句号、感叹号、问号）
    private static final Pattern SENTENCE_END_PATTERN = Pattern.compile("[。！？!?]");

    // 逗号、分号等停顿标点
    private static final Pattern PAUSE_PATTERN = Pattern.compile("[，、；,;]");

    // 冒号和引号等特殊标点
    private static final Pattern SPECIAL_PATTERN = Pattern.compile("[：:\"]");

    // 换行符
    private static final Pattern NEWLINE_PATTERN = Pattern.compile("[\n\r]");

    // 数字模式（用于检测小数点是否在数字中）
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+\\.\\d+");

    // 表情符号模式
    private static final Pattern EMOJI_PATTERN = Pattern.compile("\\p{So}|\\p{Sk}|\\p{Sm}");

    // 最小句子长度（字符数）
    private static final int MIN_SENTENCE_LENGTH = 5;

    // 新句子判断的字符阈值
    private static final int NEW_SENTENCE_TOKEN_THRESHOLD = 8;

    @Autowired
    private SysConfigService configService;

    @Autowired
    private ChatMemory chatMemory;

    @Autowired
    private SessionManager sessionManager;

    // 设备LLM服务缓存，每个设备只保留一个服务
    private Map<String, LlmService> deviceLlmServices = new ConcurrentHashMap<>();
    // 设备当前使用的configId缓存
    private Map<String, Integer> deviceConfigIds = new ConcurrentHashMap<>();
    // 会话完成状态，因为 coze 会返回两次 onComplete 事件，会导致重复保存到数据库中
    private final Map<String, AtomicBoolean> sessionCompletionFlags = new ConcurrentHashMap<>();

    /**
     * 处理用户查询（同步方式）
     * 
     * @param device  设备信息
     * @param message 用户消息
     * @return 模型回复
     */
    public String chat(SysDevice device, String message) {
        try {
            String deviceId = device.getDeviceId();
            Integer configId = device.getModelId();

            // 获取LLM服务
            LlmService llmService = getLlmService(deviceId, configId);

            // 创建模型上下文
            ModelContext modelContext = new ModelContext(
                    deviceId,
                    device.getSessionId(),
                    device.getRoleId(),
                    chatMemory);

            // 调用LLM
            return llmService.chat(message, modelContext);

        } catch (Exception e) {
            logger.error("处理查询时出错: {}", e.getMessage(), e);
            return "抱歉，我在处理您的请求时遇到了问题。请稍后再试。";
        }
    }

    /**
     * 处理用户查询（流式方式）
     * 
     * @param device         设备信息
     * @param message        用户消息
     * @param streamListener 流式响应监听器
     */
    public void chatStream(SysDevice device, String message, StreamResponseListener streamListener) {
        try {
            String deviceId = device.getDeviceId();
            Integer configId = device.getModelId();

            // 获取LLM服务
            LlmService llmService = getLlmService(deviceId, configId);

            FunctionSessionHolder functionSessionHolder = sessionManager
                    .getFunctionSessionHolder(device.getSessionId());
            // 创建模型上下文
            ModelContext modelContext = new ModelContext(
                    deviceId,
                    device.getSessionId(),
                    device.getRoleId(),
                    chatMemory,
                    functionSessionHolder);

            // 调用LLM流式接口
            llmService.chatStream(message, modelContext, streamListener);

        } catch (Exception e) {
            logger.error("处理流式查询时出错: {}", e.getMessage(), e);
            streamListener.onError(e);
        }
    }

    /**
     * 处理用户查询（流式方式，使用lambda表达式）
     * 
     * @param device       设备信息
     * @param message      用户消息
     * @param tokenHandler token处理函数，接收每个生成的token
     */
    public void chatStream(SysDevice device, String message, Consumer<String> tokenHandler) {
        try {
            // 创建流式响应监听器
            StreamResponseListener streamListener = new StreamResponseListener() {
                private final StringBuilder fullResponse = new StringBuilder();

                @Override
                public void onStart() {
                }

                @Override
                public void onToken(String token) {
                    // 将token添加到完整响应
                    fullResponse.append(token);

                    // 调用处理函数
                    tokenHandler.accept(token);
                }

                @Override
                public void onComplete(String completeResponse) {
                }

                @Override
                public void onFinal(List<Map<String, Object>> allMessages, LlmService llmService) {

                }

                @Override
                public void onError(Throwable e) {
                    logger.error("流式响应出错: {}", e.getMessage(), e);
                }
            };

            // 调用现有的流式方法
            chatStream(device, message, streamListener);

        } catch (Exception e) {
            logger.error("处理流式查询时出错: {}", e.getMessage(), e);
        }
    }

    public void chatStreamBySentence(SysDevice device, String message,
            TriConsumer<String, Boolean, Boolean> sentenceHandler) {
        try {
            final String deviceId = device.getDeviceId();
            final String sessionId = device.getSessionId();
            final Integer roleId = device.getRoleId();

            // 为这个会话创建或重置完成标志
            AtomicBoolean sessionCompleted = sessionCompletionFlags.computeIfAbsent(sessionId,
                    k -> new AtomicBoolean(false));
            sessionCompleted.set(false);

            FunctionSessionHolder functionSessionHolder = sessionManager
                    .getFunctionSessionHolder(device.getSessionId());
            // 创建模型上下文
            ModelContext modelContext = new ModelContext(
                    deviceId,
                    sessionId,
                    roleId,
                    chatMemory,
                    functionSessionHolder);

            final StringBuilder currentSentence = new StringBuilder(); // 当前句子的缓冲区
            final StringBuilder contextBuffer = new StringBuilder(); // 上下文缓冲区，用于检测数字中的小数点
            final AtomicInteger sentenceCount = new AtomicInteger(0); // 已发送句子的计数
            final StringBuilder fullResponse = new StringBuilder(); // 完整响应的缓冲区
            final AtomicBoolean finalSentenceSent = new AtomicBoolean(false); // 跟踪最后一个句子是否已发送

            // 创建流式响应监听器
            StreamResponseListener streamListener = new StreamResponseListener() {
                @Override
                public void onStart() {
                    sessionCompleted.set(false);
                    finalSentenceSent.set(false);
                }

                @Override
                public void onToken(String token) {
                    // 将token添加到完整响应
                    fullResponse.append(token);

                    // 逐字符处理token
                    for (int i = 0; i < token.length();) {
                        int codePoint = token.codePointAt(i);
                        String charStr = new String(Character.toChars(codePoint));

                        // 将字符添加到上下文缓冲区（保留最近的字符以检测数字模式）
                        contextBuffer.append(charStr);
                        if (contextBuffer.length() > 20) { // 保留足够的上下文
                            contextBuffer.delete(0, contextBuffer.length() - 20);
                        }

                        // 将字符添加到当前句子缓冲区
                        currentSentence.append(charStr);

                        // 检查各种断句标记
                        boolean shouldSendSentence = false;
                        boolean isEndMark = SENTENCE_END_PATTERN.matcher(charStr).find();
                        boolean isPauseMark = PAUSE_PATTERN.matcher(charStr).find();
                        boolean isSpecialMark = SPECIAL_PATTERN.matcher(charStr).find();
                        boolean isNewline = NEWLINE_PATTERN.matcher(charStr).find();
                        boolean isEmoji = EmojiUtils.isEmoji(codePoint);

                        // 检查当前句子是否包含颜文字
                        boolean containsKaomoji = false;
                        if (currentSentence.length() >= 3) { // 颜文字至少需要3个字符
                            containsKaomoji = EmojiUtils.containsKaomoji(currentSentence.toString());
                        }

                        // 如果当前字符是句号，检查它是否是数字中的小数点
                        if (isEndMark && charStr.equals(".")) {
                            String context = contextBuffer.toString();
                            Matcher numberMatcher = NUMBER_PATTERN.matcher(context);
                            // 如果找到数字模式（如"0.271"），则不视为句子结束标点
                            if (numberMatcher.find() && numberMatcher.end() >= context.length() - 3) {
                                isEndMark = false;
                            }
                        }

                        // 判断是否应该发送当前句子
                        if (isEndMark) {
                            // 句子结束标点是强断句信号
                            shouldSendSentence = true;
                        } else if (isNewline) {
                            // 换行符也是强断句信号
                            shouldSendSentence = true;
                        } else if ((isPauseMark || isSpecialMark || isEmoji || containsKaomoji)
                                && currentSentence.length() >= MIN_SENTENCE_LENGTH) {
                            // 停顿标点、特殊标点、表情符号或颜文字在句子足够长时可以断句
                            shouldSendSentence = true;
                        }

                        // 如果应该发送句子，且当前句子长度满足要求
                        if (shouldSendSentence && currentSentence.length() >= MIN_SENTENCE_LENGTH) {
                            String sentence = currentSentence.toString().trim();

                            // 过滤颜文字
                            sentence = EmojiUtils.filterKaomoji(sentence);

                            if (containsSubstantialContent(sentence)) {
                                boolean isFirst = sentenceCount.get() == 0;
                                boolean isLast = false; // 只有在onComplete中才会有最后一个句子

                                sentenceHandler.accept(sentence, isFirst, isLast);
                                sentenceCount.incrementAndGet();

                                // 清空当前句子缓冲区
                                currentSentence.setLength(0);
                            }
                        }

                        // 移动到下一个码点
                        i += Character.charCount(codePoint);
                    }
                }

                @Override
                public void onComplete(String completeResponse) {
                    // 检查该会话是否已完成处理
                    if (sessionCompleted.compareAndSet(false, true)) {
                        // 处理当前缓冲区剩余的内容（如果有）
                        if (currentSentence.length() > 0 && containsSubstantialContent(currentSentence.toString())
                                && !finalSentenceSent.get()) {
                            String sentence = currentSentence.toString().trim();
                            boolean isFirst = sentenceCount.get() == 0;
                            boolean isLast = true; // 这是最后一个句子

                            sentenceHandler.accept(sentence, isFirst, isLast);
                            sentenceCount.incrementAndGet();
                            finalSentenceSent.set(true);
                        } else if (!finalSentenceSent.get()) {
                            // 如果没有剩余内容但也没有发送过最后一个句子，发送一个空的最后句子标记
                            // 这确保即使没有剩余内容，也会发送最后一个句子标记
                            boolean isFirst = sentenceCount.get() == 0;
                            sentenceHandler.accept("", isFirst, true);
                            finalSentenceSent.set(true);
                        }

                        // 记录处理的句子数量
                        logger.debug("总共处理了 {} 个句子", sentenceCount.get());
                    }
                }

                @Override
                public void onFinal(List<Map<String, Object>> allMessages, LlmService llmService) {
                    if (allMessages.isEmpty()) {
                        return;
                    }
                    List<Map<String, Object>> newMessages = new ArrayList<>();
                    // 如果本轮对话是function_all或mcp调用(最后一条信息的类型)，把用户的消息类型也修正为同样类型
                    String lastMessageType = allMessages.get(allMessages.size() - 1).get("messageType").toString();
                    // 遍历allMessages，将未保存的user及assistant入库
                    allMessages.forEach(message -> {
                        Object messageId = message.get("messageId");
                        String role = String.valueOf(message.get("role"));

                        // 消息入库
                        if (!"system".equals(role) && messageId == null) {// 系统消息跳过
                            // 这里后续看下，是否需要把content为空和角色为tool的入库，目前不入库（这类主要是function_call的二次调用llm进行总结时的过程消息）
                            String messageContent = message.get("content") == null ? ""
                                    : String.valueOf(message.get("content"));
                            if (!"tool".equals(role) && !messageContent.isEmpty()
                                    && !message.containsKey("messageId")) {// 非空未入库消息，则进行入库
                                modelContext.addMessage(messageContent, role, lastMessageType);
                                // 数据入库后，给个id，避免下次再被入库
                                message.put("messageId", 0);
                                message.put("messageType", lastMessageType);
                                newMessages.add(message);
                            }
                        }
                    });
                    newMessages.forEach(message -> {
                        if ("NORMAL".equals(String.valueOf(message.get("messageType")))) {
                            // 普通消息才加入历史缓存
                            llmService.updateHistoryCache(modelContext, message);
                        }
                    });
                }

                @Override
                public void onError(Throwable e) {
                    logger.error("流式响应出错: {}", e.getMessage(), e);
                    // 发送错误信号
                    sentenceHandler.accept("抱歉，我在处理您的请求时遇到了问题。", true, true);

                    // 清除会话完成标志
                    sessionCompletionFlags.remove(sessionId);
                }
            };

            // 调用现有的流式方法
            chatStream(device, message, streamListener);

        } catch (Exception e) {
            logger.error("处理流式查询时出错: {}", e.getMessage(), e);
            // 发送错误信号
            sentenceHandler.accept("抱歉，我在处理您的请求时遇到了问题。", true, true);

            // 清除会话完成标志
            sessionCompletionFlags.remove(device.getSessionId());
        }
    }

    /**
     * 判断文本是否包含实质性内容（不仅仅是空白字符或标点符号）
     * 
     * @param text 要检查的文本
     * @return 是否包含实质性内容
     */
    private boolean containsSubstantialContent(String text) {
        if (text == null || text.trim().length() < MIN_SENTENCE_LENGTH) {
            return false;
        }

        // 移除所有标点符号和空白字符后，检查是否还有内容
        String stripped = text.replaceAll("[\\p{P}\\s]", "");
        return stripped.length() >= 2; // 至少有两个非标点非空白字符
    }

    /**
     * 获取或创建LLM服务
     * 
     * @param deviceId 设备ID
     * @param configId 配置ID
     * @return LLM服务
     */
    public LlmService getLlmService(String deviceId, Integer configId) {

        // 检查设备是否已有服务且配置ID不同
        Integer currentConfigId = deviceConfigIds.get(deviceId);
        if (currentConfigId != null && !currentConfigId.equals(configId)) {
            // 配置ID变更，移除旧服务
            deviceLlmServices.remove(deviceId);
        }

        // 更新设备当前使用的configId
        deviceConfigIds.put(deviceId, configId);

        // 获取或创建服务
        return deviceLlmServices.computeIfAbsent(deviceId, k -> createLlmService(configId));
    }

    /**
     * 创建LLM服务
     * 
     * @param configId 配置ID
     * @return LLM服务
     */
    private LlmService createLlmService(Integer configId) {
        // 根据配置ID查询配置
        SysConfig config = configService.selectConfigById(configId);
        if (config == null) {
            throw new RuntimeException("找不到配置ID: " + configId);
        }

        String provider = config.getProvider().toLowerCase();
        String model = config.getConfigName();
        String endpoint = config.getApiUrl();
        String apiKey = config.getApiKey();
        String appId = config.getAppId();
        String apiSecret = config.getApiSecret();

        return LlmServiceFactory.createLlmService(provider, endpoint, appId, apiKey, apiSecret, model);
    }

    /**
     * 清除设备缓存
     * 
     * @param deviceId 设备ID
     */
    public void clearMessageCache(String deviceId) {
        chatMemory.clearMessages(deviceId);
    }

    /**
     * 三参数消费者接口
     */
    public interface TriConsumer<T, U, V> {
        void accept(T t, U u, V v);
    }
}