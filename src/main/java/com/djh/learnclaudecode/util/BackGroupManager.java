package com.djh.learnclaudecode.util;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

public class BackGroupManager {

    private Map<String, Task> tasks;

    private Queue<Task> notificationQueue;

    private ReentrantLock lock = new ReentrantLock();

    public BackGroupManager() {
        this.tasks = new HashMap<>();
        this.notificationQueue = new LinkedList<>();
    }


    public String run(String command) {
        String taskId = UUID.randomUUID().toString().replace("-", "");

        Task task = Task.build(taskId, "running", null, command);
        this.tasks.put(taskId, task);
        new Thread(new Runnable() {
            @Override
            public void run() {
                execute(task);
            }
        }).start();
        return String.format("Backgroud task %s started: %s", taskId, command);
    }

    public void execute(Task task) {
        System.out.println("begin to run sub task ,task_id is " + task.getTaskId());
        String output = "no output";
        String status = "";
        try {
            Callable<String> callable = new Callable<>() {
                @Override
                public String call() throws Exception {
                    return ToolUtil.runBash(task.getCommand());
                }
            };
            FutureTask<String> futureTask = new FutureTask<String>(callable);

            // 3. 启动线程
            new Thread(futureTask).start();

            // 4. 获取返回值（阻塞等待）
            output = futureTask.get(300, TimeUnit.SECONDS);
            status = "completed";
        } catch (TimeoutException e) {
            output = "Error, timeout 300s";
            status = "timeout";
        } catch (Exception e) {
            output = "Error, " + e.getMessage();
            status = "error";
        }

        try {
            lock.lock();
            task.setStatus(status);
            task.setResult(output.length() > 500 ? output.substring(0, 500) : output);
            this.notificationQueue.add(task);
        } finally {
            lock.unlock();
        }
    }

    public String check(String taskId) {
        if (taskId != null && !taskId.isEmpty()) {
            if (!tasks.containsKey(taskId)) {
                return String.format("Error: Unknown task %s", taskId);
            }
            Task task = tasks.get(taskId);
            return String.format("[%s] %s\n %s", task.getStatus(), task.getCommand().length() > 60 ? task.getCommand().substring(0, 60) : task.getCommand(), task.getResult() == null ? "running" : task.getResult());
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Task> entry : tasks.entrySet()) {
            String line = String.format("%s: [%s] %s", entry.getKey(), entry.getValue().getStatus(), entry.getValue().getCommand().length() > 60 ? entry.getValue().getCommand().substring(0, 60) : entry.getValue().getCommand());
            sb.append(line).append("\n");
        }
        if (!sb.isEmpty()) {
            sb.deleteCharAt(sb.length() - 1);
        } else {
            sb.append("No background tasks.");
        }
        return sb.toString();
    }

    public List<Task> drainNotifications() {
        try {
            lock.lock();
            ArrayList<Task> list = new ArrayList<>(this.notificationQueue);
            this.notificationQueue.clear();
            return list;
        } finally {
            lock.unlock();
        }
    }


    public static class Task {

        private String taskId;

        private String status;

        private String result;

        private String command;

        public static Task build(String taskId, String status, String result, String command) {
            Task task = new Task();
            task.setCommand(command);
            task.setResult(result);
            task.setTaskId(taskId);
            task.setStatus(status);
            return task;
        }

        public String getTaskId() {
            return taskId;
        }

        public void setTaskId(String taskId) {
            this.taskId = taskId;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getResult() {
            return result;
        }

        public void setResult(String result) {
            this.result = result;
        }

        public String getCommand() {
            return command;
        }

        public void setCommand(String command) {
            this.command = command;
        }
    }

}
