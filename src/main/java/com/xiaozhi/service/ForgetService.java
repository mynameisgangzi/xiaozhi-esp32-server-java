package com.xiaozhi.service;


import cn.hutool.core.collection.CollUtil;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.xiaozhi.entity.dto.TaskDTO;
import com.xiaozhi.entity.dto.WordDTO;
import com.xiaozhi.http.ForgetHttp;
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

    private static final String TOKEN_KEY = "forget:token:";

    private static final String TASK_KEY = "forget:task:";

    private static final String WORD_KEY = "forget:word:";

    private static final String WORD_INDEX_KEY = "forget:word_index:";

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ForgetHttp forgetHttp;

    /**
     * 检查抗遗忘任务 | 如果第一次检查则获取所有任务,第一次之后就拉取最新的任务
     *
     * @param account    学生账号
     * @param tenantCode 租户code
     * @return 待完成任务数量
     */
    public int checkForgetTask(String account, String tenantCode) {
        // 获取token令牌
        String token = "";
        // 获取今日任务列表
        List<TaskDTO> tasks = forgetHttp.checkForgetTaskHttp(token);
        return 0;
    }

    /**
     * 获取下一个单词
     *
     * @param account    账号
     * @param tenantCode 租户编号
     * @return 单词信息,如果返回null,则表示没有学习任务
     */
    public WordDTO getNextWord(String account, String tenantCode) {
        // 获取下一个学习的单词
        return null;
    }

    /**
     * 提交单词发音进行评估
     *
     * @param account    账号
     * @param tenantCode 租户编码
     * @param data       发音数据
     */
    public void submitWordVoice(String account, String tenantCode, byte[] data) {

    }

}
