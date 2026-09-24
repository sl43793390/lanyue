package com.sl.docker.model;

import cn.hutool.core.collection.CollectionUtil;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据卷，字段来自 {@code docker volume inspect} 的 JSON。
 */
public class DockerVolume implements Serializable {

    private static final long serialVersionUID = 1L;

    private String name;
    private String driver;
    private String mountpoint;
    private String scope;
    private String createdAt;
    /** 正在使用该卷的容器名 */
    private List<String> usedBy = new ArrayList<String>();

    public String getUsedByText() {
        if (CollectionUtil.isEmpty(usedBy)) {
            return "未被使用";
        }
        StringBuilder sb = new StringBuilder();
        for (String item : usedBy) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(item);
        }
        return sb.toString();
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

    public String getMountpoint() {
        return mountpoint;
    }

    public void setMountpoint(String mountpoint) {
        this.mountpoint = mountpoint;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public List<String> getUsedBy() {
        return usedBy;
    }

    public void setUsedBy(List<String> usedBy) {
        this.usedBy = usedBy == null ? new ArrayList<String>() : usedBy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DockerVolume)) {
            return false;
        }
        DockerVolume that = (DockerVolume) o;
        return name != null && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return name == null ? 0 : name.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }
}
