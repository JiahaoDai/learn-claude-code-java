package com.djh.learnclaudecode.util;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SkillLoader {

    private String skillDir;

    private Map<String, Skill> skillMap;

    public SkillLoader() {
    }

    public SkillLoader(String skillDir) {
        this.skillDir = skillDir;
        this.skillMap = new HashMap<>();
        this.loadSkill();
    }

    private void loadSkill() {
        Path path = Path.of(this.skillDir);
        if (!Files.exists(path) || !Files.isDirectory(path)) {
            return;
        }

        List<File> skillFiles = new ArrayList<>();
        findAllSkillMd(new File(this.skillDir), skillFiles);
        skillFiles.forEach((file) -> {
            try {
                // 读取内容（UTF-8 编码）
                String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                Skill skill = parseSkill(content, file.getPath());
                if (skill.getMeta() != null) {
                    skillMap.put(skill.getMeta().get("name"), skill);
                } else {
                    // 在不规范的情况下，使用父目录作为skill的名称
                    skillMap.put(file.getParentFile().getName(), skill);
                }
            } catch (Exception e) {
                System.out.println("读取失败：" + e.getMessage());
            }
        });

    }

    private static void findAllSkillMd(File dir, List<File> fileList) {
        // 目录为空或不存在，直接返回
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return;
        }

        // 获取目录下所有文件/子目录
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                // 递归：继续扫描子文件夹
                findAllSkillMd(file, fileList);
            } else if ("SKILL.md".equals(file.getName())) {
                // 匹配到 skill.md，加入列表
                fileList.add(file);
            }
        }
    }

    private Skill parseSkill(String content, String skillPath) {
        Pattern pattern = Pattern.compile("^---\\n(.*?)\\n---\\n(.*)", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(content);

        Skill skill = new Skill();
        Map<String, String> meta = new HashMap<>();
        if (matcher.find()) {
            String frontMatter = matcher.group(1); // 配置区
            String[] arr = frontMatter.strip().split("\\n");
            for (String s : arr) {
                String name = s.split(":")[0].strip();
                String value = s.split(":")[1].strip();
                meta.put(name, value);
            }
            String body = matcher.group(2).strip();        // 正文
            skill.setMeta(meta);
            skill.setBody(body);
        } else {
            skill.setBody(content);
        }
        skill.setPath(skillPath);
        return skill;
    }

    public String getDescription() {
        if (this.skillMap.isEmpty()) {
            return "(no skills available)";
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        int sz = skillMap.size();
        for (Map.Entry<String, Skill> entry : skillMap.entrySet()) {
            count++;
            String name = entry.getKey();
            String desc = entry.getValue().getMeta().get("description");
            String line = String.format("  - %s: %s", name, desc);
            if (count == sz) {
                sb.append(line);
            } else {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    public String getContent(String skillName) {
        if (!this.skillMap.containsKey(skillName)) {
            String keys = String.join(",", this.skillMap.keySet());
            return String.format("Error: Unknown skill '%s'. Available: %s", skillName, keys);
        }
        return String.format("<skill name=\"%s\">\n%s\n</skill>", skillName, skillMap.get(skillName).getBody());
    }


    static class Skill {
        private Map<String, String> meta;
        private String body;
        private String path;

        public Map<String, String> getMeta() {
            return meta;
        }

        public void setMeta(Map<String, String> meta) {
            this.meta = meta;
        }

        public String getBody() {
            return body;
        }

        public void setBody(String body) {
            this.body = body;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }
    }

}
