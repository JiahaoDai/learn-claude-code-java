package com.djh.learnclaudecode;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.djh.learnclaudecode.util.BackGroupManager;
import com.djh.learnclaudecode.util.ToolUtil;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

public class S08_background_tasks {

    private static final String SYSTEM_PROMPT = String.format(
            "You are a coding agent at %s. Use background_run for long-running commands.",
            System.getProperty("WORK_DIR", System.getProperty("user.dir"))
    );

    private static final AnthropicClient client = AnthropicOkHttpClient.fromEnv();

    private static final List<ToolUnion> TOOLS = new ArrayList<>();

    private static final Map<String, ToolUnion> TOOLS_DEFINE_MAP = new HashMap<>();

    private static final String modelName = "qwen3.5-flash";

    private static final Map<String, String> toolMap = new HashMap<>();

    private static final Map<String, Method> METHOD_MAP = new HashMap<>();

    static {
        Tool bashTool = buildBashTool();
        Tool readTool = buildReadTool();
        Tool writeTool = buildWriteTool();
        Tool editTool = buildEditTool();
        Tool backGroundRunTool = buildBackgroundRunTool();
        Tool backGroundCheckTool = buildBackgroundCheckTool();

        TOOLS.add(ToolUnion.ofTool(bashTool));
        TOOLS.add(ToolUnion.ofTool(readTool));
        TOOLS.add(ToolUnion.ofTool(writeTool));
        TOOLS.add(ToolUnion.ofTool(editTool));

        TOOLS.add(ToolUnion.ofTool(backGroundRunTool));
        TOOLS.add(ToolUnion.ofTool(backGroundCheckTool));

        toolMap.put("bash", "runBash");
        toolMap.put("read_file", "runRead");
        toolMap.put("write_file", "runWrite");
        toolMap.put("edit_file", "runEdit");

        toolMap.put("background_run", "runBackgroundRun");
        toolMap.put("check_background", "runBackgroundCheck");

        TOOLS_DEFINE_MAP.put("bash", ToolUnion.ofTool(bashTool));
        TOOLS_DEFINE_MAP.put("read_file", ToolUnion.ofTool(readTool));
        TOOLS_DEFINE_MAP.put("write_file", ToolUnion.ofTool(writeTool));
        TOOLS_DEFINE_MAP.put("edit_file", ToolUnion.ofTool(editTool));

        TOOLS_DEFINE_MAP.put("background_run", ToolUnion.ofTool(backGroundRunTool));
        TOOLS_DEFINE_MAP.put("check_background", ToolUnion.ofTool(backGroundCheckTool));
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

            List<BackGroupManager.Task> notifications = ToolUtil.drainNotification();
            if (notifications != null && !notifications.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (BackGroupManager.Task notification : notifications) {
                    sb.append(String.format("[bg:%s] %s: %s", notification.getTaskId(), notification.getStatus(), notification.getResult())).append("\n");
                }
                if(!sb.isEmpty()){
                    sb.deleteCharAt(sb.length() - 1);
                    history.add(buildNotificationResult(sb.toString()));
                }
            }

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
                if (!toolMap.containsKey(toolName) || !TOOLS_DEFINE_MAP.containsKey(toolName)) {
                    history.add(buildToolResult(toolUse, "tool is not register", true));
                    continue;
                }

                try {
                    Object result = invokeTool(toolName, toolUse);
                    history.add(buildToolResult(toolUse, result == null ? "" : result.toString(), false));
                } catch (Exception e) {
                    System.out.println(e);
                    history.add(buildToolResult(toolUse, "call tool error: " + e.getMessage(), true));
                }
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

    private static MessageParam buildNotificationResult(String content){
        return MessageParam.builder()
                .role(MessageParam.Role.USER)
                .contentOfBlockParams(List.of(
                        ContentBlockParam.ofText(
                                TextBlockParam.builder().text(content).build()
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

    public static Tool buildWriteTool() {
        Tool.InputSchema.Builder inputSchemaBuild = new Tool.InputSchema.Builder();
        inputSchemaBuild.required(List.of("path", "content"));
        Tool.InputSchema.Properties properties = Tool.InputSchema.Properties.builder().additionalProperties((new HashMap<>() {{
            put("path", JsonValue.from("string"));
            put("content", JsonValue.from("string"));
        }})).build();
        inputSchemaBuild.properties(properties);

        inputSchemaBuild.type(JsonValue.from("object"));
        return Tool.builder().inputSchema(inputSchemaBuild.build()).name("write_file").description("Write content to file.").build();
    }

    public static Tool buildEditTool() {
        Tool.InputSchema.Builder inputSchemaBuild = new Tool.InputSchema.Builder();
        inputSchemaBuild.required(List.of("path", "oldText", "newText"));
        Tool.InputSchema.Properties properties = Tool.InputSchema.Properties.builder().additionalProperties((new HashMap<>() {{
            put("path", JsonValue.from("string"));
            put("oldText", JsonValue.from("string"));
            put("newText", JsonValue.from("string"));
        }})).build();
        inputSchemaBuild.properties(properties);

        inputSchemaBuild.type(JsonValue.from("object"));
        return Tool.builder().inputSchema(inputSchemaBuild.build()).name("edit_file").description("Replace exact text in file.").build();
    }


    public static Tool buildTodoTool() {
        Tool.InputSchema.Builder inputSchemaBuild = new Tool.InputSchema.Builder();
        inputSchemaBuild.addRequired("items");
        Tool.InputSchema.Properties properties = Tool.InputSchema.Properties.builder().additionalProperties(new HashMap<>() {{
            put("items", JsonValue.from(new HashMap<String, Object>() {{
                put("type", "array");
                put("items", new HashMap<String, Object>() {{
                    put("type", "object");
                    put("properties", new HashMap<String, Object>() {{
                        put("id", new HashMap<String, Object>() {{
                            put("type", "string");
                        }});
                        put("text", new HashMap<String, Object>() {{
                            put("type", "string");
                        }});
                        put("status", new HashMap<String, Object>() {{
                            put("type", "string");
                            put("enum", List.of("pending", "in_progress", "completed"));
                        }});
                    }});
                    put("required", List.of("id", "text", "status"));
                }});
            }}));
        }}).build();
        inputSchemaBuild.properties(properties);

        inputSchemaBuild.type(JsonValue.from("object"));
        return Tool.builder()
                .name("todo")
                .description("Update task list. Track progress on multi-step tasks.")
                .inputSchema(inputSchemaBuild.build())
                .build();
    }

    public static Tool buildBackgroundRunTool() {
        Tool.InputSchema.Builder inputSchemaBuild = new Tool.InputSchema.Builder();
        inputSchemaBuild.required(List.of("command"));
        Tool.InputSchema.Properties properties = Tool.InputSchema.Properties.builder().additionalProperties((new HashMap<>() {{
            put("command", JsonValue.from("string"));
        }})).build();
        inputSchemaBuild.properties(properties);
        inputSchemaBuild.type(JsonValue.from("object"));
        return Tool.builder().inputSchema(inputSchemaBuild.build()).name("background_run").description("Run command in background thread. Returns task_id immediately.").build();
    }

    public static Tool buildBackgroundCheckTool() {
        Tool.InputSchema.Builder inputSchemaBuild = new Tool.InputSchema.Builder();
        inputSchemaBuild.required(List.of("taskId"));
        Tool.InputSchema.Properties properties = Tool.InputSchema.Properties.builder().additionalProperties((new HashMap<>() {{
            put("taskId", JsonValue.from("string"));
        }})).build();
        inputSchemaBuild.properties(properties);
        inputSchemaBuild.type(JsonValue.from("object"));
        return Tool.builder().inputSchema(inputSchemaBuild.build()).name("check_background").description("Check background task status. Omit task_id to list all.").build();
    }
}
