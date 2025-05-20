package com.xiaozhi.mapper.redis;


import com.xiaozhi.entity.dto.TaskDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * @author 匡江山
 */
@Repository
public class TaskRedisMapper {

    private static final String TASK_REDIS_KEY = "forget:task:%s:%s";

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * 获取用户的任务列表
     *
     * @param account 账号
     * @param date    日期
     * @return List<TaskDTO>
     */
    @SuppressWarnings("unchecked")
    public List<TaskDTO> getTasks(String account, String date) {
        String key = getTaskRedisKey(account, date);
        return Optional.ofNullable((List<TaskDTO>) redisTemplate.opsForValue().get(key)).orElse(new ArrayList<>());
    }

    public void updateTaskFinsh(String account, String date, Long taskId) {
        List<TaskDTO> tasks = getTasks(account, date);
        for (TaskDTO task : tasks) {
            if (task.getTaskId().equals(taskId)) {
                // 更新为已完成
                task.setFinished(1);
            }
        }
        // 保存任务信息
        batchSaveTasks(account, date, tasks);
    }

    /**
     * 保存用户的任务列表
     *
     * @param account 账号
     * @param date    日期
     * @param tasks   任务列表
     */
    public void batchSaveTasks(String account, String date, List<TaskDTO> tasks) {
        String key = getTaskRedisKey(account, date);
        redisTemplate.opsForValue().set(key, tasks);
    }

    /**
     * 获取 redis key
     *
     * @param account 账号
     * @param date    日期
     * @return String
     */
    private String getTaskRedisKey(String account, String date) {
        return String.format(TASK_REDIS_KEY, account, date);
    }
}
