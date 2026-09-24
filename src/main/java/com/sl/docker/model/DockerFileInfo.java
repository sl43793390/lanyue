package com.sl.docker.model;

import java.io.Serializable;

/**
 * 容器内文件，解析自 {@code docker exec <id> ls -la <path>} 的输出。
 * <p>
 * 不用 {@code --time-style} 或 {@code find -printf}：alpine 这类镜像里是 BusyBox 的
 * {@code ls}/{@code find}，这些 GNU 扩展参数不支持，会直接报错。
 */
public class DockerFileInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    private String name;
    /** 容器内绝对路径 */
    private String path;
    /** 父目录，用于「返回上级」 */
    private String parentPath;
    /** 是不是符号链接（ls 首字符为 l） */
    private boolean symlink;
    private boolean directory;
    private String permission;
    private String owner;
    private String group;
    private String size;
    private String modifyTime;

    /** ls 首字符：-, d, l, c, b, p, s */
    public void resolveType(char typeChar) {
        this.symlink = typeChar == 'l';
        this.directory = typeChar == 'd';
    }

    public boolean isFile() {
        return !directory;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getParentPath() {
        return parentPath;
    }

    public void setParentPath(String parentPath) {
        this.parentPath = parentPath;
    }

    public boolean isSymlink() {
        return symlink;
    }

    public void setSymlink(boolean symlink) {
        this.symlink = symlink;
    }

    public boolean isDirectory() {
        return directory;
    }

    public void setDirectory(boolean directory) {
        this.directory = directory;
    }

    public String getPermission() {
        return permission;
    }

    public void setPermission(String permission) {
        this.permission = permission;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getSize() {
        return size;
    }

    public void setSize(String size) {
        this.size = size;
    }

    public String getModifyTime() {
        return modifyTime;
    }

    public void setModifyTime(String modifyTime) {
        this.modifyTime = modifyTime;
    }

    @Override
    public String toString() {
        return path;
    }
}
