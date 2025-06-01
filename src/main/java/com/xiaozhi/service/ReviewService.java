package com.xiaozhi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaozhi.entity.SysDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.Calendar;
@Service
public class ReviewService {

    private static final Logger logger = LoggerFactory.getLogger(ReviewService.class);
    private static final String REVIEW_CACHE_KEY_PREFIX = "review:task:";
    private static final String REVIEW_MODE_KEY_PREFIX = "review:mode:";
    private static final String ERROR_REVIEW_MODE_KEY_PREFIX = "error_review:mode:";
    private static final int CACHE_EXPIRY_HOURS = 24;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 获取用户的复习任务
     * @param userId 用户ID
     * @param date 日期（可选，默认当天）
     * @return 复习任务列表
     */
    public List<Map<String, String>> getReviewTasks(String userId, Date date) {
        String cacheKey = REVIEW_CACHE_KEY_PREFIX + userId;
        
        // 尝试从缓存获取
        Object cachedTasks = redisTemplate.opsForValue().get(cacheKey);
        if (cachedTasks != null) {
            try {
                return objectMapper.convertValue(cachedTasks, 
                    new TypeReference<List<Map<String, String>>>() {});
            } catch (Exception e) {
                logger.error("从缓存获取复习任务失败", e);
            }
        }
        
        // 缓存未命中，调用API获取
        try {
            // 格式化日期
            String planDate = date == null ? 
                new SimpleDateFormat("yyyyMMdd").format(new Date()) : 
                new SimpleDateFormat("yyyyMMdd").format(date);
            
            // 构建API URL
            String apiUrl = "http://121.41.95.171:28082/edu/eduForget" +
                "?tenantCode=member" +
                "&account=" + userId +
                "&planDate=20240401" ;//+ planDate;
            
            ResponseEntity<String> response;
            try {
                // 尝试调用真实API
                response = restTemplate.getForEntity(apiUrl, String.class);
            } catch (Exception e) {
                logger.warn("无法连接到服务器，使用模拟数据", e);
                // 创建模拟的ResponseEntity
                response = createMockResponseEntity();
            }
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode rootNode = objectMapper.readTree(response.getBody());
                
                if (rootNode.has("code") && rootNode.get("code").asInt() == 200 && rootNode.has("data")) {
                    JsonNode dataNode = rootNode.get("data");
                    List<Map<String, String>> tasks = objectMapper.convertValue(dataNode,
                        new TypeReference<List<Map<String, String>>>() {});
                    
                    // 缓存结果
                    //计算现在离0点还有多称时间
                    Calendar now = Calendar.getInstance();
                    Calendar endOfDay = Calendar.getInstance();
                    endOfDay.set(Calendar.HOUR_OF_DAY, 23);
                    endOfDay.set(Calendar.MINUTE, 59);
                    endOfDay.set(Calendar.SECOND, 59);
                    
                    long timeUntilEndOfDay = endOfDay.getTimeInMillis() - now.getTimeInMillis();
                    // 转换为秒，确保至少缓存1分钟
                    long cacheSeconds = Math.max(timeUntilEndOfDay / 1000, 60);
                    
                    redisTemplate.opsForValue().set(cacheKey, tasks, cacheSeconds, TimeUnit.SECONDS);
                    
                    return tasks;
                }
            }
            
            logger.error("获取复习任务失败: {}", response.getBody());
            return new ArrayList<>();
            
        } catch (Exception e) {
            logger.error("处理复习任务数据失败", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 创建模拟的ResponseEntity对象
     * @return 模拟的HTTP响应
     */
    private ResponseEntity<String> createMockResponseEntity() {
        try {
            // 创建模拟数据
            Map<String, Object> mockResponse = new HashMap<>();
            mockResponse.put("code", 200);
            mockResponse.put("msg", "操作成功");
            
            List<Map<String, String>> dataList = new ArrayList<>();
            
            Map<String, String> word1 = new HashMap<>();
            word1.put("word", "bear");
            word1.put("paraphrase", "熊");
            word1.put("pronunciation", "/beə/");
            dataList.add(word1);
            
            Map<String, String> word2 = new HashMap<>();
            word2.put("word", "brother");
            word2.put("paraphrase", "兄弟");
            word2.put("pronunciation", "/ˈbrʌðə/");
            dataList.add(word2);
            
            Map<String, String> word3 = new HashMap<>();
            word3.put("word", "crayon");
            word3.put("paraphrase", "蜡笔");
            word3.put("pronunciation", "/ˈkreɪən/");
            dataList.add(word3);
            
            mockResponse.put("data", dataList);
            
            // 将Map转换为JSON字符串
            String mockResponseJson = objectMapper.writeValueAsString(mockResponse);
            
            logger.info("创建模拟响应: {}", mockResponseJson);
            
            // 创建模拟的ResponseEntity，状态码为200 OK
            return new ResponseEntity<>(mockResponseJson, HttpStatus.OK);
        } catch (JsonProcessingException e) {
            logger.error("创建模拟响应失败", e);
            return new ResponseEntity<>("{\"code\":500,\"msg\":\"服务器错误\",\"data\":[]}", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
    
    
    /**
     * 设置用户为复习模式
     * @param sessionId WebSocket会话ID
     * @param userId 用户ID
     */
    public void setReviewMode(String sessionId, String userId) {
        String modeKey = REVIEW_MODE_KEY_PREFIX + sessionId;
        
        // 保存当前正在复习的用户ID和复习进度
        redisTemplate.opsForHash().put(modeKey, "userId", userId);
        redisTemplate.opsForHash().put(modeKey, "currentIndex", 0);
        redisTemplate.opsForHash().put(modeKey, "active", true);
        redisTemplate.expire(modeKey, CACHE_EXPIRY_HOURS, TimeUnit.HOURS);
    }

    /**
     * 检查会话是否处于复习模式
     * @param sessionId WebSocket会话ID
     * @return 是否处于复习模式
     */
    public boolean isInReviewMode(String sessionId) {
        String modeKey = REVIEW_MODE_KEY_PREFIX + sessionId;
        Object active = redisTemplate.opsForHash().get(modeKey, "active");
        return active != null && Boolean.TRUE.equals(active);
    }
    
    /**
     * 获取复习进度信息
     * @param sessionId WebSocket会话ID
     * @return 进度信息，包含userId和currentIndex
     */
    public Map<Object, Object> getReviewProgress(String sessionId) {
        String modeKey = REVIEW_MODE_KEY_PREFIX + sessionId;
        return redisTemplate.opsForHash().entries(modeKey);
    }
    
    /**
     * 更新复习进度
     * @param sessionId WebSocket会话ID
     * @param currentIndex 当前索引
     */
    public void updateReviewProgress(String sessionId, int currentIndex) {
        String modeKey = REVIEW_MODE_KEY_PREFIX + sessionId;
        redisTemplate.opsForHash().put(modeKey, "currentIndex", currentIndex);
    }
    
    /**
     * 退出复习模式
     * @param sessionId WebSocket会话ID
     */
    public void exitReviewMode(String sessionId) {
        String modeKey = REVIEW_MODE_KEY_PREFIX + sessionId;
        redisTemplate.delete(modeKey);
    }

    public void setErrorReviewMode(String sessionId) {
        String modeKey = ERROR_REVIEW_MODE_KEY_PREFIX + sessionId;
        // 保存当前正在复习的用户ID和复习进度
        redisTemplate.opsForHash().put(modeKey, "active", true);
        redisTemplate.expire(modeKey, CACHE_EXPIRY_HOURS, TimeUnit.HOURS);
    }

    public boolean isInErrorReviewMode(String sessionId) {
        String modeKey = ERROR_REVIEW_MODE_KEY_PREFIX + sessionId;
        Object active = redisTemplate.opsForHash().get(modeKey, "active");
        return Boolean.TRUE.equals(active);
    }

    public void exitErrorReviewMode(String sessionId) {
        String modeKey = ERROR_REVIEW_MODE_KEY_PREFIX + sessionId;
        redisTemplate.delete(modeKey);
    }
} 