package com.sl.util;

import cn.hutool.core.util.StrUtil;
import com.sl.entity.ConnectionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SSH 共享连接池：跨页面复用「发送命令类」的 SSH 连接。
 * <p>
 * <b>背景。</b>文件管理、应用管理、指标监控、Docker、Compose、Nginx 这些页面
 * 都各自懒建一条 SSH、页面关闭时断开。同开三个标签就是三条连接，切来切去
 * 每次都要重新走 10~30 秒的建连。连接池把连接按「主机:端口:用户」收敛成一条：
 * 第一个页面建好之后放进池子，其它页面（包括同一页面的标签重开）直接取用，
 * 标签关闭不再断开。
 * <p>
 * <b>不进池的连接（各自直连，不受本池管理）：</b>
 * <ul>
 *   <li>SSH 终端 / Docker 容器终端——交互式会话，生命周期必须跟着终端走；</li>
 *   <li>远程日志搜索——长驻的输出流（tail 滚动），通道挂在连接上不能共享；</li>
 *   <li>「添加机器」的连通性验证——本来就该验证完立刻关。</li>
 * </ul>
 * <p>
 * <b>空闲回收。</b>每次 {@link #acquire} 都会刷新最后使用时间；后台回收线程每分钟
 * 扫一遍，空闲超过 {@link #IDLE_KEEP_MS}（默认 10 分钟）的连接自动断开并移出池子。
 * 需要注意的是「使用」以 acquire 为准：单个超过 10 分钟的长传输期间若没有新的
 * acquire，理论上可能被回收线程判定为空闲——现有操作（受 100MB 上传上限约束）
 * 都远短于这个时长，不为它引入引用计数。
 * <p>
 * <b>线程安全。</b>sshj 的每条 exec 命令都是独立的 session 通道，多线程并发执行
 * 命令是安全的；共享的 SFTP 客户端在 {@link SSHClientUtil#getSftpClient()} 里做了
 * 同步保护。
 */
public final class SshConnectionPool {

    private static final Logger log = LoggerFactory.getLogger(SshConnectionPool.class);

    /** 空闲保留时长：最后一次使用后默认保留 10 分钟，超时自动断开 */
    private static final long IDLE_KEEP_MS = 15 * 60 * 1000L;

    /** 回收线程的扫描间隔 */
    private static final long SWEEP_INTERVAL_SECONDS = 60;

    private static final Map<String, Entry> POOL = new ConcurrentHashMap<>();

    private static final ScheduledExecutorService REAPER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ssh-pool-reaper");
        t.setDaemon(true);
        return t;
    });

    static {
        REAPER.scheduleWithFixedDelay(SshConnectionPool::reap, SWEEP_INTERVAL_SECONDS, SWEEP_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private SshConnectionPool() {
    }

    private static final class Entry {
        final SSHClientUtil util;
        volatile long lastUsed;

        Entry(SSHClientUtil util) {
            this.util = util;
            this.lastUsed = System.currentTimeMillis();
        }

        void touch() {
            lastUsed = System.currentTimeMillis();
        }
    }

    /**
     * 取一条共享连接：池里有活的就直接复用（刷新最后使用时间），没有就新建一条放进去。
     * 连接失效（网络断开、服务端踢掉）时自动丢弃旧的并重新建连，调用方无感。
     */
    public static SSHClientUtil acquire(ConnectionInfo info) throws IOException {
        if (info == null) {
            throw new IOException("连接信息为空");
        }
        String key = keyOf(info);

        Entry entry = POOL.get(key);
        if (entry != null) {
            if (entry.util.isConnected()) {
                entry.touch();
                return entry.util;
            }
            // 池里的连接已经死了（空闲被服务端掐掉、网络抖动等）：丢弃并重连
            if (POOL.remove(key, entry)) {
                closeQuietly(entry.util);
                log.info("共享 SSH 连接已失效，准备重建：{}", key);
            }
        }

        SSHClientUtil fresh = SSHClientUtil.connect(info);
        Entry newcomer = new Entry(fresh);
        Entry raced = POOL.putIfAbsent(key, newcomer);
        if (raced == null) {
            log.info("共享 SSH 连接已建立（放入连接池）：{}", key);
            return fresh;
        }
        // 并发建连：别人抢先放进去了，用他的，自己这条用完即弃
        if (raced.util.isConnected()) {
            closeQuietly(fresh);
            raced.touch();
            return raced.util;
        }
        POOL.remove(key, raced);
        closeQuietly(raced.util);
        if (POOL.putIfAbsent(key, newcomer) != null) {
            // 极小概率：第三次并发又抢先了，放弃登记，本次调用直接用自己这条
            log.warn("共享 SSH 连接登记冲突，本次调用使用临时连接：{}", key);
        }
        return fresh;
    }

    /** 当前池内连接数（给监控/排查日志用）。 */
    public static int size() {
        return POOL.size();
    }

    /** 连接的池内键：主机:端口:用户。密码不同的同名键复用先建的连接（运维平台单密码来源，可接受）。 */
    private static String keyOf(ConnectionInfo info) {
        return info.getIdHost() + ":" + StrUtil.blankToDefault(info.getCdPort(), "22").trim()
                + ":" + StrUtil.nullToEmpty(info.getIdUser());
    }

    private static void reap() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Entry> e : POOL.entrySet()) {
            Entry entry = e.getValue();
            if (now - entry.lastUsed < IDLE_KEEP_MS) {
                continue;
            }
            if (POOL.remove(e.getKey(), entry)) {
                closeQuietly(entry.util);
                log.info("共享 SSH 连接空闲超过 {} 分钟，已自动断开：{}",
                        TimeUnit.MILLISECONDS.toMinutes(IDLE_KEEP_MS), e.getKey());
            }
        }
    }

    private static void closeQuietly(SSHClientUtil util) {
        try {
            util.closeConnection();
        } catch (Exception e) {
            log.warn("关闭共享 SSH 连接失败：{}", e.getMessage());
        }
    }
}
