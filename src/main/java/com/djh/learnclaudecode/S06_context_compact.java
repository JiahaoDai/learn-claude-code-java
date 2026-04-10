package com.djh.learnclaudecode;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.djh.learnclaudecode.util.ContextCompact;
import com.djh.learnclaudecode.util.ToolUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

public class S06_context_compact {

    private static final String SYSTEM_PROMPT = String.format(
            "You are a coding agent at %s. Use tools to solve tasks.",
            System.getProperty("WORK_DIR", System.getProperty("user.dir")));

    private static final AnthropicClient client = AnthropicOkHttpClient.fromEnv();

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final List<ToolUnion> TOOLS = new ArrayList<>();

    private static final String modelName = "qwen3.5-flash";

    private static final Map<String, String> toolMap = new HashMap<>();

    private static final Map<String, Method> METHOD_MAP = new HashMap<>();

    private static final int THRESHOLD = 50_000;

    static {
        Tool bashTool = ToolUtil.buildBashTool();
        Tool readTool = ToolUtil.buildReadTool();
        Tool writeTool = ToolUtil.buildWriteTool();
        Tool editTool = ToolUtil.buildEditTool();
        Tool taskTool = ToolUtil.buildSubagentTool();
        Tool compactTool = ToolUtil.buildCompactTool();

        TOOLS.add(ToolUnion.ofTool(bashTool));
        TOOLS.add(ToolUnion.ofTool(readTool));
        TOOLS.add(ToolUnion.ofTool(writeTool));
        TOOLS.add(ToolUnion.ofTool(editTool));
        TOOLS.add(ToolUnion.ofTool(taskTool));
        TOOLS.add(ToolUnion.ofTool(compactTool));

        toolMap.put("bash", "runBash");
        toolMap.put("read_file", "runRead");
        toolMap.put("write_file", "runWrite");
        toolMap.put("edit_file", "runEdit");
        toolMap.put("task", "runSubagent");
        toolMap.put("compact", "compact");
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
            history = AgentLoop(history);
            printAssistantText(history.get(history.size() - 1));
        }
        scanner.close();
    }

    public static List<MessageParam> AgentLoop(List<MessageParam> history) {
        while (true) {
            history = ContextCompact.microCompact(history);
            if(evaluateToken(history) > THRESHOLD){
               history = ContextCompact.autoCompact(client, modelName, history);
            }
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
                return history;
            }

            boolean needCompress = false;
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
                    Object result = null;
                    if ("compact".equalsIgnoreCase(toolName)) {
                        needCompress = true;
                        result = "compressing";
                    } else {
                        result = invokeTool(toolName, toolUse);
                    }
                    history.add(buildToolResult(toolUse, result == null ? "" : result.toString(), false));
                } catch (Exception e) {
                    history.add(buildToolResult(toolUse, "call tool error: " + e.getMessage(), true));
                }
            }
            if (needCompress) {
                history = ContextCompact.autoCompact(client, modelName, history);
            }
        }
    }

    private static Object invokeTool(String toolName, ToolUseBlock toolUse)
            throws InvocationTargetException, IllegalAccessException {
        return ToolUtil.invokeRegisteredTool(toolMap, METHOD_MAP, toolName, toolUse);
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
        String role = getRole(messageParam);
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

    private static int evaluateToken(List<MessageParam> messages) {
        try {
            return OBJECT_MAPPER.writeValueAsString(messages).length() / 4;
        } catch (JsonProcessingException e) {
            throw new RuntimeException("cal token error");
        }
    }

    private static String getRole(MessageParam message) {
        try {
            String role = message._role().asKnown().get().asString();
            System.out.println(role);
            return role;
        } catch (Exception e){
            String role = message._role().asString().orElse("");
            System.out.println(role);
            return role;
        }
    }

}
