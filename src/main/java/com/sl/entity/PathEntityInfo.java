package com.sl.entity;

/**
 * 文件列表里的一行（本地日志搜索、本地文件管理、远程文件管理共用）。
 * <p>
 * 与旧项目 logviewer 的区别：旧类里挂着 {@code com.vaadin.ui.Button} 和
 * {@code com.vaadin.ui.AbstractLayout} 两个字段，即 DTO 里直接持有 UI 组件——
 * 那是为了在 Vaadin 8 的 {@code Grid.addComponentColumn} 里把已建好的按钮对象带出来。
 * <p>
 * Vaadin 24 的 {@code addComponentColumn} 是一个「行数据 -&gt; 组件」的纯函数，
 * 组件在渲染时才生成，根本不需要提前塞进数据对象。所以这里只留数据字段，
 * 这个类也就能脱离 UI 层、放在 {@code entity} 包里被后端逻辑复用。
 */
public class PathEntityInfo implements Comparable<PathEntityInfo> {

    /** 所在目录 */
    private String parentPath;

    /** 文件名（含后缀） */
    private String fileName;

    /** 完整路径，读取与下载都用它 */
    private String absolutePath;

    /** 已格式化的大小文本，如 {@code 12.34KB} */
    private String fileSize;

    /** 文件后缀，决定用哪种方式预览 */
    private String suffix;

    /** 最后修改时间，格式 {@code yyyy-MM-dd HH:mm:ss} */
    private String createDate;

    public String getParentPath() {
        return parentPath;
    }

    public void setParentPath(String parentPath) {
        this.parentPath = parentPath;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getAbsolutePath() {
        return absolutePath;
    }

    public void setAbsolutePath(String absolutePath) {
        this.absolutePath = absolutePath;
    }

    public String getFileSize() {
        return fileSize;
    }

    public void setFileSize(String fileSize) {
        this.fileSize = fileSize;
    }

    public String getSuffix() {
        return suffix;
    }

    public void setSuffix(String suffix) {
        this.suffix = suffix;
    }

    public String getCreateDate() {
        return createDate;
    }

    public void setCreateDate(String createDate) {
        this.createDate = createDate;
    }

    /**
     * 按修改时间排序。{@code createDate} 是 {@code yyyy-MM-dd HH:mm:ss} 格式，
     * 字典序与时序一致，可以直接比较。
     */
    @Override
    public int compareTo(PathEntityInfo other) {
        if (createDate == null) {
            return other.createDate == null ? 0 : -1;
        }
        if (other.createDate == null) {
            return 1;
        }
        return createDate.compareTo(other.createDate);
    }

    @Override
    public String toString() {
        return "PathEntityInfo [parentPath=" + parentPath + ", fileName=" + fileName
                + ", absolutePath=" + absolutePath + ", fileSize=" + fileSize
                + ", suffix=" + suffix + ", createDate=" + createDate + "]";
    }
}
