package com.xiaozhi.http;


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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 抗遗忘API调用
 *
 * @author 匡江山
 */
@Component
public class ForgetHttp {

    @Value("${forget.url}")
    private String forgetApi;

    /**
     * 登录获取token
     *
     * @param account    账号
     * @param tenantCode 租户
     * @return token
     */
    private String getTokenHttp(String account, String tenantCode) {
        // 获取token
        String loginUrl = forgetApi + "/deviceLogin";
        Map<String, Object> formData = new HashMap<>();
        formData.put("account", account);
        HttpRequest post = HttpUtil.createPost(loginUrl);
        post.form(formData);
        post.timeout(3000);
        HttpResponse response = post.execute();
        String body = response.body();
        if (StrUtil.isNotBlank(body)) {
            JSONObject json = JSONUtil.parseObj(body);
            if (json.getInt("code") == 200) {
                UserDTO data = json.getBean("data", UserDTO.class);
                data.setToken(json.getStr("token"));
                return data.getToken();
            }
        }
        return null;
    }

    /**
     * 检查抗遗忘任务 | 如果第一次检查则获取所有任务,第一次之后就拉取最新的任务
     *
     * @param token token令牌
     */
    public List<TaskDTO> checkForgetTaskHttp(String token) {
        // 获取今日任务列表
        String taskUrl = forgetApi + "/forgetApp/todayCalendar/queryCalendarTaskList";
        HttpResponse response = HttpUtil.createGet(taskUrl).header("Authorization", token).timeout(6000).execute();
        String body = response.body();
        // 解析结果
        JSONObject jsonObject = JSONUtil.parseObj(body);
        if (jsonObject.getInt("code") == 200) {
            Long calendarId = jsonObject.getLong("calendarId");
            List<TaskDTO> tasks = jsonObject.getBeanList("forgeTaskSouResultVos", TaskDTO.class);
            if (CollUtil.isNotEmpty(tasks)) {
                tasks.forEach(task -> task.setCalendarId(calendarId));
            }
        }
        return null;
    }

    public void getWordList(String token, TaskDTO task) {
        String wordUrl = forgetApi + "/forgetApp/study/queryTaskWordsByTaskId?taskId=" + task.getTaskId();
        HttpResponse response = HttpUtil.createGet(wordUrl).header("Authorization", token).timeout(6000).execute();
        String body = response.body();
        JSONObject jsonObject = JSONUtil.parseObj(body);
        if (jsonObject.getInt("code") == 200) {
            List<WordDTO> words = jsonObject.getBeanList("data", WordDTO.class);
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
    }

}
