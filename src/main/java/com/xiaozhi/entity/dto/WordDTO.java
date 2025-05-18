package com.xiaozhi.entity.dto;


import lombok.Data;

/**
 * @author 匡江山
 */
@Data
public class WordDTO {

    /**
     * 任务详情ID
     */
    private Long detailId;

    /**
     * 日历ID
     */
    private Long calendarId;

    /**
     * 任务ID
     */
    private Long taskId;

    /**
     * 单词ID
     */
    private Long vocabularyId;

    /**
     * 单词
     */
    private String word;

    /**
     * 释义
     */
    private String paraphrase;

    /**
     * 是否该任务的最后一个单词 0-不是最后一个单词 | 1-是最后一个单词 （包括中文）
     */
    private Integer lastWord = 0;
}
