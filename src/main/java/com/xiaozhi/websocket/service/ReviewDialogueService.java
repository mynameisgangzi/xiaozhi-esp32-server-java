package com.xiaozhi.websocket.service;

import com.xiaozhi.entity.SysConfig;
import com.xiaozhi.entity.SysDevice;
import com.xiaozhi.service.ReviewService;
import com.xiaozhi.websocket.stt.SttService;
import com.xiaozhi.websocket.stt.factory.SttServiceFactory;
import com.xiaozhi.websocket.tts.TtsService;
import com.xiaozhi.websocket.tts.factory.TtsServiceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.WebSocketSession;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 复习对话服务
 * 负责处理复习模式下的对话逻辑
 */
@Service
public class ReviewDialogueService {
    private static final Logger logger = LoggerFactory.getLogger(ReviewDialogueService.class);
    
    // 学习相关意图的正则表达式
    private static final Pattern LEARN_INTENT_PATTERN = Pattern.compile(
            ".*?(学习|复习|练习|单词|英语|课程|作业|教材|朗读).*?", 
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Autowired
    private ReviewService reviewService;
    
    @Autowired
    private SessionManager sessionManager;
    
    @Autowired
    private SentenceAudioService sentenceAudioService;
    
    // 保存当前复习进度
    private final Map<String, Integer> reviewIndexMap = new ConcurrentHashMap<>();
    
    /**
     * 检查是否包含学习意图
     * @param text 识别的文本
     * @return 是否包含学习意图
     */
    public boolean containsLearningIntent(String text) {
        return LEARN_INTENT_PATTERN.matcher(text).matches();
    }
    
    /**
     * 尝试切换到复习模式
     * @param session WebSocket会话
     * @param text 识别的文本
     * @param device 设备信息
     * @return 是否已切换到复习模式
     */
    public Mono<Boolean> tryEnterReviewMode(WebSocketSession session, String sessionId,String text, SysDevice device,SysConfig ttsConfig) {
        // 检查文本是否包含学习相关意图
        logger.info("检查文本是否包含学习相关意图: {}", text);
        if (!containsLearningIntent(text)) {
            return Mono.just(false);
        }

        String studentAccount = "XP114841";device.getStudentAccount();
        
        if (studentAccount == null || studentAccount.isEmpty()) {
            logger.warn("无法切换到复习模式：设备未绑定用户");
            return Mono.just(false);
        }
        
        // 获取用户的复习任务
        return Mono.<List<Map<String, String>>>fromCallable(() -> reviewService.getReviewTasks(studentAccount, null))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(tasks -> Mono.<Boolean>defer(() -> {
                if (tasks == null || tasks.isEmpty()) {
                    // 没有复习任务
                    String noTaskMessage = "今天没有复习任务";
                    
                    // 使用SentenceAudioService发送消息
//                    SysConfig ttsConfig = null;
//                    if (device.getTtsId() != null) {
//                        ttsConfig = sessionManager.getCachedConfig(device.getTtsId());
//                    }
                    logger.info("device.getVoiceName()=={}",device.getVoiceName());
                    return sentenceAudioService.sendSingleMessage(
                            session,
                            sessionId,
                            noTaskMessage, 
                            ttsConfig, 
                            device.getVoiceName())
                        .thenReturn(false);
                }
                
                // 设置为复习模式
                logger.info("设置为复习模式");
                reviewService.setReviewMode(sessionId, studentAccount);
                reviewIndexMap.put(sessionId, 0);
                String taskInfo = "我们现在开始复习吧";
                
                // 使用SentenceAudioService发送复习模式开始提示
                

                logger.info("发送复习模式开始提示");
                return sentenceAudioService.sendSingleMessage(
                        session,
                        sessionId,
                        taskInfo, 
                        ttsConfig, 
                        device.getVoiceName())
                    .then(startReviewSession(session, studentAccount))
                    .thenReturn(true);
            }))
            .onErrorResume(e -> {
                logger.error("切换到复习模式失败", e);
                String errorMessage = "切换到复习模式失败，请稍后再试。";
                
                // 使用SentenceAudioService发送错误消息

                return sentenceAudioService.sendSingleMessage(
                        session,
                        sessionId,
                        errorMessage, 
                        ttsConfig, 
                        device.getVoiceName())
                    .thenReturn(false);
            });
    }
    
    /**
     * 开始复习会话
     */
    private Mono<Void> startReviewSession(WebSocketSession session, String studentAccount) {
        String sessionId = session.getId();
        SysDevice device = sessionManager.getDeviceConfig(sessionId);
        
        if (device == null) {
            logger.error("无法获取设备配置");
            return Mono.empty();
        }

        // 获取TTS配置
        final SysConfig ttsConfig = device.getTtsId() != null ? 
            sessionManager.getCachedConfig(device.getTtsId()) : null;
        
        // 获取当前复习项
        return Mono.fromCallable(() -> {
            List<Map<String, String>> tasks = reviewService.getReviewTasks(studentAccount, null);
            if (tasks == null || tasks.isEmpty()) {
                return null;
            }
            return tasks.get(0);
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(firstItem -> {
            if (firstItem == null) {
                return Mono.empty();
            }
            
            // 提示第一个单词
            String word = firstItem.get("word");
            
            StringBuilder prompt = new StringBuilder();
            prompt.append("第一个单词是：").append(word);
            prompt.append("，请读出这个单词和它的中文意思。");
            String promptMessage = prompt.toString();
            logger.info("生成第一个单词提示：{}", promptMessage);

            //学第一个单词后,更新单词索引到1
            reviewIndexMap.put(sessionId, 1);
            
            // 使用SentenceAudioService处理音频生成和发送
            sentenceAudioService.handleSentence(
                    session,
                    sessionId,
                    promptMessage,
                    true,  // 是第一句
                    true,  // 是最后一句
                    ttsConfig,
                    device.getVoiceName());

            // SentenceAudioService会异步处理音频生成和发送，所以这里可以直接返回
            return Mono.empty();
        });
    }
    
    /**
     * 处理复习模式下的音频
     */
    public Mono<Void> processReviewAudio(WebSocketSession session, byte[] audioData) {
        String sessionId = session.getId();
        SysDevice device = sessionManager.getDeviceConfig(sessionId);
        logger.info("在复习模式下收到学生发音，将提交到发音评估接口");
        return Mono.empty();
        
    }
    

    
    /**
     * 检查是否处于复习模式
     */
    public boolean isInReviewMode(String sessionId) {
        return reviewService.isInReviewMode(sessionId);
    }
    
    /**
     * 退出复习模式
     */
    public Mono<Void> exitReviewMode(WebSocketSession session) {
        String sessionId = session.getId();
        reviewService.exitReviewMode(sessionId);
        reviewIndexMap.remove(sessionId);
        
        SysDevice device = sessionManager.getDeviceConfig(sessionId);
        if (device == null) {
            return Mono.empty();
        }
        
        // 获取TTS配置
        final SysConfig ttsConfig = device.getTtsId() != null ? 
            sessionManager.getCachedConfig(device.getTtsId()) : null;
        
        String message = "已退出复习模式，你可以继续与我对话。";
        
        // 使用SentenceAudioService发送退出消息
        return sentenceAudioService.sendSingleMessage(
                session,
                sessionId,
                message,
                ttsConfig,
                device.getVoiceName());
    }
    
    /**
     * 处理下一个单词
     */
    public Mono<Void> processNextWord(WebSocketSession session, String sessionId,SysDevice device,SysConfig ttsConfig) {

        String studentAccount = device.getStudentAccount();
        
        // 获取当前复习项
        return Mono.fromCallable(() -> {
            List<Map<String, String>> tasks = reviewService.getReviewTasks(studentAccount, null);
            if (tasks == null || tasks.isEmpty()) {
                return null;
            }
            
            // 获取当前索引
            int currentIndex = reviewIndexMap.getOrDefault(sessionId, 0);
            if (currentIndex >= tasks.size()) {
                // 所有单词都已复习完
                return new HashMap<String, String>();
            }
            
            // 获取下一个单词
            Map<String, String> nextWord = tasks.get(currentIndex);
            // 更新索引
            reviewIndexMap.put(sessionId, currentIndex + 1);
            return nextWord;
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(nextWord -> {
            if (nextWord == null || nextWord.isEmpty()) {
                // 所有单词都已复习完
                String completionMessage = "恭喜你完成了所有单词的复习！";
                
                // 使用SentenceAudioService发送完成消息
                return sentenceAudioService.sendSingleMessage(
                        session,
                        sessionId,
                        completionMessage,
                        ttsConfig,
                        device.getVoiceName())
                    .then(exitReviewMode(session));
            }
            
            // 提示下一个单词
            String word = nextWord.get("word");

            StringBuilder prompt = new StringBuilder();
            prompt.append("下一个：").append(word);

            String promptMessage = prompt.toString();
            logger.info("生成下一个单词提示：{}", promptMessage);
            // 使用SentenceAudioService发送下一个单词提示
            return sentenceAudioService.sendSingleMessage(
                    session,
                    sessionId,
                    promptMessage,
                    ttsConfig,
                    device.getVoiceName());
        });
    }
    
    /**
     * 清理会话资源
     */
    public void cleanupSession(String sessionId) {
        reviewService.exitReviewMode(sessionId);
        reviewIndexMap.remove(sessionId);
    }
}