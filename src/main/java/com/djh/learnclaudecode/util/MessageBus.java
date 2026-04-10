package com.djh.learnclaudecode.util;

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

    public String send(String sender, String to, String content, String msgType, Map<String, String> extra) {
        if(sender.equals("alice")){
            System.out.println("[subagent:" + sender + "]: send msg is: " + content);
        }

        if(sender.equals("bob")){
            System.out.println("[subagent:" + sender + "]: send msg is: " + content);
        }
        if (!VALID_MSG_TYPES.contains(msgType)) {
            return String.format("Error: Invalid type '%s'. Valid: [%s]", msgType, String.join(",", VALID_MSG_TYPES.stream().map(String::valueOf).toList()));
        }
        TeamMsg teamMsg = TeamMsg.build(msgType, sender, content);
        if (extra != null && !extra.isEmpty()) {
            teamMsg.extra = extra;
        }

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
        return String.format("Sent %s to %s", msgType, to);

    }

    public List<TeamMsg> readInbox(String name) {
        String inboxPath = this.msgDir + "/" + name + ".jsonl";
        if (!Files.exists(Path.of(inboxPath)) || !Files.isRegularFile(Path.of(inboxPath))) {
            return Collections.EMPTY_LIST;
        }
        List<String> msgLists = readFileByLine(inboxPath);
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


    public static class TeamMsg {
        public String type;

        public String sender;

        public String content;

        public Timestamp timestamp = new Timestamp(System.currentTimeMillis());

        public Map<String, String> extra;

        public static TeamMsg build(String type, String sender, String content) {
            TeamMsg teamMsg = new TeamMsg();
            teamMsg.type = type;
            teamMsg.sender = sender;
            teamMsg.content = content;
            return teamMsg;
        }
    }
}
