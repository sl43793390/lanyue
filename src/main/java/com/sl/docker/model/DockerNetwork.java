package com.sl.docker.model;

import cn.hutool.core.collection.CollectionUtil;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Docker 网络，基础字段来自 {@code docker network ls}，
 * 子网 / 网关 / 内容器来自 {@code docker network inspect} 的 JSON。
 */
public class DockerNetwork implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;
    private String name;
    private String driver;
    private String scope;
    private String subnet;
    private String gateway;
    private String internal;
    /** 已接入该网络的容器，形如 {@code nginx(1a2b3c4d)} */
    private List<String> containers = new ArrayList<String>();

    public String getShortId() {
        if (id == null) {
            return "";
        }
        return id.length() > 12 ? id.substring(0, 12) : id;
    }

    public String getContainersText() {
        if (CollectionUtil.isEmpty(containers)) {
            return "无容器接入";
        }
        StringBuilder sb = new StringBuilder();
        for (String item : containers) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(item);
        }
        return sb.toString();
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

    public String getDriver() {
        return driver;
    }

    public void setDriver(String driver) {
        this.driver = driver;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getSubnet() {
        return subnet;
    }

    public void setSubnet(String subnet) {
        this.subnet = subnet;
    }

    public String getGateway() {
        return gateway;
    }

    public void setGateway(String gateway) {
        this.gateway = gateway;
    }

    public String getInternal() {
        return internal;
    }

    public void setInternal(String internal) {
        this.internal = internal;
    }

    public List<String> getContainers() {
        return containers;
    }

    public void setContainers(List<String> containers) {
        this.containers = containers == null ? new ArrayList<String>() : containers;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DockerNetwork)) {
            return false;
        }
        DockerNetwork that = (DockerNetwork) o;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }
}
