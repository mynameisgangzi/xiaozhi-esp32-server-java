package com.xiaozhi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaozhi.entity.SysDevice;
import com.xiaozhi.websocket.iot.IotDescriptor;
import com.xiaozhi.websocket.llm.LlmManager;
import com.xiaozhi.websocket.llm.tool.function.FunctionSessionHolder;
import com.xiaozhi.websocket.service.IotService;
import com.xiaozhi.websocket.service.SessionManager;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.web.WebAppConfiguration;

import javax.annotation.Resource;
import java.util.Map;

@SpringBootTest
@WebAppConfiguration
public class IotServiceTest {
    private static final Logger logger = LoggerFactory.getLogger(IotServiceTest.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Resource
    private IotService iotService;
    @Resource
    private SessionManager sessionManager;
    @Resource
    private LlmManager llmManager;

    /**
     * descriptorMessage的json串
     *   [{
     * 		"name": "Screen",
     * 		"description": "这是一个屏幕，可设置主题和亮度",
     * 		"properties": {
     * 			"theme": {
     * 				"description": "主题",
     * 				"type": "string"
     * 			            },
     * 			"brightness": {
     * 				"description": "当前亮度百分比",
     * 				"type": "number"
     *            }},
     * 		"methods": {
     * 			"SetTheme": {
     * 				"description": "设置屏幕主题",
     * 				"parameters": {
     * 					"theme_name": {
     * 						"description": "主题模式, light 或 dark",
     * 						"type": "string"
     *                    }
     *                }
     *            },
     * 			"SetBrightness": {
     * 				"description": "设置亮度",
     * 				"parameters": {
     * 					"brightness": {
     * 						"description": "0到100之间的整数",
     * 						"type": "number"
     *                    }
     *                }
     *            }
     *        }
     *    }]
     * statesMessage的json串
     * [
     * 	        {
     * 		        "name": "Speaker",
     * 		        "state": {
     * 			        "volume": 70
     * 		        }
     * 		    },
     * 		    {
     * 		        "name": "Screen",
     * 		        "state": {
     * 			        "theme": "dark",
     * 			        "brightness": 75
     * 		        }
     * 	        }
     * 	    ]
     *
     * @throws Exception
     */
    @Test
    public void test() throws Exception {
        // 首先尝试解析JSON消息
        String session_id = "session_id_test";

        //test descriptorMessage
        String descriptorMessage = "[{\"name\":\"Screen\",\"description\":\"这是一个屏幕，可设置主题和亮度\",\"properties\":{\"theme\":{\"description\":\"主题\",\"type\":\"string\"},\"brightness\":{\"description\":\"当前亮度百分比\",\"type\":\"number\"}},\"methods\":{\"SetTheme\":{\"description\":\"设置屏幕主题\",\"parameters\":{\"theme_name\":{\"description\":\"主题模式, light 或 dark\",\"type\":\"string\"}}},\"SetBrightness\":{\"description\":\"设置亮度\",\"parameters\":{\"brightness\":{\"description\":\"0到100之间的整数\",\"type\":\"number\"}}}}}]";
        JsonNode descriptorJsonNode = objectMapper.readTree(descriptorMessage);
        iotService.handleDeviceDescriptors(session_id, descriptorJsonNode);
        Map<String, IotDescriptor> allIotDescriptor = sessionManager.getAllIotDescriptor(session_id);
//        logger.info("allIotDescriptor: {}", allIotDescriptor);

        //test statesMessage
        String statesMessage = "[{\"name\":\"Screen\",\"state\":{\"theme\":\"dark\",\"brightness\":55}}]";
        iotService.handleDeviceStates(session_id, objectMapper.readTree(statesMessage));
//        logger.info("after statesMessage allIotDescriptor: {}", allIotDescriptor);

        //test getIotStatus
//        logger.info("iotDescriptor Speaker volume: {}", iotService.getIotStatus(session_id, "Speaker", "volume"));
//        logger.info("iotDescriptor Screen brightness: {}", iotService.getIotStatus(session_id, "Screen", "brightness"));
//        logger.info("iotDescriptor Screen theme: {}", iotService.getIotStatus(session_id, "Screen", "theme"));

        //test setIotStatus
//        iotService.setIotStatus(session_id, "Speaker", "volume", 80);
//        iotService.setIotStatus(session_id, "Screen", "brightness", 80);
//        iotService.setIotStatus(session_id, "Screen", "theme", "dark");
//        logger.info("after statesMessage allIotDescriptor: {}", allIotDescriptor);

        //test FunctionSessionHolder
        FunctionSessionHolder functionSessionHolder = sessionManager.getFunctionSessionHolder(session_id);
//        logger.info("functionSessionHolder getAllFunctionName: {}", functionSessionHolder.getAllFunctionName());
//        logger.info("functionSessionHolder getAllFunctions: {}", functionSessionHolder.getAllFunction());
//        logger.info("functionSessionHolder getAllFunctionLlmDesc: {}", functionSessionHolder.getAllFunctionLlmDescription());

        //发起函数调用
        SysDevice sysDevice = new SysDevice();
        sysDevice.setSessionId(session_id);
        sysDevice.setDeviceId("94:a9:90:2b:de:fc");
        sysDevice.setRoleId(1);
        sysDevice.setModelId(1);
        // llmManager.chatStream(sysDevice, "查询当前屏幕的亮度", response -> {
        //     logger.info("response: {}", response);
        // });
//        llmManager.chatStream(sysDevice, "小智，再见", response -> {
//            logger.info("response: {}", response);
//        });

//        llmManager.chatStream(sysDevice, "请设置屏幕的主题为dark", response -> {
//            logger.info("response: {}", response);
//        });
//        llmManager.chatStream(sysDevice, "我累了，去睡觉了，拜拜", response -> {
//            logger.info("response: {}", response);
//        });
//        llmManager.chatStream(sysDevice, "你好，小智", response -> {
//            logger.info("response: {}", response);
//        });

        while(true){
            Thread.sleep(100);//睡一会，避免主线程结束
        }
    }

}