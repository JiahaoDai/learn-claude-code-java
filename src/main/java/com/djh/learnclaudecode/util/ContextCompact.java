package com.djh.learnclaudecode.util;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class ContextCompact {

    private static final int KEEP_RECENT = 3;
    private static final int TOOL_RESULT_MIN_LENGTH = 100;
    private static final int SUMMARY_INPUT_CHAR_LIMIT = 80_000;
    private static final long SUMMARY_MAX_TOKENS = 2_000L;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> PRESERVE_RESULT_TOOLS = Set.of("read_file");
    private static final Path TRANSCRIPT_DIR = Paths.get(
            System.getProperty("WORK_DIR", System.getProperty("user.dir")),
            "transcripts"
    );

    private ContextCompact() {
    }

    public static List<MessageParam> microCompact(List<MessageParam> messages) {
        List<ToolResultRef> toolResults = collectToolResults(messages);
        if (toolResults.size() <= KEEP_RECENT) {
            return messages;
        }

        Map<String, String> toolNameMap = buildToolNameMap(messages);
        Set<BlockKey> blocksToCompact = new HashSet<>();
        for (int i = 0; i < toolResults.size() - KEEP_RECENT; i++) {
            ToolResultRef ref = toolResults.get(i);
            if (shouldCompact(ref.toolResult(), toolNameMap)) {
                blocksToCompact.add(new BlockKey(ref.messageIndex(), ref.blockIndex()));
            }
        }
        if (blocksToCompact.isEmpty()) {
            return messages;
        }

        List<MessageParam> compacted = new ArrayList<>(messages.size());
        for (int msgIndex = 0; msgIndex < messages.size(); msgIndex++) {
            MessageParam message = messages.get(msgIndex);
            if (!message.content().isBlockParams()) {
                compacted.add(message);
                continue;
            }

            List<ContentBlockParam> rebuiltBlocks = new ArrayList<>();
            List<ContentBlockParam> blocks = message.content().asBlockParams();
            for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
                ContentBlockParam block = blocks.get(blockIndex);
                BlockKey key = new BlockKey(msgIndex, blockIndex);
                if (!blocksToCompact.contains(key) || block.toolResult().isEmpty()) {
                    rebuiltBlocks.add(block);
                    continue;
                }

                ToolResultBlockParam toolResult = block.toolResult().get();
                String toolName = toolNameMap.getOrDefault(toolResult.toolUseId(), "unknown");
                ToolResultBlockParam compactedResult = toolResult.toBuilder()
                        .content("[Previous: used " + toolName + "]")
                        .build();
                rebuiltBlocks.add(ContentBlockParam.ofToolResult(compactedResult));
            }
            compacted.add(message.toBuilder()
                    .contentOfBlockParams(rebuiltBlocks)
                    .build());
        }
        return compacted;
    }

    public static List<MessageParam> autoCompact(AnthropicClient client, String modelName, List<MessageParam> messages) {
        try {
            Files.createDirectories(TRANSCRIPT_DIR);
            Path transcriptPath = TRANSCRIPT_DIR.resolve("transcript_" + System.currentTimeMillis() + ".jsonl");
            writeTranscript(messages, transcriptPath);

            String conversationText = serializeMessages(messages);
            if (conversationText.length() > SUMMARY_INPUT_CHAR_LIMIT) {
                conversationText = conversationText.substring(conversationText.length() - SUMMARY_INPUT_CHAR_LIMIT);
            }

            Message response = client.messages().create(
                    MessageCreateParams.builder()
                            .model(modelName)
                            .maxTokens(SUMMARY_MAX_TOKENS)
                            .addUserMessage(
                                    "Summarize this conversation for continuity. Include: "
                                            + "1) What was accomplished, 2) Current state, 3) Key decisions made. "
                                            + "Be concise but preserve critical details.\n\n"
                                            + conversationText
                            )
                            .build()
            );

            String summary = extractText(response);
            if (summary == null || summary.isBlank()) {
                summary = "No summary generated.";
            }

            return List.of(
                    MessageParam.builder()
                            .role(MessageParam.Role.USER)
                            .content("[Conversation compressed. Transcript: " + transcriptPath + "]\n\n" + summary)
                            .build()
            );
        } catch (IOException e) {
            throw new RuntimeException("context compact save transcript error", e);
        }
    }

    private static List<ToolResultRef> collectToolResults(List<MessageParam> messages) {
        List<ToolResultRef> toolResults = new ArrayList<>();
        for (int msgIndex = 0; msgIndex < messages.size(); msgIndex++) {
            MessageParam message = messages.get(msgIndex);
            if (message.role().value() != MessageParam.Role.Value.USER || !message.content().isBlockParams()) {
                continue;
            }
            List<ContentBlockParam> blocks = message.content().asBlockParams();
            for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
                ContentBlockParam block = blocks.get(blockIndex);
                if (block.toolResult().isPresent()) {
                    toolResults.add(new ToolResultRef(msgIndex, blockIndex, block.toolResult().get()));
                }
            }
        }
        return toolResults;
    }

    private static Map<String, String> buildToolNameMap(List<MessageParam> messages) {
        Map<String, String> toolNameMap = new HashMap<>();
        for (MessageParam message : messages) {
            if (message.role().value() != MessageParam.Role.Value.ASSISTANT || !message.content().isBlockParams()) {
                continue;
            }
            for (ContentBlockParam block : message.content().asBlockParams()) {
                block.toolUse().ifPresent(toolUse -> toolNameMap.put(toolUse.id(), toolUse.name()));
            }
        }
        return toolNameMap;
    }

    private static boolean shouldCompact(ToolResultBlockParam toolResult, Map<String, String> toolNameMap) {
        if (toolResult.content().isEmpty() || !toolResult.content().get().isString()) {
            return false;
        }
        String content = toolResult.content().get().asString();
        if (content.length() <= TOOL_RESULT_MIN_LENGTH) {
            return false;
        }
        String toolName = toolNameMap.getOrDefault(toolResult.toolUseId(), "unknown");
        return !PRESERVE_RESULT_TOOLS.contains(toolName);
    }

    private static void writeTranscript(List<MessageParam> messages, Path transcriptPath) throws IOException {
        List<String> lines = new ArrayList<>(messages.size());
        for (MessageParam message : messages) {
            try {
                lines.add(OBJECT_MAPPER.writeValueAsString(message));
            } catch (JsonProcessingException e) {
                throw new RuntimeException("serialize transcript message error", e);
            }
        }
        Files.write(transcriptPath, lines, StandardCharsets.UTF_8);
    }

    private static String serializeMessages(List<MessageParam> messages) {
        try {
            return OBJECT_MAPPER.writeValueAsString(messages);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("serialize messages error", e);
        }
    }

    private static String extractText(Message response) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : response.content()) {
            block.text().ifPresent(text -> {
                if (!sb.isEmpty()) {
                    sb.append("\n");
                }
                sb.append(text.text());
            });
        }
        return sb.toString();
    }

    private record ToolResultRef(int messageIndex, int blockIndex, ToolResultBlockParam toolResult) {
    }

    private record BlockKey(int messageIndex, int blockIndex) {
    }
}
