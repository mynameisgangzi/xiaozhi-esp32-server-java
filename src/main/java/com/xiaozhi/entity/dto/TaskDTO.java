package com.xiaozhi.entity.dto;


import lombok.Data;

/**
 * @author 匡江山
 */
@Data
public class TaskDTO {

    /**
     * 抗遗忘任务日历ID
     */
    private Long calendarId;

    /**
     * 抗遗忘任务ID
     */
    private Long taskId;

    /**
     * 任务顺序,越小越靠前
     */
    private Integer sequence;

    /**
     * 任务是否完成
     */
    private Integer finished;

    /**
     * 单词数量
     */
    private Integer wordsNum;
}
