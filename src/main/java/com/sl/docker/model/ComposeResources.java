package com.sl.docker.model;

import cn.hutool.core.util.StrUtil;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 一个 compose 项目占用/声明的资源盘点：网络、卷、端口、磁盘。
 * <p>
 * 目的是回答「这个项目到底动了宿主机的哪些东西」，尤其是端口冲突 ——
 * 这是 compose 部署里最常撞的墙（{@code Bind for 0.0.0.0:8080 failed: port is already allocated}），
 * 与其让用户在 up 的报错里去猜，不如在部署前就把冲突标出来。
 */
public class ComposeResources implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<DockerNetwork> networks = new ArrayList<DockerNetwork>();
    private List<DockerVolume> volumes = new ArrayList<DockerVolume>();
    private List<ComposePortMapping> ports = new ArrayList<ComposePortMapping>();
    /** 项目镜像总占用（人类可读，如 1.2GB） */
    private String imageUsage = "—";
    /** 容器可写层总占用 */
    private String containerUsage = "—";
    /** 项目卷磁盘占用（需要宿主机可读 /var/lib/docker，拿不到时为「未知」） */
    private String volumeUsage = "未知";
    private List<String> notes = new ArrayList<String>();

    public int getConflictCount() {
        int count = 0;
        for (ComposePortMapping mapping : ports) {
            if (mapping.isConflict()) {
                count++;
            }
        }
        return count;
    }

    public String getDiskSummary() {
        return "镜像 " + imageUsage + "　容器可写层 " + containerUsage + "　数据卷 " + volumeUsage
                + "　网络 " + networks.size() + " 个　卷 " + volumes.size() + " 个";
    }

    public String getConflictSummary() {
        int count = getConflictCount();
        if (count == 0) {
            return "未发现端口冲突";
        }
        StringBuilder sb = new StringBuilder("发现 " + count + " 处端口冲突：");
        int i = 0;
        for (ComposePortMapping mapping : ports) {
            if (!mapping.isConflict()) {
                continue;
            }
            if (i > 0) {
                sb.append("；");
            }
            sb.append(mapping.getService()).append(' ').append(mapping.getHostPort())
                    .append("（").append(mapping.getConflictWith()).append("）");
            i++;
            if (i >= 5) {
                sb.append("…");
                break;
            }
        }
        return sb.toString();
    }

    public void addNote(String note) {
        if (StrUtil.isNotBlank(note) && !notes.contains(note)) {
            notes.add(note);
        }
    }

    public List<DockerNetwork> getNetworks() {
        return networks;
    }

    public void setNetworks(List<DockerNetwork> networks) {
        this.networks = (null == networks) ? new ArrayList<DockerNetwork>() : networks;
    }

    public List<DockerVolume> getVolumes() {
        return volumes;
    }

    public void setVolumes(List<DockerVolume> volumes) {
        this.volumes = (null == volumes) ? new ArrayList<DockerVolume>() : volumes;
    }

    public List<ComposePortMapping> getPorts() {
        return ports;
    }

    public void setPorts(List<ComposePortMapping> ports) {
        this.ports = (null == ports) ? new ArrayList<ComposePortMapping>() : ports;
    }

    public String getImageUsage() {
        return imageUsage;
    }

    public void setImageUsage(String imageUsage) {
        this.imageUsage = StrUtil.emptyToDefault(imageUsage, "—");
    }

    public String getContainerUsage() {
        return containerUsage;
    }

    public void setContainerUsage(String containerUsage) {
        this.containerUsage = StrUtil.emptyToDefault(containerUsage, "—");
    }

    public String getVolumeUsage() {
        return volumeUsage;
    }

    public void setVolumeUsage(String volumeUsage) {
        this.volumeUsage = StrUtil.emptyToDefault(volumeUsage, "未知");
    }

    public List<String> getNotes() {
        return notes;
    }

    public void setNotes(List<String> notes) {
        this.notes = (null == notes) ? new ArrayList<String>() : notes;
    }
}
