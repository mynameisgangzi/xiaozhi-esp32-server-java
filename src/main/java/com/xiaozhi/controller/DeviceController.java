package com.xiaozhi.controller;

import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.PageInfo;
import com.xiaozhi.common.web.AjaxResult;
import com.xiaozhi.entity.SysConfig;
import com.xiaozhi.entity.SysDevice;
import com.xiaozhi.entity.SysRole;
import com.xiaozhi.entity.SysUser;
import com.xiaozhi.service.SysConfigService;
import com.xiaozhi.service.SysDeviceService;
import com.xiaozhi.service.SysRoleService;
import com.xiaozhi.utils.CmsUtils;
import com.xiaozhi.websocket.service.SessionManager;

import org.springframework.util.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * 设备管理
 * 
 * @author Joey
 * 
 */

@RestController
@RequestMapping("/api/device")
public class DeviceController {

    private static final Logger logger = LoggerFactory.getLogger(DeviceController.class);

    @Resource
    private SysDeviceService deviceService;

    @Resource
    private SysRoleService roleService;

    @Resource
    private SysConfigService configService;

    @Resource
    private SessionManager sessionManager;

    @Autowired
    private Environment environment;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 设备查询
     * 
     * @param device
     * @return deviceList
     */
    @GetMapping("/query")
    public Mono<AjaxResult> query(SysDevice device, ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            try {
                // 从请求属性中获取用户信息
                SysUser user = exchange.getAttribute(CmsUtils.USER_ATTRIBUTE_KEY);
                if (user != null) {
                    device.setUserId(user.getUserId());
                }

                List<SysDevice> deviceList = deviceService.query(device);
                AjaxResult result = AjaxResult.success();
                result.put("data", new PageInfo<>(deviceList));
                return result;
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
                return AjaxResult.error();
            }
        });
    }

    /**
     * 设备信息更新
     * 
     * @param device
     * @return
     */
    @PostMapping("/update")
    public Mono<AjaxResult> update(SysDevice device, ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            try {
                int rows = deviceService.update(device);
                if (rows > 0) {
                    refreshSessionConfig(device);
                    return AjaxResult.success();
                } else {
                    return AjaxResult.error();
                }
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
                return AjaxResult.error();
            }
        });
    }

    private boolean refreshSessionConfig(SysDevice device) {
        try {
            String deviceId = device.getDeviceId();
            String sessionId = sessionManager.getSessionByDeviceId(deviceId);

            if (sessionId != null) {
                SysDevice olDevice = sessionManager.getDeviceConfig(sessionId);
                SysDevice updateDevice = device;
                updateDevice.setSessionId(sessionId);
                SysRole roleConfig = new SysRole();
                // 通过roleId获取ttsId
                if (device.getRoleId() != null) {
                    roleConfig = roleService.selectRoleById(device.getRoleId());
                }
                if (device.getModelId() != null) {
                    updateDevice.setModelId(device.getModelId());
                }
                if (device.getSttId() != null) {
                    updateDevice.setSttId(device.getSttId());
                    if (device.getSttId() != -1) {
                        SysConfig sttConfig = configService.selectConfigById(device.getSttId());
                        // 清空已缓存的配置信息
                        sessionManager.removeConfig(olDevice.getSttId());
                        sessionManager.cacheConfig(sttConfig.getConfigId(), sttConfig);
                    }
                }
                if (roleConfig.getTtsId() != null) {
                    updateDevice.setTtsId(roleConfig.getTtsId());
                    if (device.getTtsId() != -1) {
                        SysConfig tssConfig = configService.selectConfigById(roleConfig.getTtsId());
                        // 清空已缓存的配置信息
                        sessionManager.removeConfig(olDevice.getTtsId());
                        sessionManager.cacheConfig(tssConfig.getConfigId(), tssConfig);
                        updateDevice.setVoiceName(roleConfig.getVoiceName());
                    }
                }
                // 更新配置信息
                sessionManager.registerDevice(sessionId, updateDevice);
            }
        } catch (Exception e) {
            logger.error("刷新设备会话配置时发生错误", e);
        }
        return false;
    }

    /**
     * 添加设备
     * 
     * @param code
     */
    @PostMapping("/add")
    public Mono<AjaxResult> add(ServerWebExchange exchange) {
        return exchange.getFormData()
                .flatMap(formData -> {
                    String code = formData.getFirst("code");
                    return Mono.fromCallable(() -> {
                        try {
                            SysDevice device = new SysDevice();
                            device.setCode(code);
                            SysDevice query = deviceService.queryVerifyCode(device);
                            if (query == null) {
                                return AjaxResult.error("无效验证码");
                            }

                            // 从请求属性中获取用户信息
                            SysUser user = exchange.getAttribute(CmsUtils.USER_ATTRIBUTE_KEY);
                            if (user != null) {
                                device.setUserId(user.getUserId());
                            }

                            device.setDeviceId(query.getDeviceId());
                            device.setDeviceName(query.getType().length() > 0 ? query.getType() : "小智");
                            int row = deviceService.add(device);
                            if (row > 0) {
                                String deviceId = device.getDeviceId();
                                String sessionId = sessionManager.getSessionByDeviceId(deviceId);
                                if (sessionId != null) {
                                    sessionManager.closeSession(sessionId);
                                }
                                return AjaxResult.success();
                            } else {
                                return AjaxResult.error();
                            }
                        } catch (Exception e) {
                            logger.error(e.getMessage(), e);
                            return AjaxResult.error();
                        }
                    });
                });
    }

    @PostMapping("/addDevice")
    public Mono<AjaxResult> addDevice(ServerWebExchange exchange) {
        return exchange.getFormData()
                .flatMap(formData -> {
                    String deviceMac = formData.getFirst("deviceId");
                    String account = formData.getFirst("account");
                    String username = formData.getFirst("username");
                    return Mono.fromCallable(() -> {
                        try {
                            SysDevice device = new SysDevice();
                            device.setDeviceId(deviceMac);
                            device.setDeviceName(account + "_" + deviceMac);
                            // 设置用户信息
                            device.setUsername(username);
                            device.setStudentAccount(account);
                            device.setUserId(1);
                            int row = deviceService.add(device);
                            if (row > 0) {
                                String deviceId = device.getDeviceId();
                                String sessionId = sessionManager.getSessionByDeviceId(deviceId);
                                if (sessionId != null) {
                                    sessionManager.closeSession(sessionId);
                                }
                                return AjaxResult.success();
                            } else {
                                return AjaxResult.error();
                            }
                        } catch (Exception e) {
                            logger.error(e.getMessage(), e);
                            return AjaxResult.error();
                        }
                    });
                });
    }

    /**
     * 删除设备
     * 
     * @param device
     * @return
     */
    @PostMapping("/delete")
    public Mono<AjaxResult> delete(SysDevice device, ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            try {
                // 从请求属性中获取用户信息
                SysUser user = exchange.getAttribute(CmsUtils.USER_ATTRIBUTE_KEY);
                if (user != null) {
                    device.setUserId(user.getUserId());
                }

                // 删除设备
                int rows = deviceService.delete(device);

                if (rows > 0) {
                    // 如果设备有会话，清除会话
                    String deviceId = device.getDeviceId();
                    String sessionId = sessionManager.getSessionByDeviceId(deviceId);
                    if (sessionId != null) {
                        sessionManager.closeSession(sessionId);
                    }
                    return AjaxResult.success("删除成功");
                } else {
                    return AjaxResult.error("删除失败");
                }
            } catch (Exception e) {
                logger.error("删除设备时发生错误", e);
                return AjaxResult.error("删除设备时发生错误");
            }
        });
    }

    @PostMapping("/ota")
    public Mono<Map<String, Object>> ota(ServerWebExchange exchange) {
        // 读取请求体内容
        return DataBufferUtils.join(exchange.getRequest().getBody())
                .flatMap(dataBuffer -> {
                    try {
                        byte[] bytes = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(bytes);
                        DataBufferUtils.release(dataBuffer);

                        String requestBody = new String(bytes);
                        SysDevice device = new SysDevice();
                        String deviceIdAuth = null;

                        // 从请求头获取设备ID
                        deviceIdAuth = exchange.getRequest().getHeaders().getFirst("Device-Id");
                        
                        // 解析JSON请求体
                        try {
                            Map<String, Object> jsonData = objectMapper.readValue(requestBody,
                                    new TypeReference<Map<String, Object>>() {});
                            
                            // 获取设备ID (MAC地址)
                            if (deviceIdAuth == null && jsonData.containsKey("mac_address")) {
                                deviceIdAuth = (String) jsonData.get("mac_address");
                            }
                            
                            // 提取芯片型号
                            if (jsonData.containsKey("chip_model_name")) {
                                device.setChipModelName((String) jsonData.get("chip_model_name"));
                            }
                            
                            // 提取应用版本
                            if (jsonData.containsKey("application") && jsonData.get("application") instanceof Map) {
                                Map<String, Object> application = (Map<String, Object>) jsonData.get("application");
                                if (application.containsKey("version")) {
                                    device.setVersion((String) application.get("version"));
                                }
                            }
                            
                            // 提取WiFi名称和设备类型
                            if (jsonData.containsKey("board") && jsonData.get("board") instanceof Map) {
                                Map<String, Object> board = (Map<String, Object>) jsonData.get("board");
                                if (board.containsKey("ssid")) {
                                    device.setWifiName((String) board.get("ssid"));
                                }
                                if (board.containsKey("type")) {
                                    device.setType((String) board.get("type"));
                                }
                                if (board.containsKey("ip")) {
                                    device.setIp((String) board.get("ip"));
                                }
                            }
                        } catch (Exception e) {
                            logger.debug("JSON解析失败: {}", e.getMessage());
                        }

                        if (deviceIdAuth == null) {
                            Map<String, Object> errorResponse = new java.util.HashMap<>();
                            errorResponse.put("error", "设备ID为空");
                            return Mono.just(errorResponse);
                        }

                        final String deviceId = deviceIdAuth;
                        device.setDeviceId(deviceId);
                        device.setLastLogin(new Date().toString());
                        
                        // 设置设备IP地址（如果JSON中没有获取到）
                        if (device.getIp() == null) {
                            String remoteAddress = exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
                            device.setIp(remoteAddress);
                        }

                        // 查询设备是否已绑定
                        return Mono.fromCallable(() -> deviceService.query(device))
                                .flatMap(queryDevice -> {
                                    Map<String, Object> responseData = new java.util.HashMap<>();
                                    Map<String, Object> firmwareData = new java.util.HashMap<>();
                                    Map<String, Object> serverTimeData = new java.util.HashMap<>();
                                    Map<String, Object> websocketData = new java.util.HashMap<>();

                                    // 设置服务器时间
                                    long timestamp = System.currentTimeMillis();
                                    serverTimeData.put("timestamp", timestamp);
                                    serverTimeData.put("timezone_offset", 480); // 东八区

                                    // 设置固件信息
                                    firmwareData.put("url", "");
                                    firmwareData.put("version", "1.0.0");

                                    // 获取服务器IP和端口
                                    String serverIp = CmsUtils.getServerIp();
                                    String portStr = environment.getProperty("server.port");
                                    int port = Integer.parseInt(portStr);

                                    // 设置WebSocket连接信息
                                    websocketData.put("url",
                                            "ws://" + serverIp + ":" + port + "/ws/xiaozhi/v1/");
                                    websocketData.put("token", "");
                                    
                                    // 检查设备是否已绑定
                                    if (ObjectUtils.isEmpty(queryDevice)) {
                                        // 设备未绑定，生成验证码
                                        try {
                                            SysDevice codeResult = deviceService.generateCode(device);
                                            Map<String, Object> activationData = new java.util.HashMap<>();
                                            activationData.put("code", codeResult.getCode());
                                            activationData.put("message", codeResult.getCode());
                                            responseData.put("activation", activationData);
                                        } catch (Exception e) {
                                            logger.error("生成验证码失败", e);
                                            Map<String, Object> errorResponse = new java.util.HashMap<>();
                                            errorResponse.put("error", "生成验证码失败");
                                            return Mono.just(errorResponse);
                                        }
                                    } else {
                                        // 设备已绑定，更新设备状态和信息
                                        SysDevice boundDevice = queryDevice.get(0);
                                        
                                        // 保留原设备名称，更新其他信息
                                        device.setDeviceName(boundDevice.getDeviceName());
                                        device.setState("1");
                                        
                                        // 更新设备信息
                                        deviceService.update(device);
                                    }

                                    // 组装响应数据
                                    responseData.put("firmware", firmwareData);
                                    responseData.put("serverTime", serverTimeData);
                                    responseData.put("websocket", websocketData);

                                    return Mono.just(responseData);
                                });
                    } catch (Exception e) {
                        logger.error("处理OTA请求失败", e);
                        Map<String, Object> errorResponse = new java.util.HashMap<>();
                        errorResponse.put("error", "处理请求失败: " + e.getMessage());
                        return Mono.just(errorResponse);
                    }
                });
    }
}