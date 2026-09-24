package com.sl.docker.model;

import cn.hutool.core.util.StrUtil;

import java.io.Serializable;

/**
 * 容器信息，对应 {@code docker ps -a --format ...} 的一行。
 * <p>
 * 不解析 {@code {{.State}}}：这个占位符在部分 Docker 版本的 {@code docker ps} 上不可用，
 * 从 {@code Status} 前缀推导状态更稳（{@code Up 3 hours} / {@code Exited (0) 2 days ago} / ...）。
 */
public class DockerContainer implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String STATE_RUNNING = "running";
    public static final String STATE_PAUSED = "paused";
    public static final String STATE_STOPPED = "stopped";
    public static final String STATE_CREATED = "created";
    public static final String STATE_RESTARTING = "restarting";
    public static final String STATE_DEAD = "dead";
    public static final String STATE_UNKNOWN = "unknown";

    private String id;
    private String name;
    private String image;
    private String command;
    private String createdAt;
    private String status;
    private String ports;
    /** 从 status 推导出来的状态，见上面的常量 */
    private String state = STATE_UNKNOWN;

    public void resolveStateFromStatus() {
        String text = StrUtil.trimToEmpty(status);
        if (text.contains("(Paused)")) {
            state = STATE_PAUSED;
        } else if (text.startsWith("Up")) {
            state = STATE_RUNNING;
        } else if (text.startsWith("Exited")) {
            state = STATE_STOPPED;
        } else if (text.startsWith("Created")) {
            state = STATE_CREATED;
        } else if (text.startsWith("Restarting")) {
            state = STATE_RESTARTING;
        } else if (text.startsWith("Dead")) {
            state = STATE_DEAD;
        } else if (text.startsWith("Removal In Progress")) {
            state = STATE_DEAD;
        } else {
            state = STATE_UNKNOWN;
        }
    }

    /** 页面上显示的状态文案 */
    public String getStateLabel() {
        if (STATE_RUNNING.equals(state)) {
            return "运行中";
        }
        if (STATE_PAUSED.equals(state)) {
            return "已暂停";
        }
        if (STATE_STOPPED.equals(state)) {
            return "已停止";
        }
        if (STATE_CREATED.equals(state)) {
            return "已创建";
        }
        if (STATE_RESTARTING.equals(state)) {
            return "重启中";
        }
        if (STATE_DEAD.equals(state)) {
            return "已失效";
        }
        return "未知";
    }

    public boolean isRunning() {
        return STATE_RUNNING.equals(state);
    }

    public boolean isPaused() {
        return STATE_PAUSED.equals(state);
    }

    /** 停止 / 暂停 / 重启等操作只对运行中的容器有意义 */
    public boolean isUp() {
        return isRunning() || isPaused();
    }

    /** 12 位短 ID，就是 docker 命令普遍接受的那种写法 */
    public String getShortId() {
        if (StrUtil.isBlank(id)) {
            return "";
        }
        return id.length() > 12 ? id.substring(0, 12) : id;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPorts() {
        return ports;
    }

    public void setPorts(String ports) {
        this.ports = ports;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DockerContainer)) {
            return false;
        }
        DockerContainer that = (DockerContainer) o;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }

    @Override
    public String toString() {
        return name + "(" + getShortId() + ")";
    }
}
