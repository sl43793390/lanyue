package com.sl.docker;

import cn.hutool.core.util.StrUtil;
import com.sl.entity.ConnectionInfo;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 容器终端 / 日志的凭证登记表。
 * <p>
 * 和 {@code RemoteSSHXterm.PENDING_CONNECTIONS} 是同一套思路：页面打开时生成一个随机
 * token 把「连哪台机器、哪个容器、跑什么命令」登记进来，websocket 握手时凭 token 取。
 * <p>
 * <b>为什么不能放静态字段。</b>历史上把 {@code ConnectionInfo} 放在静态字段里共享，
 * 结果多用户同时使用时 A 用户打开的终端连到了 B 用户选中的机器。token 是页面级随机串，
 * 天然隔离用户。
 * <p>
 * token 允许重复使用（页面上"重新连接"要用同一个 token），带有效期，页面 detach 时回收。
 */
public final class DockerTerminalRegistry {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DockerTerminalRegistry.class);

    /** 登记表容量上限 */
    private static final int MAX_PENDING = 500;

    /** 通道建不起来时的回调（页面用它把加载遮罩换成失败原因） */
    public interface FailListener extends java.io.Serializable {
        void onFail(String message);
    }

    /** token 有效期，6 小时 */
    private static final long TOKEN_TTL_MS = 6 * 60 * 60 * 1000L;

    public enum Kind {
        /** docker logs -f，只读流 */
        LOGS,
        /** docker exec -it，交互终端 */
        EXEC,
        /**
         * docker compose logs -f，只读流。
         * <p>
         * 刻意走 pty（而不是像 {@link #LOGS} 那样用无 tty 的 exec 通道）：
         * compose 只有在输出目标是 TTY 时才会给每行加「服务名」前缀并着色，
         * 这正是「多个容器合并显示 + 颜色区分」要的效果。代价是输出里会带上
         * 我们敲进去的那条命令行（pty 回显），反而方便用户核对跑的是什么。
         */
        COMPOSE_LOGS
    }

    public static final class Spec {

        private final Kind kind;
        private final ConnectionInfo info;
        /** docker 命令前缀（可能是 sudo -n /usr/bin/docker） */
        private final String dockerCommand;
        private final String containerId;
        private final String containerName;
        /** LOGS 用：--tail 行数 */
        private final int tailLines;
        /** LOGS 用：是否带时间戳 */
        private final boolean timestamps;
        /** EXEC 用：容器内的 shell 路径 */
        private final String shell;

        /* ---- COMPOSE_LOGS 用 ---- */
        /** 探测到的 compose 命令（{@code docker compose} 或 {@code docker-compose}）/ 或项目目录 */
        private String projectDir = "";
        /** 参与叠加的 compose 文件（相对项目目录） */
        private java.util.List<String> composeFiles = new java.util.ArrayList<String>();
        private String projectName = "";
        private String composeService = "";

        private volatile long expireAt;

        /**
         * 通道建好之后的回调，由页面在点击「显示日志 / 连接」时注册。
         * <p>
         * 存在的理由：iframe 里的 xterm 页面要新建一条 SSH 通道才能出内容，真机上
         * 这一步十几秒起步，页面得知道"什么时候算连上了"才能把加载遮罩收掉。
         * <p>
         * 在 websocket 线程上触发，回调里必须自己切回 UI 线程。
         * 只在服务端用，不参与序列化；触发一次即清空，避免把已关闭的弹窗一直挂在登记表上。
         */
        private transient volatile Runnable readyListener;
        /** 连接失败回调，参数是可以直接展示给用户的原因 */
        private transient volatile FailListener failListener;

        public Spec(Kind kind, ConnectionInfo info, String dockerCommand, String containerId,
                    String containerName, int tailLines, boolean timestamps, String shell) {
            this.kind = kind;
            this.info = info;
            this.dockerCommand = StrUtil.emptyToDefault(dockerCommand, "docker");
            this.containerId = containerId;
            this.containerName = containerName;
            this.tailLines = tailLines;
            this.timestamps = timestamps;
            this.shell = StrUtil.emptyToDefault(shell, "/bin/sh");
            this.expireAt = System.currentTimeMillis() + TOKEN_TTL_MS;
        }

        /**
         * COMPOSE_LOGS 专用构造：{@code dockerCommand} 直接就是探测出来的 compose 命令
         * （可能是 {@code sudo -n /usr/bin/docker compose}），后面会再拼 {@code logs -f}。
         */
        public Spec(Kind kind, ConnectionInfo info, String composeCommand, String projectDir,
                    java.util.List<String> composeFiles, String projectName, String composeService,
                    int tailLines, boolean timestamps) {
            this.kind = kind;
            this.info = info;
            this.dockerCommand = StrUtil.emptyToDefault(composeCommand, "docker compose");
            this.containerId = "";
            this.containerName = StrUtil.isBlank(composeService) ? projectName : projectName + "/" + composeService;
            this.tailLines = tailLines;
            this.timestamps = timestamps;
            this.shell = "/bin/sh";
            this.projectDir = StrUtil.emptyToDefault(projectDir, "");
            this.composeFiles = (null == composeFiles) ? new java.util.ArrayList<String>() : composeFiles;
            this.projectName = StrUtil.emptyToDefault(projectName, "");
            this.composeService = StrUtil.emptyToDefault(composeService, "");
            this.expireAt = System.currentTimeMillis() + TOKEN_TTL_MS;
        }

        public Kind getKind() {
            return kind;
        }

        public ConnectionInfo getInfo() {
            return info;
        }

        public String getContainerId() {
            return containerId;
        }

        public String getContainerName() {
            return containerName;
        }

        public int getTailLines() {
            return tailLines;
        }

        public boolean isTimestamps() {
            return timestamps;
        }

        public String getShell() {
            return shell;
        }

        /** 只读日志流要跑的完整命令 */
        public String buildLogsCommand() {
            StringBuilder sb = new StringBuilder(dockerCommand).append(" logs -f");
            if (tailLines >= 0) {
                sb.append(" --tail ").append(tailLines);
            }
            if (timestamps) {
                sb.append(" --timestamps");
            }
            sb.append(' ').append(DockerExecutor.q(containerId)).append(" 2>&1");
            return sb.toString();
        }

        /**
         * 交互终端要执行的命令。
         * <p>
         * 末尾额外挂一个 {@code exit}：容器里的 shell 退出（或被 Ctrl+C 打断）之后，
         * 会话直接结束，而不是把用户**丢到宿主机的 shell 里** —— 那等于绕过了所有
         * 菜单权限，变成一台可以随便敲命令的堡垒机。
         */
        public String buildExecCommand() {
            return dockerCommand + " exec -it " + DockerExecutor.q(containerId) + " " + DockerExecutor.q(shell)
                    + "; exit";
        }

        public String getProjectDir() {
            return projectDir;
        }

        public String getProjectName() {
            return projectName;
        }

        public String getComposeService() {
            return composeService;
        }

        public java.util.List<String> getComposeFiles() {
            return composeFiles;
        }

        /* ---- 加载状态回调（页面用，见 readyListener 的注释） ---- */

        public void setReadyListener(Runnable listener) {
            this.readyListener = listener;
        }

        public void setFailListener(FailListener listener) {
            this.failListener = listener;
        }

        /** 通道就绪，页面可以撤掉加载遮罩了。重复调用只生效一次 */
        public void fireReady() {
            Runnable listener = readyListener;
            readyListener = null;
            failListener = null;
            if (null == listener) {
                return;
            }
            try {
                listener.run();
            } catch (RuntimeException e) {
                log.warn("docker 通道就绪回调出错：{}", e.getMessage());
            }
        }

        /** 通道没建起来，把原因交给页面显示（比一直转圈强） */
        public void fireFail(String message) {
            FailListener listener = failListener;
            readyListener = null;
            failListener = null;
            if (null == listener) {
                return;
            }
            try {
                listener.onFail(message);
            } catch (RuntimeException e) {
                log.warn("docker 通道失败回调出错：{}", e.getMessage());
            }
        }

        /**
         * 项目聚合日志命令。
         * <p>
         * 先 {@code cd} 进项目目录：{@code -f} 的相对路径在 v1/v2 里都按「当前工作目录」解析，
         * 只有 cd 进去才是两边一致的行为；不用 {@code --project-directory}，因为那对
         * {@code -f} 不起作用（踩过：项目起了但日志命令报 no configuration file provided）。
         */
        public String buildComposeLogsCommand() {
            StringBuilder sb = new StringBuilder();
            if (StrUtil.isNotBlank(projectDir)) {
                sb.append("cd ").append(DockerExecutor.q(projectDir)).append(" && ");
            }
            sb.append(dockerCommand);
            if (StrUtil.isNotBlank(projectName)) {
                sb.append(" -p ").append(DockerExecutor.q(projectName));
            }
            for (String file : composeFiles) {
                if (StrUtil.isNotBlank(file)) {
                    sb.append(" -f ").append(DockerExecutor.q(file));
                }
            }
            sb.append(" logs -f");
            if (tailLines >= 0) {
                sb.append(" --tail ").append(tailLines);
            }
            if (timestamps) {
                sb.append(" --timestamps");
            }
            if (StrUtil.isNotBlank(composeService)) {
                sb.append(' ').append(DockerExecutor.q(composeService));
            }
            return sb.toString();
        }
    }

    private static final Map<String, Spec> PENDING = new ConcurrentHashMap<String, Spec>();

    private DockerTerminalRegistry() {
    }

    public static String register(Spec spec) {
        purgeExpired();
        if (PENDING.size() >= MAX_PENDING) {
            PENDING.clear();
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        PENDING.put(token, spec);
        return token;
    }

    /**
     * 按 token 取登记信息，取到就续期。
     * <p>
     * 不做"取出即删"：页面上点"重新连接"、或者 iframe 被浏览器重载时用的还是同一个 token，
     * 删掉就再也连不上了。
     */
    public static Spec resolve(String token) {
        if (StrUtil.isBlank(token)) {
            return null;
        }
        purgeExpired();
        Spec spec = PENDING.get(token);
        if (null == spec) {
            return null;
        }
        spec.expireAt = System.currentTimeMillis() + TOKEN_TTL_MS;
        return spec;
    }

    public static void release(String token) {
        if (StrUtil.isNotBlank(token)) {
            PENDING.remove(token);
        }
    }

    private static void purgeExpired() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Spec> entry : PENDING.entrySet()) {
            if (entry.getValue().expireAt < now) {
                PENDING.remove(entry.getKey());
            }
        }
    }
}
