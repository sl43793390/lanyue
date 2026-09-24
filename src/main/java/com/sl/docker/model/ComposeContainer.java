package com.sl.docker.model;

import cn.hutool.core.util.StrUtil;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Compose 项目下的一个容器实例。
 * <p>
 * 与 {@link DockerContainer} 的区别：这里额外带上了「属于哪个项目 / 哪个服务 / 第几个副本」，
 * 以及健康检查、重启次数、退出码这三个专属于 compose 排障的字段
 * （它们靠 {@code docker ps --format} 拿不到，必须 {@code docker inspect}）。
 * <p>
 * 容器的归属不是猜的，而是直接读 docker 打在容器上的标签：
 * {@code com.docker.compose.project} / {@code .service} / {@code .container-number}。
 * 这也是为什么「按服务分组」不需要解析 compose 文件就能做对。
 */
public class ComposeContainer implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String HEALTHY = "healthy";
    public static final String UNHEALTHY = "unhealthy";
    public static final String STARTING = "starting";
    /** 配置里没有 healthcheck */
    public static final String NO_HEALTHCHECK = "none";

    private String id;
    private String name;
    private String image;
    private String status;
    private String ports;
    private String project;
    private String service = "";
    private int number;
    /** 从 status 推导，取值同 {@link DockerContainer} 里的常量 */
    private String state = DockerContainer.STATE_UNKNOWN;

    private String health = NO_HEALTHCHECK;
    private int restartCount;
    private int exitCode;
    private String startedAt = "";
    private String createdAt = "";
    private String composeConfigFile = "";
    private List<String> networks = new ArrayList<String>();

    public void resolveStateFromStatus() {
        String text = StrUtil.trimToEmpty(status);
        if (text.contains("(Paused)")) {
            state = DockerContainer.STATE_PAUSED;
        } else if (text.startsWith("Up")) {
            state = DockerContainer.STATE_RUNNING;
        } else if (text.startsWith("Exited")) {
            state = DockerContainer.STATE_STOPPED;
        } else if (text.startsWith("Created")) {
            state = DockerContainer.STATE_CREATED;
        } else if (text.startsWith("Restarting")) {
            state = DockerContainer.STATE_RESTARTING;
        } else if (text.startsWith("Dead") || text.startsWith("Removal In Progress")) {
            state = DockerContainer.STATE_DEAD;
        } else {
            state = DockerContainer.STATE_UNKNOWN;
        }
    }

    public boolean isRunning() {
        return DockerContainer.STATE_RUNNING.equals(state);
    }

    public boolean isPaused() {
        return DockerContainer.STATE_PAUSED.equals(state);
    }

    /** 停止 / 暂停 / 重启这类操作只对 Up（含暂停）的容器有意义 */
    public boolean isUp() {
        return isRunning() || isPaused();
    }

    public boolean isFailed() {
        return DockerContainer.STATE_STOPPED.equals(state) && exitCode != 0;
    }

    public String getShortId() {
        if (StrUtil.isBlank(id)) {
            return "";
        }
        return id.length() > 12 ? id.substring(0, 12) : id;
    }

    public String getStateLabel() {
        if (isRunning()) {
            return "运行中";
        }
        if (isPaused()) {
            return "已暂停";
        }
        if (DockerContainer.STATE_STOPPED.equals(state)) {
            return exitCode == 0 ? "已退出" : "已退出(" + exitCode + ")";
        }
        if (DockerContainer.STATE_RESTARTING.equals(state)) {
            return "重启中";
        }
        if (DockerContainer.STATE_DEAD.equals(state)) {
            return "已失效";
        }
        if (DockerContainer.STATE_CREATED.equals(state)) {
            return "已创建";
        }
        return "未知";
    }

    public String getHealthLabel() {
        if (HEALTHY.equals(health)) {
            return "健康";
        }
        if (UNHEALTHY.equals(health)) {
            return "不健康";
        }
        if (STARTING.equals(health)) {
            return "检测中";
        }
        return "—";
    }

    public String getStateStyle() {
        if (isRunning()) {
            return "docker-state-running";
        }
        if (isPaused()) {
            return "docker-state-paused";
        }
        if (isFailed()) {
            return "docker-state-error";
        }
        return "docker-state-stopped";
    }

    public String getHealthStyle() {
        if (HEALTHY.equals(health)) {
            return "docker-state-running";
        }
        if (UNHEALTHY.equals(health)) {
            return "docker-state-error";
        }
        if (STARTING.equals(health)) {
            return "docker-state-paused";
        }
        return "docker-state-stopped";
    }

    public String getServiceContainerLabel() {
        if (StrUtil.isBlank(service)) {
            return "—";
        }
        return number > 0 ? service + " #" + number : service;
    }

    /** 转成容器管理页用的模型，好复用「详情 / 日志 / 终端 / 监控」那一整套能力 */
    public DockerContainer toDockerContainer() {
        DockerContainer container = new DockerContainer();
        container.setId(id);
        container.setName(name);
        container.setImage(image);
        container.setStatus(status);
        container.setPorts(StrUtil.emptyToDefault(ports, ""));
        container.setCreatedAt(createdAt);
        container.resolveStateFromStatus();
        return container;
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

    public String getProject() {
        return project;
    }

    public void setProject(String project) {
        this.project = project;
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public int getNumber() {
        return number;
    }

    public void setNumber(int number) {
        this.number = number;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getHealth() {
        return health;
    }

    public void setHealth(String health) {
        this.health = health;
    }

    public int getRestartCount() {
        return restartCount;
    }

    public void setRestartCount(int restartCount) {
        this.restartCount = restartCount;
    }

    public int getExitCode() {
        return exitCode;
    }

    public void setExitCode(int exitCode) {
        this.exitCode = exitCode;
    }

    public String getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(String startedAt) {
        this.startedAt = startedAt;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getComposeConfigFile() {
        return composeConfigFile;
    }

    public void setComposeConfigFile(String composeConfigFile) {
        this.composeConfigFile = composeConfigFile;
    }

    public List<String> getNetworks() {
        return networks;
    }

    public void setNetworks(List<String> networks) {
        this.networks = networks;
    }

    @Override
    public String toString() {
        return name + "(" + getShortId() + ")";
    }
}
