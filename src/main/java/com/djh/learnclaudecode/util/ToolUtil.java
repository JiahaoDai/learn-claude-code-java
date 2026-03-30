package com.djh.learnclaudecode.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class ToolUtil {

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
        try(FileOutputStream fileOutputStream = new FileOutputStream(new File(path))) {
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

}
