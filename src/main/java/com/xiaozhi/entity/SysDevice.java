package com.xiaozhi.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 设备表
 * 
 * @author Joey
 * 
 */
@JsonIgnoreProperties({ "startTime", "endTime", "start", "limit", "userId", "code" })
public class SysDevice extends SysRole {

    private String deviceId;

    private String sessionId;

    private Integer modelId;

    private Integer sttId;

    /**
     * 设备名称
     */
    private String deviceName;

    /**
     * 设备状态
     */
    private String state;

    /**
     * 设备对话次数
     */
    private Integer totalMessage;

    /**
     * 验证码
     */
    private String code;

    /**
     * 音频文件
     */
    private String audioPath;

    /**
     * 最后在线时间
     */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private String lastLogin;

    /**
     * WiFi名称
     */
    private String wifiName;

    /**
     * IP
     */
    private String ip;

    /**
     * 芯片型号
     */
    private String chipModelName;

    /**
     * 芯片类型
     */
    private String type;

    /**
     * 固件版本
     */
    private String version;

    /**
     * 可用全局function的名称列表(逗号分割)，为空则使用所有全局function
     */
    private String functionNames;

    /**
     * 
     * 学生所在平台编号
     */
    private String tenantCode;
    /**
     * 学生账号
     */
    private String studentAccount;

    /**
     * 学生姓名
     */
    private String username;

    /**
     * 配对码
     */
    private String pairCode;

    /**
     * 创建学生所在平台编号 get set方法
     */
    public String getTenantCode() {
        return tenantCode;
    }
    public SysDevice setTenantCode(String tenantCode) {
        this.tenantCode = tenantCode;
        return this;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPairCode() {
        return pairCode;
    }

    public void setPairCode(String pairCode) {
        this.pairCode = pairCode;
    }

    /**
     * get set
     */
    public String getStudentAccount() {
        return studentAccount;
    }
    
    public SysDevice setStudentAccount(String studentAccount) {
        this.studentAccount = studentAccount;
        return this;
    }
    

    public Integer getModelId() {
        return modelId;
    }

    public SysDevice setModelId(Integer modelId) {
        this.modelId = modelId;
        return this;
    }

    public Integer getSttId() {
        return sttId;
    }

    public SysDevice setSttId(Integer sttId) {
        this.sttId = sttId;
        return this;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public SysDevice setDeviceId(String deviceId) {
        this.deviceId = deviceId;
        return this;
    }

    public String getSessionId() {
        return sessionId;
    }

    public SysDevice setSessionId(String sessionId) {
        this.sessionId = sessionId;
        return this;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public SysDevice setDeviceName(String deviceName) {
        this.deviceName = deviceName;
        return this;
    }

    public String getState() {
        return state;
    }

    public SysDevice setState(String state) {
        this.state = state;
        return this;
    }

    public Integer getTotalMessage() {
        return totalMessage;
    }

    public SysDevice setTotalMessage(Integer totalMessage) {
        this.totalMessage = totalMessage;
        return this;
    }

    public String getCode() {
        return code;
    }

    public SysDevice setCode(String code) {
        this.code = code;
        return this;
    }

    public String getAudioPath() {
        return audioPath;
    }

    public SysDevice setAudioPath(String audioPath) {
        this.audioPath = audioPath;
        return this;
    }

    public String getLastLogin() {
        return lastLogin;
    }

    public SysDevice setLastLogin(String lastLogin) {
        this.lastLogin = lastLogin;
        return this;
    }

    public String getWifiName() {
        return wifiName;
    }

    public SysDevice setWifiName(String wifiName) {
        this.wifiName = wifiName;
        return this;
    }

    public String getIp() {
        return ip;
    }

    public SysDevice setIp(String ip) {
        this.ip = ip;
        return this;
    }

    public String getChipModelName() {
        return chipModelName;
    }

    public SysDevice setChipModelName(String chipModelName) {
        this.chipModelName = chipModelName;
        return this;
    }

    public String getType() {
        return type;
    }
    
    public SysDevice setType(String type) {
        this.type = type;
        return this;
    }

    public String getVersion() {
        return version;
    }

    public SysDevice setVersion(String version) {
        this.version = version;
        return this;
    }

    public String getFunctionNames() {
        return functionNames;
    }

    public void setFunctionNames(String functionNames) {
        this.functionNames = functionNames;
    }

    @Override
    public String toString() {
        return "SysDevice [deviceId=" + deviceId + ", sessionId=" + sessionId + ", modelId=" + modelId + ", sttId="
                + sttId + ", deviceName=" + deviceName + ", state=" + state + ", totalMessage="
                + totalMessage + ", code=" + code + ", audioPath=" + audioPath + ", lastLogin=" + lastLogin
                + ", wifiName=" + wifiName + ", ip=" + ip + ", chipModelName=" + chipModelName
                + ", version=" + version + ", functionNames=" + functionNames + ", type=" + type
                + "]";
    }
}