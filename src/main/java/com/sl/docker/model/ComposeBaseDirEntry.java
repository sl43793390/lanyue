package com.sl.docker.model;

import java.util.Objects;

/**
 * 一个「项目根目录」的历史记录。
 * <p>
 * 用户在不同目录下建过 compose 项目时，这个类就是一个可切换的候选：
 * 展示目录全路径、上次在这个目录里看到过几个项目、最后一次使用的时间。
 */
public class ComposeBaseDirEntry {

    private String dir;
    /** 上次在该目录下数到的项目数，-1 表示还没统计过 */
    private int projectCount = -1;
    /** 最后一次使用时间，文本格式 yyyy-MM-dd HH:mm:ss */
    private String usedAt;

    public ComposeBaseDirEntry() {
    }

    public ComposeBaseDirEntry(String dir, int projectCount, String usedAt) {
        this.dir = dir;
        this.projectCount = projectCount;
        this.usedAt = usedAt;
    }

    public String getDir() {
        return dir;
    }

    public void setDir(String dir) {
        this.dir = dir;
    }

    public int getProjectCount() {
        return projectCount;
    }

    public void setProjectCount(int projectCount) {
        this.projectCount = projectCount;
    }

    public String getUsedAt() {
        return usedAt;
    }

    public void setUsedAt(String usedAt) {
        this.usedAt = usedAt;
    }

    /** 下拉框里显示的文案：路径 + 上次看到的项目数 */
    public String caption() {
        if (projectCount < 0) {
            return dir;
        }
        return dir + "　（" + projectCount + " 个项目）";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (null == o || getClass() != o.getClass()) {
            return false;
        }
        ComposeBaseDirEntry that = (ComposeBaseDirEntry) o;
        return Objects.equals(dir, that.dir);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dir);
    }
}
