package com.sl.docker.model;

import cn.hutool.core.util.StrUtil;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compose 里一个服务的「声明 + 运行态」。
 * <p>
 * 声明部分全部来自 {@code docker compose config} 的输出 —— 注意是 {@code config} 之后的结果，
 * 也就是变量已经替换、{@code extends}/{@code include} 已经展开、默认值已经补齐的最终形态。
 * 直接解析用户写的原始 yml 是做不到这一点的（{@code ${VAR} 没值、{@code env_file} 没合并）。
 */
public class ComposeServiceInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_PARTIAL = "partial";
    public static final String STATUS_STOPPED = "stopped";
    public static final String STATUS_NOT_CREATED = "not_created";

    private String name;
    private String image = "";
    /** 声明了 container_name 的服务，副本数只能是 1 */
    private String containerName = "";
    private String buildContext = "";
    private String command = "";
    private String restartPolicy = "";
    private String healthcheck = "";
    /** 期望副本数（deploy.replicas，默认 1） */
    private int replicas = 1;
    /** 声明里写死了 deploy.replicas 时提示一句：此时 --scale 不生效 */
    private boolean replicasDeclared;

    private List<String> ports = new ArrayList<String>();
    private List<String> dependsOn = new ArrayList<String>();
    private List<String> networks = new ArrayList<String>();
    private List<String> volumes = new ArrayList<String>();
    private List<String> profiles = new ArrayList<String>();
    private Map<String, String> environment = new LinkedHashMap<String, String>();

    /* 运行态 */
    private int totalContainers;
    private int runningContainers;
    private int unhealthyContainers;
    private String status = STATUS_NOT_CREATED;

    public void resolveStatus() {
        if (totalContainers <= 0) {
            status = STATUS_NOT_CREATED;
            return;
        }
        if (runningContainers <= 0) {
            status = STATUS_STOPPED;
            return;
        }
        status = runningContainers >= totalContainers ? STATUS_RUNNING : STATUS_PARTIAL;
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
        return "未启动";
    }

    public String getStatusStyle() {
        if (STATUS_RUNNING.equals(status)) {
            return "docker-state-running";
        }
        if (STATUS_PARTIAL.equals(status)) {
            return "docker-state-paused";
        }
        return "docker-state-stopped";
    }

    /** 副本数展示成 {@code 2 / 3}（实际 / 期望） */
    public String getReplicaText() {
        return runningContainers + " / " + replicas;
    }

    public String getPortsText() {
        return ports.isEmpty() ? "—" : StrUtil.join(", ", ports);
    }

    public boolean isScalable() {
        return !replicasDeclared && StrUtil.isBlank(containerName);
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
        this.image = StrUtil.emptyToDefault(image, "");
    }

    public String getContainerName() {
        return containerName;
    }

    public void setContainerName(String containerName) {
        this.containerName = StrUtil.emptyToDefault(containerName, "");
    }

    public String getBuildContext() {
        return buildContext;
    }

    public void setBuildContext(String buildContext) {
        this.buildContext = StrUtil.emptyToDefault(buildContext, "");
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = StrUtil.emptyToDefault(command, "");
    }

    public String getRestartPolicy() {
        return restartPolicy;
    }

    public void setRestartPolicy(String restartPolicy) {
        this.restartPolicy = StrUtil.emptyToDefault(restartPolicy, "");
    }

    public String getHealthcheck() {
        return healthcheck;
    }

    public void setHealthcheck(String healthcheck) {
        this.healthcheck = StrUtil.emptyToDefault(healthcheck, "");
    }

    public int getReplicas() {
        return replicas;
    }

    public void setReplicas(int replicas) {
        this.replicas = replicas;
    }

    public boolean isReplicasDeclared() {
        return replicasDeclared;
    }

    public void setReplicasDeclared(boolean replicasDeclared) {
        this.replicasDeclared = replicasDeclared;
    }

    public List<String> getPorts() {
        return ports;
    }

    public void setPorts(List<String> ports) {
        this.ports = (null == ports) ? new ArrayList<String>() : ports;
    }

    public List<String> getDependsOn() {
        return dependsOn;
    }

    public void setDependsOn(List<String> dependsOn) {
        this.dependsOn = (null == dependsOn) ? new ArrayList<String>() : dependsOn;
    }

    public List<String> getNetworks() {
        return networks;
    }

    public void setNetworks(List<String> networks) {
        this.networks = (null == networks) ? new ArrayList<String>() : networks;
    }

    public List<String> getVolumes() {
        return volumes;
    }

    public void setVolumes(List<String> volumes) {
        this.volumes = (null == volumes) ? new ArrayList<String>() : volumes;
    }

    public List<String> getProfiles() {
        return profiles;
    }

    public void setProfiles(List<String> profiles) {
        this.profiles = (null == profiles) ? new ArrayList<String>() : profiles;
    }

    public Map<String, String> getEnvironment() {
        return environment;
    }

    public void setEnvironment(Map<String, String> environment) {
        this.environment = (null == environment) ? new LinkedHashMap<String, String>() : environment;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return name;
    }
}
