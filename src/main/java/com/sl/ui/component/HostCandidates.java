package com.sl.ui.component;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sl.entity.ConnectionInfo;
import com.sl.mapper.ConnectionInfoMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「目标服务器」下拉的候选清单。
 * <p>
 * 两个来源合并：数据库表 {@code connection_info}（页面上添加过的机器，带密码/私钥）与
 * 配置文件 {@code remoteServerList.conf}（部署时写死的一批机器）。按 <b>host:port</b>
 * 去重——同名同端口的以数据库那条为准，因为只有它带完整认证信息。
 * <p>
 * 抽出来是因为 Docker 管理、Compose 管理、常用 Linux 命令三个页面都要这份清单，
 * 各自抄一遍就会出现「某个页面漏读了配置文件里的机器」这类只有定时炸弹能发现的差异。
 */
public final class HostCandidates {

    private static final Logger log = LoggerFactory.getLogger(HostCandidates.class);

    private HostCandidates() {
    }

    /** 读全部候选主机（数据库优先，配置文件补齐） */
    public static List<ConnectionInfo> load(ConnectionInfoMapper mapper) {
        List<ConnectionInfo> list = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try {
            List<ConnectionInfo> fromDb = mapper.selectList(new QueryWrapper<>());
            if (fromDb != null) {
                for (ConnectionInfo info : fromDb) {
                    add(list, seen, info);
                }
            }
        } catch (Exception e) {
            log.warn("读取数据库中的服务器列表失败：{}", e.getMessage());
        }
        try {
            for (String line : com.sl.util.Util.getRemoteServerList()) {
                String[] split = line.split("=");
                if (split.length < 4) {
                    continue;
                }
                String keyPath = split.length > 4 ? split[4] : null;
                add(list, seen, new ConnectionInfo(split[0], split[3], split[1], split[2], keyPath));
            }
        } catch (Exception e) {
            log.warn("读取 remoteServerList.conf 失败：{}", e.getMessage());
        }
        return list;
    }

    private static void add(List<ConnectionInfo> list, Set<String> seen, ConnectionInfo info) {
        if (info == null || StrUtil.isBlank(info.getIdHost())) {
            return;
        }
        if (!seen.add(info.getIdHost() + ":" + portOf(info))) {
            return;
        }
        list.add(info);
    }

    public static String portOf(ConnectionInfo info) {
        return StrUtil.isBlank(info.getCdPort()) ? "22" : info.getCdPort().trim();
    }

    /** 在候选清单里找与 {@code wanted} 同 host:port 的那一条；找不到返回 null */
    public static ConnectionInfo match(List<ConnectionInfo> list, ConnectionInfo wanted) {
        if (list == null || wanted == null || StrUtil.isBlank(wanted.getIdHost())) {
            return null;
        }
        String key = wanted.getIdHost() + ":" + portOf(wanted);
        for (ConnectionInfo info : list) {
            if (key.equals(info.getIdHost() + ":" + portOf(info))) {
                return info;
            }
        }
        return null;
    }
}
