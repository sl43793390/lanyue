package com.sl.docker.model;

import cn.hutool.core.util.StrUtil;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 一个 Docker Compose 项目。
 * <p>
 * 「项目」在本模块里的定义：<b>宿主机上的一个目录 + 一组有序的 compose 文件</b>。
 * 目录是 compose 的 {@code --project-directory}（也是 {@code .env} 的查找位置），
 * 文件列表就是 {@code -f base.yml -f override.yml} 的展开顺序 —— 顺序会直接影响叠加结果，
 * 所以要存下来而不是每次去目录里重扫。
 * <p>
 * 项目分两类：
 * <ul>
 *   <li>{@code managed=true}：由本系统创建，目录下有一个 {@code .logviewer-meta.json}
 *       记录名称 / 描述 / 文件顺序，可以完整地编辑、删除；</li>
 *   <li>{@code managed=false}：从 {@code docker compose ls} 发现的「外来项目」，
 *       只能看和启停，不能改文件（因为不知道人家的文件该写哪儿）。</li>
 * </ul>
 */
public class ComposeProject implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_PARTIAL = "partial";
    public static final String STATUS_STOPPED = "stopped";
    public static final String STATUS_ERROR = "error";
    /** 项目已登记但从未 up 过 */
    public static final String STATUS_NOT_CREATED = "not_created";
    public static final String STATUS_UNKNOWN = "unknown";

    /** compose 项目名（容器名前缀、卷名前缀都用它） */
    private String name;
    /** 宿主机上的项目目录，绝对路径 */
    private String directory = "";
    /** 参与叠加的 compose 文件（相对项目目录的文件名，按 -f 顺序） */
    private List<String> files = new ArrayList<String>();
    private String description = "";
    private boolean managed = true;

    private String status = STATUS_UNKNOWN;
    /** {@code docker compose ls} 给出的原始状态串，例如 {@code running(2)} */
    private String statusText = "";
    /** 配置里声明的服务数；拿不到时为 -1 */
    private int serviceCount = -1;
    private int runningServices;
    private int totalContainers;
    private int runningContainers;
    private int unhealthyContainers;
    /** 有容器处于 Exited 且退出码非 0 */
    private boolean hasFailed;

    private String createdAt = "";
    private boolean envFilePresent;

    public void resolveStatus() {
        if (totalContainers <= 0) {
            status = serviceCount > 0 ? STATUS_STOPPED : STATUS_NOT_CREATED;
            return;
        }
        if (hasFailed) {
            status = STATUS_ERROR;
            return;
        }
        if (runningContainers <= 0) {
            status = STATUS_STOPPED;
            return;
        }
        if (runningContainers < totalContainers) {
            status = STATUS_PARTIAL;
            return;
        }
        status = STATUS_RUNNING;
    }

    public String getStatusLabel() {
        if (STATUS_RUNNING.equals(status)) {
            return "运行中";
        }
        if (STATUS_PARTIAL.equals(status)) {
            return "部分运行";
        }
        if (STATUS_STOPPED.equals(status)) {
            return "已停止";
        }
        if (STATUS_ERROR.equals(status)) {
            return "异常";
        }
        if (STATUS_NOT_CREATED.equals(status)) {
            return "未启动";
        }
        return "未知";
    }

    /** 列表里给 CSS 用的样式名 */
    public String getStatusStyle() {
        if (STATUS_RUNNING.equals(status)) {
            return "docker-state-running";
        }
        if (STATUS_PARTIAL.equals(status)) {
            return "docker-state-paused";
        }
        if (STATUS_ERROR.equals(status)) {
            return "docker-state-error";
        }
        return "docker-state-stopped";
    }

    public String getServiceCountText() {
        if (serviceCount < 0) {
            return "—";
        }
        if (totalContainers <= 0) {
            return String.valueOf(serviceCount);
        }
        return runningServices + " / " + serviceCount;
    }

    public String getContainerCountText() {
        if (totalContainers <= 0) {
            return "—";
        }
        return runningContainers + " / " + totalContainers;
    }

    /** 拼 {@code -f} 参数（调用方负责加引号） */
    public List<String> getFileArgs() {
        List<String> args = new ArrayList<String>();
        for (String file : files) {
            args.add("-f");
            args.add(file);
        }
        return args;
    }

    public String getFilesText() {
        if (files.isEmpty()) {
            return "—";
        }
        return StrUtil.join(" + ", files);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDirectory() {
        return directory;
    }

    public void setDirectory(String directory) {
        this.directory = StrUtil.emptyToDefault(directory, "");
    }

    public List<String> getFiles() {
        return files;
    }

    public void setFiles(List<String> files) {
        this.files = (null == files) ? new ArrayList<String>() : files;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = StrUtil.emptyToDefault(description, "");
    }

    public boolean isManaged() {
        return managed;
    }

    public void setManaged(boolean managed) {
        this.managed = managed;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStatusText() {
        return statusText;
    }

    public void setStatusText(String statusText) {
        this.statusText = StrUtil.emptyToDefault(statusText, "");
    }

    public int getServiceCount() {
        return serviceCount;
    }

    public void setServiceCount(int serviceCount) {
        this.serviceCount = serviceCount;
    }

    public int getRunningServices() {
        return runningServices;
    }

    public void setRunningServices(int runningServices) {
        this.runningServices = runningServices;
    }

    public int getTotalContainers() {
        return totalContainers;
    }

    public void setTotalContainers(int totalContainers) {
        this.totalContainers = totalContainers;
    }

    public int getRunningContainers() {
        return runningContainers;
    }

    public void setRunningContainers(int runningContainers) {
        this.runningContainers = runningContainers;
    }

    public int getUnhealthyContainers() {
        return unhealthyContainers;
    }

    public void setUnhealthyContainers(int unhealthyContainers) {
        this.unhealthyContainers = unhealthyContainers;
    }

    public boolean isHasFailed() {
        return hasFailed;
    }

    public void setHasFailed(boolean hasFailed) {
        this.hasFailed = hasFailed;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = StrUtil.emptyToDefault(createdAt, "");
    }

    public boolean isEnvFilePresent() {
        return envFilePresent;
    }

    public void setEnvFilePresent(boolean envFilePresent) {
        this.envFilePresent = envFilePresent;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ComposeProject)) {
            return false;
        }
        ComposeProject that = (ComposeProject) o;
        return null != name && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return null == name ? 0 : name.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }
}
