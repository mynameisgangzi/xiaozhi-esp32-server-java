package com.xiaozhi.websocket.service;

import com.xiaozhi.entity.SysConfig;
import com.xiaozhi.utils.EmojiUtils;
import com.xiaozhi.utils.EmojiUtils.EmoSentence;
import com.xiaozhi.websocket.tts.factory.TtsServiceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.text.DecimalFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 句子音频处理服务
 * 负责处理句子的音频生成和发送逻辑
 */
@Service
public class SentenceAudioService {
    private static final Logger logger = LoggerFactory.getLogger(SentenceAudioService.class);
    private static final DecimalFormat df = new DecimalFormat("0.00");
    private static final long TIMEOUT_MS = 5000;

    @Autowired
    private TtsServiceFactory ttsFactory;

    @Autowired
    private AudioService audioService;

    @Autowired
    private SessionManager sessionManager;

    // 会话状态管理
    private final Map<String, AtomicInteger> seqCounters = new ConcurrentHashMap<>();
    private final Map<String, Long> processingStartTimes = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<Sentence>> sentenceQueue = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

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
        private double processingTime = 0.0; // 处理时间（秒）
        private double ttsGenerationTime = 0.0; // TTS生成时间（秒）

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

        public void setProcessingTime(double time) {
            this.processingTime = time;
        }

        public double getProcessingTime() {
            return processingTime;
        }

        public void setTtsGenerationTime(double time) {
            this.ttsGenerationTime = time;
        }

        public double getTtsGenerationTime() {
            return ttsGenerationTime;
        }
    }

    /**
     * 初始化会话状态
     */
    public void initSession(String sessionId) {
        processingStartTimes.put(sessionId, System.currentTimeMillis());
        seqCounters.putIfAbsent(sessionId, new AtomicInteger(0));
        sentenceQueue.putIfAbsent(sessionId, new CopyOnWriteArrayList<>());
        locks.putIfAbsent(sessionId, new ReentrantLock());
    }

    /**
     * 处理句子，生成音频并发送
     */
    public void handleSentence(
            WebSocketSession session,
            String sessionId,
            String text,
            boolean isFirst,
            boolean isLast,
            SysConfig ttsConfig,
            String voiceName) {

        // 确保会话已初始化
        initSession(sessionId);

        // 获取句子序列号
        int seq = seqCounters.get(sessionId).incrementAndGet();

        // 计算处理时间
        final double processingTime;
        Long startTime = processingStartTimes.get(sessionId);
        if (startTime != null) {
            processingTime = (System.currentTimeMillis() - startTime) / 1000.0;
        } else {
            processingTime = 0.0;
        }

        // 创建句子对象
        Sentence sentence = new Sentence(seq, text, isFirst, isLast);
        sentence.setProcessingTime(processingTime); // 记录处理时间

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
                logger.info("开始生成音频 - 句子序号: {}, 内容: \"{}\"", seq, text);
                
                // 生成音频
                long ttsStartTime = System.currentTimeMillis();
                String audioPath = ttsFactory.getTtsService(ttsConfig, voiceName)
                        .textToSpeech(emoSentence.getTtsSentence());
                long ttsDuration = System.currentTimeMillis() - ttsStartTime;

                // 记录TTS生成时间
                double ttsGenerationTime = ttsDuration / 1000.0;
                sentence.setTtsGenerationTime(ttsGenerationTime);

                // 记录日志
                logger.info("句子音频生成完成 - 序号: {}, 响应: {}秒, 语音生成: {}秒, 内容: \"{}\"",
                        seq, df.format(sentence.getProcessingTime()),
                        df.format(sentence.getTtsGenerationTime()), text);

                // 标记音频准备就绪
                sentence.setAudio(audioPath);
                logger.info("句子音频生成完成,加入到队列：{}",audioPath);
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
                logger.info("将句子发送到客户端：{}",sentenceToProcess.getText());
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
     * 发送单个文本消息并生成音频
     * 简化版的处理逻辑，适用于只有一个句子的情况
     */
    public Mono<Void> sendSingleMessage(
            WebSocketSession session,
            String sessionId,
            String text,
            SysConfig ttsConfig,
            String voiceName) {

        
        // 设置会话为非监听状态，防止处理自己的声音
        sessionManager.setListeningState(sessionId, false);
        
        // 使用handleSentence处理单个消息
        handleSentence(session, sessionId, text, true, true, ttsConfig, voiceName);
        
        // 等待处理完成
        return Mono.fromRunnable(() -> {
            // 这是一个空操作，实际的处理和发送在handleSentence中异步进行
        }).then();
    }

    /**
     * 清理会话资源
     */
    public void cleanupSession(String sessionId) {
        seqCounters.remove(sessionId);
        processingStartTimes.remove(sessionId);
        sentenceQueue.remove(sessionId);
        locks.remove(sessionId);
    }
} 