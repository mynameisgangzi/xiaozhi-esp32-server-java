package com.xiaozhi.runner;


import com.xiaozhi.mapper.redis.TaskRedisMapper;
import com.xiaozhi.mapper.redis.WordRedisMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * @author 匡江山
 */
@Configuration
public class RedisCleanRunner implements CommandLineRunner {

    @Autowired
    private TaskRedisMapper taskRedisMapper;

    @Autowired
    private WordRedisMapper wordRedisMapper;

    @Override
    public void run(String... args) throws Exception {
        System.out.println("清理缓存");
        taskRedisMapper.cleanTasks();
        wordRedisMapper.cleanWord();
    }
}
