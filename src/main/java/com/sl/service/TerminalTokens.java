package com.sl.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sl.entity.ConnectionInfo;

/**
 * Web 终端的「一次性连接凭证」登记表。
 * <p>
 * 背景：终端页面本身只是一个 iframe（xterm.js），真正的 ssh 通道由 websocket 端点
 * {@code SshHandler} 持有。页面打开时必须把自己要连的那台机器交给 websocket，
 * 但连接信息不能放在静态字段里直接共享 —— 那样多用户同时使用时，A 用户打开的终端
 * 会连到 B 用户选中的机器。
 * <p>
 * 做法：页面每次打开生成一个随机 token 登记到本类，iframe 建立 websocket 时把 token
 * 带在 query string 上，websocket 侧用 token 换回连接信息。
 * <p>
 * token 是绑定在浏览器页面上的随机串，<b>允许重复使用</b>（页面上的「重新连接」按钮
 * 要能用），但带有效期，且页面 detach 时会立即回收。
 * <p>
 * 这个类刻意不依赖 Vaadin：它被 websocket 端点（容器创建、非 UI 线程）和 UI 组件
 * （Vaadin 线程）同时使用，放在 UI 包里会让 websocket 侧反向依赖 UI 层。
 * 原来是 {@code com.so.component.remote.RemoteSSHXterm} 里的静态成员，迁移时剥出来。
 */
public final class TerminalTokens {

    private static final Logger log = LoggerFactory.getLogger(TerminalTokens.class);

    /** 登记表容量上限，超过就先清掉过期的，仍然超就整体清空 */
    private static final int MAX_PENDING = 500;

    /** token 有效期，6 小时 */
    private static final long TOKEN_TTL_MS = 6 * 60 * 60 * 1000L;

    /** 待用连接信息登记表：token -> 连接信息 */
    private static final Map<String, PendingEntry> PENDING_CONNECTIONS = new ConcurrentHashMap<>();

    private TerminalTokens() {
    }

    /** 一次性终端凭证：token 本身是页面级随机的，不跨用户共享 */
    private static final class PendingEntry {
        private final ConnectionInfo info;
        private volatile long expireAt;

        PendingEntry(ConnectionInfo info, long expireAt) {
            this.info = info;
            this.expireAt = expireAt;
        }
    }

    /**
     * 登记一份连接信息，返回给页面用的 token。
     *
     * @param info 目标机器的连接信息，不可为 null
     * @return 随机 token
     */
    public static String register(ConnectionInfo info) {
        if (null == info) {
            throw new IllegalArgumentException("连接信息不能为空");
        }
        purgeExpired();
        if (PENDING_CONNECTIONS.size() >= MAX_PENDING) {
            PENDING_CONNECTIONS.clear();
            log.warn("终端连接登记表超过上限，已整体清空");
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        PENDING_CONNECTIONS.put(token, new PendingEntry(info, System.currentTimeMillis() + TOKEN_TTL_MS));
        return token;
    }

    /**
     * websocket 建立时按 token 取连接信息，取到就续期。
     * <p>
     * 这里不再「取出即删」：页面上点「重新连接」、或者 iframe 被浏览器重载时，
     * 用的还是同一个 token，删掉就再也连不上了。
     *
     * @return 取不到（token 为空 / 不存在 / 已过期）返回 null
     */
    public static ConnectionInfo resolvePending(String token) {
        if (null == token || token.isEmpty()) {
            return null;
        }
        purgeExpired();
        PendingEntry entry = PENDING_CONNECTIONS.get(token);
        if (null == entry) {
            return null;
        }
        entry.expireAt = System.currentTimeMillis() + TOKEN_TTL_MS;
        return entry.info;
    }

    /** 页面销毁时回收 token。重复调用无副作用。 */
    public static void release(String token) {
        if (null != token) {
            PENDING_CONNECTIONS.remove(token);
        }
    }

    /** 当前登记表里的条目数（含未过期的）。仅用于排查。 */
    public static int size() {
        return PENDING_CONNECTIONS.size();
    }

    private static void purgeExpired() {
        long now = System.currentTimeMillis();
        PENDING_CONNECTIONS.entrySet().removeIf(e -> e.getValue().expireAt < now);
    }
}
