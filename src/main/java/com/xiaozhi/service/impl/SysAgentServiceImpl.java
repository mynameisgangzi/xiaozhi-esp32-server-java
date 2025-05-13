package com.xiaozhi.service.impl;

import com.xiaozhi.dao.ConfigMapper;
import com.xiaozhi.entity.SysAgent;
import com.xiaozhi.entity.SysConfig;
import com.xiaozhi.service.SysAgentService;

import org.springframework.util.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.annotation.Resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 智能体服务实现
 * 
 * @author Joey
 */
@Service
public class SysAgentServiceImpl implements SysAgentService {

    private static final Logger logger = LoggerFactory.getLogger(SysAgentServiceImpl.class);

    @Resource
    private ConfigMapper configMapper;

    private final WebClient webClient = WebClient.builder().build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 添加智能体
     * 
     * @param agent 智能体信息
     * @return 结果
     */
    @Override
    @Transactional
    public int add(SysAgent agent) {
        return 0;
    }

    /**
     * 修改智能体
     * 
     * @param agent 智能体信息
     * @return 结果
     */
    @Override
    @Transactional
    public int update(SysAgent agent) {
        return 0;
    }

    /**
     * 删除智能体
     * 
     * @param agentId 智能体ID
     * @return 结果
     */
    @Override
    @Transactional
    public int delete(Integer agentId) {
        return 0;
    }

    /**
     * 查询智能体列表
     * 
     * @param agent 智能体信息
     * @return 智能体集合的Mono对象
     */
    @Override
    public Mono<List<SysAgent>> query(SysAgent agent) {
        // 如果设置了平台为Coze，则从Coze API获取智能体列表
        if ("coze".equalsIgnoreCase(agent.getProvider())) {
            return getCozeAgents(agent);
        } else if ("dify".equalsIgnoreCase(agent.getProvider())) {
            // 如果是DIFY平台，则从DIFY API获取智能体列表
            return getDifyAgents(agent);
        } else {
            // 如果不是特定平台，返回空
            return Mono.just(new ArrayList<>());
        }
    }

    /**
     * 从DIFY API获取智能体信息，并与数据库同步
     * 
     * @param agent 智能体信息
     * @return 智能体集合的Mono对象
     */
    private Mono<List<SysAgent>> getDifyAgents(SysAgent agent) {
        Integer userId = agent.getUserId();
        
        // 查询所有类型的Dify配置
        SysConfig queryConfig = new SysConfig();
        queryConfig.setUserId(userId);
        queryConfig.setProvider("dify");
        List<SysConfig> allConfigs = configMapper.query(queryConfig);
        
        if (ObjectUtils.isEmpty(allConfigs)) {
            return Mono.just(new ArrayList<>());
        }

        List<SysConfig> agentConfigs = allConfigs.stream()
                .filter(config -> "agent".equals(config.getConfigType()))
                .collect(Collectors.toList());

        // 创建一个Map来存储llm配置，以apiKey为键
        Map<String, SysConfig> llmConfigMap = new HashMap<>();
        allConfigs.stream()
                .filter(config -> "llm".equals(config.getConfigType()))
                .forEach(config -> {
                    if (config.getApiKey() != null) {
                        llmConfigMap.put(config.getApiKey(), config);
                    }
                });
        
        List<Mono<SysAgent>> agentMonos = new ArrayList<>();
        
        // 处理每个agent配置
        for (SysConfig agentConfig : agentConfigs) {
            String apiKey = agentConfig.getApiKey();
            String apiUrl = agentConfig.getApiUrl();
            Integer configId = agentConfig.getConfigId();
            
            // 检查是否已存在对应的llm配置
            SysConfig existingLlmConfig = llmConfigMap.get(apiKey);
            
            // 如果已存在llm配置，直接创建Agent对象返回
            if (existingLlmConfig != null) {
                SysAgent difyAgent = new SysAgent();
                difyAgent.setConfigId(existingLlmConfig.getConfigId());
                difyAgent.setProvider("dify");
                difyAgent.setApiKey(apiKey);
                difyAgent.setAgentName(existingLlmConfig.getConfigName());
                difyAgent.setAgentDesc(existingLlmConfig.getConfigDesc());
                difyAgent.setIsDefault(existingLlmConfig.getIsDefault());
                difyAgent.setPublishTime(existingLlmConfig.getCreateTime());
                
                // 添加到结果列表
                agentMonos.add(Mono.just(difyAgent));
            } else {
                // 如果不存在llm配置，调用API获取信息并创建新的llm配置
                Mono<SysAgent> agentMono = webClient.get()
                        .uri(apiUrl + "/info")
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .retrieve()
                        .bodyToMono(String.class)
                        .flatMap(infoResponse -> {
                            SysAgent difyAgent = new SysAgent();
                            difyAgent.setConfigId(configId);
                            difyAgent.setProvider("dify");
                            difyAgent.setApiKey(apiKey);
                            
                            try {
                                JsonNode infoNode = objectMapper.readTree(infoResponse);
                                String name = infoNode.has("name") ? infoNode.get("name").asText() : "DIFY Agent";
                                String description = infoNode.has("description") ? infoNode.get("description").asText() : "";
                                
                                difyAgent.setAgentName(name);
                                difyAgent.setAgentDesc(description);
                                
                                // 创建新的llm配置
                                SysConfig newLlmConfig = new SysConfig();
                                newLlmConfig.setUserId(userId);
                                newLlmConfig.setConfigType("llm");
                                newLlmConfig.setProvider("dify");
                                newLlmConfig.setApiKey(apiKey);
                                newLlmConfig.setConfigName(name);
                                newLlmConfig.setConfigDesc(description);
                                newLlmConfig.setApiUrl(apiUrl);
                                newLlmConfig.setState("1");  // 默认启用
                                
                                // 添加到数据库
                                Mono.fromCallable(() -> configMapper.add(newLlmConfig))
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .subscribe(
                                        result -> {
                                            logger.debug("添加DIFY LLM配置成功: {}", apiKey);
                                            difyAgent.setConfigId(newLlmConfig.getConfigId());
                                        },
                                        error -> logger.error("添加DIFY LLM配置失败: {}", error.getMessage())
                                    );
                                
                                // 获取图标信息
                                return webClient.get()
                                        .uri(apiUrl + "/meta")
                                        .header("Authorization", "Bearer " + apiKey)
                                        .header("Content-Type", "application/json")
                                        .retrieve()
                                        .bodyToMono(String.class)
                                        .map(metaResponse -> {
                                            try {
                                                JsonNode metaNode = objectMapper.readTree(metaResponse);
                                                if (metaNode.has("tool_icons") && metaNode.get("tool_icons").has("api_tool")) {
                                                    JsonNode apiTool = metaNode.get("tool_icons").get("api_tool");
                                                    if (apiTool.has("content")) {
                                                        String iconContent = apiTool.get("content").asText();
                                                        difyAgent.setIconUrl(iconContent);
                                                    }
                                                }
                                            } catch (Exception e) {
                                                logger.error("解析DIFY meta API响应异常", e);
                                            }
                                            return difyAgent;
                                        })
                                        .onErrorResume(e -> {
                                            logger.error("获取DIFY meta信息异常", e);
                                            return Mono.just(difyAgent);
                                        });
                            } catch (Exception e) {
                                logger.error("解析DIFY info API响应异常", e);
                                return Mono.just(difyAgent);
                            }
                        })
                        .onErrorResume(e -> {
                            logger.error("查询DIFY智能体信息异常", e);
                            SysAgent errorAgent = new SysAgent();
                            errorAgent.setConfigId(configId);
                            errorAgent.setProvider("dify");
                            errorAgent.setApiKey(apiKey);
                            errorAgent.setAgentName(agentConfig.getConfigName() != null ? agentConfig.getConfigName() : "DIFY Agent");
                            errorAgent.setAgentDesc("无法连接到DIFY API");
                            return Mono.just(errorAgent);
                        });
                
                agentMonos.add(agentMono);
            }
        }
        
        // 如果没有配置，返回空列表
        if (agentMonos.isEmpty()) {
            return Mono.just(new ArrayList<>());
        }
        
        // 合并所有智能体的Mono
        return Mono.zip(agentMonos, objects -> {
            List<SysAgent> agentList = new ArrayList<>();
            for (Object obj : objects) {
                SysAgent difyAgent = (SysAgent) obj;
                
                // 如果前端传入了智能体名称过滤条件，则进行过滤
                if (StringUtils.hasText(agent.getAgentName())) {
                    if (difyAgent.getAgentName() != null && 
                        difyAgent.getAgentName().toLowerCase().contains(agent.getAgentName().toLowerCase())) {
                        agentList.add(difyAgent);
                    }
                } else {
                    agentList.add(difyAgent);
                }
            }
            return agentList;
        });
    }

    /**
     * 从Coze API获取智能体列表，并与数据库同步
     * 
     * @param agent 智能体信息
     * @return 智能体集合的Mono对象
     */
    private Mono<List<SysAgent>> getCozeAgents(SysAgent agent) {
        // 获取当前用户的Coze配置
        List<SysConfig> configs = configMapper.query(agent);
        if (ObjectUtils.isEmpty(configs)) {
            return Mono.just(new ArrayList<>());
        }
        
        SysConfig config = configs.get(0);
        
        // 获取API密钥和空间ID
        String apiSecret = config.getApiSecret();
        String spaceId = config.getAppId();
        Integer userId = agent.getUserId();

        // 调用Coze API获取智能体列表
        return webClient.get()
                .uri("https://api.coze.cn/v1/space/published_bots_list?space_id=" + spaceId)
                .header("Authorization", "Bearer " + apiSecret)
                .header("Content-Type", "application/json")
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(response -> {
                    List<SysAgent> agentList = new ArrayList<>();
                    try {
                        JsonNode rootNode = objectMapper.readTree(response);
                        if (rootNode.has("code") && rootNode.get("code").asInt() == 0) {
                            JsonNode spaceBots = rootNode.path("data").path("space_bots");
                            
                            // 查询数据库中现有的所有与当前用户相关的coze智能体配置
                            SysConfig queryConfig = new SysConfig();
                            queryConfig.setUserId(userId);
                            queryConfig.setConfigType("llm");
                            queryConfig.setProvider("coze");
                            List<SysConfig> existingConfigs = configMapper.query(queryConfig);
                            
                            // 创建一个Map来存储现有的配置，以botId为键
                            Map<String, SysConfig> existingConfigMap = new HashMap<>();
                            for (SysConfig existingConfig : existingConfigs) {
                                if (existingConfig.getAppId() != null) {
                                    existingConfigMap.put(existingConfig.getAppId(), existingConfig);
                                }
                            }
                            
                            // 记录API返回的所有botId，用于后续比对删除
                            List<String> apiBotIds = new ArrayList<>();

                            // 遍历智能体列表
                            for (JsonNode botNode : spaceBots) {
                                String botId = botNode.path("bot_id").asText();
                                String botName = botNode.path("bot_name").asText();
                                String description = botNode.path("description").asText();
                                String iconUrl = botNode.path("icon_url").asText();
                                long publishTime = Long.parseLong(botNode.path("publish_time").asText());
                                
                                apiBotIds.add(botId);
                                
                                // 创建SysAgent对象用于返回
                                SysAgent botAgent = new SysAgent();
                                botAgent.setBotId(botId);
                                botAgent.setAgentName(botName);
                                botAgent.setAgentDesc(description);
                                botAgent.setIconUrl(iconUrl);
                                botAgent.setPublishTime(new Date(publishTime * 1000));
                                botAgent.setProvider("coze");
                                
                                // 如果前端传入了智能体名称过滤条件，则进行过滤
                                if (StringUtils.hasText(agent.getAgentName())) {
                                    if (botAgent.getAgentName().toLowerCase()
                                            .contains(agent.getAgentName().toLowerCase())) {
                                        agentList.add(botAgent);
                                    }
                                } else {
                                    agentList.add(botAgent);
                                }
                                
                                // 同步到数据库（使用非阻塞方式）
                                // 检查是否已存在该botId的配置
                                if (existingConfigMap.containsKey(botId)) {
                                    // 存在则更新
                                    SysConfig existingConfig = existingConfigMap.get(botId);
                                    existingConfig.setConfigName(botId);
                                    existingConfig.setConfigDesc(description);
                                    // 如果数据库已存在，返回对应 ConfigId 为前端设备绑定使用
                                    botAgent.setConfigId(existingConfig.getConfigId());
                                    botAgent.setIsDefault(existingConfig.getIsDefault());

                                    // 使用publishOn将数据库操作调度到适合的线程池
                                    Mono.fromCallable(() -> configMapper.update(existingConfig))
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .subscribe(
                                            result -> logger.debug("更新智能体配置成功: {}", botId),
                                            error -> logger.error("更新智能体配置失败: {}", error.getMessage())
                                        );
                                } else {
                                    // 不存在则新增
                                    SysConfig newConfig = new SysConfig();
                                    newConfig.setUserId(userId);
                                    newConfig.setConfigType("llm");
                                    newConfig.setProvider("coze");
                                    newConfig.setAppId(botId);
                                    newConfig.setConfigName(botId);
                                    newConfig.setConfigDesc(description);
                                    newConfig.setApiSecret(apiSecret);  // 使用主配置的apiSecret
                                    newConfig.setState("1");  // 默认启用

                                    Mono.fromCallable(() -> configMapper.add(newConfig))
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .subscribe(
                                            result -> logger.debug("添加智能体配置成功: {}", botId),
                                            error -> logger.error("添加智能体配置失败: {}", error.getMessage())
                                        );
                                }
                            }
                            
                            // 删除不再存在的智能体配置
                            // for (String existingBotId : existingConfigMap.keySet()) {
                            //     if (!apiBotIds.contains(existingBotId)) {
                            //         SysConfig configToDelete = existingConfigMap.get(existingBotId);
                            //         Mono.fromCallable(() -> configMapper.delete(configToDelete.getConfigId()))
                            //             .subscribeOn(Schedulers.boundedElastic())
                            //             .subscribe(
                            //                 result -> logger.debug("删除智能体配置成功: {}", existingBotId),
                            //                 error -> logger.error("删除智能体配置失败: {}", error.getMessage())
                            //             );
                            //     }
                            // }
                        } else {
                            String errorMsg = rootNode.has("msg") ? rootNode.get("msg").asText() : "未知错误";
                            logger.error("查询Coze智能体列表失败：{}", errorMsg);
                        }
                    } catch (Exception e) {
                        logger.error("解析Coze API响应异常", e);
                    }
                    return Mono.just(agentList);
                })
                .onErrorResume(e -> {
                    logger.error("查询Coze智能体列表异常", e);
                    return Mono.just(new ArrayList<>());
                });
    }
}