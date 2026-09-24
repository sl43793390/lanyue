package com.sl.controller;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.sl.docker.DockerTerminalRegistry;
import com.sl.entity.ConnectionInfo;
import com.sl.util.SSHClientUtil;
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
 * 容器日志 / 容器交互终端的 websocket 端点。
 * <p>
 * 一个端点承担两种模式，靠 token 对应的 {@link DockerTerminalRegistry.Kind} 区分：
 * <ul>
 *   <li>{@code LOGS} —— 在 <b>SSH exec 通道（无 pty）</b>上跑 {@code docker logs -f}。
 *       不要用 pty：pty 会把 {@code \n} 转成 {@code \r\n} 并回显输入，日志里会混进
 *       一堆终端控制字符；而且日志是只读的，本来也不需要 tty。</li>
 *   <li>{@code EXEC} —— {@code docker exec -it} 必须要 tty，所以这里用
 *       <b>SSH shell 通道 + pty</b>（和 {@code SshHandler} 一样），把 docker 命令当普通
 *       输入写进去，命令后面挂 {@code ; exit}，容器里的 shell 一退出整个会话就结束，
 *       不会把用户丢到宿主机 shell 上。</li>
 * </ul>
 * 帧协议与 {@code SshHandler} 完全一致（二进制帧 = 原始字节，文本帧 = notice JSON），
 * 所以浏览器侧可以复用同一套 xterm.js 页面逻辑。
 */
@ServerEndpoint("/ws/docker")
@Component
public class DockerTermHandler {

    private static final Logger log = LoggerFactory.getLogger(DockerTermHandler.class);

    private static final int DEFAULT_COLS = 120;
    private static final int DEFAULT_ROWS = 30;
    private static final int MIN_COLS = 20;
    private static final int MAX_COLS = 500;
    private static final int MIN_ROWS = 5;
    private static final int MAX_ROWS = 200;
    private static final int CONNECT_TIMEOUT_MS = 15000;

    private static final ConcurrentHashMap<String, HandlerItem> HANDLERS =
            new ConcurrentHashMap<String, HandlerItem>();
    private static final AtomicInteger ONLINE_COUNT = new AtomicInteger(0);

    @PostConstruct
    public void init() {
        log.info("docker 终端 websocket 加载");
    }

    @OnOpen
    public void onOpen(jakarta.websocket.Session session) {
        DockerTerminalRegistry.Spec spec = DockerTerminalRegistry.resolve(getRequestParameter(session, "token"));
        if (null == spec) {
            log.warn("docker 终端连接未检测到有效凭证，sessionId={}", session.getId());
            sendNotice(session, "err", "连接失败：终端凭证已失效，请关闭该标签页后重新打开。");
            closeQuietly(session);
            return;
        }
        int cols = parseSize(getRequestParameter(session, "cols"), DEFAULT_COLS, MIN_COLS, MAX_COLS);
        int rows = parseSize(getRequestParameter(session, "rows"), DEFAULT_ROWS, MIN_ROWS, MAX_ROWS);

        HandlerItem item;
        try {
            item = new HandlerItem(session, spec, cols, rows);
        } catch (Exception e) {
            String reason = StrUtil.emptyToDefault(e.getMessage(), e.getClass().getSimpleName());
            log.error("连接容器 {} 失败：{}", spec.getContainerId(), reason);
            sendNotice(session, "err", "容器连接失败：" + reason);
            // 页面上还盖着加载遮罩，得告诉它这次没成，别一直转圈
            spec.fireFail(reason);
            closeQuietly(session);
            return;
        }

        HANDLERS.put(session.getId(), item);
        log.info("docker {} 连接加入，当前连接数：{}，sessionId={}，目标={}，容器={}",
                spec.getKind(), ONLINE_COUNT.incrementAndGet(), session.getId(),
                spec.getInfo().getIdHost(), spec.getContainerName());
        try {
            item.start();
        } catch (Exception e) {
            log.error("启动 docker 终端读线程失败：{}", e.getMessage());
            sendNotice(session, "err", "启动会话失败：" + e.getMessage());
            spec.fireFail(StrUtil.emptyToDefault(e.getMessage(), e.getClass().getSimpleName()));
            destroy(session);
        }
    }

    @OnClose
    public void onClose(jakarta.websocket.Session session) {
        if (destroy(session)) {
            log.info("docker 终端连接关闭，当前连接数：{}", ONLINE_COUNT.decrementAndGet());
        }
    }

    @OnMessage
    public void onMessage(String message, jakarta.websocket.Session session) {
        HandlerItem item = HANDLERS.get(session.getId());
        if (null == item) {
            log.warn("收到消息但没有对应的 docker 通道，忽略，sessionId={}", session.getId());
            return;
        }
        try {
            JSONObject json = JSON.parseObject(message);
            if (null == json) {
                return;
            }
            JSONObject resize = json.getJSONObject("resize");
            if (null != resize) {
                item.resize(resize.getIntValue("cols"), resize.getIntValue("rows"));
                return;
            }
            item.sendInput(json.getString("data"));
        } catch (Exception e) {
            log.warn("处理 docker 终端输入失败：{}", e.getMessage());
        }
    }

    @OnError
    public void onError(jakarta.websocket.Session session, Throwable error) {
        log.error("docker 终端 websocket 发生错误：{}，Session ID：{}", error.getMessage(), session.getId());
        destroy(session);
    }

    /**
     * 释放会话占用的资源。
     *
     * @return 该会话是否真的建立过通道
     */
    public boolean destroy(jakarta.websocket.Session session) {
        HandlerItem item = HANDLERS.remove(session.getId());
        if (null != item) {
            item.closeQuietly();
        }
        closeQuietly(session);
        return null != item;
    }

    /* ------------------------------------------------------------------ */
    /* 工具                                                                */
    /* ------------------------------------------------------------------ */

    private static int parseSize(String value, int defaultValue, int min, int max) {
        if (StrUtil.isBlank(value)) {
            return defaultValue;
        }
        try {
            return Math.min(max, Math.max(min, Integer.parseInt(value.trim())));
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
        // 读线程和 UI 线程（resize）都可能发消息，不同步会出现 IllegalStateException
        synchronized (session) {
            try {
                session.getBasicRemote().sendText(payload.toJSONString());
            } catch (Exception e) {
                log.debug("发送 websocket 消息失败：{}", e.getMessage());
            }
        }
    }

    private static void sendBinary(jakarta.websocket.Session session, byte[] buffer, int length) {
        if (null == session || !session.isOpen() || length <= 0) {
            return;
        }
        ByteBuffer payload = ByteBuffer.wrap(buffer, 0, length);
        synchronized (session) {
            try {
                session.getBasicRemote().sendBinary(payload);
            } catch (Exception e) {
                log.debug("发送终端数据失败：{}", e.getMessage());
            }
        }
    }

    private static void sendNotice(jakarta.websocket.Session session, String level, String message) {
        JSONObject payload = new JSONObject();
        payload.put("t", "notice");
        payload.put("lv", level);
        payload.put("v", message);
        send(session, payload);
    }

    /* ------------------------------------------------------------------ */
    /* 会话                                                                */
    /* ------------------------------------------------------------------ */

    private class HandlerItem implements Runnable {

        private final jakarta.websocket.Session session;
        private final DockerTerminalRegistry.Spec spec;
        private int cols;
        private int rows;

        /** LOGS 模式：sshj 的命令通道 */
        private SSHClientUtil ssh;
        private InputStream logStream;

        /** EXEC 模式：JSch 的 shell 通道（docker exec -it 需要 tty） */
        private Session jschSession;
        private ChannelShell channel;
        private OutputStream execOutput;
        private InputStream execInput;

        HandlerItem(jakarta.websocket.Session session, DockerTerminalRegistry.Spec spec, int cols, int rows)
                throws Exception {
            this.session = session;
            this.spec = spec;
            this.cols = cols;
            this.rows = rows;
            ConnectionInfo info = spec.getInfo();
            if (DockerTerminalRegistry.Kind.LOGS == spec.getKind()) {
                openLogsChannel(info);
            } else {
                // EXEC 与 COMPOSE_LOGS 都要 tty（前者为了交互，后者为了让 compose 给日志着色）
                openPtyChannel(info);
            }
        }

        private void openLogsChannel(ConnectionInfo info) throws Exception {
            this.ssh = SSHClientUtil.connect(info);
            try {
                this.logStream = ssh.openCommandStream(spec.buildLogsCommand());
            } catch (Exception e) {
                // 建通道失败就把这条 ssh 连接关掉，别留半条连接挂着
                ssh.closeConnection();
                this.ssh = null;
                throw e;
            }
            sendNotice(session, "info", "日志跟踪已开始（docker logs -f --tail " + spec.getTailLines() + "）。"
                    + "该视图只读；搜索用 Ctrl+F，导出用工具栏的「导出」。");
        }

        private void openPtyChannel(ConnectionInfo info) throws Exception {
            int port;
            try {
                port = StrUtil.isBlank(info.getCdPort()) ? 22 : Integer.parseInt(info.getCdPort().trim());
            } catch (NumberFormatException e) {
                throw new java.io.IOException("端口不是合法数字：" + info.getCdPort());
            }
            JSch jsch = new JSch();
            String keyPath = info.getCdKeyPath();
            if (StrUtil.isNotBlank(keyPath) && new File(keyPath).isFile()) {
                if (StrUtil.isBlank(info.getCdPassword())) {
                    jsch.addIdentity(keyPath);
                } else {
                    jsch.addIdentity(keyPath, info.getCdPassword());
                }
                log.info("docker pty 会话使用私钥认证：{}", keyPath);
            }
            Session sshSession = jsch.getSession(info.getIdUser(), info.getIdHost(), port);
            sshSession.setPassword(info.getCdPassword());
            sshSession.setConfig("StrictHostKeyChecking", "no");
            sshSession.connect(CONNECT_TIMEOUT_MS);
            this.jschSession = sshSession;

            this.channel = (ChannelShell) sshSession.openChannel("shell");
            try {
                this.channel.setPtyType("xterm-256color");
                this.channel.setPtySize(cols, rows, 0, 0);
            } catch (Exception e) {
                log.warn("设置 pty 尺寸失败，将使用默认 80x24：{}", e.getMessage());
            }
            this.execOutput = this.channel.getOutputStream();
            this.execInput = this.channel.getInputStream();

            if (DockerTerminalRegistry.Kind.COMPOSE_LOGS == spec.getKind()) {
                sendNotice(session, "info", "正在聚合跟踪 " + spec.getContainerName()
                        + " 的日志（docker compose logs -f，按服务着色）。该视图只读。");
            } else {
                sendNotice(session, "info", "正在进入容器 " + spec.getContainerName() + "（"
                        + spec.buildExecCommand().replace("; exit", "") + "）。退出该容器后本会话即结束。");
            }
        }

        /** 会话启动时要往 pty 里敲的那条命令；LOGS 模式没有（走的是 exec 通道） */
        private String startupCommand() {
            if (DockerTerminalRegistry.Kind.COMPOSE_LOGS == spec.getKind()) {
                return spec.buildComposeLogsCommand();
            }
            return spec.buildExecCommand();
        }

        void start() throws Exception {
            if (DockerTerminalRegistry.Kind.LOGS != spec.getKind()) {
                this.channel.connect(CONNECT_TIMEOUT_MS);
                // 把 docker 命令当成用户输入写进去；exec 那条末尾的 exit 会把整个 shell 一起收掉
                this.execOutput.write((startupCommand() + "\n").getBytes(StandardCharsets.UTF_8));
                this.execOutput.flush();
            }
            Thread thread = new Thread(this, "docker-term-" + session.getId());
            thread.setDaemon(true);
            thread.start();
            // 通道已建好、读线程已在拉数据 —— 可以撤掉页面上的加载遮罩了。
            // 放在这里而不是"收到第一段输出"时：容器里没有新日志时第一段输出可能永远不来，
            // 遮罩会一直转圈，那正是这次要修掉的体验。
            spec.fireReady();
        }

        void sendInput(String data) throws Exception {
            if (StrUtil.isEmpty(data)) {
                return;
            }
            if (DockerTerminalRegistry.Kind.EXEC != spec.getKind()) {
                // 日志是只读视图，忽略按键（页面上也不会发）
                return;
            }
            if (null == execOutput) {
                return;
            }
            execOutput.write(data.getBytes(StandardCharsets.UTF_8));
            execOutput.flush();
        }

        void resize(int newCols, int newRows) {
            if (newCols <= 0 || newRows <= 0 || (newCols == cols && newRows == rows)) {
                return;
            }
            cols = newCols;
            rows = newRows;
            if (null == channel) {
                return;
            }
            try {
                channel.setPtySize(cols, rows, 0, 0);
            } catch (Exception e) {
                log.warn("调整 pty 尺寸失败：{}", e.getMessage());
            }
        }

        @Override
        public void run() {
            InputStream in = DockerTerminalRegistry.Kind.LOGS == spec.getKind() ? logStream : execInput;
            byte[] buffer = new byte[8192];
            try {
                int len;
                while ((len = in.read(buffer)) != -1) {
                    sendBinary(session, buffer, len);
                }
            } catch (Exception e) {
                log.info("docker 终端读线程结束：{}", e.getMessage());
                sendNotice(session, "err", "会话已结束：" + e.getMessage());
            } finally {
                destroy(session);
            }
        }

        void closeQuietly() {
            if (null != logStream) {
                IoUtil.close(logStream);
                logStream = null;
            }
            if (null != ssh) {
                ssh.closeConnection();
                ssh = null;
            }
            IoUtil.close(execInput);
            IoUtil.close(execOutput);
            if (null != channel) {
                try {
                    channel.disconnect();
                } catch (Exception e) {
                    log.debug("关闭 shell 通道失败：{}", e.getMessage());
                }
                channel = null;
            }
            if (null != jschSession) {
                try {
                    jschSession.disconnect();
                } catch (Exception e) {
                    log.debug("关闭 ssh 会话失败：{}", e.getMessage());
                }
                jschSession = null;
            }
        }
    }
}
