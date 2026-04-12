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

    private static final String modelName = "qwen3.5-flash";

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

    public String spawn(String name, String role, String prompt) {
        System.out.println("[subagent] --------------spawn " + name + "----------------------");
        TeamMember member = findMember(name);
        if (member != null) {
            if ("idle".equals(member.status) || "shutdown".equals(member.status)) {
                return String.format("Error: '%s' is currently %s", name, member.status);
            }
            member.status = "working";
            member.role = role;
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
                    for (MessageBus.TeamMsg teamMsg: teamMsgs) {
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
        members.forEach((member)->{
            if(name.equals(member.getName()) && !"shutdown".equals(member.getStatus())){
                member.status = "idle";
            }
        });
        saveConfig();
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
