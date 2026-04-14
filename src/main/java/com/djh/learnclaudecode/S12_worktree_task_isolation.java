package com.djh.learnclaudecode;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.djh.learnclaudecode.util.BashUtil;
import com.djh.learnclaudecode.util.ToolUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class S12_worktree_task_isolation {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final AnthropicClient CLIENT = AnthropicOkHttpClient.fromEnv();
    private static final String MODEL_NAME = System.getenv().getOrDefault("MODEL_ID", "qwen3.5-27b");
    private static final Path WORKDIR = Path.of(System.getProperty("WORK_DIR", System.getProperty("user.dir"))).toAbsolutePath().normalize();
    private static final Path REPO_ROOT = detectRepoRoot(WORKDIR);
    private static final String SYSTEM_PROMPT = String.format(
            "You are a coding agent at %s. Use task + worktree tools for multi-task work. " +
                    "For parallel or risky changes: create tasks, allocate worktree lanes, run commands in those lanes, " +
                    "then choose keep/remove for closeout. Use worktree_events when you need lifecycle visibility.",
            WORKDIR
    );

    private static final EventBus EVENTS = new EventBus(REPO_ROOT.resolve(".worktrees").resolve("events.jsonl"));
    private static final TaskManager TASKS = new TaskManager(REPO_ROOT.resolve(".tasks"));
    private static final WorktreeManager WORKTREES = new WorktreeManager(REPO_ROOT, TASKS, EVENTS);

    private static final List<ToolUnion> TOOLS = new ArrayList<>();
    private static final Map<String, String> TOOL_MAP = new HashMap<>();
    private static final Map<String, Method> METHOD_MAP = new HashMap<>();

    static {
        Tool bashTool = buildBashTool();
        Tool readTool = buildReadTool();
        Tool writeTool = buildWriteTool();
        Tool editTool = buildEditTool();
        Tool taskCreateTool = buildTaskCreateTool();
        Tool taskListTool = buildTaskListTool();
        Tool taskGetTool = buildTaskGetTool();
        Tool taskUpdateTool = buildTaskUpdateTool();
        Tool taskBindWorktreeTool = buildTaskBindWorktreeTool();
        Tool worktreeCreateTool = buildWorktreeCreateTool();
        Tool worktreeListTool = buildWorktreeListTool();
        Tool worktreeStatusTool = buildWorktreeStatusTool();
        Tool worktreeRunTool = buildWorktreeRunTool();
        Tool worktreeKeepTool = buildWorktreeKeepTool();
        Tool worktreeRemoveTool = buildWorktreeRemoveTool();
        Tool worktreeEventsTool = buildWorktreeEventsTool();

        TOOLS.add(ToolUnion.ofTool(bashTool));
        TOOLS.add(ToolUnion.ofTool(readTool));
        TOOLS.add(ToolUnion.ofTool(writeTool));
        TOOLS.add(ToolUnion.ofTool(editTool));
        TOOLS.add(ToolUnion.ofTool(taskCreateTool));
        TOOLS.add(ToolUnion.ofTool(taskListTool));
        TOOLS.add(ToolUnion.ofTool(taskGetTool));
        TOOLS.add(ToolUnion.ofTool(taskUpdateTool));
        TOOLS.add(ToolUnion.ofTool(taskBindWorktreeTool));
        TOOLS.add(ToolUnion.ofTool(worktreeCreateTool));
        TOOLS.add(ToolUnion.ofTool(worktreeListTool));
        TOOLS.add(ToolUnion.ofTool(worktreeStatusTool));
        TOOLS.add(ToolUnion.ofTool(worktreeRunTool));
        TOOLS.add(ToolUnion.ofTool(worktreeKeepTool));
        TOOLS.add(ToolUnion.ofTool(worktreeRemoveTool));
        TOOLS.add(ToolUnion.ofTool(worktreeEventsTool));

        TOOL_MAP.put("bash", "runBash");
        TOOL_MAP.put("read_file", "runRead");
        TOOL_MAP.put("write_file", "runWrite");
        TOOL_MAP.put("edit_file", "runEdit");
        TOOL_MAP.put("task_create", "runTaskCreate");
        TOOL_MAP.put("task_list", "runTaskList");
        TOOL_MAP.put("task_get", "runTaskGet");
        TOOL_MAP.put("task_update", "runTaskUpdate");
        TOOL_MAP.put("task_bind_worktree", "runTaskBindWorktree");
        TOOL_MAP.put("worktree_create", "runWorktreeCreate");
        TOOL_MAP.put("worktree_list", "runWorktreeList");
        TOOL_MAP.put("worktree_status", "runWorktreeStatus");
        TOOL_MAP.put("worktree_run", "runWorktreeRun");
        TOOL_MAP.put("worktree_keep", "runWorktreeKeep");
        TOOL_MAP.put("worktree_remove", "runWorktreeRemove");
        TOOL_MAP.put("worktree_events", "runWorktreeEvents");

        for (Method method : S12_worktree_task_isolation.class.getMethods()) {
            METHOD_MAP.put(method.getName(), method);
        }
    }

    public static void main(String[] args) {
        System.out.println("Repo root for s12: " + REPO_ROOT);
        if (!WORKTREES.isGitAvailable()) {
            System.out.println("Note: Not in a git repo. worktree_* tools will return errors.");
        }

        Scanner scanner = new Scanner(System.in);
        List<MessageParam> history = new ArrayList<>();
        while (true) {
            System.out.print("\u001B[36ms12 >> \u001B[0m");
            if (!scanner.hasNextLine()) {
                break;
            }
            String input = scanner.nextLine();
            String normalized = input == null ? "" : input.strip().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty() || "q".equals(normalized) || "exit".equals(normalized)) {
                break;
            }

            history.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content(input)
                    .build());
            int fromIndex = history.size();
            agentLoop(history);
            printAssistantTexts(history, fromIndex);
            System.out.println();
        }
        scanner.close();
    }

    public static void agentLoop(List<MessageParam> messages) {
        while (true) {
            MessageCreateParams.Builder builder = MessageCreateParams.builder()
                    .system(SYSTEM_PROMPT)
                    .thinking(ThinkingConfigDisabled.builder().build())
                    .maxTokens(8000L)
                    .model(MODEL_NAME)
                    .tools(TOOLS);

            for (MessageParam message : messages) {
                builder.addMessage(message);
            }

            Message response = CLIENT.messages().create(builder.build());
            messages.add(response.toParam());

            if (response.stopReason().isEmpty() || !"tool_use".equals(response.stopReason().get().asString())) {
                return;
            }

            for (ContentBlock content : response.content()) {
                if (content.toolUse().isEmpty()) {
                    continue;
                }
                ToolUseBlock toolUse = content.toolUse().get();
                try {
                    Object result = invokeTool(toolUse.name(), toolUse);
                    messages.add(buildToolResult(toolUse, result == null ? "" : result.toString(), false));
                    System.out.println("> " + toolUse.name() + ":");
                    System.out.println(truncate(result == null ? "" : result.toString(), 200));
                } catch (Exception e) {
                    String error = "Error: " + e.getMessage();
                    messages.add(buildToolResult(toolUse, error, true));
                    System.out.println("> " + toolUse.name() + ":");
                    System.out.println(truncate(error, 200));
                }
            }
        }
    }

    public static String runBash(String command) {
        if (isDangerous(command)) {
            return "Error: Dangerous command blocked";
        }
        return trimOutput(BashUtil.exec(command, WORKDIR.toString()));
    }

    public static String runRead(String path, Integer limit) {
        try {
            Path file = safePath(path);
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (limit != null && limit > 0 && limit < lines.size()) {
                List<String> limited = new ArrayList<>(lines.subList(0, limit));
                limited.add("... (" + (lines.size() - limit) + " more)");
                lines = limited;
            }
            return trimOutput(String.join("\n", lines));
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    public static String runWrite(String path, String content) {
        try {
            Path file = safePath(path);
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, Objects.requireNonNullElse(content, ""), StandardCharsets.UTF_8);
            return "Wrote " + Objects.requireNonNullElse(content, "").length() + " bytes";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    public static String runEdit(String path, String old_text, String new_text) {
        try {
            Path file = safePath(path);
            String current = Files.readString(file, StandardCharsets.UTF_8);
            if (old_text == null || old_text.isEmpty() || !current.contains(old_text)) {
                return "Error: Text not found in " + path;
            }
            int index = current.indexOf(old_text);
            String replacement = current.substring(0, index)
                    + Objects.requireNonNullElse(new_text, "")
                    + current.substring(index + old_text.length());
            Files.writeString(file, replacement, StandardCharsets.UTF_8);
            return "Edited " + path;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    public static String runTaskCreate(String subject, String description) {
        return TASKS.create(subject, description);
    }

    public static String runTaskList() {
        return TASKS.listAll();
    }

    public static String runTaskGet(Integer task_id) {
        return TASKS.get(task_id);
    }

    public static String runTaskUpdate(Integer task_id, String status, String owner) {
        return TASKS.update(task_id, status, owner);
    }

    public static String runTaskBindWorktree(Integer task_id, String worktree, String owner) {
        return TASKS.bindWorktree(task_id, worktree, owner);
    }

    public static String runWorktreeCreate(String name, Integer task_id, String base_ref) {
        return WORKTREES.create(name, task_id, base_ref == null || base_ref.isBlank() ? "HEAD" : base_ref);
    }

    public static String runWorktreeList() {
        return WORKTREES.listAll();
    }

    public static String runWorktreeStatus(String name) {
        return WORKTREES.status(name);
    }

    public static String runWorktreeRun(String name, String command) {
        return WORKTREES.run(name, command);
    }

    public static String runWorktreeKeep(String name) {
        return WORKTREES.keep(name);
    }

    public static String runWorktreeRemove(String name, Boolean force, Boolean complete_task) {
        return WORKTREES.remove(name, Boolean.TRUE.equals(force), Boolean.TRUE.equals(complete_task));
    }

    public static String runWorktreeEvents(Integer limit) {
        return EVENTS.listRecent(limit == null ? 20 : limit);
    }

    private static Object invokeTool(String toolName, ToolUseBlock toolUse)
            throws InvocationTargetException, IllegalAccessException {
        return ToolUtil.invokeRegisteredTool(TOOL_MAP, METHOD_MAP, toolName, toolUse);
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

    private static void printAssistantTexts(List<MessageParam> history, int fromIndex) {
        for (int i = Math.max(0, fromIndex); i < history.size(); i++) {
            MessageParam messageParam = history.get(i);
            if (messageParam._role().asString().isEmpty()) {
                continue;
            }
            if (!MessageParam.Role.Value.ASSISTANT.name().equalsIgnoreCase(messageParam._role().asString().get())) {
                continue;
            }
            if (!messageParam.content().isBlockParams()) {
                System.out.println(messageParam.content().asString());
                continue;
            }
            for (ContentBlockParam content : messageParam.content().asBlockParams()) {
                content.text().ifPresent(text -> System.out.println(text.text()));
            }
        }
    }

    private static Path detectRepoRoot(Path cwd) {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "--show-toplevel")
                    .directory(cwd.toFile())
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            process.waitFor();
            if (process.exitValue() == 0 && !output.isBlank()) {
                Path root = Path.of(output).toAbsolutePath().normalize();
                if (Files.exists(root)) {
                    return root;
                }
            }
        } catch (Exception ignored) {
        }
        return cwd;
    }

    private static Path safePath(String path) {
        Path resolved = WORKDIR.resolve(Objects.requireNonNullElse(path, "")).normalize().toAbsolutePath();
        if (!resolved.startsWith(WORKDIR)) {
            throw new IllegalArgumentException("Path escapes workspace: " + path);
        }
        return resolved;
    }

    private static String trimOutput(String text) {
        String value = text == null ? "" : text.strip();
        if (value.isEmpty()) {
            return "(no output)";
        }
        return truncate(value, 50000);
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max);
    }

    private static boolean isDangerous(String command) {
        if (command == null) {
            return false;
        }
        List<String> dangerous = List.of("rm -rf /", "sudo", "shutdown", "reboot", "> /dev/");
        return dangerous.stream().anyMatch(command::contains);
    }

    private static Tool buildBashTool() {
        return Tool.builder()
                .name("bash")
                .description("Run a shell command in the current workspace (blocking).")
                .inputSchema(schema(List.of("command"), mapOf(
                        "command", "string"
                )))
                .build();
    }

    private static Tool buildReadTool() {
        return Tool.builder()
                .name("read_file")
                .description("Read file contents.")
                .inputSchema(schema(List.of("path"), mapOf(
                        "path", "string",
                        "limit", "integer"
                )))
                .build();
    }

    private static Tool buildWriteTool() {
        return Tool.builder()
                .name("write_file")
                .description("Write content to file.")
                .inputSchema(schema(List.of("path", "content"), mapOf(
                        "path", "string",
                        "content", "string"
                )))
                .build();
    }

    private static Tool buildEditTool() {
        return Tool.builder()
                .name("edit_file")
                .description("Replace exact text in file.")
                .inputSchema(schema(List.of("path", "old_text", "new_text"), mapOf(
                        "path", "string",
                        "old_text", "string",
                        "new_text", "string"
                )))
                .build();
    }

    private static Tool buildTaskCreateTool() {
        return Tool.builder()
                .name("task_create")
                .description("Create a new task on the shared task board.")
                .inputSchema(schema(List.of("subject"), mapOf(
                        "subject", "string",
                        "description", "string"
                )))
                .build();
    }

    private static Tool buildTaskListTool() {
        return Tool.builder()
                .name("task_list")
                .description("List all tasks with status, owner, and worktree binding.")
                .inputSchema(schema(List.of(), new LinkedHashMap<>()))
                .build();
    }

    private static Tool buildTaskGetTool() {
        return Tool.builder()
                .name("task_get")
                .description("Get task details by ID.")
                .inputSchema(schema(List.of("task_id"), mapOf(
                        "task_id", "integer"
                )))
                .build();
    }

    private static Tool buildTaskUpdateTool() {
        return Tool.builder()
                .name("task_update")
                .description("Update task status or owner.")
                .inputSchema(schema(List.of("task_id"), mapOf(
                        "task_id", "integer",
                        "status", enumSchema("string", List.of("pending", "in_progress", "completed")),
                        "owner", "string"
                )))
                .build();
    }

    private static Tool buildTaskBindWorktreeTool() {
        return Tool.builder()
                .name("task_bind_worktree")
                .description("Bind a task to a worktree name.")
                .inputSchema(schema(List.of("task_id", "worktree"), mapOf(
                        "task_id", "integer",
                        "worktree", "string",
                        "owner", "string"
                )))
                .build();
    }

    private static Tool buildWorktreeCreateTool() {
        return Tool.builder()
                .name("worktree_create")
                .description("Create a git worktree and optionally bind it to a task.")
                .inputSchema(schema(List.of("name"), mapOf(
                        "name", "string",
                        "task_id", "integer",
                        "base_ref", "string"
                )))
                .build();
    }

    private static Tool buildWorktreeListTool() {
        return Tool.builder()
                .name("worktree_list")
                .description("List worktrees tracked in .worktrees/index.json.")
                .inputSchema(schema(List.of(), new LinkedHashMap<>()))
                .build();
    }

    private static Tool buildWorktreeStatusTool() {
        return Tool.builder()
                .name("worktree_status")
                .description("Show git status for one worktree.")
                .inputSchema(schema(List.of("name"), mapOf(
                        "name", "string"
                )))
                .build();
    }

    private static Tool buildWorktreeRunTool() {
        return Tool.builder()
                .name("worktree_run")
                .description("Run a shell command in a named worktree directory.")
                .inputSchema(schema(List.of("name", "command"), mapOf(
                        "name", "string",
                        "command", "string"
                )))
                .build();
    }

    private static Tool buildWorktreeKeepTool() {
        return Tool.builder()
                .name("worktree_keep")
                .description("Mark a worktree as kept in lifecycle state without removing it.")
                .inputSchema(schema(List.of("name"), mapOf(
                        "name", "string"
                )))
                .build();
    }

    private static Tool buildWorktreeRemoveTool() {
        return Tool.builder()
                .name("worktree_remove")
                .description("Remove a worktree and optionally mark its bound task completed.")
                .inputSchema(schema(List.of("name"), mapOf(
                        "name", "string",
                        "force", "boolean",
                        "complete_task", "boolean"
                )))
                .build();
    }

    private static Tool buildWorktreeEventsTool() {
        return Tool.builder()
                .name("worktree_events")
                .description("List recent worktree/task lifecycle events from .worktrees/events.jsonl.")
                .inputSchema(schema(List.of(), mapOf(
                        "limit", "integer"
                )))
                .build();
    }

    private static Tool.InputSchema schema(List<String> required, Map<String, Object> propertiesMap) {
        Tool.InputSchema.Builder builder = new Tool.InputSchema.Builder();
        builder.type(JsonValue.from("object"));
        if (required != null && !required.isEmpty()) {
            builder.required(required);
        }
        builder.properties(Tool.InputSchema.Properties.builder()
                .additionalProperties(convertToJsonValueMap(propertiesMap))
                .build());
        return builder.build();
    }

    private static Map<String, Object> mapOf(Object... pairs) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> enumSchema(String type, List<String> values) {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", type);
        schema.put("enum", values);
        return schema;
    }

    private static Map<String, JsonValue> convertToJsonValueMap(Map<String, Object> propertiesMap) {
        LinkedHashMap<String, JsonValue> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : propertiesMap.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String stringValue && isSimpleJsonType(stringValue)) {
                LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
                schema.put("type", stringValue);
                result.put(entry.getKey(), JsonValue.from(schema));
            } else {
                result.put(entry.getKey(), JsonValue.from(value));
            }
        }
        return result;
    }

    private static boolean isSimpleJsonType(String value) {
        return List.of("string", "integer", "boolean", "number", "object", "array").contains(value);
    }

    static class EventBus {
        private final Path path;

        EventBus(Path eventLogPath) {
            this.path = eventLogPath;
            try {
                if (this.path.getParent() != null) {
                    Files.createDirectories(this.path.getParent());
                }
                if (!Files.exists(this.path)) {
                    Files.writeString(this.path, "", StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                throw new RuntimeException("init event bus failed", e);
            }
        }

        synchronized void emit(String event, Map<String, Object> task, Map<String, Object> worktree, String error) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event", event);
            payload.put("ts", Instant.now().toEpochMilli() / 1000.0);
            payload.put("task", task == null ? Map.of() : task);
            payload.put("worktree", worktree == null ? Map.of() : worktree);
            if (error != null && !error.isBlank()) {
                payload.put("error", error);
            }
            try {
                Files.writeString(
                        path,
                        OBJECT_MAPPER.writeValueAsString(payload) + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND
                );
            } catch (IOException e) {
                throw new RuntimeException("write event failed", e);
            }
        }

        String listRecent(int limit) {
            int size = Math.max(1, Math.min(limit, 200));
            try {
                List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                int start = Math.max(0, lines.size() - size);
                List<Map<String, Object>> items = new ArrayList<>();
                for (String line : lines.subList(start, lines.size())) {
                    if (line == null || line.isBlank()) {
                        continue;
                    }
                    try {
                        items.add(OBJECT_MAPPER.readValue(line, new TypeReference<>() {
                        }));
                    } catch (Exception parseError) {
                        items.add(Map.of("event", "parse_error", "raw", line));
                    }
                }
                return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(items);
            } catch (IOException e) {
                return "Error: " + e.getMessage();
            }
        }
    }

    static class TaskManager {
        private final Path dir;
        private int nextId;

        TaskManager(Path tasksDir) {
            this.dir = tasksDir;
            try {
                Files.createDirectories(this.dir);
            } catch (IOException e) {
                throw new RuntimeException("init task dir failed", e);
            }
            this.nextId = maxId() + 1;
        }

        synchronized String create(String subject, String description) {
            TaskRecord task = new TaskRecord();
            task.id = nextId++;
            task.subject = subject == null ? "" : subject;
            task.description = description == null ? "" : description;
            task.status = "pending";
            task.owner = "";
            task.worktree = "";
            task.blockedBy = new ArrayList<>();
            task.createdAt = now();
            task.updatedAt = now();
            save(task);
            return toPrettyJson(task);
        }

        synchronized String get(Integer taskId) {
            return toPrettyJson(load(requireTaskId(taskId)));
        }

        synchronized boolean exists(Integer taskId) {
            return taskId != null && Files.exists(path(taskId));
        }

        synchronized String update(Integer task_id, String status, String owner) {
            TaskRecord task = load(requireTaskId(task_id));
            if (status != null && !status.isBlank()) {
                if (!List.of("pending", "in_progress", "completed").contains(status)) {
                    throw new IllegalArgumentException("Invalid status: " + status);
                }
                task.status = status;
            }
            if (owner != null) {
                task.owner = owner;
            }
            task.updatedAt = now();
            save(task);
            return toPrettyJson(task);
        }

        synchronized String bindWorktree(Integer task_id, String worktree, String owner) {
            TaskRecord task = load(requireTaskId(task_id));
            task.worktree = worktree == null ? "" : worktree;
            if (owner != null && !owner.isBlank()) {
                task.owner = owner;
            }
            if ("pending".equals(task.status)) {
                task.status = "in_progress";
            }
            task.updatedAt = now();
            save(task);
            return toPrettyJson(task);
        }

        synchronized String unbindWorktree(Integer task_id) {
            TaskRecord task = load(requireTaskId(task_id));
            task.worktree = "";
            task.updatedAt = now();
            save(task);
            return toPrettyJson(task);
        }

        synchronized String listAll() {
            try {
                List<TaskRecord> tasks = listTaskRecords();
                if (tasks.isEmpty()) {
                    return "No tasks.";
                }
                List<String> lines = new ArrayList<>();
                for (TaskRecord task : tasks) {
                    String marker = switch (task.status) {
                        case "pending" -> "[ ]";
                        case "in_progress" -> "[>]";
                        case "completed" -> "[x]";
                        default -> "[?]";
                    };
                    String owner = task.owner != null && !task.owner.isBlank() ? " owner=" + task.owner : "";
                    String worktree = task.worktree != null && !task.worktree.isBlank() ? " wt=" + task.worktree : "";
                    lines.add(marker + " #" + task.id + ": " + task.subject + owner + worktree);
                }
                return String.join("\n", lines);
            } catch (IOException e) {
                return "Error: " + e.getMessage();
            }
        }

        private int requireTaskId(Integer taskId) {
            if (taskId == null) {
                throw new IllegalArgumentException("Task ID is required");
            }
            return taskId;
        }

        private int maxId() {
            try {
                return listTaskPaths().stream()
                        .map(Path::getFileName)
                        .map(Path::toString)
                        .map(name -> name.replace("task_", "").replace(".json", ""))
                        .mapToInt(Integer::parseInt)
                        .max()
                        .orElse(0);
            } catch (Exception e) {
                return 0;
            }
        }

        private List<Path> listTaskPaths() throws IOException {
            if (!Files.exists(dir)) {
                return List.of();
            }
            try (var stream = Files.list(dir)) {
                return stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().matches("task_\\d+\\.json"))
                        .sorted(Comparator.comparing(Path::toString))
                        .collect(Collectors.toList());
            }
        }

        private List<TaskRecord> listTaskRecords() throws IOException {
            List<TaskRecord> tasks = new ArrayList<>();
            for (Path taskPath : listTaskPaths()) {
                tasks.add(loadByPath(taskPath));
            }
            tasks.sort(Comparator.comparingInt(task -> task.id));
            return tasks;
        }

        private Path path(int taskId) {
            return dir.resolve("task_" + taskId + ".json");
        }

        private TaskRecord load(int taskId) {
            Path file = path(taskId);
            if (!Files.exists(file)) {
                throw new IllegalArgumentException("Task " + taskId + " not found");
            }
            return loadByPath(file);
        }

        private TaskRecord loadByPath(Path file) {
            try {
                return OBJECT_MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8), TaskRecord.class);
            } catch (IOException e) {
                throw new RuntimeException("load task failed: " + file, e);
            }
        }

        private void save(TaskRecord task) {
            try {
                Files.writeString(path(task.id), OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(task), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException("save task failed", e);
            }
        }
    }

    static class WorktreeManager {
        private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9._-]{1,40}");

        private final Path repoRoot;
        private final TaskManager tasks;
        private final EventBus events;
        private final Path dir;
        private final Path indexPath;
        private final boolean gitAvailable;

        WorktreeManager(Path repoRoot, TaskManager tasks, EventBus events) {
            this.repoRoot = repoRoot;
            this.tasks = tasks;
            this.events = events;
            this.dir = repoRoot.resolve(".worktrees");
            this.indexPath = dir.resolve("index.json");
            try {
                Files.createDirectories(this.dir);
                if (!Files.exists(this.indexPath)) {
                    Files.writeString(this.indexPath, "{\n  \"worktrees\" : [ ]\n}", StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                throw new RuntimeException("init worktree dir failed", e);
            }
            this.gitAvailable = isGitRepo();
        }

        boolean isGitAvailable() {
            return gitAvailable;
        }

        synchronized String create(String name, Integer task_id, String base_ref) {
            validateName(name);
            if (find(name) != null) {
                throw new IllegalArgumentException("Worktree '" + name + "' already exists in index");
            }
            if (task_id != null && !tasks.exists(task_id)) {
                throw new IllegalArgumentException("Task " + task_id + " not found");
            }

            Path path = dir.resolve(name);
            String branch = "wt/" + name;
            Map<String, Object> task = task_id == null ? Map.of() : Map.of("id", task_id);
            events.emit("worktree.create.before", task, Map.of("name", name, "base_ref", base_ref), null);
            try {
                runGit(List.of("worktree", "add", "-b", branch, path.toString(), base_ref));

                WorktreeEntry entry = new WorktreeEntry();
                entry.name = name;
                entry.path = path.toString();
                entry.branch = branch;
                entry.taskId = task_id;
                entry.status = "active";
                entry.createdAt = now();

                WorktreeIndex index = loadIndex();
                index.worktrees.add(entry);
                saveIndex(index);

                if (task_id != null) {
                    tasks.bindWorktree(task_id, name, "");
                }

                events.emit("worktree.create.after", task, Map.of(
                        "name", name,
                        "path", path.toString(),
                        "branch", branch,
                        "status", "active"
                ), null);
                return toPrettyJson(entry);
            } catch (Exception e) {
                events.emit("worktree.create.failed", task, Map.of("name", name, "base_ref", base_ref), e.getMessage());
                throw new RuntimeException(e.getMessage(), e);
            }
        }

        synchronized String listAll() {
            WorktreeIndex index = loadIndex();
            if (index.worktrees.isEmpty()) {
                return "No worktrees in index.";
            }
            List<String> lines = new ArrayList<>();
            for (WorktreeEntry worktree : index.worktrees) {
                String taskSuffix = worktree.taskId != null ? " task=" + worktree.taskId : "";
                lines.add("[" + defaultString(worktree.status, "unknown") + "] " + worktree.name +
                        " -> " + worktree.path + " (" + defaultString(worktree.branch, "-") + ")" + taskSuffix);
            }
            return String.join("\n", lines);
        }

        synchronized String status(String name) {
            WorktreeEntry worktree = requireWorktree(name);
            Path path = Path.of(worktree.path);
            if (!Files.exists(path)) {
                return "Error: Worktree path missing: " + path;
            }
            try {
                Process process = new ProcessBuilder("git", "status", "--short", "--branch")
                        .directory(path.toFile())
                        .start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                process.waitFor();
                String text = (output + error).strip();
                return text.isBlank() ? "Clean worktree" : text;
            } catch (Exception e) {
                return "Error: " + e.getMessage();
            }
        }

        synchronized String run(String name, String command) {
            if (isDangerous(command)) {
                return "Error: Dangerous command blocked";
            }
            WorktreeEntry worktree = requireWorktree(name);
            Path path = Path.of(worktree.path);
            if (!Files.exists(path)) {
                return "Error: Worktree path missing: " + path;
            }
            return trimOutput(BashUtil.exec(command, path.toString()));
        }

        synchronized String remove(String name, boolean force, boolean completeTask) {
            WorktreeEntry worktree = requireWorktree(name);
            Map<String, Object> task = worktree.taskId == null ? Map.of() : Map.of("id", worktree.taskId);
            events.emit("worktree.remove.before", task, Map.of("name", name, "path", worktree.path), null);
            try {
                List<String> args = new ArrayList<>(List.of("worktree", "remove"));
                if (force) {
                    args.add("--force");
                }
                args.add(worktree.path);
                runGit(args);

                if (completeTask && worktree.taskId != null) {
                    TaskRecord before = OBJECT_MAPPER.readValue(tasks.get(worktree.taskId), TaskRecord.class);
                    tasks.update(worktree.taskId, "completed", null);
                    tasks.unbindWorktree(worktree.taskId);
                    events.emit("task.completed", Map.of(
                            "id", worktree.taskId,
                            "subject", defaultString(before.subject, ""),
                            "status", "completed"
                    ), Map.of("name", name), null);
                }

                WorktreeIndex index = loadIndex();
                for (WorktreeEntry item : index.worktrees) {
                    if (name.equals(item.name)) {
                        item.status = "removed";
                        item.removedAt = now();
                    }
                }
                saveIndex(index);

                events.emit("worktree.remove.after", task, Map.of(
                        "name", name,
                        "path", worktree.path,
                        "status", "removed"
                ), null);
                return "Removed worktree '" + name + "'";
            } catch (Exception e) {
                events.emit("worktree.remove.failed", task, Map.of("name", name, "path", worktree.path), e.getMessage());
                throw new RuntimeException(e.getMessage(), e);
            }
        }

        synchronized String keep(String name) {
            WorktreeIndex index = loadIndex();
            WorktreeEntry kept = null;
            for (WorktreeEntry item : index.worktrees) {
                if (name.equals(item.name)) {
                    item.status = "kept";
                    item.keptAt = now();
                    kept = item;
                }
            }
            if (kept == null) {
                return "Error: Unknown worktree '" + name + "'";
            }
            saveIndex(index);
            Map<String, Object> task = kept.taskId == null ? Map.of() : Map.of("id", kept.taskId);
            events.emit("worktree.keep", task, Map.of(
                    "name", name,
                    "path", kept.path,
                    "status", "kept"
            ), null);
            return toPrettyJson(kept);
        }

        private boolean isGitRepo() {
            try {
                Process process = new ProcessBuilder("git", "rev-parse", "--is-inside-work-tree")
                        .directory(repoRoot.toFile())
                        .start();
                process.waitFor();
                return process.exitValue() == 0;
            } catch (Exception e) {
                return false;
            }
        }

        private void runGit(List<String> args) {
            if (!gitAvailable) {
                throw new IllegalStateException("Not in a git repository. worktree tools require git.");
            }
            try {
                List<String> command = new ArrayList<>();
                command.add("git");
                command.addAll(args);
                Process process = new ProcessBuilder(command)
                        .directory(repoRoot.toFile())
                        .start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                process.waitFor();
                if (process.exitValue() != 0) {
                    String message = (output + error).strip();
                    throw new IllegalStateException(message.isBlank() ? "git command failed" : message);
                }
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }

        private WorktreeEntry requireWorktree(String name) {
            WorktreeEntry worktree = find(name);
            if (worktree == null) {
                throw new IllegalArgumentException("Unknown worktree '" + name + "'");
            }
            return worktree;
        }

        private WorktreeEntry find(String name) {
            for (WorktreeEntry worktree : loadIndex().worktrees) {
                if (name.equals(worktree.name)) {
                    return worktree;
                }
            }
            return null;
        }

        private void validateName(String name) {
            if (name == null || !VALID_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("Invalid worktree name. Use 1-40 chars: letters, numbers, ., _, -");
            }
        }

        private WorktreeIndex loadIndex() {
            try {
                WorktreeIndex index = OBJECT_MAPPER.readValue(Files.readString(indexPath, StandardCharsets.UTF_8), WorktreeIndex.class);
                if (index.worktrees == null) {
                    index.worktrees = new ArrayList<>();
                }
                return index;
            } catch (IOException e) {
                throw new RuntimeException("load worktree index failed", e);
            }
        }

        private void saveIndex(WorktreeIndex index) {
            try {
                Files.writeString(indexPath, OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(index), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException("save worktree index failed", e);
            }
        }
    }

    static class TaskRecord {
        public int id;
        public String subject;
        public String description;
        public String status;
        public String owner;
        public String worktree;
        public List<Integer> blockedBy;
        public double createdAt;
        public double updatedAt;
    }

    static class WorktreeIndex {
        public List<WorktreeEntry> worktrees = new ArrayList<>();
    }

    static class WorktreeEntry {
        public String name;
        public String path;
        public String branch;
        public Integer taskId;
        public String status;
        public double createdAt;
        public Double removedAt;
        public Double keptAt;
    }

    private static double now() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    private static String toPrettyJson(Object value) {
        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("serialize json failed", e);
        }
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
