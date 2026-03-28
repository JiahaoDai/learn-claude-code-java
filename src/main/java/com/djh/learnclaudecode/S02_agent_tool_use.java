package com.djh.learnclaudecode;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonString;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.djh.learnclaudecode.util.BashUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class S02_agent_tool_use {
    private static final String SYSTEM_PROMPT = String.format("You are a coding agent at %s. Use tools to solve tasks. Act, don't explain.", System.getProperty("user.dir"));

    private static final AnthropicClient client = AnthropicOkHttpClient.fromEnv();

    private static final List<ToolUnion> TOOLS = new ArrayList<>();

    private static final Map<String, ToolUnion> TOOLS_DEFINE_MAP = new HashMap<>();

    private static final String modelName = "qwen3.5-plus";

    private static final Map<String, String> toolMap = new HashMap<>();

    private static Map<String, Method> METHOD_MAP = new HashMap<>();

    static {
        TOOLS.add(ToolUnion.ofTool(buildBashTool()));
        TOOLS.add(ToolUnion.ofTool(buildReadTool()));

        toolMap.put("bash", "runBash");
        toolMap.put("read_file", "runRead");
//        toolMap.put("write_file", "runWrite");
//        toolMap.put("edit_file", "runEdit");

        TOOLS_DEFINE_MAP.put("bash", ToolUnion.ofTool(buildBashTool()));
        TOOLS_DEFINE_MAP.put("read_file", ToolUnion.ofTool(buildReadTool()));

    }

    static class MessageInfo {
        private String msg;

        private String role;

        public String getMsg() {
            return msg;
        }

        public void setMsg(String msg) {
            this.msg = msg;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public static MessageInfo build(String msg, String role) {
            MessageInfo messageInfo = new MessageInfo();
            messageInfo.setMsg(msg);
            messageInfo.setRole(role);
            return messageInfo;
        }
    }

    public static void main(String[] args) {
        try {
            Class<?> aClass = Class.forName("com.djh.learnclaudecode.util.ToolUtil");
            Method[] methods = aClass.getMethods();
            for (Method method : methods) {
                METHOD_MAP.put(method.getName(), method);
            }
        } catch (Exception e) {
            throw new RuntimeException("get method error");
        }

        Scanner scanner = new Scanner(System.in);
        List<MessageInfo> history = new ArrayList<>();

        while (true) {
            String s = scanner.next();
            history.add(MessageInfo.build(s, "user"));
            if (s.strip().toLowerCase(Locale.ROOT).equals("q")) {
                break;
            }
            AgentLoop(history);
            System.out.println(history.get(history.size() - 1).getMsg());
        }
        scanner.close();

    }

    public static void AgentLoop(List<MessageInfo> history) {
        while (true) {
            MessageCreateParams.Builder builder = MessageCreateParams.builder()
                    .system(SYSTEM_PROMPT)
                    .thinking(ThinkingConfigDisabled.builder().build())
                    .maxTokens(8000L)
                    .tools(TOOLS)
                    .model(modelName);
            for (int i = 0; i < history.size(); i++) {
                if (history.get(i).getRole().equals("user")) {
                    builder.addUserMessage(history.get(i).getMsg());
                } else if (history.get(i).getRole().equals("assistant")) {
                    builder.addAssistantMessage(history.get(i).getMsg());
                }
            }
            MessageCreateParams params = builder.build();
            Message response = client.messages().create(params);
            if (!response.stopReason().get().asString().equals("tool_use")) {
                // KnownValue
                history.add(MessageInfo.build(response.content().get(0).text().get().text(), "assistant"));
                break;
            }
            for (ContentBlock content : response.content()) {
                if (content.toolUse().isPresent()) {
                    String toolName = content.toolUse().get().name();
                    if (!toolMap.containsKey(toolName) || !TOOLS_DEFINE_MAP.containsKey(toolName)) {
                        // 理论上不会出现进入到这个if的场景
                        System.out.println("tool is not register");
                        break;
                    }
                    String tool = toolMap.get(toolName);
                    ToolUnion toolUnion = TOOLS_DEFINE_MAP.get(toolName);
                    Object result = null;
                    try {
                        Method method = METHOD_MAP.get(tool);
                        Parameter[] parameters = method.getParameters();
                        Object[] methodParams = new Object[parameters.length];
                        Tool.InputSchema.Properties properties = toolUnion.tool().get().inputSchema().properties().get();
                        Map<String, JsonValue> stringJsonValueMap = properties._additionalProperties();
                        Map objMap = (Map) content.toolUse().get()._input().asObject().get();
                        for (Map.Entry<String, JsonValue> entry : stringJsonValueMap.entrySet()) {
                            String paramName = entry.getKey();
                            JsonString json = (JsonString) objMap.get(paramName);
                            String paramValue = (String) json.asString().get();
                            // TODO: 将参数名和参数值对应
                            for (int i = 0; i < parameters.length; i++) {
                                if (paramName.equals(parameters[i].getName())) {
                                    methodParams[i] = paramValue;
                                    break;
                                }
                            }
                        }
                        // 定义的全部都是public static方法
                        result = method.invoke(null, methodParams);
                        System.out.println(result.toString());
                        history.add(MessageInfo.build(String.format("{'type': 'tool_result', 'tool_use_id': '%s', 'content': '%s'}",
                                content.toolUse().get()._id().asString().get(), result.toString()), "user"));

                    } catch (Exception e) {
                        history.add(MessageInfo.build("call tool error", "assistant"));
                    }
                }
            }
        }
    }

    public static Tool buildBashTool() {
        Tool.InputSchema.Builder inputSchemaBuild = new Tool.InputSchema.Builder();
        inputSchemaBuild.addRequired("command");
        Tool.InputSchema.Properties properties = Tool.InputSchema.Properties.builder().additionalProperties((new HashMap<>() {{
            put("command", JsonValue.from("string"));
        }})).build();
        inputSchemaBuild.properties(properties);

        inputSchemaBuild.type(JsonValue.from("object"));
        return Tool.builder().inputSchema(inputSchemaBuild.build()).name("bash").description("Run a shell command.").build();
    }

    public static Tool buildReadTool() {
        Tool.InputSchema.Builder inputSchemaBuild = new Tool.InputSchema.Builder();
        inputSchemaBuild.addRequired("path");
        Tool.InputSchema.Properties properties = Tool.InputSchema.Properties.builder().additionalProperties((new HashMap<>() {{
            put("path", JsonValue.from("string"));
        }})).build();
        inputSchemaBuild.properties(properties);

        inputSchemaBuild.type(JsonValue.from("object"));
        return Tool.builder().inputSchema(inputSchemaBuild.build()).name("read_file").description("Read file contents.").build();
    }

}
