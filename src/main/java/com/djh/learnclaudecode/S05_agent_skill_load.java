package com.djh.learnclaudecode;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonString;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.djh.learnclaudecode.util.ToolUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;

public class S05_agent_skill_load {
    private static final String SYSTEM_PROMPT = String.format(
            "You are a coding agent at %s.\n" +
                    "Use load_skill to access specialized knowledge before tackling unfamiliar topics.\n" +
                    "\n" +
                    "Skills available:\n" +
                    "%s",
            System.getProperty("WORK_DIR", System.getProperty("user.dir")), ToolUtil.SKILL_LOADER.getDescription()
    );

    private static final AnthropicClient client = AnthropicOkHttpClient.fromEnv();

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final List<ToolUnion> TOOLS = new ArrayList<>();

    private static final String modelName = "qwen3.5-plus";

    private static final Map<String, String> toolMap = new HashMap<>();

    private static final Map<String, Method> METHOD_MAP = new HashMap<>();

    static {
        Tool bashTool = ToolUtil.buildBashTool();
        Tool readTool = ToolUtil.buildReadTool();
        Tool writeTool = ToolUtil.buildWriteTool();
        Tool editTool = ToolUtil.buildEditTool();
        Tool taskTool = ToolUtil.buildSubagentTool();
        Tool loadSkillTool = ToolUtil.buildLoadSkillTool();

        TOOLS.add(ToolUnion.ofTool(bashTool));
        TOOLS.add(ToolUnion.ofTool(readTool));
        TOOLS.add(ToolUnion.ofTool(writeTool));
        TOOLS.add(ToolUnion.ofTool(editTool));
        TOOLS.add(ToolUnion.ofTool(taskTool));
        TOOLS.add(ToolUnion.ofTool(loadSkillTool));

        toolMap.put("bash", "runBash");
        toolMap.put("read_file", "runRead");
        toolMap.put("write_file", "runWrite");
        toolMap.put("edit_file", "runEdit");
        toolMap.put("task", "runSubagent");
        toolMap.put("load_skill", "loadSkill");
    }

    public static void main(String[] args) {
        try {
            Class<?> aClass = Class.forName("com.djh.learnclaudecode.util.ToolUtil");
            Method[] methods = aClass.getMethods();
            for (Method method : methods) {
                METHOD_MAP.put(method.getName(), method);
            }
        } catch (Exception e) {
            throw new RuntimeException("get method error", e);
        }

        Scanner scanner = new Scanner(System.in);
        List<MessageParam> history = new ArrayList<>();

        while (true) {
            String input = scanner.nextLine();
            if (input.strip().toLowerCase(Locale.ROOT).equals("q")) {
                break;
            }

            history.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content(input)
                    .build());
            AgentLoop(history);
            printAssistantText(history.get(history.size() - 1));
        }
        scanner.close();
    }

    public static void AgentLoop(List<MessageParam> history) {
        while (true) {
            MessageCreateParams.Builder builder = MessageCreateParams.builder()
                    .system(SYSTEM_PROMPT)
                    .thinking(ThinkingConfigDisabled.builder().build())
                    .maxTokens(8000L)
                    .tools(TOOLS)
                    .model(modelName);

            for (MessageParam messageParam : history) {
                builder.addMessage(messageParam);
            }

            Message response = client.messages().create(builder.build());
            history.add(response.toParam());

            if (!response.stopReason().isPresent()
                    || !"tool_use".equals(response.stopReason().get().asString())) {
                break;
            }
            for (ContentBlock content : response.content()) {
                if (content.toolUse().isEmpty()) {
                    continue;
                }

                ToolUseBlock toolUse = content.toolUse().get();
                String toolName = toolUse.name();
                if (!toolMap.containsKey(toolName)) {
                    history.add(buildToolResult(toolUse, "tool is not register", true));
                    continue;
                }
                try {
                    Object result = invokeTool(toolName, toolUse);
                    history.add(buildToolResult(toolUse, result == null ? "" : result.toString(), false));
                } catch (Exception e) {
                    history.add(buildToolResult(toolUse, "call tool error: " + e.getMessage(), true));
                }
            }
        }
    }

    private static Object invokeTool(String toolName, ToolUseBlock toolUse)
            throws InvocationTargetException, IllegalAccessException {
        String methodName = toolMap.get(toolName);
        Method method = METHOD_MAP.get(methodName);
        if (method == null) {
            throw new IllegalStateException("method not found: " + methodName);
        }

        Parameter[] parameters = method.getParameters();
        Object[] methodParams = new Object[parameters.length];
        Map<?, ?> inputMap = (Map<?, ?>) toolUse._input().asObject().get();

        for (int i = 0; i < parameters.length; i++) {
            String paramName = parameters[i].getName();
            Object rawValue = inputMap.get(paramName);
            if (rawValue == null) {
                continue;
            }
            methodParams[i] = convertToolInput(rawValue);
        }
        return method.invoke(null, methodParams);
    }

    private static String convertToolInput(Object rawValue) {
        if (rawValue instanceof JsonString jsonString) {
            return (String) jsonString.asString().orElse("");
        }
        if (rawValue instanceof JsonValue jsonValue) {
            try {
                return OBJECT_MAPPER.writeValueAsString(jsonValue.convert(Object.class));
            } catch (JsonProcessingException e) {
                throw new RuntimeException("serialize json value error", e);
            }
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(rawValue);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("serialize tool input error", e);
        }
    }

    private static MessageParam buildToolResult(ToolUseBlock toolUse, String result, boolean isError) {
        return MessageParam.builder()
                .role(MessageParam.Role.USER)
                .contentOfBlockParams(List.of(
                        ContentBlockParam.ofToolResult(
                                ToolResultBlockParam.builder()
                                        .toolUseId(toolUse.id())
                                        .content(result)
                                        .isError(isError)
                                        .build()
                        )
                ))
                .build();
    }

    private static void printAssistantText(MessageParam messageParam) {
        String role = messageParam._role().asString().get();
        if (!role.equalsIgnoreCase(MessageParam.Role.Value.ASSISTANT.name())) {
            return;
        }

        if (!messageParam.content().isBlockParams()) {
            System.out.println(messageParam.content().asString());
            return;
        }
        for (ContentBlockParam content : messageParam.content().asBlockParams()) {
            content.text().ifPresent(textBlockParam -> System.out.println(textBlockParam.text()));
        }
    }
}
