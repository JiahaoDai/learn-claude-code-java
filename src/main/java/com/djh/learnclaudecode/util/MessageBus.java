package com.djh.learnclaudecode.util;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.*;

public class MessageBus {
    public static final String EXTRA_CONVERSATION_ID = "conversation_id";

    public static final String EXTRA_REPLY_TO = "reply_to";

    public static final String EXTRA_MESSAGE_KIND = "message_kind";

    public static final String MESSAGE_KIND_REQUEST = "request";

    public static final String MESSAGE_KIND_REPLY = "reply";

    private String msgDir;

    private TeammateManager teammateManager;

    private static final Set<String> VALID_MSG_TYPES = new HashSet<>();

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    static {
        VALID_MSG_TYPES.add("message");
        VALID_MSG_TYPES.add("broadcast");
        VALID_MSG_TYPES.add("shutdown_request");
        VALID_MSG_TYPES.add("shutdown_response");
        VALID_MSG_TYPES.add("plan_approval_response");
    }

    public MessageBus(String dir) {
        this.msgDir = dir;
        if (!Files.exists(Path.of(dir))) {
            try {
                Files.createDirectory(Path.of(dir));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public void setTeammateManager(TeammateManager teammateManager) {
        this.teammateManager = teammateManager;
    }

    public SendReceipt sendDetailed(String sender, String to, String content, String msgType, Map<String, String> extra) {
        if (!VALID_MSG_TYPES.contains(msgType)) {
            return SendReceipt.error(String.format("Error: Invalid type '%s'. Valid: [%s]", msgType, String.join(",", VALID_MSG_TYPES.stream().map(String::valueOf).toList())));
        }
        Map<String, String> normalizedExtra = normalizeExtra(msgType, extra);
        TeamMsg teamMsg = TeamMsg.build(msgType, sender, content, normalizedExtra);

        String inboxPath = this.msgDir + "/" + to + ".jsonl";
        if (!Files.exists(Path.of(inboxPath))) {
            try {
                Files.createFile(Path.of(inboxPath));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        String jsonMsg = "";
        try {
            jsonMsg = OBJECT_MAPPER.writeValueAsString(teamMsg);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        appendContentToFile(inboxPath, jsonMsg + "\n");
        return SendReceipt.success(String.format("Sent %s to %s", msgType, to), teamMsg);
    }

    public String send(String sender, String to, String content, String msgType, Map<String, String> extra) {
        return sendDetailed(sender, to, content, msgType, extra).message;
    }

    public List<TeamMsg> readInbox(String name) {
        String inboxPath = this.msgDir + "/" + name + ".jsonl";
        if (!Files.exists(Path.of(inboxPath)) || !Files.isRegularFile(Path.of(inboxPath))) {
            return Collections.EMPTY_LIST;
        }
        List<String> msgLists = readFileByLine(inboxPath);
        if (msgLists.isEmpty()) {
            return Collections.EMPTY_LIST;
        }
        List<TeamMsg> teamMsgs = new ArrayList<>();
        for (String msg : msgLists) {
            TeamMsg teamMsg = null;
            try {
                teamMsg = OBJECT_MAPPER.readValue(msg, new TypeReference<TeamMsg>() {
                });
                teamMsgs.add(teamMsg);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        // 清空文件内容
        ToolUtil.runWrite(inboxPath, "");
        System.out.println("read msg from " + name + ".jsonl");
        for (String msgList : msgLists) {
            System.out.println("----" + msgList);
        }
        return teamMsgs;
    }

    public String broadcast(String sender, String content) {
        int count = 0;
        List<String> teammates = teammateManager.memberNames();
        for (String mate : teammates) {
            if (!sender.equals(mate)) {
                this.send(sender, mate, content, "broadcast", null);
                count++;
            }
        }
        return String.format("Broadcast to %s teammates", count);
    }

    private Map<String, String> normalizeExtra(String msgType, Map<String, String> extra) {
        Map<String, String> normalized = new HashMap<>();
        if (extra != null) {
            normalized.putAll(extra);
        }
        if ("message".equalsIgnoreCase(msgType)) {
            normalized.computeIfAbsent(EXTRA_CONVERSATION_ID, ignored -> UUID.randomUUID().toString());
            if (normalized.containsKey(EXTRA_REPLY_TO)) {
                normalized.putIfAbsent(EXTRA_MESSAGE_KIND, MESSAGE_KIND_REPLY);
            } else {
                normalized.putIfAbsent(EXTRA_MESSAGE_KIND, MESSAGE_KIND_REQUEST);
            }
        }
        return normalized.isEmpty() ? null : normalized;
    }

    private List<String> readFileByLine(String filePath) {
        List<String> results = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(
                        new java.io.FileInputStream(filePath),
                        StandardCharsets.UTF_8
                )
        )) {
            String line;
            // readLine() 读取一行，返回 null 表示文件结束
            while ((line = br.readLine()) != null) {
                results.add(line);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return results;
    }

    private void appendContentToFile(String filePath, String content) {
        try (BufferedWriter bw = new BufferedWriter(
                new OutputStreamWriter(
                        new FileOutputStream(filePath, true), // append = true
                        StandardCharsets.UTF_8 // 强制指定编码
                )
        )) {
            bw.write(content);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TeamMsg {
        public String messageId;

        public String type;

        public String sender;

        public String content;

        public Timestamp timestamp = new Timestamp(System.currentTimeMillis());

        public Map<String, String> extra;

        // Backward compatibility for messages written before the explicit protocol fields.
        public Boolean reply;

        public Boolean request;

        public static TeamMsg build(String type, String sender, String content, Map<String, String> extra) {
            TeamMsg teamMsg = new TeamMsg();
            teamMsg.messageId = UUID.randomUUID().toString();
            teamMsg.type = type;
            teamMsg.sender = sender;
            teamMsg.content = content;
            teamMsg.extra = extra;
            return teamMsg;
        }

        public String conversationId() {
            return extra == null ? null : extra.get(EXTRA_CONVERSATION_ID);
        }

        public String replyTo() {
            return extra == null ? null : extra.get(EXTRA_REPLY_TO);
        }

        public String messageKind() {
            return extra == null ? null : extra.get(EXTRA_MESSAGE_KIND);
        }

        public boolean isReply() {
            if (Boolean.TRUE.equals(reply)) {
                return true;
            }
            return MESSAGE_KIND_REPLY.equalsIgnoreCase(messageKind());
        }

        public boolean isRequest() {
            if (!"message".equalsIgnoreCase(type)) {
                return false;
            }
            if (Boolean.TRUE.equals(request)) {
                return true;
            }
            return !isReply();
        }

        public String getMessageId() {
            return messageId;
        }

        public void setMessageId(String messageId) {
            this.messageId = messageId;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getSender() {
            return sender;
        }

        public void setSender(String sender) {
            this.sender = sender;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public Timestamp getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(Timestamp timestamp) {
            this.timestamp = timestamp;
        }

        public Map<String, String> getExtra() {
            return extra;
        }

        public void setExtra(Map<String, String> extra) {
            this.extra = extra;
        }
    }

    public static class SendReceipt {
        public boolean ok;

        public String message;

        public String messageId;

        public String conversationId;

        public String messageKind;

        public static SendReceipt success(String message, TeamMsg teamMsg) {
            SendReceipt receipt = new SendReceipt();
            receipt.ok = true;
            receipt.message = message;
            receipt.messageId = teamMsg.messageId;
            receipt.conversationId = teamMsg.conversationId();
            receipt.messageKind = teamMsg.messageKind();
            return receipt;
        }

        public static SendReceipt error(String message) {
            SendReceipt receipt = new SendReceipt();
            receipt.ok = false;
            receipt.message = message;
            return receipt;
        }
    }
}
