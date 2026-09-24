package com.sl.docker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.sl.docker.model.ComposeBaseDirEntry;
import com.sl.docker.model.ComposeUiPreference;
import com.sl.util.AppSettingStore;

import cn.hutool.core.util.StrUtil;

/**
 * Compose 页面的「上次用的服务器 / 历史项目根目录」存取。
 * <p>
 * 落库位置是 {@link AppSettingStore} 的 kv 表，键 {@code compose.pref.<登录用户>}。
 * 存的是一段纯文本行，不用 JSON —— 结构非常简单（清单 + 每行一个目录），
 * 纯文本在库里直接能看懂、也不依赖序列化库的泛型解析行为：
 * <pre>
 * lastHost=192.168.80.151:22
 * dir=192.168.80.151:22	/root/logviewer-compose	3	2026-09-23 20:50:12
 * </pre>
 * 每行结尾是"最近使用"的顺序：越靠前越新，超量的尾巴会被裁掉。
 * <p>
 * 任何读写失败都只记日志：偏好丢了只是少了个下拉项，不该影响 compose 主流程。
 */
@Service
public class ComposePreferenceStore {

    private static final Logger log = LoggerFactory.getLogger(ComposePreferenceStore.class);

    public static final String KEY_PREFIX = "compose.pref.";

    /** 最多记几台机器 / 每台机器最多记几个目录，防止无限增长 */
    private static final int MAX_HOSTS = 8;
    private static final int MAX_DIRS_PER_HOST = 10;

    private static final char SEP = '\t';

    private final AppSettingStore settings;

    @Autowired
    public ComposePreferenceStore(AppSettingStore settings) {
        this.settings = settings;
    }

    public ComposeUiPreference load(String user) {
        ComposeUiPreference pref = new ComposeUiPreference();
        String text = settings.get(key(user));
        if (StrUtil.isBlank(text)) {
            return pref;
        }
        try {
            for (String line : text.split("\n")) {
                String row = line.trim();
                if (row.startsWith("lastHost=")) {
                    pref.setLastHost(StrUtil.trimToNull(row.substring("lastHost=".length())));
                } else if (row.startsWith("dir=")) {
                    parseDirLine(pref, row.substring("dir=".length()));
                }
            }
        } catch (Exception e) {
            log.warn("解析 compose 偏好失败，按空偏好处理：{}", e.getMessage());
            return new ComposeUiPreference();
        }
        return pref;
    }

    private void parseDirLine(ComposeUiPreference pref, String body) {
        String[] parts = body.split(String.valueOf(SEP), -1);
        if (parts.length < 2 || StrUtil.isBlank(parts[0]) || StrUtil.isBlank(parts[1])) {
            return;
        }
        int count = -1;
        try {
            count = Integer.parseInt(parts[2]);
        } catch (Exception ignore) {
            // 老格式 / 脏数据：只有目录也能用
        }
        String usedAt = parts.length > 3 ? parts[3] : null;
        List<ComposeBaseDirEntry> list = pref.getBaseDirsByHost().get(parts[0]);
        if (null == list) {
            list = new ArrayList<ComposeBaseDirEntry>();
            pref.getBaseDirsByHost().put(parts[0], list);
        }
        list.add(new ComposeBaseDirEntry(parts[1], count, usedAt));
    }

    public void save(String user, ComposeUiPreference pref) {
        if (null == pref) {
            return;
        }
        settings.put(key(user), toText(pref));
    }

    private String toText(ComposeUiPreference pref) {
        StringBuilder sb = new StringBuilder();
        if (StrUtil.isNotBlank(pref.getLastHost())) {
            sb.append("lastHost=").append(clean(pref.getLastHost())).append('\n');
        }
        int hosts = 0;
        for (Map.Entry<String, List<ComposeBaseDirEntry>> entry : pref.getBaseDirsByHost().entrySet()) {
            if (hosts++ >= MAX_HOSTS) {
                break;
            }
            int dirs = 0;
            for (ComposeBaseDirEntry dir : entry.getValue()) {
                if (dirs++ >= MAX_DIRS_PER_HOST) {
                    break;
                }
                sb.append("dir=").append(clean(entry.getKey())).append(SEP)
                        .append(clean(dir.getDir())).append(SEP)
                        .append(dir.getProjectCount()).append(SEP)
                        .append(StrUtil.emptyToDefault(dir.getUsedAt(), "")).append('\n');
            }
        }
        return sb.toString();
    }

    /** 去掉分隔符与换行，避免一行数据被撕成两行读不回来 */
    private static String clean(String text) {
        if (null == text) {
            return "";
        }
        return text.replace('\n', ' ').replace('\r', ' ').replace(SEP, ' ').trim();
    }

    private static String key(String user) {
        return KEY_PREFIX + (StrUtil.isBlank(user) ? "default" : user.trim());
    }

    /** 供排查用：把某台机器的历史目录整理成一个"最近的在前"的副本 */
    public List<ComposeBaseDirEntry> dirsOf(String user, String hostKey) {
        return new ArrayList<ComposeBaseDirEntry>(load(user).dirsOf(hostKey));
    }

    /** 测试/维护用：清掉某个用户的全部 compose 偏好 */
    public void clear(String user) {
        settings.remove(key(user));
    }
}
