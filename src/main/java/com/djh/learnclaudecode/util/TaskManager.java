package com.djh.learnclaudecode.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class TaskManager {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Set<String> STATUS_SET = new HashSet<>() {{
        add("pending");
        add("in_progress");
        add("completed");
    }};

    private String taskDir;

    private AtomicInteger nextId = new AtomicInteger(0);

    public TaskManager(String taskDir) {
        this.taskDir = taskDir;
        this.nextId.set(getMaxTaskId());
    }

    private List<Path> getTaskFile(){
        try {
            List<Path> taskFiles = Files.list(Path.of(this.taskDir))
                    .filter(Files::isRegularFile)  // 只保留文件，排除目录
                    .filter(path -> path.getFileName().toString().matches("task_.*\\.json"))
                    .collect(Collectors.toList());
            return taskFiles;
        }catch (Exception e){
            throw new RuntimeException("get task file error");
        }
    }

    private int getMaxTaskId() {
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:task_*.json");
        int maxIndex = 0;
        try {
            List<Path> taskFiles = getTaskFile();
            for (Path file : taskFiles) {
                String taskJson = ToolUtil.runRead(file.toString());
                Task task = OBJECT_MAPPER.readValue(taskJson, new TypeReference<Task>() {
                });
                maxIndex = Math.max(maxIndex, task.getId());
            }
        } catch (Exception e) {
            throw new RuntimeException("getMaxTaskId error, msg is " + e.getMessage());
        }
        return maxIndex;
    }

    public String createTask(String subject, String description) {
        Task task = new Task();
        task.setId(nextId.getAndAdd(1));
        task.setSubject(subject);
        task.setStatus("pending");
        task.setDescription(description == null ? "" : description);
        task.setOwner("");
        try {
            save(task);
            return OBJECT_MAPPER.writeValueAsString(task);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public String getTask(int taskId) {
        Task task = this.load(taskId);
        try {
            return OBJECT_MAPPER.writeValueAsString(task);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public String updateTask(Integer taskId, String status, Set<Integer> addBlockedBy, Set<Integer> removeBlockedBy) {
        if (taskId == null) {
            return "taskId can not be null";
        }
        Task task = this.load(taskId);
        if (status != null && STATUS_SET.contains(status)) {
            task.setStatus(status);
            if ("completed".equals(status)) {
                clearDependency(taskId);
            }
        }

        Set<Integer> blocked = task.getBlockedBy();
        if (blocked == null) {
            blocked = new HashSet<>();
            task.setBlockedBy(blocked);
        }
        if(addBlockedBy != null && !addBlockedBy.isEmpty()){
            blocked.addAll(addBlockedBy);
        }
        if(removeBlockedBy != null && !removeBlockedBy.isEmpty()){
            for (Integer blockId : removeBlockedBy) {
                blocked.remove(blockId);
            }
        }
        this.save(task);
        try {
            return OBJECT_MAPPER.writeValueAsString(task);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public String listAllTasks() {
        StringBuilder sb = new StringBuilder();
        try {
            List<Path> taskFiles = getTaskFile();
            int completedTaskSize = 0;
            for (Path file : taskFiles) {
                String taskJson = ToolUtil.runRead(file.toString());
                Task task = OBJECT_MAPPER.readValue(taskJson, new TypeReference<Task>() {
                });
                switch (task.getStatus()) {
                    case "pending":
                        sb.append("[ ]");
                        break;
                    case "in_progress":
                        sb.append("[>]");
                        break;
                    case "completed":
                        sb.append("[x]");
                        completedTaskSize++;
                        break;
                }
                sb.append(" #").append(task.getId()).append(": ").append(task.getSubject());
                String blockedBy = "";
                if(task.getBlockedBy() != null){
                    blockedBy = task.getBlockedBy().stream()
                            .map(Object::toString) // 调用对象的 toString() 方法
                            .collect(Collectors.joining(","));
                }
                sb.append("(blocked by:").append(blockedBy).append(")");
                sb.append("\n");
            }
            sb.append(completedTaskSize).append("/").append(taskFiles.size()).append(" completed");
        } catch (Exception e) {
            throw new RuntimeException("getMaxTaskId error, msg is " + e.getMessage());
        }
        System.out.println(sb.toString());
        return sb.toString();
    }

    /**
     * clear all blockedBy
     *
     * @param taskId completed taskId
     */
    private void clearDependency(int taskId) {
        try {
            List<Path> taskFiles = getTaskFile();
            for (Path file : taskFiles) {
                String taskJson = ToolUtil.runRead(file.toString());
                Task task = OBJECT_MAPPER.readValue(taskJson, new TypeReference<Task>() {
                });
                if (task.getBlockedBy() != null && task.getBlockedBy().contains(taskId)) {
                    task.getBlockedBy().remove(taskId);
                    this.save(task);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Task load(int taskId) {
        String path = this.taskDir + "/task_" + taskId + ".json";
        String s = ToolUtil.runRead(path);
        try {
            return OBJECT_MAPPER.readValue(s, new TypeReference<Task>() {
            });
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private void save(Task task) {
        System.out.println("save task begin, task_id is " + task.getId() + "; task name is " + task.getSubject());
        String path = this.taskDir + "/task_" + task.getId() + ".json";
        try {
            ToolUtil.runWrite(path, OBJECT_MAPPER.writeValueAsString(task));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    static class Task {
        private Integer id;

        private String subject;

        private String description;

        //        "pending", "in_progress", "completed"
        private String status;

        private Set<Integer> blockedBy;

        private String owner;

        public Integer getId() {
            return id;
        }

        public void setId(Integer id) {
            this.id = id;
        }

        public String getSubject() {
            return subject;
        }

        public void setSubject(String subject) {
            this.subject = subject;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Set<Integer> getBlockedBy() {
            return blockedBy;
        }

        public void setBlockedBy(Set<Integer> blockedBy) {
            this.blockedBy = blockedBy;
        }

        public String getOwner() {
            return owner;
        }

        public void setOwner(String owner) {
            this.owner = owner;
        }
    }

}
