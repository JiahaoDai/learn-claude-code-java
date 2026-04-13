package com.djh.learnclaudecode.util;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonString;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class TeammateManager {

    private String teamConfigPath;

    private TeamConfig teamConfig;

    private Map<String, Thread> threadMap;

    private MessageBus messageBus;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final AnthropicClient client = AnthropicOkHttpClient.fromEnv();

    private static final String modelName = "qwen3.5-27b";

    public TeammateManager(String teamConfigPath) {
        this.teamConfigPath = teamConfigPath;
        if (!Files.exists(Path.of(teamConfigPath))) {
            try {
                Files.createFile(Path.of(teamConfigPath));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        this.loadConfig();
        if (this.teamConfig == null) {
            this.teamConfig = new TeamConfig();
        }
        threadMap = new HashMap<>();
    }

    public void setMessageBus(MessageBus messageBus) {
        this.messageBus = messageBus;
    }

    private void loadConfig() {
        if (Files.exists(Path.of(this.teamConfigPath)) && Files.isRegularFile(Path.of(this.teamConfigPath))) {
            String s = ToolUtil.runRead(this.teamConfigPath);
            if (s.isBlank()) {
                return;
            }
            try {
                this.teamConfig = OBJECT_MAPPER.readValue(s, new TypeReference<TeamConfig>() {
                });
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void setStatus(String name, String status) {
        TeamMember member = this.findMember(name);
        if (member != null) {
            member.setStatus(status);
            this.saveConfig();
        }
    }

    public String spawn(String name, String role, String prompt) {
        TeamMember member = findMember(name);
        if (member != null) {
            if ("working".equals(member.status)) {
                return String.format("Error: '%s' is currently %s", name, member.status);
            }
            member.status = "working";
            member.role = role;
            System.out.println("[subagent] --------------spawn, exist team member" + name + "----------------------");
        } else {
            TeamMember teamMember = new TeamMember();
            teamMember.name = name;
            teamMember.role = role;
            teamMember.status = "working";
            if (this.teamConfig == null) {
                this.teamConfig = new TeamConfig();
                this.teamConfig.members = new ArrayList<>();
            } else if (this.teamConfig.members == null) {
                this.teamConfig.members = new ArrayList<>();
            }
            this.teamConfig.members.add(teamMember);
            System.out.println("[subagent] --------------spawn " + name + "----------------------");
        }
        this.saveConfig();
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                execute(name, role, prompt);
            }
        });
        threadMap.put(name, thread);
        thread.start();
        return String.format("Spawned '%s' (role: %s)", name, role);
    }

    public String spawnTeamMember(String name, String role, String prompt) {
        TeamMember member = findMember(name);
        if (member != null) {
            if ("working".equals(member.status)) {
                return String.format("Error: '%s' is currently %s", name, member.status);
            }
            member.status = "working";
            member.role = role;
            System.out.println("[subagent] --------------spawn, exist team member" + name + "----------------------");
        } else {
            TeamMember teamMember = new TeamMember();
            teamMember.name = name;
            teamMember.role = role;
            teamMember.status = "working";
            if (this.teamConfig == null) {
                this.teamConfig = new TeamConfig();
                this.teamConfig.members = new ArrayList<>();
            } else if (this.teamConfig.members == null) {
                this.teamConfig.members = new ArrayList<>();
            }
            this.teamConfig.members.add(teamMember);
            System.out.println("[subagent] --------------spawn " + name + "----------------------");
        }
        this.saveConfig();
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop(name, role, prompt);
            }
        });
        try {
            thread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        threadMap.put(name, thread);
        thread.start();
        return String.format("Spawned '%s' (role: %s)", name, role);
    }


    // 用于s09_agent_teams
    private void execute(String name, String role, String prompt) {
        String systemPrompt = String.format("You are '%s', role: %s, at %s. \n" +
                        "Use send_message to communicate. " +
                        "Use explicit message protocol fields in extra when needed: conversation_id, reply_to, and message_kind=request|reply. " +
                        "If you receive a teammate request, complete the requested work and send exactly one reply back to the sender before finishing. " +
                        "If you receive a reply, use it to continue your own work and do not auto-reply to that reply. " +
                        "If you ask another teammate to do work for you, send a request and wait for their reply before concluding.",
                name, role, System.getProperty("WORK_DIR", System.getProperty("user.dir")));
        MessageParam userMsg = buildUserMsg(prompt);
        List<MessageParam> history = new ArrayList<>();
        Map<String, String> awaitingReplies = new HashMap<>();
        history.add(userMsg);

        for (int i = 0; i < 1000; i++) {
            List<MessageBus.TeamMsg> teamMsgs = this.messageBus.readInbox(name);
            List<MessageBus.TeamMsg> pendingRequests = new ArrayList<>();
            if (i > 0 && (teamMsgs == null || teamMsgs.isEmpty())) {
                try {
                    Thread.sleep(2000);
                    continue;
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            try {
                if (teamMsgs != null && !teamMsgs.isEmpty()) {
                    for (MessageBus.TeamMsg teamMsg : teamMsgs) {
                        if (teamMsg.isReply() && teamMsg.replyTo() != null) {
                            awaitingReplies.remove(teamMsg.replyTo());
                        }
                        if (teamMsg.isRequest()) {
                            pendingRequests.add(teamMsg);
                        }
                        String msg = OBJECT_MAPPER.writeValueAsString(teamMsg);
                        history.add(buildUserMsg(msg));
                    }
                }
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }

            MessageCreateParams.Builder builder = MessageCreateParams.builder()
                    .system(systemPrompt)
                    .thinking(ThinkingConfigDisabled.builder().build())
                    .maxTokens(8000L)
                    .tools(ToolUtil.TEAMMATE_MANAGER_TOOLS)
                    .model(modelName);

            for (MessageParam messageParam : history) {
                builder.addMessage(messageParam);
            }

            Message response = client.messages().create(builder.build());
            history.add(response.toParam());

            if (!response.stopReason().isPresent()
                    || !"tool_use".equals(response.stopReason().get().asString())) {
                String finalText = extractAssistantText(response);
                if (!pendingRequests.isEmpty() && !finalText.isBlank()) {
                    for (MessageBus.TeamMsg requestMsg : pendingRequests) {
                        Map<String, String> replyExtra = new HashMap<>();
                        if (requestMsg.conversationId() != null && !requestMsg.conversationId().isBlank()) {
                            replyExtra.put(MessageBus.EXTRA_CONVERSATION_ID, requestMsg.conversationId());
                        }
                        replyExtra.put(MessageBus.EXTRA_REPLY_TO, requestMsg.messageId);
                        replyExtra.put(MessageBus.EXTRA_MESSAGE_KIND, MessageBus.MESSAGE_KIND_REPLY);
                        this.messageBus.send(name, requestMsg.sender, finalText, "message", replyExtra);
                    }
                }
                if (!awaitingReplies.isEmpty()) {
                    history.add(buildUserMsg(String.format(
                            "<coordination>You are still waiting for replies from: %s. Do not conclude yet. Read your inbox and continue.</coordination>",
                            String.join(", ", awaitingReplies.values())
                    )));
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    continue;
                }
                System.out.println("[subagent] team member: " + name + ", result is:\n");
                printAssistantText(history.get(history.size() - 1));
                System.out.println("-------------------------------");
                break;
            }
            for (ContentBlock content : response.content()) {
                if (content.toolUse().isEmpty()) {
                    continue;
                }

                ToolUseBlock toolUse = content.toolUse().get();
                String toolName = toolUse.name();
                if (!ToolUtil.toolMap.containsKey(toolName)) {
                    history.add(buildToolResult(toolUse, "tool is not register", true));
                    continue;
                }

                try {
                    if ("send_message".equals(toolName)) {
                        MessageBus.SendReceipt receipt = sendMessageWithProtocol(toolUse, name);
                        if (receipt.ok
                                && MessageBus.MESSAGE_KIND_REQUEST.equalsIgnoreCase(receipt.messageKind)
                                && receipt.messageId != null
                                && !receipt.messageId.isBlank()) {
                            awaitingReplies.put(receipt.messageId, readStringInput(toolUse, "to"));
                        }
                        history.add(buildToolResult(toolUse, receipt.message == null ? "" : receipt.message, !receipt.ok));
                        continue;
                    }
                    Object result = invokeTool(toolName, toolUse);
                    history.add(buildToolResult(toolUse, result == null ? "" : result.toString(), false));
                } catch (Exception e) {
                    System.out.println(e);
                    history.add(buildToolResult(toolUse, "call tool error: " + e.getMessage(), true));
                }
            }
        }
        List<TeamMember> members = this.teamConfig.members;
        members.forEach((member) -> {
            if (name.equals(member.getName()) && !"shutdown".equals(member.getStatus())) {
                member.status = "idle";
            }
        });
        saveConfig();
        System.out.println("thread name is " + Thread.currentThread().getName() + ", done");
    }

    private void loop(String name, String role, String prompt) {
        String systemPrompt = String.format("You are '%s', role: %s, at %s. \n" +
                        "Use send_message to communicate. " +
                        "Use explicit message protocol fields in extra when needed: conversation_id, reply_to, and message_kind=request|reply. " +
                        "If you receive a teammate request, complete the requested work and send exactly one reply back to the sender before finishing. " +
                        "If you receive a reply, use it to continue your own work and do not auto-reply to that reply. " +
                        "If you ask another teammate to do work for you, send a request and wait for their reply before concluding. " +
                        "If you are working on a claimed board task, call task_update with status=completed before you go idle. " +
                        "Use idle tool when you have no more work. You will auto-claim new tasks.",
                name, role, System.getProperty("WORK_DIR", System.getProperty("user.dir")));
        MessageParam userMsg = buildUserMsg(prompt);
        List<MessageParam> history = new ArrayList<>();
        Map<String, String> awaitingReplies = new HashMap<>();
        Integer currentTaskId = null;
        boolean hasPendingLocalWork = true;
        history.add(userMsg);
        while (true) {
            for (int i = 0; i < 30; i++) {
                List<MessageBus.TeamMsg> teamMsgs = this.messageBus.readInbox(name);
                List<MessageBus.TeamMsg> pendingRequests = new ArrayList<>();
                boolean hasInboxMessages = teamMsgs != null && !teamMsgs.isEmpty();
                if (hasInboxMessages) {
                    hasPendingLocalWork = true;
                }
                if (i > 0 && !hasInboxMessages && !hasPendingLocalWork) {
                    try {
                        Thread.sleep(2000);
                        continue;
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
                try {
                    if (teamMsgs != null && !teamMsgs.isEmpty()) {
                        for (MessageBus.TeamMsg teamMsg : teamMsgs) {
                            if (teamMsg.isReply() && teamMsg.replyTo() != null) {
                                awaitingReplies.remove(teamMsg.replyTo());
                            }
                            if (teamMsg.isRequest()) {
                                pendingRequests.add(teamMsg);
                            }
                            String msg = OBJECT_MAPPER.writeValueAsString(teamMsg);
                            history.add(buildUserMsg(msg));
                        }
                    }
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }

                MessageCreateParams.Builder builder = MessageCreateParams.builder()
                        .system(systemPrompt)
                        .thinking(ThinkingConfigDisabled.builder().build())
                        .maxTokens(8000L)
                        .tools(ToolUtil.TEAMMATE_MANAGER_TOOLS)
                        .model(modelName);

                for (MessageParam messageParam : history) {
                    builder.addMessage(messageParam);
                }

                hasPendingLocalWork = false;
                Message response = client.messages().create(builder.build());
                history.add(response.toParam());

                if (!response.stopReason().isPresent()
                        || !"tool_use".equals(response.stopReason().get().asString())) {
                    String finalText = extractAssistantText(response);
                    if (!pendingRequests.isEmpty() && !finalText.isBlank()) {
                        for (MessageBus.TeamMsg requestMsg : pendingRequests) {
                            Map<String, String> replyExtra = new HashMap<>();
                            if (requestMsg.conversationId() != null && !requestMsg.conversationId().isBlank()) {
                                replyExtra.put(MessageBus.EXTRA_CONVERSATION_ID, requestMsg.conversationId());
                            }
                            replyExtra.put(MessageBus.EXTRA_REPLY_TO, requestMsg.messageId);
                            replyExtra.put(MessageBus.EXTRA_MESSAGE_KIND, MessageBus.MESSAGE_KIND_REPLY);
                            this.messageBus.send(name, requestMsg.sender, finalText, "message", replyExtra);
                        }
                    }
                    if (!awaitingReplies.isEmpty()) {
                        history.add(buildUserMsg(String.format(
                                "<coordination>You are still waiting for replies from: %s. Do not conclude yet. Read your inbox and continue.</coordination>",
                                String.join(", ", awaitingReplies.values())
                        )));
                        hasPendingLocalWork = true;
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        continue;
                    }
                    System.out.println("[subagent] team member: " + name + ", result is:\n");
                    printAssistantText(history.get(history.size() - 1));
                    System.out.println("-------------------------------");
                    if (currentTaskId != null) {
                        ToolUtil.runTaskUpdate(currentTaskId, "completed", null, null);
                        history.add(buildUserMsg(String.format(
                                "<task-status>Task #%s has been marked completed. Look for more work.</task-status>",
                                currentTaskId
                        )));
                        hasPendingLocalWork = true;
                        currentTaskId = null;
                        IdleResult idleResult = enterIdlePhase(name, role, history);
                        if (!idleResult.resumed) {
                            return;
                        }
                        currentTaskId = idleResult.taskId;
                        hasPendingLocalWork = true;
                        continue;
                    }
                    break;
                }

                Boolean idleRequested = false;
                for (ContentBlock content : response.content()) {
                    if (content.toolUse().isEmpty()) {
                        continue;
                    }

                    ToolUseBlock toolUse = content.toolUse().get();
                    String toolName = toolUse.name();
                    if (!ToolUtil.toolMap.containsKey(toolName)) {
                        history.add(buildToolResult(toolUse, "tool is not register", true));
                        continue;
                    }

                    try {
                        if ("send_message".equals(toolName)) {
                            MessageBus.SendReceipt receipt = sendMessageWithProtocol(toolUse, name);
                            if (receipt.ok
                                    && MessageBus.MESSAGE_KIND_REQUEST.equalsIgnoreCase(receipt.messageKind)
                                    && receipt.messageId != null
                                    && !receipt.messageId.isBlank()) {
                                awaitingReplies.put(receipt.messageId, readStringInput(toolUse, "to"));
                            }
                            history.add(buildToolResult(toolUse, receipt.message == null ? "" : receipt.message, !receipt.ok));
                            hasPendingLocalWork = true;
                        } else if ("idle".equals(toolName)) {
                            idleRequested = true;
                            String output = "Entering idle phase. Will poll for new tasks.";
                            history.add(buildToolResult(toolUse, output, false));
                            hasPendingLocalWork = true;
                        } else if ("claim_task".equals(toolName)) {
                            Object result = invokeTool(toolName, toolUse);
                            String resultText = result == null ? "" : result.toString();
                            if (!resultText.startsWith("Error:")) {
                                currentTaskId = readIntInput(toolUse, "taskId");
                            }
                            history.add(buildToolResult(toolUse, resultText, resultText.startsWith("Error:")));
                            hasPendingLocalWork = true;
                        } else if ("task_update".equals(toolName)) {
                            Integer updatedTaskId = readIntInput(toolUse, "taskId");
                            String status = readStringInput(toolUse, "status");
                            Object result = invokeTool(toolName, toolUse);
                            if (updatedTaskId != null && updatedTaskId.equals(currentTaskId) && "completed".equals(status)) {
                                currentTaskId = null;
                            }
                            history.add(buildToolResult(toolUse, result == null ? "" : result.toString(), false));
                            hasPendingLocalWork = true;
                        } else {
                            Object result = invokeTool(toolName, toolUse);
                            history.add(buildToolResult(toolUse, result == null ? "" : result.toString(), false));
                            hasPendingLocalWork = true;
                        }
                    } catch (Exception e) {
                        System.out.println(e);
                        history.add(buildToolResult(toolUse, "call tool error: " + e.getMessage(), true));
                        hasPendingLocalWork = true;
                    }
                }

                if (!idleRequested) {
                    continue;
                }
                if (currentTaskId != null) {
                    ToolUtil.runTaskUpdate(currentTaskId, "completed", null, null);
                    history.add(buildUserMsg(String.format(
                            "<task-status>Task #%s was marked completed before idling.</task-status>",
                            currentTaskId
                    )));
                    hasPendingLocalWork = true;
                    currentTaskId = null;
                }
                IdleResult idleResult = enterIdlePhase(name, role, history);
                if (!idleResult.resumed) {
                    System.out.println("   [subagent: stop]" + name + "  end");
                    return;
                }
                currentTaskId = idleResult.taskId;
                hasPendingLocalWork = true;
            }
        }
    }

    private IdleResult enterIdlePhase(String name, String role, List<MessageParam> history) {
        this.setStatus(name, "idle");
        int pollIntervalMillis = 5000;
        int idleTimeoutMillis = 60000;
        int polls = idleTimeoutMillis / pollIntervalMillis;
        for (int j = 0; j < polls; j++) {
            try {
                Thread.sleep(pollIntervalMillis);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            List<MessageBus.TeamMsg> inboxes = ToolUtil.runReadInbox(name);
            if (inboxes != null && !inboxes.isEmpty()) {
                for (MessageBus.TeamMsg inbox : inboxes) {
                    try {
                        history.add(buildUserMsg(OBJECT_MAPPER.writeValueAsString(inbox)));
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException(e);
                    }
                }
                this.setStatus(name, "working");
                return IdleResult.resume(null);
            }

            List<TaskManager.Task> tasks = ToolUtil.runScanUnclaimTasks();
            if (tasks != null && !tasks.isEmpty()) {
                TaskManager.Task task = tasks.get(0);
                String result = ToolUtil.runClaimTask(task.getId(), name);
                if (result.startsWith("Error:")) {
                    continue;
                }
                String taskPrompt = String.format(
                        "<auto-claimed>Task #%s: %s\n%s\nMark this task completed with task_update before you go idle again.</auto-claimed>",
                        task.getId(),
                        task.getSubject(),
                        (task.getDescription() == null || task.getDescription().isBlank()) ? "" : task.getDescription()
                );
                System.out.println("   [subagent-task-claim] " + taskPrompt);
                if (history.size() <= 3) {
                    history.add(0, makeIdentityBlock(name, role, "code-team"));
                    history.add(1, buildAssistanceMsg(String.format("I am %s. Continuing.", name)));
                }
                history.add(buildUserMsg(taskPrompt));
                history.add(buildAssistanceMsg(String.format("Claimed task #%s. Working on it.", task.getId())));
                this.setStatus(name, "working");
                return IdleResult.resume(task.getId());
            }
        }
        this.setStatus(name, "shutdown");
        System.out.println("thread name is " + Thread.currentThread().getName() + ", done");
        return IdleResult.shutdown();
    }

    public MessageParam makeIdentityBlock(String name, String role, String teamName) {
        String content = String.format("<identity>You are '%s', role: %s, team: %s. Continue your work.</identity>", name, role, teamName);
        System.out.println("[  subagent-makeIdentityBlock]" + content);
        return buildUserMsg(content);
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
        return ToolUtil.invokeRegisteredTool(ToolUtil.toolMap, ToolUtil.METHOD_MAP, toolName, toolUse);
    }

    private static String readStringInput(ToolUseBlock toolUse, String fieldName) {
        Map<?, ?> inputMap = (Map<?, ?>) toolUse._input().asObject().get();
        Object rawValue = inputMap.get(fieldName);
        if (rawValue instanceof JsonString jsonString) {
            return (String) jsonString.asString().orElse("");
        }
        if (rawValue instanceof JsonValue jsonValue) {
            Object converted = jsonValue.convert(Object.class);
            return converted == null ? "" : converted.toString();
        }
        return rawValue == null ? "" : rawValue.toString();
    }

    private static Integer readIntInput(ToolUseBlock toolUse, String fieldName) {
        String value = readStringInput(toolUse, fieldName);
        if (value == null || value.isBlank()) {
            return null;
        }
        return Integer.parseInt(value);
    }

    private static class IdleResult {
        private final boolean resumed;
        private final Integer taskId;

        private IdleResult(boolean resumed, Integer taskId) {
            this.resumed = resumed;
            this.taskId = taskId;
        }

        private static IdleResult resume(Integer taskId) {
            return new IdleResult(true, taskId);
        }

        private static IdleResult shutdown() {
            return new IdleResult(false, null);
        }
    }

    private MessageBus.SendReceipt sendMessageWithProtocol(ToolUseBlock toolUse, String senderName) {
        String sender = readStringInput(toolUse, "sender");
        String to = readStringInput(toolUse, "to");
        String content = readStringInput(toolUse, "content");
        String msgType = readStringInput(toolUse, "msgType");
        Map<String, String> extra = readMapInput(toolUse, "extra");
        if (sender == null || sender.isBlank()) {
            sender = senderName;
        }
        return this.messageBus.sendDetailed(sender, to, content, msgType, extra);
    }

    private static Map<String, String> readMapInput(ToolUseBlock toolUse, String fieldName) {
        Map<?, ?> inputMap = (Map<?, ?>) toolUse._input().asObject().get();
        Object rawValue = inputMap.get(fieldName);
        if (rawValue == null) {
            return null;
        }
        Object converted;
        if (rawValue instanceof JsonValue jsonValue) {
            converted = jsonValue.convert(Object.class);
        } else {
            converted = rawValue;
        }
        if (!(converted instanceof Map<?, ?> mapValue)) {
            return null;
        }
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            result.put(entry.getKey().toString(), entry.getValue().toString());
        }
        return result.isEmpty() ? null : result;
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

    private static MessageParam buildAssistanceMsg(String content) {
        System.out.println("[subagent: buildAssistanceMsg] " + content);
        return MessageParam.builder()
                .role(MessageParam.Role.ASSISTANT)
                .contentOfBlockParams(List.of(
                        ContentBlockParam.ofText(
                                TextBlockParam.builder().text(content).build()
                        )
                ))
                .build();
    }

    private static String extractAssistantText(Message response) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock contentBlock : response.content()) {
            if (contentBlock.text().isPresent()) {
                if (!sb.isEmpty()) {
                    sb.append("\n");
                }
                sb.append(contentBlock.text().get().text());
            }
        }
        return sb.toString();
    }

    private void saveConfig() {
        String teamconfig = null;
        try {
            teamconfig = OBJECT_MAPPER.writeValueAsString(this.teamConfig);
            ToolUtil.runWrite(this.teamConfigPath, teamconfig);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private TeamMember findMember(String name) {
        if (teamConfig == null || teamConfig.members == null) {
            return null;
        }
        for (TeamMember teamMember : teamConfig.members) {
            if (name.equals(teamMember.name)) {
                return teamMember;
            }
        }
        return null;
    }

    public String listAll() {
        if (this.teamConfig == null || this.teamConfig.members == null || this.teamConfig.members.isEmpty()) {
            return "No teammates.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Team: %s", this.teamConfig.teamName)).append("\n");
        for (TeamMember member : this.teamConfig.members) {
            sb.append(String.format("  %s %s: %s", member.name, member.role, member.status)).append("\n");
        }
        sb.deleteCharAt(sb.length() - 1);
        return sb.toString();
    }

    public List<String> memberNames() {
        if (this.teamConfig == null || this.teamConfig.members == null || this.teamConfig.members.isEmpty()) {
            return Collections.EMPTY_LIST;
        }
        List<String> names = new ArrayList<>();
        for (TeamMember member : this.teamConfig.members) {
            names.add(member.name);
        }
        return names;
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
            content.text().ifPresent(textBlockParam -> System.out.println("[subagent]: " + textBlockParam.text()));
        }
    }

    public static class TeamConfig {
        public String teamName;
        public List<TeamMember> members;

        public String getTeamName() {
            return teamName;
        }

        public void setTeamName(String teamName) {
            this.teamName = teamName;
        }

        public List<TeamMember> getMembers() {
            return members;
        }

        public void setMembers(List<TeamMember> members) {
            this.members = members;
        }
    }

    public static class TeamMember {
        public String name;

        private String role;

        private String status;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }

}
