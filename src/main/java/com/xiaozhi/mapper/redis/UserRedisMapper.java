package com.xiaozhi.mapper.redis;


import com.xiaozhi.entity.dto.UserDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.concurrent.TimeUnit;

/**
 * @author 匡江山
 */
@Repository
public class UserRedisMapper {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final String USER_REDIS_KEY = "user:%s";

    private static final Integer TIME_OUT = 24;

    private static final TimeUnit TIME_UNIT = TimeUnit.MINUTES;

    /**
     * 获取用户信息
     *
     * @param account 账号
     * @return UserDTO
     */
    public UserDTO getUserInfo(String account) {
        String key = getUserRedisKey(account);
        return (UserDTO) redisTemplate.opsForValue().get(key);
    }

    /**
     * 设置用户信息
     *
     * @param account 账号
     * @param user    用户信息
     */
    public void setUserInfo(String account, UserDTO user) {
        String key = getUserRedisKey(account);
        redisTemplate.opsForValue().set(key, user, TIME_OUT, TIME_UNIT);
    }

    /**
     * 获取 user token
     *
     * @param account 账号
     * @return String
     */
    public String getUserToken(String account) {
        UserDTO user = getUserInfo(account);
        return user == null ? null : user.getToken();
    }

    /**
     * 获取 redis key
     *
     * @param account 账号
     * @return String
     */
    private String getUserRedisKey(String account) {
        return String.format(USER_REDIS_KEY, account);
    }
}
