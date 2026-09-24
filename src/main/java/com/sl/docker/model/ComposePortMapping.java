package com.sl.docker.model;

import cn.hutool.core.util.StrUtil;

import java.io.Serializable;

/**
 * 一条端口映射，用于「端口映射总览 / 冲突检查」。
 * <p>
 * 冲突分两种，处理方式完全不同，所以分开记：
 * <ul>
 *   <li>{@code inProject=true}：同一个项目里有两条规则抢同一个宿主端口 —— 必然起不来，
 *       属于配置写错了，要改 yml；</li>
 *   <li>{@code inProject=false}：宿主端口已经被本项目之外的容器占用 —— compose up 时会报
 *       {@code port is already allocated}，要去停掉那个容器或者换端口。</li>
 * </ul>
 */
public class ComposePortMapping implements Serializable {

    private static final long serialVersionUID = 1L;

    private String service = "";
    private String hostIp = "";
    private String hostPort = "";
    private String containerPort = "";
    private String protocol = "tcp";
    /** 与 docker inspect 得到的发布端口是否一致（声明了但没起来时为 false） */
    private boolean published;
    private boolean conflict;
    private boolean inProjectConflict;
    /** 冲突对象的人话描述 */
    private String conflictWith = "";

    public String getDisplay() {
        StringBuilder sb = new StringBuilder();
        if (StrUtil.isNotBlank(hostIp) && !"0.0.0.0".equals(hostIp)) {
            sb.append(hostIp).append(':');
        }
        sb.append(StrUtil.emptyToDefault(hostPort, "-")).append(" → ").append(containerPort);
        if (StrUtil.isNotBlank(protocol) && !"tcp".equals(protocol)) {
            sb.append('/').append(protocol);
        }
        return sb.toString();
    }

    public String getConflictText() {
        return conflict ? conflictWith : "—";
    }

    public String getHostPort() {
        return hostPort;
    }

    public void setHostPort(String hostPort) {
        this.hostPort = StrUtil.emptyToDefault(hostPort, "");
    }

    public String getHostIp() {
        return hostIp;
    }

    public void setHostIp(String hostIp) {
        this.hostIp = StrUtil.emptyToDefault(hostIp, "");
    }

    public String getContainerPort() {
        return containerPort;
    }

    public void setContainerPort(String containerPort) {
        this.containerPort = StrUtil.emptyToDefault(containerPort, "");
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = StrUtil.emptyToDefault(protocol, "tcp");
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = StrUtil.emptyToDefault(service, "");
    }

    public boolean isPublished() {
        return published;
    }

    public void setPublished(boolean published) {
        this.published = published;
    }

    public boolean isConflict() {
        return conflict;
    }

    public void setConflict(boolean conflict) {
        this.conflict = conflict;
    }

    public boolean isInProjectConflict() {
        return inProjectConflict;
    }

    public void setInProjectConflict(boolean inProjectConflict) {
        this.inProjectConflict = inProjectConflict;
    }

    public String getConflictWith() {
        return conflictWith;
    }

    public void setConflictWith(String conflictWith) {
        this.conflictWith = StrUtil.emptyToDefault(conflictWith, "");
    }

    @Override
    public String toString() {
        return service + " " + getDisplay();
    }
}
