package com.xiaozhi.websocket.service;

import cn.hutool.core.io.FileUtil;
import com.xiaozhi.entity.SysConfig;
import com.xiaozhi.entity.SysDevice;
import com.xiaozhi.service.ForgetService;
import com.xiaozhi.utils.AudioUtils;
import com.xiaozhi.utils.EmojiUtils;
import com.xiaozhi.utils.EmojiUtils.EmoSentence;
import com.xiaozhi.utils.OpusProcessor;
import com.xiaozhi.websocket.llm.LlmManager;
import com.xiaozhi.websocket.service.VadService.VadStatus;
import com.xiaozhi.websocket.stt.SttService;
import com.xiaozhi.websocket.stt.factory.SttServiceFactory;
import com.xiaozhi.websocket.tts.factory.TtsServiceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.text.DecimalFormat;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import com.xiaozhi.service.ReviewService;
import com.xiaozhi.websocket.service.SentenceAudioService;
/**
 * 对话处理服务
 * 负责处理语音识别和对话生成的业务逻辑
 */
@Service
public class DialogueService {
    private static final Logger logger = LoggerFactory.getLogger(DialogueService.class);
    private static final DecimalFormat df = new DecimalFormat("0.00");
    private static final long TIMEOUT_MS = 5000;

    @Autowired
    private LlmManager llmManager;

    @Autowired
    private AudioService audioService;

    @Autowired
    private TtsServiceFactory ttsFactory;

    @Autowired
    private SttServiceFactory sttFactory;

    @Autowired
    private MessageService messageService;

    @Autowired
    private VadService vadService;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private ReviewDialogueService reviewDialogueService;

    @Autowired
    private SentenceAudioService sentenceAudioService;

    @Autowired
    private OpusProcessor opusProcessor;

    // 会话状态管理
    private final Map<String, AtomicInteger> seqCounters = new ConcurrentHashMap<>();
    private final Map<String, Long> sttStartTimes = new ConcurrentHashMap<>();
    private final Map<String, Long> llmStartTimes = new ConcurrentHashMap<>();
    private final Map<String, StringBuilder> responses = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<Sentence>> sentenceQueue = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Map<String, byte[]> pcmMap = new ConcurrentHashMap<>();
    @Autowired
    private ForgetService forgetService;

    /**
     * 句子对象，用于跟踪每个句子的处理状态
     */
    private static class Sentence {
        private final int seq;
        private final String text;
        private final boolean isFirst;
        private final boolean isLast;
        private boolean ready = false;
        private String audioPath = null;
        private long timestamp = System.currentTimeMillis();
        private double modelResponseTime = 0.0; // 模型响应时间（秒）
        private double ttsGenerationTime = 0.0; // TTS生成时间（秒）
        private String dialogueId = null; // 对话ID

        public Sentence(int seq, String text, boolean isFirst, boolean isLast) {
            this.seq = seq;
            this.text = text;
            this.isFirst = isFirst;
            this.isLast = isLast;
        }

        public void setAudio(String path) {
            this.audioPath = path;
            this.ready = true;
        }

        public boolean isReady() {
            return ready;
        }

        public boolean isTimeout() {
            return System.currentTimeMillis() - timestamp > TIMEOUT_MS;
        }

        public int getSeq() {
            return seq;
        }

        public String getText() {
            return text;
        }

        public boolean isFirst() {
            return isFirst;
        }

        public boolean isLast() {
            return isLast;
        }

        public String getAudioPath() {
            return audioPath;
        }

        public void setModelResponseTime(double time) {
            this.modelResponseTime = time;
        }

        public double getModelResponseTime() {
            return modelResponseTime;
        }

        public void setTtsGenerationTime(double time) {
            this.ttsGenerationTime = time;
        }

        public double getTtsGenerationTime() {
            return ttsGenerationTime;
        }

        public void setDialogueId(String dialogueId) {
            this.dialogueId = dialogueId;
        }

        public String getDialogueId() {
            return dialogueId;
        }
    }

    /**
     * 处理音频数据
     */
    public Mono<Void> processAudioData(WebSocketSession session, byte[] opusData) {
        String sessionId = session.getId();
        SysDevice device = sessionManager.getDeviceConfig(sessionId);

        // 如果设备未注册或不在监听状态，忽略音频数据
        if (device == null) {
            logger.info("设备未注册，忽略音频数据");
            return Mono.empty();
        }
        if (!sessionManager.isListening(sessionId)) {
            logger.info("不在监听状态，忽略音频数据");
            return Mono.empty();
        }

        SysConfig sttConfig = null;//语音识别配置
        SysConfig ttsConfig = null;//语音合成配置

        if (device.getSttId() != null) {
            sttConfig = sessionManager.getCachedConfig(device.getSttId());
        }

        if (device.getTtsId() != null) {
            ttsConfig = sessionManager.getCachedConfig(device.getTtsId());
        }

        final SysConfig finalSttConfig = sttConfig;
        final SysConfig finalTtsConfig = ttsConfig;
        //logger.info("处理音频数据，vadService.processAudio(sessionId, opusData)");
        return Mono.fromCallable(() -> vadService.processAudio(sessionId, opusData))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(vadResult -> {
                    if(vadResult.getProcessedData() == null){
                        return Mono.empty();
                    }
                    // 如果VAD处理出错，直接返回
                    if (vadResult.getStatus() == VadStatus.ERROR ) {
                        logger.error("VAD处理出错，直接返回");
                        return Mono.empty();
                    }

                    // 检测到语音
                    sessionManager.updateLastActivity(sessionId);
                    //logger.info("VAD处理完成，根据VAD状态处理");
                    // 根据VAD状态处理
                    switch (vadResult.getStatus()) {
                        case SPEECH_START:
                            // 检测到语音开始
                            sttStartTimes.put(sessionId, System.currentTimeMillis());
                            //logger.info("检测到语音开始，开始语音识别");
                            return startStt(session, sessionId, finalSttConfig, finalTtsConfig,
                                    device, vadResult.getProcessedData());

                        case SPEECH_CONTINUE:
                            // 语音继续，发送数据到流式识别
                            //logger.info("检测到语音继续，发送数据到流式识别");
                            if (sessionManager.isStreaming(sessionId)) {
                                Sinks.Many<byte[]> audioSink = sessionManager.getAudioSink(sessionId);
                                if (audioSink != null) {
                                    audioSink.tryEmitNext(vadResult.getProcessedData());
                                }
                            }
                            return Mono.empty();

                        case SPEECH_END:
                            // 语音结束，完成流式识别
                            if (sessionManager.isStreaming(sessionId)) {
                                Sinks.Many<byte[]> audioSink = sessionManager.getAudioSink(sessionId);
                                if (audioSink != null) {
                                    audioSink.tryEmitComplete();
                                    sessionManager.setStreamingState(sessionId, false);

                                }
                            }
                            return Mono.empty();

                        default:
                            return Mono.empty();
                    }
                })
                .onErrorResume(e -> {
                    logger.error("处理音频数据失败: {}", e.getMessage(), e);
                    return Mono.empty();
                });
    }

    /**
     * 启动语音识别
     */
    private Mono<Void> startStt(
            WebSocketSession session,
            String sessionId,
            SysConfig sttConfig,
            SysConfig ttsConfig,
            SysDevice device,
            byte[] initialAudio) {

        // 如果已经在进行流式识别，先清理旧的资源
        sessionManager.closeAudioSink(sessionId);

        // 创建新的音频数据接收器
        Sinks.Many<byte[]> audioSink = sessionManager.createAudioSink(sessionId);
        sessionManager.setStreamingState(sessionId, true);

        // 获取STT服务
        SttService sttService = sttFactory.getSttService(sttConfig);

        if (sttService == null) {
            logger.error("无法获取STT服务 - Provider: {}", sttConfig != null ? sttConfig.getProvider() : "null");
            return Mono.empty();
        }

        // 发送初始音频数据
        if (initialAudio != null && initialAudio.length > 0) {
            audioSink.tryEmitNext(initialAudio);
        }

        final SysConfig finalTtsConfig = ttsConfig;

        // 为当前对话生成唯一ID
        final String dialogueId = sessionId + "_" + System.currentTimeMillis();
        sessionManager.setSessionAttribute(sessionId, "currentDialogueId", dialogueId);
        // 启动流式识别
        sttService.streamRecognition(audioSink.asFlux())
                .defaultIfEmpty("")
                .last() // 获取最终结果
                .flatMap(finalText -> {
                    if (!StringUtils.hasText(finalText)) {
                        return Mono.empty();
                    }

                    initChat(sessionId);

                    // 设置会话为非监听状态，防止处理自己的声音
                    sessionManager.setListeningState(sessionId, false);


                    // 获取完整的音频数据并保存
                    return Mono.fromCallable(() -> {
                        // 获取完整的PCM数据 - 使用原始未处理的PCM数据而非处理后的
                        List<byte[]> pcmFrames = vadService.getRawAudioData(sessionId);
                        vadService.removeState(sessionId);
                        String userAudioPath = null;

                        if (pcmFrames != null && !pcmFrames.isEmpty()) {
                            try {
                                // 简单合并PCM帧，不进行任何额外处理
                                int totalSize = pcmFrames.stream().mapToInt(frame -> frame.length).sum();
                                byte[] fullPcmData = new byte[totalSize];
                                int offset = 0;

                                // 直接合并，不进行淡入淡出处理
                                for (byte[] frame : pcmFrames) {
                                    System.arraycopy(frame, 0, fullPcmData, offset, frame.length);
                                    offset += frame.length;
                                }
                                byte[] bytes = opusProcessor.pcmToWav(fullPcmData);
                                //TODO
                                pcmMap.put(device.getStudentAccount(), bytes);
                                // 保存完整的PCM数据
                                userAudioPath = AudioUtils.AUDIO_PATH + AudioUtils.saveAsWav(fullPcmData);
                                sessionManager.setSessionAttribute(sessionId, "userAudioPath_" + dialogueId,
                                        userAudioPath);

                            } catch (Exception e) {
                                logger.error("保存用户音频失败: {}", e.getMessage(), e);
                            }
                        }

                        return finalText;
                    })
                            .subscribeOn(Schedulers.boundedElastic())
                            //不回显用户说的话
                            .flatMap(text -> messageService.sendMessage(session, "stt", "final", "")
                                    .then(audioService.sendStart(session))
                                    .then(Mono.fromRunnable(() -> {
                                        // TODO 在这里判断是否进入复习模式，如果是复习模式，则不需要调用大模型

                                        // 先检查是否已经在复习模式中
                                        if (reviewDialogueService.isInReviewMode(sessionId)) {
                                            logger.info("用户已在复习模式中，发送下一个单词");
                                            // 异步处理下一个单词，避免阻塞当前线程
                                            CompletableFuture.runAsync(() -> {
                                                String account = device.getStudentAccount();
                                                // 获取pcm data[]
                                                byte[] data = pcmMap.get(account);
                                                // 提交评分
                                                boolean lastWord = forgetService.submitWordVoice(account, data, "wav");
                                                // 如果是当前任务的最后单词,则鼓励用户
                                                reviewDialogueService.processNextWord(session,sessionId, device,ttsConfig, dialogueId,lastWord)
                                                        .subscribe();
                                            });
                                            return; // 不再执行后续的大模型调用
                                        }
                                        else if (reviewDialogueService.isInErrorReviewMode(sessionId)) {
                                            logger.info("检测在错误模式复习中");
                                            CompletableFuture.runAsync(() -> reviewDialogueService.processErrorNextWord(session, sessionId, device, ttsConfig,dialogueId).subscribe());
                                            return;
                                        }
                                        // 判断是否需要进入复习模式
                                        if (reviewDialogueService.containsLearningIntent(finalText)) {
                                            logger.info("检测到学习意图，启动复习模式而不是调用大模型");
                                            // 异步启动复习模式，避免阻塞当前线程
                                            CompletableFuture.runAsync(() -> {
                                                reviewDialogueService.tryEnterReviewMode(session,sessionId, finalText, device,ttsConfig,dialogueId)
                                                        .subscribe();
                                            });
                                            return; // 不再执行后续的大模型调用
                                        }

                                        // 使用句子切分处理响应
                                        llmManager.chatStreamBySentence(device, finalText, true,
                                                (sentence, isFirst, isLast) -> {
                                                    handleSentence(
                                                            session,
                                                            sessionId,
                                                            sentence,
                                                            isFirst,
                                                            isLast,
                                                            finalTtsConfig,
                                                            device.getVoiceName(),
                                                            dialogueId); // 传递对话ID
                                                });
                                    })));
                }).subscribe();

        return Mono.empty();
    }

    /**
     * 初始化对话状态
     */
    private void initChat(String sessionId) {
        llmStartTimes.put(sessionId, System.currentTimeMillis());
        responses.put(sessionId, new StringBuilder());
        seqCounters.putIfAbsent(sessionId, new AtomicInteger(0));
        sentenceQueue.putIfAbsent(sessionId, new CopyOnWriteArrayList<>());
        locks.putIfAbsent(sessionId, new ReentrantLock());
        // 初始化SentenceAudioService会话
        sentenceAudioService.initSession(sessionId);
    }

    /**
     * 处理LLM返回的句子
     */
    private void handleSentence(
            WebSocketSession session,
            String sessionId,
            String text,
            boolean isFirst,
            boolean isLast,
            SysConfig ttsConfig,
            String voiceName,
            String dialogueId) { // 添加对话ID参数

        // 获取句子序列号
        int seq = seqCounters.get(sessionId).incrementAndGet();

        // 累加完整回复内容
        if (text != null && !text.isEmpty()) {
            responses.get(sessionId).append(text);
        }

        // 计算模型响应时间
        final double responseTime;
        Long startTime = llmStartTimes.get(sessionId);
        if (startTime != null) {
            responseTime = (System.currentTimeMillis() - startTime) / 1000.0;
        } else {
            responseTime = 0.0;
        }

        // 创建句子对象
        Sentence sentence = new Sentence(seq, text, isFirst, isLast);
        sentence.setModelResponseTime(responseTime); // 记录模型响应时间
        sentence.setDialogueId(dialogueId); // 设置对话ID

        // 添加到句子队列
        CopyOnWriteArrayList<Sentence> queue = sentenceQueue.get(sessionId);
        queue.add(sentence);

        // 如果句子为空且是结束状态，直接标记为准备好（不需要生成音频）
        if ((text == null || text.isEmpty()) && isLast) {
            sentence.setAudio(null);
            sentence.setTtsGenerationTime(0); // 设置TTS生成时间为0
            processQueue(session, sessionId); // 尝试处理队列
            return;
        }

        // 处理表情符号
        EmoSentence emoSentence = EmojiUtils.processSentence(text);

        // 异步生成音频文件
        CompletableFuture.runAsync(() -> {
            try {
                // 生成音频
                long ttsStartTime = System.currentTimeMillis();
                String audioPath = ttsFactory.getTtsService(ttsConfig, voiceName)
                        .textToSpeech(emoSentence.getTtsSentence());
                long ttsDuration = System.currentTimeMillis() - ttsStartTime;

                // 记录TTS生成时间
                double ttsGenerationTime = ttsDuration / 1000.0;
                sentence.setTtsGenerationTime(ttsGenerationTime);

                // 记录日志
                logger.info("句子音频生成完成 - 序号: {}, 对话ID: {}, 模型响应: {}秒, 语音生成: {}秒, 内容: \"{}\"",
                        seq, dialogueId, df.format(sentence.getModelResponseTime()),
                        df.format(sentence.getTtsGenerationTime()), text);

                // 标记音频准备就绪
                sentence.setAudio(audioPath);

                // 如果是最后一个句子，存储助手的完整音频路径
                if (isLast && audioPath != null) {
                    sessionManager.setSessionAttribute(sessionId, "assistantAudioPath_" + dialogueId, audioPath);
                }

                // 尝试处理队列
                processQueue(session, sessionId);
            } catch (Exception e) {
                logger.error("生成音频失败 - 句子序号: {}, 错误: {}", seq, e.getMessage(), e);
                // 即使失败也标记为准备好，以便队列继续处理
                sentence.setAudio(null);
                sentence.setTtsGenerationTime(0);

                // 尝试处理队列
                processQueue(session, sessionId);
            }
        });
    }

    /**
     * 处理音频队列
     * 在音频生成完成后调用
     */
    private void processQueue(WebSocketSession session, String sessionId) {
        // 获取锁，确保线程安全
        ReentrantLock lock = locks.get(sessionId);
        if (lock == null) {
            return;
        }

        // 尝试获取锁，避免多线程同时处理
        if (!lock.tryLock()) {
            return;
        }

        try {
            // 获取句子队列
            CopyOnWriteArrayList<Sentence> queue = sentenceQueue.get(sessionId);
            if (queue == null || queue.isEmpty()) {
                return;
            }

            // 检查当前是否有句子正在播放
            boolean isCurrentlyPlaying = audioService.isPlaying(sessionId);

            if (isCurrentlyPlaying) {
                return;
            }

            // 找出最小序号
            int minSeq = Integer.MAX_VALUE;
            for (Sentence s : queue) {
                if (s.getSeq() < minSeq) {
                    minSeq = s.getSeq();
                }
            }

            // 找出该序号的句子
            Sentence nextSentence = null;
            for (Sentence s : queue) {
                if (s.getSeq() == minSeq) {
                    // 检查句子是否准备好或超时
                    if (s.isReady()) {
                        nextSentence = s;
                    } else if (s.isTimeout()) {
                        // 如果句子超时，标记为准备好但没有音频
                        s.setAudio(null);
                        nextSentence = s;
                    }
                    break;
                }
            }

            if (nextSentence != null) {
                final Sentence sentenceToProcess = nextSentence;

                // 发送到客户端
                audioService.sendAudioMessage(
                        session,
                        sentenceToProcess.getAudioPath(),
                        sentenceToProcess.getText(),
                        sentenceToProcess.isFirst(), // 是否是第一句
                        sentenceToProcess.isLast() // 是否是最后一句
                ).subscribe(
                        null,
                        error -> {
                            // 移除已处理的句子，即使失败也移除
                            queue.remove(sentenceToProcess);
                            // 递归调用，尝试处理下一个句子
                            processQueue(session, sessionId);
                        },
                        () -> {
                            // 从队列中移除已处理的句子
                            queue.remove(sentenceToProcess);

                            // 如果队列为空且是最后一句，重置监听状态
                            if (queue.isEmpty() && sentenceToProcess.isLast()) {
                                sessionManager.setListeningState(sessionId, true);
                            } else {
                                // 递归调用，尝试处理下一个句子
                                processQueue(session, sessionId);
                            }
                        });
            } else {
                // 如果队列为空，重置监听状态
                if (queue.isEmpty()) {
                    sessionManager.setListeningState(sessionId, true);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 处理语音唤醒
     */
    public Mono<Void> handleWakeWord(WebSocketSession session, String text) {
        String sessionId = session.getId();
        SysDevice device = sessionManager.getDeviceConfig(sessionId);

        if (device == null) {
            return Mono.empty();
        }

        // 获取配置
        final SysConfig ttsConfig = device.getTtsId() != null ? sessionManager.getCachedConfig(device.getTtsId())
                : null;
        sessionManager.updateLastActivity(sessionId);
        logger.info("检测到唤醒词: \"{}\"", text);

        // 初始化对话处理状态
        initChat(sessionId);

        // 设置为非监听状态，防止处理自己的声音
        sessionManager.setListeningState(sessionId, false);

        // 为当前对话生成唯一ID
        final String dialogueId = sessionId + "_" + System.currentTimeMillis();
        sessionManager.setSessionAttribute(sessionId, "currentDialogueId", dialogueId);

        // 保存用户消息内容（唤醒词）
        sessionManager.setSessionAttribute(sessionId, "userMessage_" + dialogueId, text);

        // 发送识别结果
        return messageService.sendMessage(session, "stt", "start", text)
                .then(audioService.sendStart(session)) // 立即发送TTS开始状态
                .then(Mono.fromRunnable(() -> {
                    // 使用句子切分处理响应
                    llmManager.chatStreamBySentence(device, text, true,
                            (sentence, isFirst, isLast) -> {
                                // 使用SentenceAudioService处理句子
                                sentenceAudioService.handleSentence(
                                        session,
                                        sessionId,
                                        sentence,
                                        isFirst,
                                        isLast,
                                        ttsConfig,
                                        device.getVoiceName(),dialogueId);
                            });
                }).then());
    }

    /**
     * 中止当前对话
     */
    public Mono<Void> abortDialogue(WebSocketSession session, String reason) {
        String sessionId = session.getId();
        logger.info("中止对话 - SessionId: {}, Reason: {}", sessionId, reason);

        // 关闭音频流
        sessionManager.closeAudioSink(sessionId);
        sessionManager.setStreamingState(sessionId, false);

        // 清空句子队列
        CopyOnWriteArrayList<Sentence> queue = sentenceQueue.get(sessionId);
        if (queue != null) {
            queue.clear();
        }

        // 重新设置监听状态
        sessionManager.setListeningState(sessionId, true);

        // 终止语音发送
        return audioService.sendStop(session);
    }

    /**
     * 清理会话资源
     */
    public void cleanupSession(String sessionId) {
        seqCounters.remove(sessionId);
        sttStartTimes.remove(sessionId);
        llmStartTimes.remove(sessionId);
        responses.remove(sessionId);
        sentenceQueue.remove(sessionId);
        locks.remove(sessionId);

        // 清理SentenceAudioService中的资源
        sentenceAudioService.cleanupSession(sessionId);

        // 清理AudioService中的资源
        audioService.cleanupSession(sessionId);

        // 清理复习模式资源
        reviewDialogueService.cleanupSession(sessionId);
        logger.info("清理会话资源 - SessionId: {}", sessionId);
    }

    /**
     * 退出复习模式
     */
//    public Mono<Void> exitReviewMode(WebSocketSession session) {
//        return reviewDialogueService.exitReviewMode(session);
//    }
}