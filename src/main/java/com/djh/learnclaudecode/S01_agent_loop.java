package com.djh.learnclaudecode;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonString;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Tool;
import com.djh.learnclaudecode.util.BashUtil;

import java.util.*;
import java.util.function.Function;

public class S01_agent_loop {

    private static final String systemPrompt = "";

    private static final AnthropicClient client = AnthropicOkHttpClient.fromEnv();

    private static Tool bashTool = buildBashTool();

    private static final String modelName = "kimi-k2.5";

    private static Map<String, Function> toolMap = new HashMap<>() {{
        put("bash", new Function() {
            @Override
            public Object apply(Object o) {
                return runBash((String) o);
            }
        });
    }};

    static class MessageInfo {
        private String msg;

        private String role;

        public String getMsg() {
            return msg;
        }

        public void setMsg(String msg) {
            this.msg = msg;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public static MessageInfo build(String msg, String role) {
            MessageInfo messageInfo = new MessageInfo();
            messageInfo.setMsg(msg);
            messageInfo.setRole(role);
            return messageInfo;
        }
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        List<MessageInfo> history = new ArrayList<>();

        while (true) {
            String s = scanner.next();
            history.add(MessageInfo.build(s, "user"));
            if (s.strip().toLowerCase(Locale.ROOT).equals("q")) {
                break;
            }
            AgentLoop(history);
            System.out.println(history.get(history.size() - 1).getMsg());
        }
        scanner.close();
    }

    public static void AgentLoop(List<MessageInfo> history) {
        while (true) {
            MessageCreateParams.Builder builder = MessageCreateParams.builder()
                    .maxTokens(8000L)
                    .addTool(bashTool)
                    .model(modelName);
            for (int i = 0; i < history.size(); i++) {
                if (history.get(i).getRole().equals("user")) {
                    builder.addUserMessage(history.get(i).getMsg());
                } else if (history.get(i).getRole().equals("assistant")) {
                    builder.addAssistantMessage(history.get(i).getMsg());
                }
            }
            MessageCreateParams params = builder.build();
            Message response = client.messages().create(params);
            if (!response.stopReason().get().asString().equals("tool_use")) {
                // KnownValue
                history.add(MessageInfo.build(response.content().get(0).text().get().text(), "assistant"));
                break;
            }

            for (ContentBlock content : response.content()) {
                if (content.toolUse().isPresent()) {
                    String command = null;
                    Object obj = content.toolUse().get()._input().asObject().get();
                    if (obj instanceof Map map) {
                        JsonString json = (JsonString) map.get("command");
                        command = (String) json.asString().get();
                    }
                    if (command == null) {
                        throw new RuntimeException("");
                    }

                    String result = runBash(command);
                    String ss = String.format("{'type': 'tool_result', 'tool_use_id': %s, 'result': %s}", content.toolUse().get()._id().asString().get(), result);
                    history.add(MessageInfo.build(ss, "user"));
                }
            }
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

    public static String runBash(String command) {
        if (command.contains("rm") || command.contains("sudo")) {
            return "error, dangerous command";
        }
        return BashUtil.exec(command);
    }

}
