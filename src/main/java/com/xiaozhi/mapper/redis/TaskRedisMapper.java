package com.xiaozhi.mapper.redis;


import org.springframework.stereotype.Repository;

/**
 * @author 匡江山
 */
@Repository
public class TaskRedisMapper {

    private static final String TASK_REDIS_KEY = "forget:task:%s:%s";

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
