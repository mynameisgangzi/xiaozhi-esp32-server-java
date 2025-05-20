package com.xiaozhi.service;


import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.StrUtil;
import com.xiaozhi.entity.dto.TaskDTO;
import com.xiaozhi.entity.dto.UserDTO;
import com.xiaozhi.entity.dto.WordDTO;
import com.xiaozhi.http.ForgetHttp;
import com.xiaozhi.mapper.redis.TaskRedisMapper;
import com.xiaozhi.mapper.redis.UserRedisMapper;
import com.xiaozhi.mapper.redis.WordRedisMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * 抗遗忘任务
 *
 * @author 匡江山
 */
@Slf4j
@Service
public class ForgetService {

    @Autowired
    private ForgetHttp forgetHttp;

    @Autowired
    private TaskRedisMapper taskRedisMapper;

    @Autowired
    private UserRedisMapper userRedisMapper;

    @Autowired
    private WordRedisMapper wordRedisMapper;

    /**
     * 获取用户信息
     *
     * @param account 账号
     * @return UserDTO
     */
    public UserDTO getUser(String account) {
        // 缓存中获取用户信息
        UserDTO info = userRedisMapper.getUserInfo(account);
        if (info == null) {
            // 远程调用API获取用户信息
            info = forgetHttp.getUserInfo(account);
            if (info != null) {
                // 保存用户信息
                userRedisMapper.setUserInfo(account, info);
            }
        }
        return info;
    }

    /**
     * 检查抗遗忘任务 | 如果第一次检查则获取所有任务,第一次之后就拉取最新的任务
     *
     * @param account 学生账号
     * @return 待完成任务数量
     */
    public int checkForgetTask(String account) {
        // 获取token令牌
        String token = getToken(account);

        // 当前日期
        String date = LocalDate.now().toString();

        // 获取今日任务列表
        List<TaskDTO> tasks = forgetHttp.checkForgetTaskHttp(account, token);
        log.info("用户:{}, 检查全部抗遗忘任务数量:{}", account, tasks.size());

        // TODO: 待合并已保存的任务和最新的任务列表

        // 异步更新任务的单词列表
        ThreadUtil.execute(() -> saveTaskWordList(account, token, date, tasks));

        // 返回待完成的任务数量
        return tasks.size();
    }

    /**
     * 获取下一个单词
     *
     * @param account 账号
     * @return 单词信息, 如果返回null, 则表示没有学习任务
     */
    public WordDTO getNextWord(String account) {
        String date = LocalDate.now().toString();
        // 获取下一个学习的单词
        return wordRedisMapper.getNextWord(account, date);
    }

    /**
     * 提交单词发音进行评估
     *
     * @param account    账号
     * @param data       发音数据
     * @param fileSuffix 文件后缀
     */
    public void submitWordVoice(String account, byte[] data, String fileSuffix) {
        // 获取当前用户正在学习的单词
        String date = LocalDate.now().toString();
        WordDTO currentWord = wordRedisMapper.getCurrentWord(account, date);

        if (currentWord == null || data == null || data.length == 0) {
            // 如果当前学习单词查询失败获取发音数据不存在,则直接返回
            return;
        }

        // 提交发音数据进行评估
        String token = getToken(account);

        // 异步提交评分
        ThreadUtil.execute(() -> forgetHttp.submitWordVoice(account, token, currentWord, data, fileSuffix));
    }

    /**
     * 获取 用户 token
     *
     * @param account 账号
     * @return String
     */
    private String getToken(String account) {
        String token = userRedisMapper.getUserToken(account);
        if (StrUtil.isBlank(token)) {
            // 远程调用API获取用户信息
            UserDTO info = Optional.ofNullable(forgetHttp.getUserInfo(account)).orElseThrow(() -> new RuntimeException("账号:" + account + ",身份认证失败"));
            // 保存用户信息
            userRedisMapper.setUserInfo(account, info);
            token = info.getToken();
        }
        return token;
    }

    /**
     * 保存任务的单词列表
     *
     * @param account 账号
     * @param token   token
     * @param date    日期
     * @param tasks   任务
     */
    private void saveTaskWordList(String account, String token, String date, List<TaskDTO> tasks) {
        for (TaskDTO task : tasks) {
            // 远程调用获取单词列表
            List<WordDTO> words = forgetHttp.getWordList(account, token, task);
            if (CollUtil.isNotEmpty(words)) {
                // 保存单词列表
                wordRedisMapper.batchSaveWord(account, date, words);
            }
        }
    }

}
