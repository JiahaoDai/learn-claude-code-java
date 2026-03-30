package com.djh.learnclaudecode.util;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TodoManager {

    private static Set<String> statusSet = new HashSet<>() {{
        add("pending");
        add("in_progress");
        add("completed");
    }};

    public static void update(List<Item> items) {
        if (items.size() >= 10) {
            throw new RuntimeException("todo list is too long");
        }
        for (int i = 0; i < items.size(); i++) {
            if (!statusSet.contains(items.get(i).getStatus())) {
                throw new RuntimeException("item status is not right");
            }
        }
        render(items);
    }

    private static void render(List<Item> items) {
        StringBuilder sb = new StringBuilder();
        int completedTaskSize = 0;
        for (Item item : items) {
            switch (item.getStatus()) {
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
            sb.append(" #").append(item.getId()).append(": ").append(item.getText()).append("\n");
        }
        sb.append(completedTaskSize).append("/").append(items.size()).append(" completed");

    }

    static class Item {

        private String id;

        private String status;

        private String text;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }
    }
}
