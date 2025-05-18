package com.xiaozhi.entity.dto;


import lombok.Data;

/**
 * @author 匡江山
 */
@Data
public class UserDTO {

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 用户名
     */
    private String username;

    /**
     * 账号
     */
    private String account;

    /**
     * 机构名称
     */
    private String schoolName;

    /**
     * token令牌
     */
    private String token;
}
