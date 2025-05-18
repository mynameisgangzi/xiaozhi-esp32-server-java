package com.xiaozhi.service;


import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.xiaozhi.entity.dto.TaskDTO;
import com.xiaozhi.entity.dto.UserDTO;
import com.xiaozhi.entity.dto.WordDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 抗遗忘任务
 *
 * @author 匡江山
 */
@Service
public class ForgetService {

    private static final String API = "http://127.0.0.1:24914";

    private static final String TOKEN_KEY = "forget:token:";

    private static final String TASK_KEY = "forget:task:";

    private static final String WORD_KEY = "forget:word:";

    private static final String WORD_INDEX_KEY = "forget:word_index:";

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * 检查抗遗忘任务 | 如果第一次检查则获取所有任务,第一次之后就拉取最新的任务
     *
     * @param account    学生账号
     * @param tenantCode 租户code
     * @param apiKey     apiKey
     */
    public void checkForgetTask(String account, String tenantCode, String apiKey) {
        // 获取token令牌
        String token = getToken(account, tenantCode, apiKey);
        // 获取今日任务列表
        String taskUrl = API + "/forgetApp/todayCalendar/queryCalendarTaskList";
        HttpResponse response = HttpUtil.createGet(taskUrl).header("Authorization", token).timeout(6000).execute();
        String body = response.body();
        // 解析结果
        JSONObject jsonObject = JSONUtil.parseObj(body);
        if (jsonObject.getInt("code") == 200) {
            Long calendarId = jsonObject.getLong("calendarId");
            List<TaskDTO> tasks = jsonObject.getBeanList("forgeTaskSouResultVos", TaskDTO.class);
            if (CollUtil.isNotEmpty(tasks)) {
                tasks.forEach(task -> task.setCalendarId(calendarId));
                // 将任务进行缓存覆盖
                String taskKey = buildTaskKey(account, tenantCode);
                redisTemplate.opsForValue().set(TASK_KEY, taskKey, 24, TimeUnit.HOURS);
            }
        }
    }

    /**
     * 获取下一个任务
     *
     * @param account    账号
     * @param tenantCode 租户编号
     * @return 任务信息
     */
    @SuppressWarnings("unchecked")
    public TaskDTO getNextTask(String account, String tenantCode) {
        String taskKey = buildTaskKey(account, tenantCode);
        List<TaskDTO> tasks = (List<TaskDTO>) redisTemplate.opsForValue().get(taskKey);
        if (CollUtil.isNotEmpty(tasks)) {
            Optional<TaskDTO> first = tasks.stream().sorted(Comparator.comparing(TaskDTO::getSequence)).filter(x -> x.getFinished() == 0).findFirst();
            return first.orElse(null);
        }
        return null;
    }

    /**
     * 获取下一个单词
     *
     * @param account    账号
     * @param tenantCode 租户编号
     * @param apiKey     apiKey
     * @param task       任务信息
     * @return 单词信息
     */
    @SuppressWarnings("unchecked")
    public WordDTO getNextWord(String account, String tenantCode, String apiKey, TaskDTO task) {
        // 缓存key
        String wordKey = buildWordKey(account, tenantCode, task.getTaskId());
        String wordIndexKey = buildWordIndexKey(account, tenantCode, task.getTaskId());
        // 从缓存中查询单词列表
        List<WordDTO> words = (List<WordDTO>) redisTemplate.opsForValue().get(wordKey);
        if (CollUtil.isNotEmpty(words)) {
            // 如果单词列表为空就查询API
            String wordUrl = API + "/forgetApp/study/queryTaskWordsByTaskId?taskId=" + task.getTaskId();
            HttpResponse response = HttpUtil.createGet(wordUrl).header("Authorization", getToken(account, tenantCode, apiKey)).timeout(6000).execute();
            String body = response.body();
            JSONObject jsonObject = JSONUtil.parseObj(body);
            if (jsonObject.getInt("code") == 200) {
                words = jsonObject.getBeanList("data", WordDTO.class);
                if (CollUtil.isNotEmpty(words)) {
                    for (int i = 0; i < words.size(); i++) {
                        WordDTO word = words.get(i);
                        boolean lastWord = i == words.size() - 1;
                        word.setTaskId(task.getTaskId());
                        word.setCalendarId(task.getCalendarId());
                        // 设置是否是最后一个单词,用来标识这个单词完成后任务是否完成
                        word.setLastWord(lastWord ? 1 : 0);
                    }
                    // 缓存单词列表
                    redisTemplate.opsForValue().set(wordIndexKey, 0, 24, TimeUnit.HOURS);
                    redisTemplate.opsForValue().set(wordKey, words, 24, TimeUnit.HOURS);
                }
            }
        }
        if (CollUtil.isNotEmpty(words)) {
            int index = Optional.ofNullable((Integer) redisTemplate.opsForValue().get(wordIndexKey)).orElse(0);
            // 将索引+1
            redisTemplate.opsForValue().increment(wordIndexKey);
            return words.get(index);
        }
        return null;
    }

    /**
     * 登录获取token
     *
     * @param account    账号
     * @param tenantCode 租户
     * @param apiKey     apiKey
     * @return token
     */
    private String getToken(String account, String tenantCode, String apiKey) {
        String tokenKey = buildTokenKey(account, tenantCode);
        // 获取token
        UserDTO user = (UserDTO) redisTemplate.opsForValue().get(tokenKey);
        if (user != null) {
            // 重置过期时间
            redisTemplate.expire(tokenKey, 2400, TimeUnit.SECONDS);
            return user.getToken();
        }
        String loginUrl = API + "/deviceLogin";
        Map<String, Object> formData = new HashMap<>();
        formData.put("account", account);
        HttpRequest post = HttpUtil.createPost(loginUrl);
        post.form(formData);
        post.header("apiKey", apiKey);
        post.timeout(3000);
        HttpResponse response = post.execute();
        String body = response.body();
        if (StrUtil.isNotBlank(body)) {
            JSONObject json = JSONUtil.parseObj(body);
            if (json.getInt("code") == 200) {
                UserDTO data = json.getBean("data", UserDTO.class);
                data.setToken(json.getStr("token"));
                redisTemplate.opsForValue().set(tokenKey, data, 2400, TimeUnit.SECONDS);
                return data.getToken();
            }
        }
        return null;
    }

    private String buildTokenKey(String account, String tenantCode) {
        return TOKEN_KEY + tenantCode + ":" + account;
    }

    private String buildTaskKey(String account, String tenantCode) {
        return TASK_KEY + tenantCode + ":" + account;
    }

    private String buildWordKey(String account, String tenantCode, Long taskId) {
        return WORD_KEY + tenantCode + ":" + account + ":" + taskId;
    }

    private String buildWordIndexKey(String account, String tenantCode, Long taskId) {
        return WORD_INDEX_KEY + tenantCode + ":" + account + ":" + taskId;
    }


}
