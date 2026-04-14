package com.djh.learnclaudecode;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.djh.learnclaudecode.util.MessageBus;
import com.djh.learnclaudecode.util.ToolUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class S11_autonomous_agents {

    private static final String SYSTEM_PROMPT = String.format(
            "You are a team lead at %s. Teammates are autonomous -- they find work themselves.",
            System.getProperty("WORK_DIR", System.getProperty("user.dir"))
    );

    private static final AnthropicClient client = AnthropicOkHttpClient.fromEnv();

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final List<ToolUnion> TOOLS = new ArrayList<>();

    private static final Map<String, ToolUnion> TOOLS_DEFINE_MAP = new HashMap<>();

    private static final String modelName = "qwen3.5-27b";

    private static final Map<String, String> toolMap = new HashMap<>();

    private static final Map<String, Method> METHOD_MAP = new HashMap<>();

    static {
        Tool bashTool = buildBashTool();
        Tool readTool = buildReadTool();
        Tool writeTool = buildWriteTool();
        Tool editTool = buildEditTool();
        Tool spawnTeammateTool = ToolUtil.buildSpawnTeammateTool();
        Tool listTeammateTool = ToolUtil.buildListTeammateTool();
        Tool sendMessageTool = ToolUtil.buildSendMessageTool();
        Tool readInboxTool = ToolUtil.buildReadInboxTool();
        Tool broadcastTool = ToolUtil.buildBroadcastTool();

        Tool idleTool = ToolUtil.buildLeaderIdleTool();
        Tool claimTaskTool = ToolUtil.buildClaimTaskTool();

        TOOLS.add(ToolUnion.ofTool(bashTool));
        TOOLS.add(ToolUnion.ofTool(readTool));
        TOOLS.add(ToolUnion.ofTool(writeTool));
        TOOLS.add(ToolUnion.ofTool(editTool));

        TOOLS.add(ToolUnion.ofTool(spawnTeammateTool));
        TOOLS.add(ToolUnion.ofTool(listTeammateTool));
        TOOLS.add(ToolUnion.ofTool(sendMessageTool));
        TOOLS.add(ToolUnion.ofTool(readInboxTool));
        TOOLS.add(ToolUnion.ofTool(broadcastTool));

        TOOLS.add(ToolUnion.ofTool(idleTool));
        TOOLS.add(ToolUnion.ofTool(claimTaskTool));

        toolMap.put("bash", "runBash");
        toolMap.put("read_file", "runRead");
        toolMap.put("write_file", "runWrite");
        toolMap.put("edit_file", "runEdit");

        toolMap.put("spawn_teammate", "runSpawnTeammate2");
        toolMap.put("list_teammates", "runListTeammates");
        toolMap.put("send_message", "runSendMessage");
        toolMap.put("read_inbox", "runReadInbox");
        toolMap.put("broadcast", "runBroadcast");


        toolMap.put("idle", "runIdle");
        toolMap.put("claim_task", "runClaimTask");

        TOOLS_DEFINE_MAP.put("bash", ToolUnion.ofTool(bashTool));
        TOOLS_DEFINE_MAP.put("read_file", ToolUnion.ofTool(readTool));
        TOOLS_DEFINE_MAP.put("write_file", ToolUnion.ofTool(writeTool));
        TOOLS_DEFINE_MAP.put("edit_file", ToolUnion.ofTool(editTool));

        TOOLS_DEFINE_MAP.put("spawn_teammate", ToolUnion.ofTool(spawnTeammateTool));
        TOOLS_DEFINE_MAP.put("list_teammates", ToolUnion.ofTool(listTeammateTool));
        TOOLS_DEFINE_MAP.put("send_message", ToolUnion.ofTool(sendMessageTool));
        TOOLS_DEFINE_MAP.put("read_inbox", ToolUnion.ofTool(readInboxTool));
        TOOLS_DEFINE_MAP.put("broadcast", ToolUnion.ofTool(broadcastTool));

        TOOLS_DEFINE_MAP.put("idle", ToolUnion.ofTool(idleTool));
        TOOLS_DEFINE_MAP.put("claim_task", ToolUnion.ofTool(claimTaskTool));
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

        List<MessageParam> history = new ArrayList<>();
        LinkedBlockingQueue<String> inputQueue = new LinkedBlockingQueue<>();
        startInputReader(inputQueue);
        while (true) {
            String input;
            try {
                input = inputQueue.poll(1000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            boolean shouldRunLead = false;
            if (input != null) {
                if (input.strip().toLowerCase(Locale.ROOT).equals("q")) {
                    break;
                }

                if (input.strip().toLowerCase(Locale.ROOT).equals("/team")) {
                    System.out.println(ToolUtil.runListTeammates());
                    continue;
                }

                if (input.strip().toLowerCase(Locale.ROOT).equals("/inbox")) {
                    System.out.println(ToolUtil.runReadInbox("lead"));
                    continue;
                }

                if (input.strip().toLowerCase(Locale.ROOT).equals("/tasks")) {
                    System.out.println(ToolUtil.runTaskListAll());
                    continue;
                }

                history.add(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .content(input)
                        .build());
                shouldRunLead = true;
            } else if (ToolUtil.MESSAGE_BUS.hasInboxMessages("lead")) {
                shouldRunLead = true;
            } else if (!history.isEmpty() && ToolUtil.TEAMMATE_MANAGER.hasActiveMembers()) {
                continue;
            } else {
                continue;
            }

            int historySizeBefore = history.size();
            AgentLoop(history);
            printAssistantTexts(history, historySizeBefore);
        }
    }

    public static void AgentLoop(List<MessageParam> history) {
        while (true) {
            List<MessageBus.TeamMsg> teamMsgs = ToolUtil.MESSAGE_BUS.readInbox("lead");
            if (teamMsgs != null && !teamMsgs.isEmpty()) {
                try {
                    history.add(buildUserMsg(String.format("<inbox>%s</inbox>", OBJECT_MAPPER.writeValueAsString(teamMsgs))));
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
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
                    if("read_inbox".equals(toolName)){
                        result = OBJECT_MAPPER.writeValueAsString(result);
                    }
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

    private static MessageParam buildNotificationResult(String content) {
        return MessageParam.builder()
                .role(MessageParam.Role.USER)
                .contentOfBlockParams(List.of(
                        ContentBlockParam.ofText(
                                TextBlockParam.builder().text(content).build()
                        )
                ))
                .build();
    }

    private static MessageParam buildUserMsg(String content) {
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
        if(!messageParam._role().asString().isPresent()){
            return;
        }
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

    private static void printAssistantTexts(List<MessageParam> history, int fromIndex) {
        if (history == null || history.isEmpty()) {
            return;
        }
        for (int i = Math.max(0, fromIndex); i < history.size(); i++) {
            printAssistantText(history.get(i));
        }
    }

    private static void startInputReader(LinkedBlockingQueue<String> inputQueue) {
        Thread inputThread = new Thread(() -> {
            Scanner scanner = new Scanner(System.in);
            try {
                while (scanner.hasNextLine()) {
                    inputQueue.put(scanner.nextLine());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                scanner.close();
            }
        });
        inputThread.setName("lead-input-reader");
        inputThread.setDaemon(true);
        inputThread.start();
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
