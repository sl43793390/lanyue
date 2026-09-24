package com.sl.docker.model;

import cn.hutool.core.util.StrUtil;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 目标主机上 docker 服务的状态。
 * <p>
 * <b>为什么需要这个类。</b>原来页面只认「命令能不能跑通」，于是 daemon 没起的时候
 * {@code docker ps} 报 {@code Cannot connect to the Docker daemon}，页面把它当成
 * 普通失败，失败回调里又去 {@code reload()}，reload 再失败、再 reload ——
 * 一条死循环，60ms 就往目标机上砸一条 ssh 命令（日志里能直接看到）。
 * 现在把「daemon 到底在不在」独立成一次探测，探测结果缓存在
 * {@link com.sl.docker.DockerExecutor} 里，不可用时直接快失败、不再发命令，
 * 由界面弹窗问用户要不要把服务拉起来。
 * <p>
 * <b>兼容性。</b>服务管理方式按发行版探测，不写死 {@code systemctl}：
 * <ul>
 *   <li>Rocky 8/9/10、Ubuntu 22：systemd，单元名 {@code docker.service}（docker-ce 也是这个名）；</li>
 *   <li>CentOS 7：默认也是 systemd，但仓库里的 {@code docker-1.13} 与
 *       {@code docker-io} 可能只有 {@code /etc/init.d/docker}，
 *       所以同时保留 {@code service docker start} 与 {@code /etc/init.d/docker start} 两条退路；</li>
 *   <li>容器里跑的系统没有 systemd（{@code /run/systemd/system} 不存在），
 *       探测结果里会把 {@code systemdUsable} 置为 false，不会硬调 systemctl。</li>
 * </ul>
 */
public class DockerDaemonStatus implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 是否真的探测过（没探测过时所有字段都不可信） */
    private boolean probed;
    /** docker 命令是否存在（探测路径 + 已解析出来的命令前缀） */
    private boolean dockerInstalled;
    /** {@code docker info} 是否成功——这是"服务在不在"的判定依据 */
    private boolean daemonRunning;

    /** docker 可执行文件路径，探测不到时为空 */
    private String binaryPath = "";
    /** 服务端版本，daemon 没起来时为空 */
    private String serverVersion = "";
    /** daemon 的报错原文（一般是 Cannot connect to the Docker daemon ...） */
    private String daemonError = "";
    /** dockerd 进程是否存在：进程在但连不上，说明是 socket 权限/路径问题，不是"没启动" */
    private boolean daemonProcess;

    /** systemctl 可用（命令存在且 PID 1 是 systemd） */
    private boolean systemdUsable;
    /** 服务单元名，Rocky/Ubuntu 上是 docker.service */
    private String serviceUnit = "";
    /** {@code service} 命令存在（CentOS 7 一定有，它是 SysV 与 systemd 的通用入口） */
    private boolean serviceCmdAvailable;
    /** {@code /etc/init.d/docker} 存在（老 CentOS 7 的 docker/ docker-io 包） */
    private boolean sysvScriptAvailable;
    /** 存在 dockerd 可执行文件（没有任何服务脚本时的最后一条退路） */
    private boolean dockerdBinary;

    /** {@code sudo -n true} 是否成功，即免密 sudo 可用 */
    private boolean sudoAvailable;
    private String uid = "";
    private String loginUser = "";
    private String osRelease = "";

    /** 本次探测时间 */
    private long probedAt;

    public boolean isProbed() {
        return probed;
    }

    public void setProbed(boolean probed) {
        this.probed = probed;
    }

    public boolean isDockerInstalled() {
        return dockerInstalled;
    }

    public void setDockerInstalled(boolean dockerInstalled) {
        this.dockerInstalled = dockerInstalled;
    }

    public boolean isDaemonRunning() {
        return daemonRunning;
    }

    public void setDaemonRunning(boolean daemonRunning) {
        this.daemonRunning = daemonRunning;
    }

    public String getBinaryPath() {
        return binaryPath;
    }

    public void setBinaryPath(String binaryPath) {
        this.binaryPath = StrUtil.emptyToDefault(binaryPath, "");
    }

    public String getServerVersion() {
        return serverVersion;
    }

    public void setServerVersion(String serverVersion) {
        this.serverVersion = StrUtil.emptyToDefault(serverVersion, "");
    }

    public String getDaemonError() {
        return daemonError;
    }

    public void setDaemonError(String daemonError) {
        this.daemonError = StrUtil.emptyToDefault(daemonError, "");
    }

    public boolean isDaemonProcess() {
        return daemonProcess;
    }

    public void setDaemonProcess(boolean daemonProcess) {
        this.daemonProcess = daemonProcess;
    }

    public boolean isSystemdUsable() {
        return systemdUsable;
    }

    public void setSystemdUsable(boolean systemdUsable) {
        this.systemdUsable = systemdUsable;
    }

    public String getServiceUnit() {
        return serviceUnit;
    }

    public void setServiceUnit(String serviceUnit) {
        this.serviceUnit = StrUtil.emptyToDefault(serviceUnit, "");
    }

    public boolean isServiceCmdAvailable() {
        return serviceCmdAvailable;
    }

    public void setServiceCmdAvailable(boolean serviceCmdAvailable) {
        this.serviceCmdAvailable = serviceCmdAvailable;
    }

    public boolean isSysvScriptAvailable() {
        return sysvScriptAvailable;
    }

    public void setSysvScriptAvailable(boolean sysvScriptAvailable) {
        this.sysvScriptAvailable = sysvScriptAvailable;
    }

    public boolean isDockerdBinary() {
        return dockerdBinary;
    }

    public void setDockerdBinary(boolean dockerdBinary) {
        this.dockerdBinary = dockerdBinary;
    }

    public boolean isSudoAvailable() {
        return sudoAvailable;
    }

    public void setSudoAvailable(boolean sudoAvailable) {
        this.sudoAvailable = sudoAvailable;
    }

    public String getUid() {
        return uid;
    }

    public void setUid(String uid) {
        this.uid = StrUtil.emptyToDefault(uid, "");
    }

    public String getLoginUser() {
        return loginUser;
    }

    public void setLoginUser(String loginUser) {
        this.loginUser = StrUtil.emptyToDefault(loginUser, "");
    }

    public String getOsRelease() {
        return osRelease;
    }

    public void setOsRelease(String osRelease) {
        this.osRelease = StrUtil.emptyToDefault(osRelease, "");
    }

    public long getProbedAt() {
        return probedAt;
    }

    public void setProbedAt(long probedAt) {
        this.probedAt = probedAt;
    }

    /** 当前用户是不是 root */
    public boolean isRoot() {
        return "0".equals(StrUtil.trimToEmpty(uid));
    }

    /**
     * 服务管理命令要不要加 sudo。
     * 非 root 且免密 sudo 不可用时返回空串，调用方据此判断"没法代为启动"。
     */
    public String servicePrefix() {
        if (isRoot()) {
            return "";
        }
        return sudoAvailable ? "sudo -n " : "";
    }

    /**
     * 按优先级列出可用于启动 docker 的命令；为空表示当前账号权限不够或机器上没有 docker。
     * <p>
     * 顺序刻意是 systemd → service → init 脚本：Rocky/Ubuntu 走第一条；
     * CentOS 7 上如果 {@code docker.service} 存在也走第一条，只有老包才落到后两条。
     */
    public List<String> buildStartCommands() {
        List<String> commands = new ArrayList<String>();
        if (!dockerInstalled) {
            return commands;
        }
        String prefix = servicePrefix();
        if (StrUtil.isEmpty(prefix) && !isRoot()) {
            // 非 root 又没有免密 sudo：连 systemctl 都调不动，直接让用户去 root 上执行
            return commands;
        }
        Set<String> unique = new LinkedHashSet<String>();
        if (systemdUsable && StrUtil.isNotBlank(serviceUnit)) {
            unique.add(prefix + "systemctl start " + serviceUnit);
        }
        if (serviceCmdAvailable) {
            unique.add(prefix + "service docker start");
        }
        if (sysvScriptAvailable) {
            unique.add(prefix + "/etc/init.d/docker start");
        }
        commands.addAll(unique);
        return commands;
    }

    /** 界面上展示「将要执行什么」，或者解释为什么执行不了 */
    public String getStartCommandPreview() {
        List<String> commands = buildStartCommands();
        if (!commands.isEmpty()) {
            return StrUtil.join("\n", commands);
        }
        if (!dockerInstalled) {
            return "目标机上没有找到 docker 命令，无法启动。请先在目标机安装 Docker Engine。";
        }
        return "当前登录用户 " + StrUtil.emptyToDefault(loginUser, "?")
                + " 不是 root，且免密 sudo 不可用，无法代为启动服务。\n"
                + "请在目标机上用 root 执行：systemctl start docker（CentOS 7 也可用 service docker start）";
    }

    /** 一段话讲清楚"为什么不可用" */
    public String diagnosis() {
        StringBuilder sb = new StringBuilder();
        if (!dockerInstalled) {
            sb.append("目标机上没有找到 docker 命令（已探测 docker、/usr/bin/docker、")
                    .append("/usr/local/bin/docker、/usr/sbin/docker、/snap/bin/docker）。\n")
                    .append("安装方式：CentOS 7 用 yum install -y docker；Rocky 8/9/10 用 dnf install -y docker-ce；")
                    .append("Ubuntu 22.04 用 apt install -y docker.io。");
            return sb.toString();
        }
        if (daemonRunning) {
            return "docker 服务运行中" + (StrUtil.isBlank(serverVersion) ? "" : "（服务端 " + serverVersion + "）");
        }
        if (daemonProcess) {
            sb.append("dockerd 进程在，但客户端连不上 daemon（").append(shortError()).append("）。\n")
                    .append("这不是「没启动」，常见原因：socket 权限不足（把用户加入 docker 组：")
                    .append("sudo usermod -aG docker $USER）、DOCKER_HOST 指向了别的地址、")
                    .append("或 daemon 正在重启中。先把命令前缀改成 sudo -n docker 再试一次。");
            return sb.toString();
        }
        sb.append("docker 服务没有运行：").append(shortError()).append('\n');
        if (!isRoot() && !sudoAvailable) {
            sb.append("当前登录用户 ").append(StrUtil.emptyToDefault(loginUser, "?")).append("(")
                    .append(StrUtil.emptyToDefault(uid, "?")).append(") 不是 root，且免密 sudo 不可用，")
                    .append("本页面无法代为启动。请用 root 执行：").append(rootStartHint());
            return sb.toString();
        }
        sb.append("可以点击「启动 Docker 服务」，将执行：");
        List<String> commands = buildStartCommands();
        sb.append(commands.isEmpty() ? rootStartHint() : commands.get(0));
        return sb.toString();
    }

    /** root 上该敲什么，按探测到的服务管理方式给 */
    public String rootStartHint() {
        if (systemdUsable && StrUtil.isNotBlank(serviceUnit)) {
            return "systemctl start " + serviceUnit;
        }
        if (serviceCmdAvailable) {
            return "service docker start";
        }
        if (sysvScriptAvailable) {
            return "/etc/init.d/docker start";
        }
        return "systemctl start docker";
    }

    private String shortError() {
        String text = StrUtil.trimToEmpty(daemonError);
        if (text.isEmpty()) {
            return "docker info 执行失败但没有输出";
        }
        return text;
    }

    /** 顶栏那行状态文案 */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Docker 服务：");
        if (probed && daemonRunning) {
            sb.append("运行中");
            if (StrUtil.isNotBlank(serverVersion)) {
                sb.append("（服务端 ").append(serverVersion).append("）");
            }
        } else if (probed && !dockerInstalled) {
            sb.append("未安装 docker 命令");
        } else if (probed) {
            sb.append("未运行");
        } else {
            sb.append("状态未知");
        }
        if (StrUtil.isNotBlank(osRelease)) {
            sb.append("　").append(osRelease);
        }
        if (StrUtil.isNotBlank(loginUser)) {
            sb.append("　登录用户 ").append(loginUser).append("(uid=").append(uid).append(")");
        }
        return sb.toString();
    }
}
