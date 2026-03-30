package com.djh.learnclaudecode.util;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonString;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ToolUtil {

    private static final String SUB_AGENT_SYSTEM_PROMPT = String.format(
            "You are a coding subagent at %s. Complete the given task, then summarize your findings.",
            System.getProperty("user.dir")
    );

    private static final AnthropicClient client = AnthropicOkHttpClient.fromEnv();

    private static final String modelName = "qwen3.5-plus";

    private static final Map<String, String> toolMap = new HashMap<>();

    private static final List<ToolUnion> CHILD_TOOLS = new ArrayList<>();

    private static final List<ToolUnion> PARENT_TOOLS = new ArrayList<>();

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Map<String, Method> METHOD_MAP = new HashMap<>();

    static {
        Tool bashTool = buildBashTool();
        Tool readTool = buildReadTool();
        Tool writeTool = buildWriteTool();
        Tool editTool = buildEditTool();
        Tool taskTool = buildSubagentTool();

        CHILD_TOOLS.add(ToolUnion.ofTool(bashTool));
        CHILD_TOOLS.add(ToolUnion.ofTool(readTool));
        CHILD_TOOLS.add(ToolUnion.ofTool(writeTool));
        CHILD_TOOLS.add(ToolUnion.ofTool(editTool));

        PARENT_TOOLS.addAll(CHILD_TOOLS);
        PARENT_TOOLS.add(ToolUnion.ofTool(taskTool));

        toolMap.put("bash", "runBash");
        toolMap.put("read_file", "runRead");
        toolMap.put("write_file", "runWrite");
        toolMap.put("edit_file", "runEdit");
        toolMap.put("task", "runSubagent");

        Class<?> aClass = null;
        try {
            aClass = Class.forName("com.djh.learnclaudecode.util.ToolUtil");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        Method[] methods = aClass.getMethods();
        for (Method method : methods) {
            METHOD_MAP.put(method.getName(), method);
        }

    }

    public static String runBash(String command) {
        if (command.contains("rm") || command.contains("sudo")) {
            return "error, dangerous command";
        }
        return BashUtil.exec(command);
    }

    public static String runRead(String path) {
        if (!Files.exists(Paths.get(path))) {
            return String.format("%s is not a real path", path);
        }
        if (!Files.isRegularFile(Paths.get(path))) {
            return String.format("%s is not a regular path", path);
        }
        File file = new File(path);
        StringBuilder sb = new StringBuilder();
        int len = 0;
        try (FileInputStream fileInputStream = new FileInputStream(file)) {
            byte[] bytes = new byte[1024];
            while ((len = fileInputStream.read(bytes)) != -1) {
                sb.append(new String(bytes, 0, len, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            return String.format("read %s error", path);
        }
        return sb.toString();
    }

    public static String runWrite(String path, String content) {
        try (FileOutputStream fileOutputStream = new FileOutputStream(new File(path))) {
            fileOutputStream.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return String.format("write %s bytes to file", content.length());
    }

    public static String runEdit(String path, String oldText, String newText) {
        if (oldText == null || oldText.isEmpty()) {
            return "replaced text must not be empty or null";
        }
        String content = runRead(path);
        if (!content.contains(oldText)) {
            return String.format("Error: Text not found in %s", path);
        }
        String newContent = content.replace(oldText, newText);
        runWrite(path, newContent);
        return String.format("%s is replaced to %s", oldText, newText);

    }

    public static String runTodo(String items) {
        System.out.println(items);
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            List<TodoManager.Item> todoItems = objectMapper.readValue(items, new TypeReference<List<TodoManager.Item>>() {
            });
            return TodoManager.update(todoItems);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public static String runSubagent(String prompt) {
        List<MessageParam> subHistory = new ArrayList<>();
        subHistory.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(prompt)
                .build());
        String result = "";
        for (int i = 0; i < 30; i++) {
            MessageCreateParams.Builder builder = MessageCreateParams.builder()
                    .system(SUB_AGENT_SYSTEM_PROMPT)
                    .thinking(ThinkingConfigDisabled.builder().build())
                    .maxTokens(8000L)
                    .tools(CHILD_TOOLS)
                    .model(modelName);

            for (MessageParam messageParam : subHistory) {
                builder.addMessage(messageParam);
            }

            Message response = client.messages().create(builder.build());
            subHistory.add(response.toParam());
            if (response.stopReason().isPresent() && !"tool_use".equalsIgnoreCase(response.stopReason().get().asString())) {
                StringBuilder sb = new StringBuilder();
                response.content().forEach((contentBlock -> {
                    if (contentBlock.text().isPresent()) {
                        sb.append(contentBlock.text().get().text()).append("\n");
                    } else {
                        sb.append("no summary").append("\n");
                    }
                }));
                result = sb.toString();
                break;
            }
            for (ContentBlock content : response.content()) {
                String toolName = content.toolUse().get().name();
                ToolUseBlock toolUse = content.toolUse().get();
                if (!toolMap.containsKey(toolName)) {
                    break;
                }

                try {
                    Object toolCallResult = invokeTool(toolName, content.toolUse().get());
                    subHistory.add(buildToolResult(toolUse, toolCallResult != null ? toolCallResult.toString() : "", false));
                } catch (Exception e) {
                    subHistory.add(buildToolResult(toolUse, "call tool error: " + e.getMessage(), true));
                }
            }
        }
        return result.isBlank() ? "no summary" : result;
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

    public static Tool buildSubagentTool() {
        Tool.InputSchema.Builder inputSchemaBuild = new Tool.InputSchema.Builder();
        inputSchemaBuild.required(List.of("prompt"));
        Tool.InputSchema.Properties properties = Tool.InputSchema.Properties.builder().additionalProperties(new HashMap<>() {{
            put("prompt", JsonValue.from("string"));
        }}).build();
        inputSchemaBuild.properties(properties);
        inputSchemaBuild.type(JsonValue.from("object"));
        return Tool.builder()
                .name("task")
                .description("Spawn a subagent with fresh context. It shares the filesystem but not conversation history.")
                .inputSchema(inputSchemaBuild.build())
                .build();
    }

}
