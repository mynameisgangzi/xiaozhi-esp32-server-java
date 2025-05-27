package com.xiaozhi.http;


import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.xiaozhi.entity.dto.TaskDTO;
import com.xiaozhi.entity.dto.UserDTO;
import com.xiaozhi.entity.dto.WordDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 抗遗忘API调用
 *
 * @author 匡江山
 */
@Slf4j
@Component
public class ForgetHttp {

    @Value("${forget.url}")
    private String forgetApi;

    /**
     * 远程调用获取用户信息
     *
     * @param account 账号
     * @return token
     */
    public UserDTO getUserInfo(String account) {
        UserDTO user = null;
        // 获取 用户信息
        try {
            String loginUrl = forgetApi + "/deviceLogin";
            Map<String, Object> formData = new HashMap<>();
            formData.put("account", account);
            HttpRequest post = HttpUtil.createPost(loginUrl);
            post.body(JSONUtil.toJsonStr(formData));
            post.timeout(3000);
            HttpResponse response = post.execute();
            String body = response.body();
            if (StrUtil.isNotBlank(body)) {
                JSONObject json = JSONUtil.parseObj(body);
                if (json.getInt("code") == 200) {
                    UserDTO data = json.getBean("data", UserDTO.class);
                    data.setToken(json.getStr("token"));
                    user = data;
                }
            }
        } catch (Exception e) {
            log.error("获取用户失败,失败账号:{}", account);
            log.error(e.getMessage(), e);
        }
        return user;
    }

    /**
     * 检查抗遗忘任务 | 如果第一次检查则获取所有任务,第一次之后就拉取最新的任务
     *
     * @param account 账号
     * @param token   token令牌
     */
    public List<TaskDTO> checkForgetTaskHttp(String account, String token) {
        List<TaskDTO> tasks = null;
        try {
            // 获取今日任务列表
            String taskUrl = forgetApi + "/forgetApp/todayCalendar/queryCalendarTaskList";
            HttpResponse response = HttpUtil.createGet(taskUrl).header("Authorization", token).timeout(6000).execute();
            String body = response.body();
            // 解析结果
            JSONObject jsonObject = JSONUtil.parseObj(body);
            if (jsonObject.getInt("code") == 200) {
                JSONObject data = jsonObject.getJSONObject("data");
                Long calendarId = data.getLong("calendarId");
                tasks = data.getBeanList("forgeTaskSouResultVos", TaskDTO.class);
                if (CollUtil.isNotEmpty(tasks)) {
                    tasks.forEach(task -> task.setCalendarId(calendarId));
                }
            }
        } catch (Exception e) {
            log.error("用户:{},检查更新抗遗忘任务失败", account);
            log.error(e.getMessage(), e);
        }
        return Optional.ofNullable(tasks).orElse(new ArrayList<>());
    }

    /**
     * 获取任务单词列表
     *
     * @param account 账号
     * @param token   token
     * @param task    任务
     * @return 单词列表
     */
    public List<WordDTO> getWordList(String account, String token, TaskDTO task) {
        List<WordDTO> words = null;
        try {
            String wordUrl = forgetApi + "/forgetApp/study/queryTaskWordsByTaskId?taskId=" + task.getTaskId();
            HttpResponse response = HttpUtil.createGet(wordUrl).header("Authorization", token).timeout(6000).execute();
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
                }
            }
        } catch (Exception e) {
            log.error("用户:{}, 获取任务单词列表失败", account);
            log.error(e.getMessage(), e);
        }
        return Optional.ofNullable(words).orElse(new ArrayList<>());
    }

    /**
     * 单词发音提交评分
     *
     * @param account 账号
     * @param token   token
     * @param word    单词
     * @param data    发音数据
     */
    public void submitWordVoice(String account, String token, WordDTO word, byte[] data, String fileSuffix) {
        try {
            // 提交评分
            String submitUrl = forgetApi + "/forgetApp/study/individualRecordingReports";
            HttpRequest post = HttpUtil.createPost(submitUrl);
            post.header("Authorization", token);
            post.form("audioFile", data, UUID.randomUUID() + "." + fileSuffix);
            post.form("detailId", word.getDetailId());
            post.form("calendarId", word.getCalendarId());
            post.form("duration", 1);
            post.form("taskId", word.getTaskId());
            post.form("vocabularyId", word.getVocabularyId());
            post.form("word", word.getWord());
            post.form("lastWord", word.getLastWord());
            post.form("paraphrase", word.getParaphrase());
            HttpResponse response = post.execute();
            String body = response.body();
            log.info("用户:{}, 提交发音评分完成,结果:{}", account, body);
        } catch (Exception e) {
            log.error("用户:{}, 提交发音评分错误", account);
            log.error(e.getMessage(), e);
        }
    }

}
