package com.sl.docker.model;

import cn.hutool.core.util.StrUtil;

import java.io.Serializable;

/**
 * 本地镜像，对应 {@code docker images --format ...} 的一行。
 */
public class DockerImage implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;
    private String repository;
    private String tag;
    private String size;
    private String createdAt;

    /** 悬空镜像（none:none），{@code docker image prune} 的目标 */
    public boolean isDangling() {
        return "<none>".equals(repository) && "<none>".equals(tag);
    }

    /** 完整引用名，删除 / 导出时用 ID 更保险，展示与拉取用引用名 */
    public String getReference() {
        if (isDangling()) {
            return id;
        }
        String repo = StrUtil.emptyToDefault(repository, "<none>");
        String t = StrUtil.emptyToDefault(tag, "<none>");
        return repo + ":" + t;
    }

    public String getShortId() {
        if (StrUtil.isBlank(id)) {
            return "";
        }
        String plain = id.startsWith("sha256:") ? id.substring("sha256:".length()) : id;
        return plain.length() > 12 ? plain.substring(0, 12) : plain;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRepository() {
        return repository;
    }

    public void setRepository(String repository) {
        this.repository = repository;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public String getSize() {
        return size;
    }

    public void setSize(String size) {
        this.size = size;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DockerImage)) {
            return false;
        }
        DockerImage that = (DockerImage) o;
        return id != null && id.equals(that.id) && StrUtil.equals(tag, that.tag);
    }

    @Override
    public int hashCode() {
        return (id == null ? 0 : id.hashCode()) * 31 + (tag == null ? 0 : tag.hashCode());
    }

    @Override
    public String toString() {
        return getReference();
    }
}
