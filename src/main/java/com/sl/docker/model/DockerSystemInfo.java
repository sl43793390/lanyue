package com.sl.docker.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Docker 基础系统信息：版本、API 版本、引擎信息、磁盘占用。
 */
public class DockerSystemInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    private String serverVersion;
    private String apiVersion;
    private String clientVersion;
    private String minApiVersion;
    private String osType;
    private String arch;
    private String kernelVersion;
    private String storageDriver;
    private String rootDir;
    private int containers;
    private int containersRunning;
    private int containersPaused;
    private int containersStopped;
    private int images;
    private String serverError;
    private String infoError;

    /* --- 目标主机环境（Rocky Linux / Ubuntu 的差异集中在这里） --- */
    /** 实际生效的 docker 命令前缀，可能是 {@code sudo -n /usr/bin/docker} */
    private String dockerCommand;
    /** 登录用户，形如 {@code root(uid=0)} */
    private String loginUser;
    /** /etc/os-release 里的 PRETTY_NAME */
    private String osRelease;
    /** SELinux 状态：Enforcing / Permissive / Disabled / 未安装 */
    private String selinuxMode;
    /** firewalld 状态 */
    private String firewallState;
    /** Docker Compose 版本（v2 插件或 v1 独立二进制） */
    private String composeVersion;
    /** 环境提示（需要 sudo、SELinux 要加 :z、端口映射要放行 firewalld 等） */
    private List<String> environmentHints = new ArrayList<String>();

    private List<DiskUsage> diskUsages = new ArrayList<DiskUsage>();

    /** 一条 {@code docker system df} 记录 */
    public static class DiskUsage implements Serializable {

        private static final long serialVersionUID = 1L;

        private String type;
        private String total;
        private String active;
        private String size;
        private String reclaimable;

        public DiskUsage(String type, String total, String active, String size, String reclaimable) {
            this.type = type;
            this.total = total;
            this.active = active;
            this.size = size;
            this.reclaimable = reclaimable;
        }

        public String getType() {
            return type;
        }

        public String getTotal() {
            return total;
        }

        public String getActive() {
            return active;
        }

        public String getSize() {
            return size;
        }

        public String getReclaimable() {
            return reclaimable;
        }
    }

    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Docker ").append(null == serverVersion ? "?" : serverVersion)
                .append("　API ").append(null == apiVersion ? "?" : apiVersion)
                .append("　").append(null == osType ? "" : osType)
                .append('/').append(null == arch ? "" : arch);
        return sb.toString();
    }

    public String getContainerSummary() {
        return "总计 " + containers + "　运行 " + containersRunning + "　暂停 " + containersPaused
                + "　停止 " + containersStopped;
    }

    public String getServerVersion() {
        return serverVersion;
    }

    public void setServerVersion(String serverVersion) {
        this.serverVersion = serverVersion;
    }

    public String getApiVersion() {
        return apiVersion;
    }

    public void setApiVersion(String apiVersion) {
        this.apiVersion = apiVersion;
    }

    public String getClientVersion() {
        return clientVersion;
    }

    public void setClientVersion(String clientVersion) {
        this.clientVersion = clientVersion;
    }

    public String getMinApiVersion() {
        return minApiVersion;
    }

    public void setMinApiVersion(String minApiVersion) {
        this.minApiVersion = minApiVersion;
    }

    public String getOsType() {
        return osType;
    }

    public void setOsType(String osType) {
        this.osType = osType;
    }

    public String getArch() {
        return arch;
    }

    public void setArch(String arch) {
        this.arch = arch;
    }

    public String getKernelVersion() {
        return kernelVersion;
    }

    public void setKernelVersion(String kernelVersion) {
        this.kernelVersion = kernelVersion;
    }

    public String getStorageDriver() {
        return storageDriver;
    }

    public void setStorageDriver(String storageDriver) {
        this.storageDriver = storageDriver;
    }

    public String getRootDir() {
        return rootDir;
    }

    public void setRootDir(String rootDir) {
        this.rootDir = rootDir;
    }

    public int getContainers() {
        return containers;
    }

    public void setContainers(int containers) {
        this.containers = containers;
    }

    public int getContainersRunning() {
        return containersRunning;
    }

    public void setContainersRunning(int containersRunning) {
        this.containersRunning = containersRunning;
    }

    public int getContainersPaused() {
        return containersPaused;
    }

    public void setContainersPaused(int containersPaused) {
        this.containersPaused = containersPaused;
    }

    public int getContainersStopped() {
        return containersStopped;
    }

    public void setContainersStopped(int containersStopped) {
        this.containersStopped = containersStopped;
    }

    public int getImages() {
        return images;
    }

    public void setImages(int images) {
        this.images = images;
    }

    public String getServerError() {
        return serverError;
    }

    public void setServerError(String serverError) {
        this.serverError = serverError;
    }

    public String getInfoError() {
        return infoError;
    }

    public void setInfoError(String infoError) {
        this.infoError = infoError;
    }

    public List<DiskUsage> getDiskUsages() {
        return diskUsages;
    }

    public void setDiskUsages(List<DiskUsage> diskUsages) {
        this.diskUsages = diskUsages == null ? new ArrayList<DiskUsage>() : diskUsages;
    }

    public String getDockerCommand() {
        return dockerCommand;
    }

    public void setDockerCommand(String dockerCommand) {
        this.dockerCommand = dockerCommand;
    }

    public String getLoginUser() {
        return loginUser;
    }

    public void setLoginUser(String loginUser) {
        this.loginUser = loginUser;
    }

    public String getOsRelease() {
        return osRelease;
    }

    public void setOsRelease(String osRelease) {
        this.osRelease = osRelease;
    }

    public String getSelinuxMode() {
        return selinuxMode;
    }

    public void setSelinuxMode(String selinuxMode) {
        this.selinuxMode = selinuxMode;
    }

    public String getFirewallState() {
        return firewallState;
    }

    public void setFirewallState(String firewallState) {
        this.firewallState = firewallState;
    }

    public String getComposeVersion() {
        return composeVersion;
    }

    public void setComposeVersion(String composeVersion) {
        this.composeVersion = composeVersion;
    }

    public List<String> getEnvironmentHints() {
        return environmentHints;
    }

    public void setEnvironmentHints(List<String> environmentHints) {
        this.environmentHints = environmentHints == null ? new ArrayList<String>() : environmentHints;
    }

    public void addHint(String hint) {
        if (hint != null && !hint.isEmpty()) {
            this.environmentHints.add(hint);
        }
    }

    /** SELinux 是否处于强制模式，用来决定要不要默认勾上卷的 :z 标签 */
    public boolean isSelinuxEnforcing() {
        return selinuxMode != null && selinuxMode.toLowerCase().startsWith("enforcing");
    }
}
