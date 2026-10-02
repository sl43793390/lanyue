package com.sl.docker;

import cn.hutool.core.util.StrUtil;
import com.sl.docker.model.DockerDaemonStatus;
import com.sl.entity.ConnectionInfo;
import com.sl.util.SSHClientUtil;
import com.sl.util.SshCommandRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Docker 命令执行器。
 * <p>
 * <b>为什么走 SSH + docker CLI，而不是 Docker Remote API。</b>
 * 内网机房的 docker daemon 基本只监听 unix socket，要开放 2375/2376 得改
 * {@code daemon.json} 并重启 docker，等于为了一个管理页面把 daemon 暴露到网络上；
 * 而这个项目本来就已经建好了 SSH 连接体系（{@link ConnectionInfo} / {@link SSHClientUtil}），
 * 目标机器一定有 SSH。所以这里统一用 SSH 通道跑 {@code docker} 命令。
 * <p>
 * <b>stderr 的处理。</b>sshj 的 {@code Session.Command} 把 stdout 和 stderr 分成两个流，
 * {@code SSHClientUtil.executeCommand} 只读 stdout，失败原因全在 stderr 里会直接丢掉
 * （典型表现：拉取镜像失败却显示"成功"）。所以这里统一在命令末尾补 {@code 2>&1}，
 * 并用 {@code echo} 把退出码带回来，失败时把真实输出抛给调用方。
 * <p>
 * <b>daemon 不在就不要再发命令。</b>目标机上 docker 没启动时，每条 docker 命令都会
 * 走完一次 SSH 往返再报 {@code Cannot connect to the Docker daemon}。页面上的失败回调
 * 又会触发重新加载，于是变成"每 60ms 往目标机砸一条命令"的死循环（见 2026-09-22 的日志）。
 * 现在所有 docker 命令入口都先过 {@link #ensureDaemonReady()}：daemon 状态由
 * {@link #probeDaemon(boolean)} 探测并缓存，判定为不可用时直接抛
 * {@link DockerDaemonDownException}，<b>一个字节都不会发到目标机</b>；
 * 界面拿到这个异常就弹窗问用户要不要把服务拉起来（{@link #startDaemon()}）。
 * <p>
 * <b>线程安全。</b>{@code SSHClientUtil.executeCommand} 每次会新开一个 channel，
 * 但同一个 SSHClient 上并发建 channel 的时序不好保证，串行由
 * {@link SshCommandRunner} 内部那把锁统一负责（docker CLI 的操作本来就是毫秒级，
 * 串行不会成为瓶颈）。
 * <p>
 * <b>连接失效自动重连。</b>底层连接走共享池，空闲 15 分钟会被回收；执行器不再自己
 * 存连接引用，改由 {@link SshCommandRunner} 在每次发命令前确认连接、必要时重建，
 * 所以页面开一整天也不会出现「点了刷新报 SSH 连接未建立」。界面想知道发生过重连，
 * 用 {@link #consumeReconnectNotice()}。
 */
public class DockerExecutor implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(DockerExecutor.class);

    /** daemon 判定为"在跑"时的缓存时长：期间不再重复探测 */
    private static final long DAEMON_CACHE_OK_MS = 30000L;
    /**
     * daemon 判定为"不可用"时的缓存时长。
     * 故意比"在跑"短：用户可能在别的窗口把 docker 起起来了，刷新一下就该看到好状态；
     * 同时又足够长，不会因为界面上的连续动作把命令刷成风暴。
     */
    private static final long DAEMON_CACHE_DOWN_MS = 10000L;

    /** 启动服务后等待 daemon 就绪的总时长 */
    private static final long DAEMON_START_WAIT_MS = 20000L;
    /** 等待 daemon 就绪的轮询间隔 */
    private static final long DAEMON_START_POLL_MS = 2000L;

    /**
     * daemon / 服务管理方式的探测脚本。
     * <p>
     * 一条命令把需要的东西全捞回来（一次 SSH 往返），输出 {@code KEY=VALUE} 行由
     * {@link #parseKeyValues(String)} 解析。{@code __PREFIX__} 会被替换成实际的
     * docker 命令前缀（可能是 {@code sudo -n /usr/bin/docker}）。
     * <p>
     * 脚本里所有探测都带 {@code command -v} / 存在性判断：CentOS 7 上可能没有
     * {@code systemctl} 可用的场景（容器内）、可能只有 {@code /etc/init.d/docker}，
     * Rocky 8 上的 {@code docker.service} 与 docker-ce 的单元名一致，都要能兜住。
     */
    private static final String PROBE_SCRIPT =
            "set +e; "
                    + "DBIN=''; "
                    + "for p in docker /usr/bin/docker /usr/local/bin/docker /usr/sbin/docker /bin/docker /snap/bin/docker; do "
                    + "if command -v \"$p\" >/dev/null 2>&1; then DBIN=$(command -v \"$p\"); break; fi; done; "
                    + "echo \"PROBE_BINARY=${DBIN}\"; "
                    + "echo \"PROBE_UID=$(id -u)\"; "
                    + "echo \"PROBE_USER=$(whoami)\"; "
                    + ". /etc/os-release 2>/dev/null; echo \"PROBE_OS=${PRETTY_NAME}\"; "
                    + "if command -v systemctl >/dev/null 2>&1 && [ -d /run/systemd/system ]; then "
                    + "echo 'PROBE_SYSTEMD=yes'; else echo 'PROBE_SYSTEMD=no'; fi; "
                    + "if systemctl cat docker.service >/dev/null 2>&1; then echo 'PROBE_UNIT=docker.service'; "
                    + "elif systemctl cat docker-ce.service >/dev/null 2>&1; then echo 'PROBE_UNIT=docker-ce.service'; "
                    + "else echo 'PROBE_UNIT='; fi; "
                    + "if command -v service >/dev/null 2>&1; then echo 'PROBE_SERVICECMD=yes'; else echo 'PROBE_SERVICECMD=no'; fi; "
                    + "if [ -x /etc/init.d/docker ]; then echo 'PROBE_SYSV=yes'; else echo 'PROBE_SYSV=no'; fi; "
                    + "if command -v dockerd >/dev/null 2>&1 || [ -x /usr/bin/dockerd ]; then "
                    + "echo 'PROBE_DOCKERDBIN=yes'; else echo 'PROBE_DOCKERDBIN=no'; fi; "
                    + "if sudo -n true >/dev/null 2>&1; then echo 'PROBE_SUDO=yes'; else echo 'PROBE_SUDO=no'; fi; "
                    + "if command -v pgrep >/dev/null 2>&1; then pgrep -x dockerd >/dev/null 2>&1 && echo 'PROBE_PROC=yes' || echo 'PROBE_PROC=no'; "
                    + "else ps -e 2>/dev/null | grep -q '[d]ockerd' && echo 'PROBE_PROC=yes' || echo 'PROBE_PROC=no'; fi; "
                    + "DOUT=$(__PREFIX__ info 2>&1); DCODE=$?; "
                    + "if [ \"$DCODE\" = \"0\" ]; then echo 'PROBE_DAEMON=running'; "
                    + "echo \"PROBE_SERVERVER=$(__PREFIX__ version --format '{{.Server.Version}}' 2>/dev/null | head -n 1)\"; "
                    + "else echo 'PROBE_DAEMON=down'; echo 'PROBE_SERVERVER='; fi; "
                    + "echo \"PROBE_ERR=$(printf '%s' \"$DOUT\" | head -n 2 | tr '\\n' ' ')\"";

    private final ConnectionInfo info;
    /**
     * 命令通道。走 {@link SshCommandRunner} 而不是自己存一条 {@code SSHClientUtil}：
     * 连接进共享池后空闲 15 分钟会被回收，自己存的引用就成了一条死连接
     * （用户现象：页面开着超过 15 分钟再点「刷新」直接报「SSH 连接未建立」）。
     * 执行器每次发命令前都会确认连接，没了就地重建，调用方无感。
     */
    private final SshCommandRunner runner;
    /**
     * docker 命令前缀，默认自动探测。非 root 用户会被自动补成 {@code sudo -n docker}
     * （Rocky / Ubuntu 上普通用户默认不在 docker 组，直接跑 docker 是 permission denied）。
     */
    private volatile String commandPrefix;
    /** 用户在界面上显式填的前缀；填了就不允许被自动重算覆盖 */
    private final String configuredPrefix;

    /** daemon 状态缓存，只在 {@link #daemonLock} 里改 */
    private volatile DockerDaemonStatus daemonStatus;
    private final Object daemonLock = new Object();

    private volatile boolean closed;

    public DockerExecutor(ConnectionInfo info, String commandPrefix) throws IOException {
        if (null == info) {
            throw new IOException("连接信息为空");
        }
        this.info = info;
        // 走共享连接池：Docker 管理 / Compose 管理 / 容器终端之外的命令操作共用一条连接，
        // 标签关闭不断开，空闲 15 分钟后由池统一回收（回收后再用会自动重连）
        this.runner = new SshCommandRunner(info);
        this.configuredPrefix = StrUtil.trimToEmpty(commandPrefix);
        this.commandPrefix = resolveCommandPrefix(this.configuredPrefix);
    }

    public ConnectionInfo getInfo() {
        return info;
    }

    public String getCommandPrefix() {
        return commandPrefix;
    }

    public String hostLabel() {
        return info.getIdHost() + (StrUtil.isBlank(info.getCdPort()) ? "" : ":" + info.getCdPort());
    }

    /**
     * 确定 docker 命令前缀。
     * <p>
     * 目标环境优先按 <b>Rocky Linux 8/9/10 与 Ubuntu 22.04</b> 考虑，
     * 同时兼容 <b>CentOS 7</b>：
     * <ul>
     *   <li>两个发行版的 docker 都在 {@code /usr/bin/docker}，但 Rocky 上也可能只装了
     *       {@code podman-docker} 或从 snap 装的，所以逐个候选路径探测一遍，
     *       而不是假定 {@code PATH} 里有 {@code docker}（SSH 非交互式 shell 的 PATH 常常被裁过）；</li>
     *   <li>非 root 用户默认不在 docker 组，直接执行会报
     *       {@code permission denied while trying to connect to the Docker daemon socket}，
     *       这里自动加上 {@code sudo -n}（{@code -n} 保证不会卡在密码提示上）。</li>
     * </ul>
     *
     * @param configured 用户在界面上显式填的前缀，填了就完全以它为准
     */
    private String resolveCommandPrefix(String configured) {
        if (StrUtil.isNotBlank(configured)) {
            return configured.trim();
        }
        String binary = "docker";
        try {
            CmdResult probe = exec("for p in docker /usr/bin/docker /usr/local/bin/docker /usr/sbin/docker "
                    + "/bin/docker /snap/bin/docker;"
                    + " do if command -v \"$p\" >/dev/null 2>&1; then command -v \"$p\"; break; fi; done");
            for (String line : probe.getOutput().split("\\R")) {
                String candidate = line.trim();
                if (candidate.startsWith("/")) {
                    binary = candidate;
                    break;
                }
            }
        } catch (IOException e) {
            log.warn("探测 {} 上的 docker 可执行文件失败：{}", hostLabel(), e.getMessage());
        }

        // 先按当前用户直接执行一次，失败且明确是权限问题就补 sudo -n
        if (!needsSudo(binary)) {
            return binary;
        }
        String withSudo = "sudo -n " + binary;
        if (sudoAvailable()) {
            log.info("{} 上普通用户无权访问 docker daemon，已自动改用 {}", hostLabel(), withSudo);
            return withSudo;
        }
        // sudo 也不可用就把原始错误留给后续命令去报，避免这里"猜"错后信息更少
        return binary;
    }

    /**
     * daemon 刚被拉起来之后重算一次前缀。
     * <p>
     * 必须重算的原因：daemon 没启动时 socket 文件不存在，非 root 用户拿到的是
     * {@code Cannot connect to the Docker daemon}（不是权限问题），于是前缀里不会带 sudo；
     * 服务起来之后同一条命令会变成 {@code permission denied}，不重算就会一直失败。
     */
    public void refreshCommandPrefix() {
        if (StrUtil.isNotBlank(configuredPrefix)) {
            return;
        }
        this.commandPrefix = resolveCommandPrefix(null);
    }

    /** 空跑一次 docker version，判断是不是权限问题 */
    private boolean needsSudo(String binary) {
        try {
            CmdResult result = exec(binary + " version --format '{{.Client.Version}}'");
            if (result.isOk()) {
                return false;
            }
            String output = StrUtil.emptyToDefault(result.getOutput(), "").toLowerCase();
            return output.contains("permission denied") || output.contains("got permission denied");
        } catch (IOException e) {
            log.warn("检测 {} 的 docker 权限失败：{}", hostLabel(), e.getMessage());
            return false;
        }
    }

    private boolean sudoAvailable() {
        try {
            CmdResult result = exec("sudo -n true");
            return result.isOk();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 给需要 SFTP（导出镜像、容器文件下载）的调用方用。
     * <p>
     * 取之前会确认连接还活着：池子回收掉旧连接之后这里会重新建一条，
     * 否则调用方拿到的是失效实例，{@code newSFTPClient()} 只会报「SSH 连接未建立」。
     */
    public SSHClientUtil ssh() {
        return runner.currentSsh();
    }

    /** 取走「自上次询问以来是否自动重连过」，供界面弹一次提示 */
    public boolean consumeReconnectNotice() {
        return runner.consumeReconnectNotice();
    }

    public boolean isClosed() {
        return closed;
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Docker 连接已关闭，请重新选择目标服务器");
        }
    }

    /* ================================================================== */
    /* daemon 状态                                                         */
    /* ================================================================== */

    /**
     * 探测目标机上的 docker daemon / 服务管理方式，结果写入缓存。
     *
     * @param force true 表示跳过缓存强制探测（用户点了「重新检测」/「启动服务」时用）
     */
    public DockerDaemonStatus probeDaemon(boolean force) throws IOException {
        synchronized (daemonLock) {
            DockerDaemonStatus cached = daemonStatus;
            if (!force && null != cached && !isCacheExpired(cached)) {
                return cached;
            }
            DockerDaemonStatus fresh = runDaemonProbe();
            daemonStatus = fresh;
            return fresh;
        }
    }

    /** 读缓存，不发起任何命令；没探测过时返回 null */
    public DockerDaemonStatus currentDaemonStatus() {
        return daemonStatus;
    }

    /** 把缓存打掉，下一次调用会重新探测（用户点「刷新」时用） */
    public void invalidateDaemon() {
        daemonStatus = null;
    }

    /**
     * 所有 docker 命令的统一前置检查：daemon 不在就抛
     * {@link DockerDaemonDownException}，一个字节都不发。
     */
    public void ensureDaemonReady() throws IOException {
        ensureOpen();
        DockerDaemonStatus cached = daemonStatus;
        if (null != cached && cached.isDaemonRunning() && !isCacheExpired(cached)) {
            return;
        }
        DockerDaemonStatus current = probeDaemon(false);
        if (!current.isDaemonRunning()) {
            throw new DockerDaemonDownException(current);
        }
    }

    private boolean isCacheExpired(DockerDaemonStatus status) {
        long ttl = status.isDaemonRunning() ? DAEMON_CACHE_OK_MS : DAEMON_CACHE_DOWN_MS;
        return System.currentTimeMillis() - status.getProbedAt() > ttl;
    }

    private DockerDaemonStatus runDaemonProbe() throws IOException {
        String prefix = StrUtil.emptyToDefault(commandPrefix, "docker");
        CmdResult result = exec(PROBE_SCRIPT.replace("__PREFIX__", prefix));
        Map<String, String> map = parseKeyValues(result.getOutput());

        DockerDaemonStatus status = new DockerDaemonStatus();
        status.setProbed(true);
        status.setProbedAt(System.currentTimeMillis());

        String binary = StrUtil.emptyToDefault(map.get("PROBE_BINARY"), "");
        status.setBinaryPath(binary);
        // 非交互式 shell 的 PATH 可能被裁得很短，command -v 探不到，
        // 但命令前缀里已经带了绝对路径，这种情况同样算"装了 docker"
        String prefixBinary = lastToken(prefix);
        boolean installed = StrUtil.isNotBlank(binary) || prefixBinary.startsWith("/");
        status.setDockerInstalled(installed);
        if (StrUtil.isBlank(binary) && prefixBinary.startsWith("/")) {
            status.setBinaryPath(prefixBinary);
        }

        status.setUid(map.get("PROBE_UID"));
        status.setLoginUser(map.get("PROBE_USER"));
        status.setOsRelease(map.get("PROBE_OS"));
        status.setSystemdUsable("yes".equals(map.get("PROBE_SYSTEMD")));
        status.setServiceUnit(map.get("PROBE_UNIT"));
        status.setServiceCmdAvailable("yes".equals(map.get("PROBE_SERVICECMD")));
        status.setSysvScriptAvailable("yes".equals(map.get("PROBE_SYSV")));
        status.setDockerdBinary("yes".equals(map.get("PROBE_DOCKERDBIN")));
        status.setSudoAvailable("yes".equals(map.get("PROBE_SUDO")));
        status.setDaemonProcess("yes".equals(map.get("PROBE_PROC")));
        status.setServerVersion(map.get("PROBE_SERVERVER"));

        boolean running = "running".equals(map.get("PROBE_DAEMON"));
        status.setDaemonRunning(running);
        if (!running) {
            status.setDaemonError(map.get("PROBE_ERR"));
        }
        log.info("{} 的 docker 状态：installed={} running={} systemd={} unit={} sudo={} process={}",
                hostLabel(), status.isDockerInstalled(), status.isDaemonRunning(), status.isSystemdUsable(),
                status.getServiceUnit(), status.isSudoAvailable(), status.isDaemonProcess());
        return status;
    }

    /**
     * 启动目标机上的 docker 服务。
     * <p>
     * 只会由用户在弹窗里点确认后调用，命令候选见
     * {@link DockerDaemonStatus#buildStartCommands()}（systemd → service → init 脚本）。
     * 某一条失败时立刻换下一条，全部失败会把 {@code systemctl is-active} 与
     * {@code journalctl -u docker} 的末尾几行一起带回来，否则用户只能看到一个"exit 1"。
     */
    public DockerActionResult startDaemon() throws IOException {
        ensureOpen();
        DockerDaemonStatus before = probeDaemon(true);
        if (before.isDaemonRunning()) {
            return new DockerActionResult(true,
                    "docker 服务已经在运行" + (StrUtil.isBlank(before.getServerVersion())
                            ? "" : "（服务端 " + before.getServerVersion() + "）"), before);
        }
        if (!before.isDockerInstalled()) {
            throw new IOException(before.diagnosis());
        }
        List<String> commands = before.buildStartCommands();
        if (commands.isEmpty()) {
            throw new IOException(before.diagnosis());
        }

        StringBuilder report = new StringBuilder();
        for (String command : commands) {
            CmdResult result = exec(command);
            report.append("$ ").append(command).append('\n');
            if (!result.isOk()) {
                report.append("  ✗ 失败：").append(result.errorMessage()).append('\n');
                continue;
            }
            report.append("  已提交启动请求，等待 daemon 就绪（最多 ")
                    .append(DAEMON_START_WAIT_MS / 1000).append(" 秒）…\n");
            if (waitDaemonUp(DAEMON_START_WAIT_MS)) {
                // daemon 起来之后，非 root 用户的权限判定可能变了（socket 从不存在的报错
                // 变成 permission denied），必须重算一次命令前缀
                refreshCommandPrefix();
                DockerDaemonStatus after = probeDaemon(true);
                report.append("  ✓ docker 服务已启动，命令前缀 `").append(commandPrefix).append("`");
                if (StrUtil.isNotBlank(after.getServerVersion())) {
                    report.append("，服务端版本 ").append(after.getServerVersion());
                }
                report.append('\n');
                return new DockerActionResult(true, report.toString(), after);
            }
            report.append("  ✗ 命令返回成功，但 ").append(DAEMON_START_WAIT_MS / 1000)
                    .append(" 秒内 daemon 仍未就绪\n");
        }
        report.append(serviceLogTail(before));
        DockerDaemonStatus after = probeDaemon(true);
        report.append("\n当前状态：").append(after.summary()).append('\n');
        return new DockerActionResult(false, report.toString(), after);
    }

    /**
     * 在目标机上安装 Docker Engine（界面「一键安装 Docker」的落地）。
     * <p>
     * 只在用户点过确认之后调用，脚本见 {@link DockerInstallScript}：按 {@code /etc/os-release}
     * 分流到 apt / dnf / yum，装不上再退回 {@code get.docker.com} 官方脚本，最后启服务、
     * 把登录用户加进 docker 组并验证 {@code docker info}。
     * <p>
     * <b>脚本怎么送到远端。</b>整段脚本用 {@link #q(String)} 转义后作为
     * {@code bash -c} 的参数一次性执行，而不是 {@code echo … | bash} 喂标准输入——
     * 后者有个经典坑：shell 从标准输入读脚本，脚本里任何一条会读标准输入的命令
     * （{@code apt-get} 的确认、{@code curl | sh} 之类）都会把后面还没执行的脚本吃掉。
     * 非 root 用 {@code sudo -n bash -c …}，不用给每一条命令单独加 sudo 前缀。
     * <p>
     * 安装失败不抛异常（除连接本身断了），而是把包管理器/脚本的原始输出原样带回界面，
     * 否则用户只能看到一句「安装失败」。
     */
    public DockerActionResult installDocker() throws IOException {
        ensureOpen();
        DockerDaemonStatus before = probeDaemon(true);
        if (before.isDockerInstalled()) {
            return new DockerActionResult(true,
                    "目标机上已经有 docker（" + before.getBinaryPath() + "），无需安装。\n"
                            + "当前状态：" + before.summary(), before);
        }
        if (!before.canInstallDocker()) {
            throw new IOException(before.installBlockedReason());
        }
        Distro distro = probeDistro();
        String command = buildInstallCommand(before, distro);
        log.info("开始为 {} 安装 docker：登录用户 {}（uid={}），系统 {}，使用 {}",
                hostLabel(), before.getLoginUser(), before.getUid(), distro.prettyName(),
                DockerInstallScript.variantLabel(distro.id(), distro.versionId()));

        CmdResult result = exec(command);
        StringBuilder report = new StringBuilder();
        report.append(result.getOutput());
        if (!report.isEmpty() && report.charAt(report.length() - 1) != '\n') {
            report.append('\n');
        }
        if (!result.isOk()) {
            report.append("安装脚本以退出码 ").append(result.getExitCode()).append(" 结束。\n");
        }
        // 装完必须重算命令前缀：装之前探不到 docker，前缀是裸的 "docker"（可能还带 sudo），
        // 现在是绝对路径，且非 root 用户刚加了 docker 组、权限判定也变了
        refreshCommandPrefix();
        DockerDaemonStatus after = probeDaemon(true);
        report.append("\n当前状态：").append(after.summary()).append('\n');
        if (after.isDockerInstalled() && !after.isDaemonRunning()) {
            report.append("docker 已装上但服务没起来，点「启动 Docker 服务」或「检测服务状态」重试。\n");
        }
        if (after.isDockerInstalled() && after.isDaemonRunning()) {
            report.append("命令前缀：`").append(commandPrefix).append("`\n");
        }
        return new DockerActionResult(after.isDockerInstalled(), report.toString(), after);
    }

    /** 发行版探测结果：/etc/os-release 的 ID / VERSION_ID / PRETTY_NAME */
    public record Distro(String id, String versionId, String prettyName) {
    }

    /**
     * 探测目标机发行版，一次 SSH 往返。「一键安装」弹窗打开时调用：
     * 按结果从 {@link DockerInstallScript#scriptFor} 选专用脚本，
     * 读不到时按 unknown 处理，调用方回落通用脚本。
     */
    public Distro probeDistro() throws IOException {
        ensureOpen();
        CmdResult result = exec(". /etc/os-release 2>/dev/null; "
                + "echo \"OS_ID=${ID:-unknown}\"; echo \"OS_VER=${VERSION_ID:-}\"; echo \"OS_NAME=${PRETTY_NAME:-unknown}\"");
        Map<String, String> map = parseKeyValues(result.getOutput());
        return new Distro(
                StrUtil.emptyToDefault(map.get("OS_ID"), "unknown"),
                StrUtil.emptyToDefault(map.get("OS_VER"), ""),
                StrUtil.emptyToDefault(map.get("OS_NAME"), "unknown"));
    }

    /**
     * 「一键安装」要在目标机上执行的完整命令：按发行版选脚本（与确认弹窗里
     * 预览的是<b>同一份</b>），非 root 加 {@code sudo -n}，整段脚本转义后交给
     * {@code bash -c}。页面的实时安装终端与 {@link #installDocker()} 共用这一份。
     */
    public String buildInstallCommand(DockerDaemonStatus before, Distro distro) {
        String script = DockerInstallScript.scriptFor(
                null == distro ? "unknown" : distro.id(),
                null == distro ? "" : distro.versionId());
        return (before.isRoot() ? "" : "sudo -n ") + "bash -c " + q(script);
    }

    /** 轮询等待 daemon 就绪；只在用户点了「启动」之后走，次数有上限 */
    private boolean waitDaemonUp(long budgetMs) {        long deadline = System.currentTimeMillis() + budgetMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(DAEMON_START_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            try {
                if (exec(commandPrefix + " info >/dev/null 2>&1").isOk()) {
                    return true;
                }
            } catch (IOException e) {
                // SSH 都断了就不用再等了
                log.warn("等待 {} 的 docker 就绪时连接中断：{}", hostLabel(), e.getMessage());
                return false;
            }
        }
        return false;
    }

    /** 服务起不来时，把服务状态与最后几行日志带上，省得用户自己去翻 */
    private String serviceLogTail(DockerDaemonStatus status) {
        StringBuilder sb = new StringBuilder();
        if (!status.isSystemdUsable() || StrUtil.isBlank(status.getServiceUnit())) {
            if (status.isDaemonProcess()) {
                sb.append("  dockerd 进程在跑但连不上 socket，检查 /var/run/docker.sock 权限与 DOCKER_HOST\n");
            }
            return sb.toString();
        }
        String prefix = status.servicePrefix();
        String unit = status.getServiceUnit();
        try {
            CmdResult active = exec(prefix + "systemctl is-active " + unit + " 2>/dev/null");
            sb.append("  systemctl is-active ").append(unit).append(" → ")
                    .append(StrUtil.trimToEmpty(active.getOutput())).append('\n');
        } catch (IOException e) {
            log.warn("读取 {} 状态失败：{}", unit, e.getMessage());
        }
        try {
            CmdResult tail = exec(prefix + "journalctl -u " + unit + " --no-pager -n 12 2>/dev/null");
            String text = StrUtil.trimToEmpty(tail.getOutput());
            if (StrUtil.isNotBlank(text)) {
                sb.append("  journalctl -u ").append(unit).append(" 末尾：\n");
                for (String line : text.split("\\R")) {
                    sb.append("    ").append(line).append('\n');
                }
            }
        } catch (IOException e) {
            log.warn("读取 {} 日志失败：{}", unit, e.getMessage());
        }
        return sb.toString();
    }

    /* ------------------------------------------------------------------ */
    /* 命令执行                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * 执行一段原始 shell 命令，返回「退出码 + 输出」。
     * <p>
     * 连接层面的失效（池子空闲回收、服务端掐线）由 {@link SshCommandRunner} 兜住：
     * 发命令前发现连接没了就重连，中途断线且确认连接已死则重连重试一次。
     */
    public CmdResult exec(String command) throws IOException {
        if (StrUtil.isBlank(command)) {
            throw new IOException("命令为空");
        }
        ensureOpen();
        SshCommandRunner.Result result = runner.exec(command);
        CmdResult mapped = new CmdResult(result.getExitCode(), result.getOutput());
        noteDaemonUnreachable(mapped);
        return mapped;
    }

    /**
     * 命令报「连不上 daemon」时把状态缓存打掉。
     * <p>
     * 缓存说"在跑"但实际已经连不上（用户在服务器上把 docker 停了），
     * 这里主动失效，下一次调用就会重新探测并快失败，而不是每条命令都白跑一趟。
     */
    private void noteDaemonUnreachable(CmdResult result) {
        if (result.isOk() || null == daemonStatus) {
            return;
        }
        if (isDaemonUnreachable(result.getOutput())) {
            daemonStatus = null;
            log.warn("{} 上的 docker daemon 已不可达，清空状态缓存", hostLabel());
        }
    }

    /** 输出里是否包含"连不上 docker daemon"的特征串（各版本 docker 的措辞都是这两句） */
    public static boolean isDaemonUnreachable(String output) {
        String text = StrUtil.emptyToDefault(output, "").toLowerCase();
        return text.contains("cannot connect to the docker daemon")
                || text.contains("is the docker daemon running")
                || text.contains("docker daemon is not running");
    }

    /** 拼一条 docker 命令（不做任何检查），返回命令行字符串 */
    public String command(String... args) {
        StringBuilder sb = new StringBuilder(commandPrefix);
        for (String arg : args) {
            sb.append(' ').append(q(arg));
        }
        return sb.toString();
    }

    /**
     * 执行 docker 子命令，返回「退出码 + 输出」。
     * <p>
     * daemon 不在时直接抛 {@link DockerDaemonDownException}，不会发出命令。
     */
    public CmdResult docker(String... args) throws IOException {
        ensureDaemonReady();
        return exec(command(args));
    }

    /**
     * 执行 docker 子命令，非 0 退出码直接抛 IOException，消息里带上真实输出。
     * <p>
     * 适用于「成功就是没输出」的写操作（start / stop / rm / pull 等）。
     */
    public String dockerChecked(String... args) throws IOException {
        CmdResult result = docker(args);
        if (!result.isOk()) {
            throw new IOException("docker " + firstArg(args) + " 执行失败：" + result.errorMessage());
        }
        return result.getOutput();
    }

    /**
     * 执行一段直接拼在 docker 前缀后面的<b>原始</b>子命令。
     * <p>
     * 和 {@link #docker(String...)} 的区别是<b>不做 shell 转义</b>：用户在「创建容器」
     * 弹窗里粘贴的是一整条命令行（{@code run -d --name nginx -p 8080:80 nginx:1.25}），
     * 走参数数组会被每个参数各套一层单引号，docker 直接认不出来。
     * <p>
     * 校验（只放行 run / create、拦住 shell 连接符）由调用方
     * {@code DockerService.runDockerCommand} 负责；这里只保证 daemon 不可用时
     * 一个字节都不发出去。
     */
    public CmdResult dockerRaw(String subCommand) throws IOException {
        if (StrUtil.isBlank(subCommand)) {
            throw new IOException("docker 子命令为空");
        }
        ensureDaemonReady();
        return exec(commandPrefix + " " + subCommand);
    }

    /** 执行 docker 子命令，按行返回非空输出行 */
    public List<String> dockerLines(String... args) throws IOException {
        CmdResult result = docker(args);
        List<String> lines = new ArrayList<String>();
        if (!result.isOk()) {
            throw new IOException("docker " + firstArg(args) + " 执行失败：" + result.errorMessage());
        }
        for (String line : result.getOutput().split("\\R")) {
            String trimmed = line.trim();
            if (StrUtil.isNotEmpty(trimmed)) {
                lines.add(trimmed);
            }
        }
        return lines;
    }

    /**
     * 打开一段命令的输出流（用于日志导出这类流式场景），已在末尾补 {@code 2>&1}。
     * 调用方负责 close。
     */
    public InputStream openStream(String command) throws IOException {
        ensureDaemonReady();
        return runner.openStream(command + " 2>&1");
    }

    /**
     * 打开命令的输出流，<b>不合并 stderr</b>。
     * <p>
     * 用于 {@code docker save} 这种输出本身就是二进制流的命令：末尾追加 {@code 2>&1}
     * 会把 stderr 的文本混进 tar 里，导出的镜像包直接损坏（解压时才报 unexpected EOF）。
     * 代价是这类命令失败时拿不到原因，所以调用方要先做一次存在性校验。
     */
    public InputStream openRawStream(String command) throws IOException {
        ensureDaemonReady();
        return runner.openStream(command);
    }

    private static String firstArg(String... args) {
        return (null == args || args.length == 0) ? "" : args[0];
    }

    private static String lastToken(String text) {
        String trimmed = StrUtil.trimToEmpty(text);
        int idx = trimmed.lastIndexOf(' ');
        return idx < 0 ? trimmed : trimmed.substring(idx + 1).trim();
    }

    /** 解析探测脚本输出的 {@code KEY=VALUE} 行（实现与 Linux 环境探测共用） */
    private static Map<String, String> parseKeyValues(String output) {
        return SshCommandRunner.parseKeyValues(output);
    }

    @Override
    public void close() {
        closed = true;
        daemonStatus = null;
        // 不再直接关 SSH：连接归共享连接池管（SshConnectionPool），关闭标签后
        // 其它页面还能复用，空闲超 15 分钟由池自动回收
        runner.close();
    }

    /* ------------------------------------------------------------------ */
    /* 工具方法                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * shell 单引号转义。容器名、镜像名、路径、用户输入的端口映射都会走到这里，
     * 不转义的话一个空格或者 {@code ;} 就能把命令截断（甚至执行任意命令）。
     */
    public static String q(String raw) {
        if (null == raw) {
            return "''";
        }
        return "'" + raw.replace("'", "'\\''") + "'";
    }

    /**
     * 一条命令的执行结果。
     * <p>
     * 直接继承 {@link SshCommandRunner.Result}：退出码 + 输出（stdout/stderr 已合并）
     * 这套东西在 Docker 命令和普通 shell 命令上完全一样，没必要各存一份解析逻辑。
     * 保留本类型只是为了不打断既有调用点上的 {@code DockerExecutor.CmdResult} 写法。
     */
    public static final class CmdResult extends SshCommandRunner.Result {

        private static final long serialVersionUID = 1L;

        public CmdResult(int exitCode, String output) {
            super(exitCode, output);
        }
    }

    /**
     * 「启动服务 / 安装 docker」这类远程动作的结果：成功与否 + 给用户看的执行报告 + 最新状态。
     * <p>
     * 失败时不抛异常而是返回报告，因为报告里可能有包管理器的报错、systemd 的报错、
     * {@code journalctl} 的末尾几行，这些内容要原样展示在弹窗里才有用。
     */
    public static final class DockerActionResult implements Serializable {

        private static final long serialVersionUID = 1L;

        private final boolean success;
        private final String report;
        private final DockerDaemonStatus status;

        DockerActionResult(boolean success, String report, DockerDaemonStatus status) {
            this.success = success;
            this.report = report;
            this.status = status;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getReport() {
            return report;
        }

        public DockerDaemonStatus getStatus() {
            return status;
        }
    }
}
