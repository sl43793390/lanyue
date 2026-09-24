package com.sl.controller;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.sl.service.TerminalTokens;
import com.sl.entity.ConnectionInfo;
import com.sl.entity.SshModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.server.ServerEndpoint;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Web 终端（xterm.js）的 websocket 端点。
 * <p>
 * 服务端 -> 浏览器分两种帧：
 * <pre>
 * 二进制帧：远端 pty 的原始字节，浏览器侧直接交给 xterm.js 的 write(Uint8Array)
 * 文本帧：  {"t":"notice","lv":"info|err","v":"提示文字"}  控制/提示消息
 * </pre>
 * 终端数据不再套 JSON 文本：pty 输出本来就是字节流，套成字符串要在服务端先按 UTF-8
 * 解码、到浏览器再编码回去，白跑两趟；而且一个多字节汉字被读取块切断时，
 * 交给 xterm.js 的流式解码器比服务端自己拼更稳妥，它还要处理 ESC ( 0 这类字符集切换。
 * <p>
 * 浏览器 -> 服务端仍然统一是文本帧 JSON：
 * <pre>
 * {"data":"按键内容"}
 * {"resize":{"cols":120,"rows":30}}
 * </pre>
 */
@ServerEndpoint("/ws/ssh")
@Component
public class SshHandler {

    private static final Logger log = LoggerFactory.getLogger(SshHandler.class);

    private static final int DEFAULT_COLS = 80;
    private static final int DEFAULT_ROWS = 24;
    private static final int MIN_COLS = 20;
    private static final int MAX_COLS = 500;
    private static final int MIN_ROWS = 5;
    private static final int MAX_ROWS = 200;
    /** 建链超时，避免 ip 通了但端口被防火墙丢弃时线程无限挂住 */
    private static final int CONNECT_TIMEOUT_MS = 15000;

    private static final ConcurrentHashMap<String, HandlerItem> HANDLER_ITEM_CONCURRENT_HASH_MAP = new ConcurrentHashMap<String, HandlerItem>();
    private static final AtomicInteger ONLINE_COUNT = new AtomicInteger(0);

    @PostConstruct
    public void init() {
        log.info("websocket 加载");
    }

    /**
     * 连接建立成功调用的方法
     */
    @OnOpen
    public void onOpen(jakarta.websocket.Session session) {
        ConnectionInfo addr = TerminalTokens.resolvePending(getRequestParameter(session, "token"));
        if (null == addr) {
            log.warn("有连接加入，未检测到连接信息，sessionId={}", session.getId());
            sendNotice(session, "err", "连接失败：终端连接信息已失效，请关闭该标签页后重新打开。");
            closeQuietly(session);
            return;
        }
        int cols = parseSize(getRequestParameter(session, "cols"), DEFAULT_COLS, MIN_COLS, MAX_COLS);
        int rows = parseSize(getRequestParameter(session, "rows"), DEFAULT_ROWS, MIN_ROWS, MAX_ROWS);

        HandlerItem item;
        try {
            item = new HandlerItem(session, addr, cols, rows);
        } catch (Exception e) {
            // 原来这里直接抛异常，浏览器只看到连接被断开（1006），完全不知道为什么失败
            String reason = StrUtil.emptyToDefault(e.getMessage(), e.getClass().getSimpleName());
            log.error("连接 {} 失败：{}", addr.getIdHost(), reason);
            sendNotice(session, "err", "SSH 连接失败：" + reason);
            closeQuietly(session);
            return;
        }

        HANDLER_ITEM_CONCURRENT_HASH_MAP.put(session.getId(), item);
        log.info("有连接加入，当前连接数为：{},sessionId={},目标={},pty={}x{}",
                ONLINE_COUNT.incrementAndGet(), session.getId(), addr.getIdHost(), cols, rows);
        try {
            item.startRead();
        } catch (Exception e) {
            log.error("启动终端读线程失败：{}", e.getMessage());
            sendNotice(session, "err", "启动终端失败：" + e.getMessage());
            destroy(session);
        }
    }

    private static int parseSize(String value, int defaultValue, int min, int max) {
        if (StrUtil.isBlank(value)) {
            return defaultValue;
        }
        try {
            int v = Integer.parseInt(value.trim());
            return Math.min(max, Math.max(min, v));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String getRequestParameter(jakarta.websocket.Session session, String name) {
        try {
            Map<String, List<String>> params = session.getRequestParameterMap();
            if (null != params) {
                List<String> values = params.get(name);
                if (null != values && !values.isEmpty()) {
                    return values.get(0);
                }
            }
        } catch (Exception e) {
            log.warn("读取 websocket 请求参数失败：{}", e.getMessage());
        }
        // 个别容器不会填充参数表，退化为手工解析 query string
        try {
            String query = session.getQueryString();
            if (StrUtil.isNotBlank(query)) {
                for (String pair : query.split("&")) {
                    int index = pair.indexOf('=');
                    if (index > 0 && name.equals(pair.substring(0, index))) {
                        return URLDecoder.decode(pair.substring(index + 1), "UTF-8");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析 websocket query string 失败：{}", e.getMessage());
        }
        return null;
    }

    /**
     * 连接关闭调用的方法
     */
    @OnClose
    public void onClose(jakarta.websocket.Session session) {
        // 释放该连接占用的 ssh 通道，否则每次开关标签页都会残留一个 ssh 会话
        boolean hadHandler = destroy(session);
        // 只有真正建立过 ssh 通道的连接才计入过在线数
        if (hadHandler) {
            log.info("有连接关闭，当前连接数为：{}", ONLINE_COUNT.decrementAndGet());
        }
    }

    /**
     * 收到客户端消息后调用的方法
     */
    @OnMessage
    public void onMessage(String message, jakarta.websocket.Session session) {
        HandlerItem handlerItem = HANDLER_ITEM_CONCURRENT_HASH_MAP.get(session.getId());
        if (null == handlerItem) {
            log.warn("收到消息但没有对应的 ssh 通道，忽略，sessionId={}", session.getId());
            return;
        }
        try {
            JSONObject json = JSON.parseObject(message);
            if (null == json) {
                return;
            }
            JSONObject resize = json.getJSONObject("resize");
            if (null != resize) {
                handlerItem.resize(resize.getIntValue("cols"), resize.getIntValue("rows"));
                return;
            }
            handlerItem.sendInput(json.getString("data"));
        } catch (Exception e) {
            log.warn("处理终端输入失败：{}", e.getMessage());
        }
    }

    /**
     * 出现错误
     */
    @OnError
    public void onError(jakarta.websocket.Session session, Throwable error) {
        log.error("websocket 发生错误：{}，Session ID： {}", error.getMessage(), session.getId());
        destroy(session);
    }

    /**
     * 释放某个 websocket 会话占用的资源。
     *
     * @return 该会话是否真的建立过 ssh 通道
     */
    public boolean destroy(jakarta.websocket.Session session) {
        HandlerItem handlerItem = HANDLER_ITEM_CONCURRENT_HASH_MAP.remove(session.getId());
        if (handlerItem != null) {
            IoUtil.close(handlerItem.inputStream);
            IoUtil.close(handlerItem.outputStream);
            try {
                handlerItem.channel.disconnect();
            } catch (Exception e) {
                log.debug("关闭 shell 通道失败：{}", e.getMessage());
            }
            try {
                handlerItem.openSession.disconnect();
            } catch (Exception e) {
                log.debug("关闭 ssh 会话失败：{}", e.getMessage());
            }
        }
        closeQuietly(session);
        return handlerItem != null;
    }

    private static void closeQuietly(jakarta.websocket.Session session) {
        try {
            if (null != session && session.isOpen()) {
                session.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "closed"));
            }
        } catch (Exception e) {
            log.debug("关闭 websocket 会话失败：{}", e.getMessage());
        }
    }

    private static void send(jakarta.websocket.Session session, JSONObject payload) {
        if (null == session || !session.isOpen()) {
            return;
        }
        // 读线程和 UI 线程（resize）都会发消息，不同步会出现 IllegalStateException
        synchronized (session) {
            try {
                session.getBasicRemote().sendText(payload.toJSONString());
            } catch (Exception e) {
                log.debug("发送 websocket 消息失败：{}", e.getMessage());
            }
        }
    }

    /**
     * 发送终端数据。走二进制帧，浏览器侧按 Uint8Array 直接写进 xterm.js。
     * <p>
     * buffer 是读线程复用的数组，这里用 getBasicRemote()（同步阻塞写），
     * 方法返回时数据已经交给容器，所以调用方可以安全地接着复用它。
     */
    static void sendBinary(jakarta.websocket.Session session, byte[] buffer, int length) {
        if (null == session || !session.isOpen() || length <= 0) {
            return;
        }
        ByteBuffer payload = ByteBuffer.wrap(buffer, 0, length);
        // 读线程和 UI 线程（resize 提示）都会发消息，不同步会出现 IllegalStateException
        synchronized (session) {
            try {
                session.getBasicRemote().sendBinary(payload);
            } catch (Exception e) {
                log.debug("发送终端数据失败：{}", e.getMessage());
            }
        }
    }

    static void sendNotice(jakarta.websocket.Session session, String level, String message) {
        JSONObject payload = new JSONObject();
        payload.put("t", "notice");
        payload.put("lv", level);
        payload.put("v", message);
        send(session, payload);
    }

    private class HandlerItem implements Runnable {

        private final jakarta.websocket.Session session;
        private final InputStream inputStream;
        private final OutputStream outputStream;
        private final Session openSession;
        private final ChannelShell channel;
        private final SshModel sshItem;
        private final StringBuilder nowLineInput = new StringBuilder();
        private int cols;
        private int rows;

        HandlerItem(jakarta.websocket.Session session, ConnectionInfo addr, int cols, int rows) throws Exception {
            this.session = session;
            this.cols = cols;
            this.rows = rows;
            this.sshItem = new SshModel();
            this.sshItem.setHost(addr.getIdHost());
            this.sshItem.setUser(addr.getIdUser());
            this.sshItem.setPassword(addr.getCdPassword());
            this.sshItem.setCharset("UTF-8");

            int port;
            try {
                port = StrUtil.isBlank(addr.getCdPort()) ? 22 : Integer.parseInt(addr.getCdPort().trim());
            } catch (NumberFormatException e) {
                throw new IOException("端口不是合法数字：" + addr.getCdPort());
            }
            this.sshItem.setPort(port);

            JSch jsch = new JSch();
            String keyPath = addr.getCdKeyPath();
            if (StrUtil.isNotBlank(keyPath)) {
                File keyFile = new File(keyPath);
                if (keyFile.isFile()) {
                    // 私钥口令沿用连接信息里的密码字段（与其它页面保持一致）
                    if (StrUtil.isBlank(addr.getCdPassword())) {
                        jsch.addIdentity(keyPath);
                    } else {
                        jsch.addIdentity(keyPath, addr.getCdPassword());
                    }
                    log.info("终端使用私钥认证：{}", keyPath);
                } else {
                    log.warn("终端配置的私钥不存在，改用密码认证：{}", keyPath);
                }
            }

            Session sshSession = jsch.getSession(addr.getIdUser(), addr.getIdHost(), port);
            sshSession.setPassword(addr.getCdPassword());
            sshSession.setConfig("StrictHostKeyChecking", "no");
            // 原来 session.connect() 不带超时，端口被防火墙 DROP 时线程会一直挂着
            sshSession.connect(CONNECT_TIMEOUT_MS);
            this.openSession = sshSession;

            this.channel = (ChannelShell) sshSession.openChannel("shell");
            try {
                // xterm-256color 比 vt100 更贴近真实终端：readline 的行编辑序列更完整
                this.channel.setPtyType("xterm-256color");
                this.channel.setPtySize(cols, rows, 0, 0);
            } catch (Exception e) {
                log.warn("设置 pty 尺寸失败，将使用默认 80x24：{}", e.getMessage());
            }
            this.inputStream = this.channel.getInputStream();
            this.outputStream = this.channel.getOutputStream();
        }

        void startRead() throws Exception {
            this.channel.connect(CONNECT_TIMEOUT_MS);
            // 用独立守护线程而不是公共线程池：读循环是整个会话生命周期的长任务，
            // 放进 ThreadUtil 的池里会长期占用池线程
            Thread thread = new Thread(this, "ssh-terminal-" + session.getId());
            thread.setDaemon(true);
            thread.start();
        }

        void sendInput(String data) throws IOException {
            if (StrUtil.isEmpty(data)) {
                return;
            }
            if (!isAllowed(data)) {
                // 先把 shell 里已经敲进去的内容清掉（Ctrl+U），再提示，避免用户看着半截命令不知道发生了什么
                outputStream.write(new byte[]{0x15});
                outputStream.flush();
                sendNotice(session, "err", "该命令已被禁止执行");
                return;
            }
            outputStream.write(data.getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        }

        /**
         * 维护"当前正在输入的这一行"，用于命令黑白名单校验。
         * <p>
         * 原来这里是拿 websocket 收到的原始 JSON 报文（{@code {"data":"l"}}）去校验的，
         * 校验永远匹配不上，等于形同虚设。
         */
        private boolean isAllowed(String data) {
            if (data.length() == 1 && data.charAt(0) == 0x7f) {
                // 退格
                int length = nowLineInput.length();
                if (length > 0) {
                    nowLineInput.delete(length - 1, length);
                }
            } else if (nowLineInput.length() < 4096) {
                nowLineInput.append(data);
            }
            String line = nowLineInput.toString();
            if (line.indexOf('\r') >= 0) {
                nowLineInput.setLength(0);
            }
            return SshModel.checkInputItem(sshItem, line);
        }

        void resize(int newCols, int newRows) {
            if (newCols <= 0 || newRows <= 0 || (newCols == cols && newRows == rows)) {
                return;
            }
            cols = newCols;
            rows = newRows;
            try {
                // 已连接后调用会走 SSH_MSG_CHANNEL_REQUEST(window-change)
                channel.setPtySize(cols, rows, 0, 0);
                log.debug("终端尺寸调整为 {}x{}", cols, rows);
            } catch (Exception e) {
                log.warn("调整 pty 尺寸失败：{}", e.getMessage());
            }
        }

        @Override
        public void run() {
            // 原样转发字节，不在服务端解码：xterm.js 的解码器是流式的，
            // 跨读取块被切断的 UTF-8 多字节序列由它拼接，比服务端先解码再编回字符串更准
            byte[] buffer = new byte[8192];
            try {
                int len;
                while ((len = inputStream.read(buffer)) != -1) {
                    sendBinary(session, buffer, len);
                }
            } catch (Exception e) {
                if (openSession.isConnected()) {
                    log.warn("终端读线程异常结束：{}", e.getMessage());
                    sendNotice(session, "err", "终端连接中断：" + e.getMessage());
                }
            } finally {
                destroy(session);
            }
        }
    }
}
