package com.xiaozhi.mapper.redis;


import com.xiaozhi.entity.dto.WordDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * @author 匡江山
 */
@Repository
public class WordRedisMapper {

    // 用户要学习的单词列表 key
    private static final String WORD_REDIS_KEY = "forget:word:%s:%s";

    // 用户即将学习单词的索引 key
    private static final String WORD_INDEX_KEY = "forget:word_index:%s:%s";

    // 用户当前所学单词 key
    private static final String WORD_CURRENT_KEY = "forget:word_current:%s:%s";

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    public void cleanWord() {
        Set<String> keys1 = redisTemplate.keys(WORD_REDIS_KEY.replace("%s:%s", "*"));
        redisTemplate.delete(keys1);
        Set<String> keys2 = redisTemplate.keys(WORD_INDEX_KEY.replace("%s:%s", "*"));
        redisTemplate.delete(keys2);
        Set<String> keys3 = redisTemplate.keys(WORD_CURRENT_KEY.replace("%s:%s", "*"));
        redisTemplate.delete(keys3);
    }

    /**
     * 保存单词列表
     *
     * @param account 账号
     * @param date    日期
     * @param words   单词列表
     */
    public void batchSaveWord(String account, String date, List<WordDTO> words) {
        String key = getWordRedisKey(account, date);
        for (WordDTO word : words) {
            redisTemplate.opsForList().rightPush(key, word);
        }
    }

    /**
     * 获取用户下一个要学习的单词
     *
     * @param account 账号
     * @param date    日期
     */
    public WordDTO getNextWord(String account, String date) {
        // 获取下一个单词的索引
        String indexKey = getWordIndexRedisKey(account, date);
        Integer index = Optional.ofNullable((Integer) redisTemplate.opsForValue().get(indexKey)).orElse(0);

        // 获取用户下一个学习的单词
        String key = getWordRedisKey(account, date);
        WordDTO word = (WordDTO) redisTemplate.opsForList().index(key, index);

        // 获取用户当前正在学习单词的key
        String currentWordRedisKey = getCurrentWordRedisKey(account, date);

        // 如果单词不为空
        if (word != null) {
            // 单词索引 + 1
            redisTemplate.opsForValue().increment(indexKey);

            // 保存用户当前正在学习的单词
            redisTemplate.opsForValue().set(currentWordRedisKey, word);
        } else {
            // 单词数据为空,则清楚正在学习的单词
            redisTemplate.delete(currentWordRedisKey);
        }
        return word == null ? new WordDTO() : word;
    }

    /**
     * 获取用户当前学习的单词
     *
     * @param account 账号
     * @param date    日期
     * @return WordDTO
     */
    public WordDTO getCurrentWord(String account, String date) {
        String key = getCurrentWordRedisKey(account, date);
        return (WordDTO) redisTemplate.opsForValue().get(key);
    }

    /**
     * 获取缓存key
     *
     * @param account 账号
     * @param date    日期
     * @return String
     */
    private String getWordRedisKey(String account, String date) {
        return String.format(WORD_REDIS_KEY, account, date);
    }

    /**
     * 获取用户即将所学单词索引的key
     *
     * @param account 账号
     * @param date    日期
     * @return String
     */
    private String getWordIndexRedisKey(String account, String date) {
        return String.format(WORD_INDEX_KEY, account, date);
    }

    /**
     * 获取用户当前所学单词key
     *
     * @param account 账号
     * @param date    日期
     * @return String
     */
    private String getCurrentWordRedisKey(String account, String date) {
        return String.format(WORD_CURRENT_KEY, account, date);
    }

}
