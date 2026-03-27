package com.djh.learnclaudecode.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

public class BashUtil {

    /**
     * 执行 bash 命令
     * @param command 命令字符串
     * @return 命令输出 + 错误信息
     */
    public static String exec(String command) {
        return exec(command, null);
    }

    /**
     * 执行 bash 命令（带工作目录）
     */
    public static String exec(String command, String directory) {
        StringBuilder sb = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            if (directory != null) {
                pb.directory(new File(directory));
            }

            Process process = pb.start();

            // 读取输出
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream())
            );
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }

            // 读取错误
            BufferedReader errorReader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream())
            );
            while ((line = errorReader.readLine()) != null) {
                sb.append("ERROR: ").append(line).append("\n");
            }

            int exitCode = process.waitFor();
            sb.append("\nExit Code: ").append(exitCode);

        } catch (Exception e) {
            sb.append("Exception: ").append(e.getMessage());
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        String result = exec("ls -al");
        System.out.println(result);
    }
}
