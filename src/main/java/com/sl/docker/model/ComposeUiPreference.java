package com.sl.docker.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compose 页面记住的界面偏好（按登录用户各存一份）：
 * <ol>
 * <li>{@code lastHost} —— 上次连过的那台机器，下次从菜单进来默认选中它；</li>
 * <li>{@code baseDirsByHost} —— 每台机器用过的项目根目录，页面上的下拉框就是它。</li>
 * </ol>
 * 为什么不放在 VaadinSession：{@code server.servlet.session.persistent=false}，
 * 重新登录后 session 是新的，"下次进来还在"这个要求只能靠落库。
 */
public class ComposeUiPreference {

    private String lastHost;

    /** hostKey -&gt; 该主机用过的根目录（最近的在前） */
    private Map<String, List<ComposeBaseDirEntry>> baseDirsByHost =
            new LinkedHashMap<String, List<ComposeBaseDirEntry>>();

    public String getLastHost() {
        return lastHost;
    }

    public void setLastHost(String lastHost) {
        this.lastHost = lastHost;
    }

    public Map<String, List<ComposeBaseDirEntry>> getBaseDirsByHost() {
        return baseDirsByHost;
    }

    public void setBaseDirsByHost(Map<String, List<ComposeBaseDirEntry>> baseDirsByHost) {
        this.baseDirsByHost = (null == baseDirsByHost)
                ? new LinkedHashMap<String, List<ComposeBaseDirEntry>>() : baseDirsByHost;
    }

    /** 某台机器的历史根目录，没有就返回空列表（调用方不用判 null） */
    public List<ComposeBaseDirEntry> dirsOf(String hostKey) {
        List<ComposeBaseDirEntry> list = baseDirsByHost.get(hostKey);
        return null == list ? new ArrayList<ComposeBaseDirEntry>() : list;
    }

    /**
     * 记下"在某台机器上用过某个根目录"。已存在则挪到最前面并更新统计，
     * 保证下拉框里的顺序永远是「最近用过的在最上面」。
     *
     * @param hostKey      主机键（host:port）
     * @param dir          项目根目录绝对路径
     * @param projectCount 本次在该目录下数到的项目数；小于 0 表示未知，保留上次的值
     */
    public void rememberDir(String hostKey, String dir, int projectCount) {
        if (null == hostKey || null == dir) {
            return;
        }
        String key = hostKey.trim();
        String path = dir.trim();
        if (key.isEmpty() || path.isEmpty()) {
            return;
        }
        List<ComposeBaseDirEntry> list = baseDirsByHost.get(key);
        if (null == list) {
            list = new ArrayList<ComposeBaseDirEntry>();
            baseDirsByHost.put(key, list);
        }
        ComposeBaseDirEntry existing = null;
        for (ComposeBaseDirEntry entry : list) {
            if (path.equals(entry.getDir())) {
                existing = entry;
                break;
            }
        }
        if (null != existing) {
            list.remove(existing);
            if (projectCount >= 0) {
                existing.setProjectCount(projectCount);
            }
        } else {
            existing = new ComposeBaseDirEntry(path, projectCount, null);
        }
        existing.setUsedAt(cn.hutool.core.date.DateUtil.now());
        list.add(0, existing);
        while (list.size() > MAX_DIRS_PER_HOST) {
            list.remove(list.size() - 1);
        }
    }

    /** 单个主机下最多留几个目录 */
    private static final int MAX_DIRS_PER_HOST = 10;
}
