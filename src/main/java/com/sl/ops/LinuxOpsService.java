package com.sl.ops;

import cn.hutool.core.util.StrUtil;
import com.sl.ops.model.LinuxEnv;
import com.sl.util.SshCommandRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 常用 Linux 运维操作的领域层：把「运维同学在终端里敲的那几件事」做成带校验、
 * 带权限判断、带输出回显的方法。
 * <p>
 * <b>为什么每件事都要现查环境。</b>同一件事在不同发行版上命令不同：
 * 防火墙有 firewalld / ufw / 裸 iptables 三套，加 sudo 权限的组在 RHEL 系叫 wheel、
 * Debian 系叫 sudo，服务管理 systemd 不可用时得退回 {@code service}。这些差异写死就是
 * 「在 Rocky 上好好的，到 Ubuntu 上按钮点了没反应」。所以先 {@link #probe()} 拿一份
 * {@link LinuxEnv}，每个动作按它选命令。
 * <p>
 * <b>输入校验是硬要求，不是礼貌。</b>用户名、服务名、端口、主机名都会被拼进远端 shell
 * 命令里，不校验就等于在页面上开了一个任意命令执行的口子（一个 {@code ;rm -rf /} 就够了）。
 * 所有进入命令的外部字符串都过一遍白名单正则或数字范围，再统一用单引号包起来
 * （与 {@code DockerExecutor.q} 同款转义）。
 * <p>
 * <b>密码不落在命令行明文里。</b>新建用户 / 重置密码走
 * {@code echo <base64> | base64 -d | chpasswd}：base64 里只有字母数字与 {@code +/=}，
 * 不会因为密码里的引号、{@code $}、空格把命令拆散，也不需要为此放宽密码规则；
 * 远端 {@code ps} 里看到的也是 base64，不是明文。
 * <p>
 * <b>展示类命令不因为退出码非 0 就当失败。</b>查状态时 {@code systemctl is-active} 返回 3、
 * {@code ufw status} 在未启用时返回 1，都是正常现象——这些输出本身就是要给用户看的信息，
 * 抛异常会把有用内容吞掉。
 */
public class LinuxOpsService {

    /** 用户名白名单：字母或下划线开头，最长 32 位 */
    private static final Pattern USER_NAME = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_-]{0,31}");

    /** systemd 单元名白名单 */
    private static final Pattern UNIT_NAME = Pattern.compile("[a-zA-Z0-9_.@:-]{1,128}");

    /** 主机名单段白名单 */
    private static final Pattern HOST_NAME = Pattern.compile("[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?");

    /** 不允许新建的系统账号名 */
    private static final Set<String> RESERVED_USERS = Set.of(
            "root", "bin", "daemon", "adm", "lp", "sync", "shutdown", "halt", "mail", "operator",
            "games", "ftp", "nobody", "dbus", "sshd", "nginx", "systemd-network", "systemd-coredump");

    private final SshCommandRunner runner;

    public LinuxOpsService(SshCommandRunner runner) {
        this.runner = runner;
    }

    public SshCommandRunner getRunner() {
        return runner;
    }

    /* ================================================================== */
    /* 环境探测                                                            */
    /* ================================================================== */

    /**
     * 一次 SSH 往返把环境信息全捞回来（只读，不需要 root）。
     * <p>
     * 输出 {@code LENV_KEY=VALUE} 行，本地按第一个 {@code =} 切开。所有探测项都带
     * {@code command -v} / 存在性判断：没有 firewalld、没有 getenforce、没有 systemd 的
     * 机器（比如容器里跑的系统）也要能出结果，只是对应能力置空。
     */
    private static final String PROBE_SCRIPT = """
            set +e
            . /etc/os-release 2>/dev/null
            echo "LENV_OS=${PRETTY_NAME}"
            echo "LENV_DISTRO=${ID}"
            echo "LENV_UID=$(id -u)"
            echo "LENV_USER=$(whoami)"
            echo "LENV_HOME=${HOME}"
            echo "LENV_HOSTNAME=$(hostname)"
            echo "LENV_KERNEL=$(uname -r)"
            echo "LENV_UPTIME=$(uptime 2>/dev/null | sed 's/^ *//')"
            echo "LENV_SUDO=$(sudo -n true >/dev/null 2>&1 && echo yes || echo no)"
            echo "LENV_SYSTEMD=$( (command -v systemctl >/dev/null 2>&1 && [ -d /run/systemd/system ]) && echo yes || echo no)"
            echo "LENV_SELINUX=$( (command -v getenforce >/dev/null 2>&1 && getenforce) 2>/dev/null)"
            echo "LENV_FIREWALL_CMD=$(command -v firewall-cmd 2>/dev/null)"
            echo "LENV_UFW_CMD=$(command -v ufw 2>/dev/null)"
            echo "LENV_IPTABLES_CMD=$(command -v iptables 2>/dev/null)"
            echo "LENV_FIREWALLD_ACTIVE=$(systemctl is-active firewalld 2>/dev/null)"
            echo "LENV_UFW_ACTIVE=$(ufw status 2>/dev/null | head -n 1 | awk '{print $2}')"
            echo "LENV_WHEEL=$(getent group wheel >/dev/null 2>&1 && echo yes || echo no)"
            echo "LENV_SUDOGRP=$(getent group sudo >/dev/null 2>&1 && echo yes || echo no)"
            echo "LENV_USERS=$(getent passwd | awk -F: '$3>=1000 && $3<65534' | wc -l)"
            """;

    public LinuxEnv probe() throws IOException {
        SshCommandRunner.Result result = runner.exec(PROBE_SCRIPT);
        Map<String, String> map = SshCommandRunner.parseKeyValues(result.getOutput());
        LinuxEnv env = new LinuxEnv();
        env.setOsRelease(map.get("LENV_OS"));
        env.setDistroId(map.get("LENV_DISTRO"));
        env.setUid(map.get("LENV_UID"));
        env.setLoginUser(map.get("LENV_USER"));
        env.setHome(map.get("LENV_HOME"));
        env.setHostname(map.get("LENV_HOSTNAME"));
        env.setKernel(map.get("LENV_KERNEL"));
        env.setUptime(map.get("LENV_UPTIME"));
        env.setSelinux(map.get("LENV_SELINUX"));
        env.setSudoAvailable("yes".equals(map.get("LENV_SUDO")));
        env.setSystemdUsable("yes".equals(map.get("LENV_SYSTEMD")));
        env.setWheelGroup("yes".equals(map.get("LENV_WHEEL")));
        env.setSudoGroup("yes".equals(map.get("LENV_SUDOGRP")));
        env.setFirewallCmd(map.get("LENV_FIREWALL_CMD"));
        env.setUfwCmd(map.get("LENV_UFW_CMD"));
        env.setIptablesCmd(map.get("LENV_IPTABLES_CMD"));
        env.setFirewalldActive(map.get("LENV_FIREWALLD_ACTIVE"));
        env.setUfwActive(map.get("LENV_UFW_ACTIVE"));
        String users = StrUtil.trimToEmpty(map.get("LENV_USERS"));
        try {
            env.setNormalUserCount(users.isEmpty() ? 0 : Integer.parseInt(users));
        } catch (NumberFormatException e) {
            env.setNormalUserCount(0);
        }
        if (StrUtil.isBlank(env.getUid())) {
            env.setProbeError(result.errorMessage());
        }
        return env;
    }

    /* ================================================================== */
    /* 防火墙                                                              */
    /* ================================================================== */

    /** 看防火墙现状：用的哪套、开没开、放了哪些端口/服务 */
    public String firewallDetail(LinuxEnv env) throws IOException {
        String sudo = sudoPrefix(env);
        StringBuilder script = new StringBuilder();
        script.append("echo \"== 检测到的防火墙组件 ==\"\n")
                .append("command -v firewall-cmd >/dev/null 2>&1 && echo \"firewalld : $(command -v firewall-cmd)\"\n")
                .append("command -v ufw >/dev/null 2>&1 && echo \"ufw       : $(command -v ufw)\"\n")
                .append("command -v iptables >/dev/null 2>&1 && echo \"iptables  : $(command -v iptables)\"\n");
        switch (env.firewallKind()) {
            case LinuxEnv.FW_FIREWALLD -> script.append("echo\n echo \"== firewalld 状态 ==\"\n")
                    .append(sudo).append("systemctl is-active firewalld 2>/dev/null\n")
                    .append(sudo).append("systemctl is-enabled firewalld 2>/dev/null\n")
                    .append("echo\n echo \"== 放行规则（默认区域） ==\"\n")
                    .append(sudo).append("firewall-cmd --list-all 2>&1\n");
            case LinuxEnv.FW_UFW -> script.append("echo\n echo \"== ufw 状态 ==\"\n")
                    .append(sudo).append("ufw status verbose 2>&1\n");
            case LinuxEnv.FW_IPTABLES -> script.append("echo\n echo \"== iptables 策略与规则（前 80 行） ==\"\n")
                    .append(sudo).append("iptables -L -n --line-numbers 2>&1 | head -n 80\n");
            default -> script.append("echo\n echo \"这台机器上既没有 firewalld，也没有 ufw / iptables\"\n");
        }
        return run(script.toString());
    }

    /** 开启 / 关闭防火墙，并一并调整开机自启（关掉就别让它在下次开机时又自己起来） */
    public String setFirewallEnabled(boolean enable, LinuxEnv env) throws IOException {
        String kind = env.firewallKind();
        if (LinuxEnv.FW_NONE.equals(kind)) {
            throw new IOException("目标机上没有 firewalld / ufw / iptables，无法操作防火墙");
        }
        String prefix = rootPrefix(env, (enable ? "开启" : "关闭") + "防火墙");
        String script = switch (kind) {
            case LinuxEnv.FW_FIREWALLD -> enable
                    ? prefix + "systemctl enable --now firewalld 2>&1"
                    : prefix + "systemctl disable --now firewalld 2>&1";
            case LinuxEnv.FW_UFW -> prefix + "ufw " + (enable ? "--force enable" : "disable") + " 2>&1";
            default -> enable
                    ? prefix + "iptables -P INPUT DROP 2>&1; " + prefix + "iptables -P FORWARD DROP 2>&1"
                    : prefix + "iptables -P INPUT ACCEPT 2>&1; " + prefix + "iptables -P FORWARD ACCEPT 2>&1";
        };
        StringBuilder out = new StringBuilder();
        out.append("$ ").append(script).append('\n');
        SshCommandRunner.Result result = runner.exec(script);
        out.append(result.getOutput()).append('\n');
        if (!result.isOk()) {
            out.append("命令退出码 ").append(result.getExitCode()).append('\n');
        }
        // 顺手回读一次状态，用户不用再点「查看状态」确认到底生效没有
        out.append("\n-- 操作后状态 --\n");
        if (LinuxEnv.FW_FIREWALLD.equals(kind)) {
            out.append("firewalld : ")
                    .append(StrUtil.trimToEmpty(runner.exec(prefix + "systemctl is-active firewalld 2>/dev/null").getOutput()))
                    .append('\n');
        } else if (LinuxEnv.FW_UFW.equals(kind)) {
            out.append("ufw       : ")
                    .append(StrUtil.trimToEmpty(runner.exec(prefix + "ufw status 2>/dev/null | head -n 1").getOutput()))
                    .append('\n');
        }
        return out.toString();
    }

    /** 放行一个端口 */
    public String firewallOpenPort(int port, String proto, LinuxEnv env) throws IOException {
        validatePort(port);
        return firewallPort(port, validateProto(proto), env, true);
    }

    /** 移除一个端口的放行规则 */
    public String firewallClosePort(int port, String proto, LinuxEnv env) throws IOException {
        validatePort(port);
        return firewallPort(port, validateProto(proto), env, false);
    }

    private String firewallPort(int port, String protocol, LinuxEnv env, boolean open) throws IOException {
        String kind = env.firewallKind();
        String action = open ? "放行" : "移除";
        if (LinuxEnv.FW_NONE.equals(kind)) {
            throw new IOException("目标机上没有 firewalld / ufw / iptables，无法" + action + "端口");
        }
        String prefix = rootPrefix(env, action + "端口");
        if (LinuxEnv.FW_FIREWALLD.equals(kind)) {
            String command = prefix + "firewall-cmd --permanent --" + (open ? "add" : "remove")
                    + "-port=" + port + "/" + protocol + " && " + prefix + "firewall-cmd --reload";
            return runEchoing(command);
        }
        if (LinuxEnv.FW_UFW.equals(kind)) {
            return runEchoing(prefix + "ufw " + (open ? "allow" : "delete allow") + " " + port + "/" + protocol);
        }
        String command = open
                ? prefix + "iptables -I INPUT -p " + protocol + " --dport " + port + " -j ACCEPT"
                : prefix + "iptables -D INPUT -p " + protocol + " --dport " + port + " -j ACCEPT";
        return runEchoing(command)
                + "\n注意：iptables 规则重启后会丢失，生产机器需要自行持久化"
                + "（service iptables save 或装 iptables-persistent）。\n";
    }

    /** 重载防火墙规则（改完配置文件不用重启服务） */
    public String firewallReload(LinuxEnv env) throws IOException {
        String kind = env.firewallKind();
        if (LinuxEnv.FW_FIREWALLD.equals(kind)) {
            return runEchoing(rootPrefix(env, "重载防火墙") + "firewall-cmd --reload");
        }
        if (LinuxEnv.FW_UFW.equals(kind)) {
            return runEchoing(rootPrefix(env, "重载防火墙") + "ufw reload");
        }
        throw new IOException("当前防火墙实现（" + kind + "）没有「重载规则」这个动作");
    }

    /* ================================================================== */
    /* 用户与权限                                                          */
    /* ================================================================== */

    /** 列出普通用户、具备 sudo 的成员、当前登录会话 */
    public String listUsers() throws IOException {
        return run("""
                echo "== 普通用户（UID >= 1000） =="
                printf '%-18s %-8s %-26s %s\n' 用户名 UID 家目录 SHELL
                getent passwd | awk -F: '$3>=1000 && $3<65534 {printf "%-18s %-8s %-26s %s\n", $1, $3, $6, $7}'
                echo
                echo "== 具备 sudo 权限的成员（wheel / sudo 组） =="
                getent group wheel sudo 2>/dev/null || echo "（这台机器上既没有 wheel 组也没有 sudo 组）"
                echo
                echo "== 当前登录会话 =="
                who 2>/dev/null
                """);
    }

    /**
     * 创建用户（「创建用户」弹窗的落地）。
     * <p>
     * 三步：{@code useradd -m -s /bin/bash} 建号并给家目录 → {@code chpasswd} 设初始密码 →
     * 可选加入 sudo 组。用户名已存在时直接拒绝，不做「顺手改成改密码」这种隐含动作。
     *
     * @param grantSudo 是否授予 sudo。是否允许由界面按「当前登录用户是不是 root」决定，
     *                  这里是执行层，只负责做或不做
     */
    public String createUser(String name, String password, boolean grantSudo, LinuxEnv env) throws IOException {
        String userName = validateUserName(name);
        validatePassword(password);
        String prefix = rootPrefix(env, "创建用户");
        if (runner.exec(prefix + "id -u " + q(userName)).isOk()) {
            throw new IOException("目标机上已存在用户 " + userName + "，请换一个名字，或用「重置密码」改它的密码");
        }

        StringBuilder report = new StringBuilder();
        report.append("$ useradd -m -s /bin/bash ").append(userName).append('\n');
        SshCommandRunner.Result add = runner.exec(prefix + "useradd -m -s /bin/bash " + q(userName));
        report.append(add.getOutput()).append('\n');
        if (!add.isOk()) {
            throw new IOException("创建用户失败：" + add.errorMessage() + "\n\n" + report);
        }
        report.append("✓ 用户 ").append(userName).append(" 已创建（家目录 /home/").append(userName)
                .append("，shell /bin/bash）\n");

        report.append("$ （设置初始密码，命令里只出现 base64）\n");
        SshCommandRunner.Result passwd = runner.exec(prefix + "sh -c " + q(chpasswdCommand(userName, password)));
        String passwdOut = StrUtil.trimToEmpty(passwd.getOutput());
        if (passwd.isOk()) {
            report.append("✓ 初始密码已设置\n");
        } else {
            report.append("✗ 密码设置失败：").append(passwd.errorMessage()).append('\n')
                    .append("  用户已经建好了，可以用「重置密码」再设一次\n");
        }
        if (StrUtil.isNotBlank(passwdOut)) {
            report.append(passwdOut).append('\n');
        }

        if (grantSudo) {
            report.append('\n').append(appendSudo(env, userName, prefix));
        } else {
            report.append("\n（未勾选授予 sudo，需要用 sudo 时再点「加入 sudo 组」）\n");
        }

        SshCommandRunner.Result id = runner.exec("id " + q(userName));
        report.append("\n-- id ").append(userName).append(" --\n").append(id.getOutput()).append('\n');
        return report.toString();
    }

    /** 重置密码（同样 base64 + chpasswd，明文不进命令行） */
    public String resetPassword(String name, String password, LinuxEnv env) throws IOException {
        String userName = validateUserName(name);
        validatePassword(password);
        String prefix = rootPrefix(env, "重置密码");
        if (!runner.exec(prefix + "id -u " + q(userName)).isOk()) {
            throw new IOException("目标机上没有用户 " + userName);
        }
        String out = runEchoing(prefix + "sh -c " + q(chpasswdCommand(userName, password)));
        return out + "已重置 " + userName + " 的密码（密码内容不会出现在命令与输出里）。\n";
    }

    /** 加入 / 移出 sudo 组（RHEL 系 wheel，Debian 系 sudo） */
    public String setSudo(String name, boolean grant, LinuxEnv env) throws IOException {
        String userName = validateUserName(name);
        String prefix = rootPrefix(env, (grant ? "授予" : "移除") + " sudo 权限");
        if (!runner.exec(prefix + "id -u " + q(userName)).isOk()) {
            throw new IOException("目标机上没有用户 " + userName);
        }
        return grant ? appendSudo(env, userName, prefix) : removeSudo(env, userName, prefix);
    }

    /** 锁定 / 解锁账号（离职停用但保留数据时用） */
    public String setUserLocked(String name, boolean lock, LinuxEnv env) throws IOException {
        String userName = validateUserName(name);
        String prefix = rootPrefix(env, (lock ? "锁定" : "解锁") + "用户");
        if (!runner.exec(prefix + "id -u " + q(userName)).isOk()) {
            throw new IOException("目标机上没有用户 " + userName);
        }
        String out = runEchoing(prefix + "passwd " + (lock ? "-l " : "-u ") + q(userName));
        SshCommandRunner.Result status = runner.exec(prefix + "passwd -S " + q(userName));
        return out + "\n-- 账号状态（第二列 L=已锁定，P=可用） --\n" + status.getOutput() + "\n";
    }

    /** 删除用户，可选一并删除家目录与邮件（-r） */
    public String deleteUser(String name, boolean removeHome, LinuxEnv env) throws IOException {
        String userName = validateUserName(name);
        String prefix = rootPrefix(env, "删除用户");
        if (!runner.exec(prefix + "id -u " + q(userName)).isOk()) {
            throw new IOException("目标机上没有用户 " + userName);
        }
        String out = runEchoing(prefix + "userdel " + (removeHome ? "-r " : "") + q(userName));
        if (runner.exec("id " + q(userName)).isOk()) {
            return out + "\n注意：用户 " + userName + " 仍然存在，删除没成功（看上面的输出）。\n";
        }
        return out + "\n✓ 用户 " + userName + " 已删除" + (removeHome ? "（家目录一并删除）" : "（家目录保留）") + "。\n";
    }

    private String appendSudo(LinuxEnv env, String userName, String prefix) throws IOException {
        String group = env.sudoGroupName();
        if (StrUtil.isBlank(group)) {
            return "注意：这台机器上既没有 wheel 组也没有 sudo 组，跳过授予 sudo。\n";
        }
        StringBuilder report = new StringBuilder();
        report.append("$ usermod -aG ").append(group).append(' ').append(userName).append('\n');
        SshCommandRunner.Result result = runner.exec(prefix + "usermod -aG " + group + " " + q(userName));
        report.append(result.getOutput()).append('\n');
        report.append(result.isOk()
                ? "✓ 已把 " + userName + " 加入 " + group + " 组，重新登录后可 sudo（sudo 时需输入它自己的密码）\n"
                : "✗ 加入 " + group + " 组失败：" + result.errorMessage() + "\n");
        return report.toString();
    }

    private String removeSudo(LinuxEnv env, String userName, String prefix) throws IOException {
        String group = env.sudoGroupName();
        if (StrUtil.isBlank(group)) {
            throw new IOException("这台机器上既没有 wheel 组也没有 sudo 组，没有可移除的 sudo 授权");
        }
        StringBuilder report = new StringBuilder();
        report.append("$ gpasswd -d ").append(userName).append(' ').append(group).append('\n');
        SshCommandRunner.Result result = runner.exec(prefix + "gpasswd -d " + q(userName) + " " + group);
        report.append(result.getOutput()).append('\n');
        report.append(result.isOk()
                ? "✓ 已把 " + userName + " 移出 " + group + " 组\n"
                : "✗ 移出失败：" + result.errorMessage() + "\n");
        return report.toString();
    }

    /* ================================================================== */
    /* 服务管理                                                            */
    /* ================================================================== */

    /** 查看服务状态 */
    public String serviceStatus(String name, LinuxEnv env) throws IOException {
        String unit = validateUnit(name);
        if (env.isSystemdUsable()) {
            return run("systemctl status " + unit + " --no-pager -l 2>&1 | head -n 40");
        }
        return runEchoing("service " + unit + " status 2>&1");
    }

    /**
     * 启动 / 停止 / 重启 / 开机自启（enable）/ 取消自启（disable）。
     * <p>
     * 没有 systemd 的机器上只有 start / stop / restart 有等价命令，enable / disable 直接拒绝。
     */
    public String serviceAction(String name, String action, LinuxEnv env) throws IOException {
        String unit = validateUnit(name);
        String prefix = rootPrefix(env, action + "服务 " + unit);
        if (!env.isSystemdUsable()) {
            if (!Set.of("start", "stop", "restart").contains(action)) {
                throw new IOException("目标机上 systemd 不可用，「" + action + "」需要 systemctl");
            }
            return runEchoing(prefix + "service " + unit + " " + action + " 2>&1");
        }
        String command = prefix + "systemctl " + action + " " + unit + " 2>&1";
        StringBuilder out = new StringBuilder("$ ").append(command).append('\n');
        SshCommandRunner.Result result = runner.exec(command);
        out.append(result.getOutput()).append('\n');
        SshCommandRunner.Result active = runner.exec(prefix + "systemctl is-active " + unit + " 2>/dev/null");
        out.append("当前状态：").append(StrUtil.trimToEmpty(active.getOutput())).append('\n');
        if (!result.isOk()) {
            out.append("提示：退出码 ").append(result.getExitCode())
                    .append("。先确认服务名对不对（systemctl list-unit-files | grep 关键字）。\n");
        }
        return out.toString();
    }

    /** 正在运行的服务列表 */
    public String listRunningServices(LinuxEnv env) throws IOException {
        if (env.isSystemdUsable()) {
            return run("systemctl list-units --type=service --state=running --no-pager 2>&1 | head -n 60");
        }
        return runEchoing("service --status-all 2>&1 | head -n 60");
    }

    /* ================================================================== */
    /* 系统巡检                                                            */
    /* ================================================================== */

    /** 系统概览：主机、负载、内存、磁盘、CPU / 内存占用最高的进程 */
    public String overview() throws IOException {
        return run("""
                OSNAME=$(sed -n 's/^PRETTY_NAME=//p' /etc/os-release 2>/dev/null | tr -d '"')
                echo "================ 主机 ================"
                echo "主机名 : $(hostname)"
                echo "系统   : ${OSNAME:-未知}"
                echo "内核   : $(uname -r)"
                echo "时间   : $(date '+%Y-%m-%d %H:%M:%S %Z')"
                echo "运行   : $(uptime 2>/dev/null | sed 's/^ *//')"
                echo
                echo "================ 内存 ================"
                free -h 2>/dev/null
                echo
                echo "================ 磁盘 ================"
                df -hT -x tmpfs -x devtmpfs 2>/dev/null || df -h
                echo
                echo "================ 进程 TOP5（CPU） ================"
                ps -eo pid,user,%cpu,%mem,stat,etime,comm --sort=-%cpu 2>/dev/null | head -n 6
                echo
                echo "================ 进程 TOP5（内存） ================"
                ps -eo pid,user,%cpu,%mem,stat,etime,comm --sort=-%mem 2>/dev/null | head -n 6
                """);
    }

    /** 磁盘空间与 inode 使用（inode 满了一样写不进文件，很多人只盯容量） */
    public String diskUsage() throws IOException {
        return run("""
                echo "================ 磁盘空间 ================"
                df -hT -x tmpfs -x devtmpfs 2>/dev/null || df -h
                echo
                echo "================ inode 使用 ================"
                df -i -x tmpfs -x devtmpfs 2>/dev/null || df -i
                echo
                echo "================ 磁盘 IO（装了 sysstat 才有） ================"
                (command -v iostat >/dev/null 2>&1 && iostat -x 1 1 2>/dev/null | head -n 20) || echo "（没装 sysstat，跳过 iostat）"
                """);
    }

    /** 根目录下各目录占用 Top15，以及 /var/log 明细（找「磁盘被谁吃了」的第一步） */
    public String topDirectories() throws IOException {
        return run("""
                echo "== / 下各目录占用（不含其它挂载点） =="
                du -xhd1 / 2>/dev/null | sort -rh | head -n 15
                echo
                echo "== /var/log 占用 =="
                du -xhd1 /var/log 2>/dev/null | sort -rh | head -n 10
                """);
    }

    /**
     * 进程排行。
     *
     * @param by {@code cpu} 或 {@code mem}
     */
    public String topProcesses(String by) throws IOException {
        boolean byMem = "mem".equalsIgnoreCase(StrUtil.trimToEmpty(by));
        String column = byMem ? "%mem" : "%cpu";
        String title = byMem ? "内存" : "CPU";
        String script = """
                echo "== %s 占用 TOP15 =="
                ps -eo pid,ppid,user,%%cpu,%%mem,stat,etime,args --sort=-%s 2>/dev/null | head -n 16
                """.formatted(title, column);
        return run(script);
    }

    /** 监听中的端口与对应进程 */
    public String listeningPorts() throws IOException {
        return run("""
                if command -v ss >/dev/null 2>&1; then
                  ss -lntup 2>/dev/null | head -n 60
                elif command -v netstat >/dev/null 2>&1; then
                  netstat -lntup 2>/dev/null | head -n 60
                else
                  echo "机器上既没有 ss 也没有 netstat"
                fi
                """);
    }

    /** 查某个端口被谁占了（排查「端口冲突」用） */
    public String portOwner(int port) throws IOException {
        validatePort(port);
        String script = """
                echo "== 监听 %d 的进程 =="
                if command -v ss >/dev/null 2>&1; then
                  ss -lntup 2>/dev/null | grep -E "[:.]%d[[:space:]]" || echo "没有进程在监听 %d"
                elif command -v netstat >/dev/null 2>&1; then
                  netstat -lntup 2>/dev/null | grep -E "[:.]%d[[:space:]]" || echo "没有进程在监听 %d"
                else
                  echo "没有 ss / netstat，无法查询"
                fi
                if command -v lsof >/dev/null 2>&1; then
                  echo
                  echo "== lsof -i :%d =="
                  lsof -nP -i :%d 2>/dev/null | head -n 20
                fi
                """.formatted(port, port, port, port, port, port, port);
        return run(script);
    }

    /** 当前登录会话与最近登录记录 */
    public String loggedInUsers() throws IOException {
        return run("""
                echo "== 当前登录 =="
                who 2>/dev/null
                echo
                w 2>/dev/null | head -n 10
                echo
                echo "== 最近登录记录 =="
                last -n 15 2>/dev/null || echo "（没有 last 命令）"
                """);
    }

    /** 系统日志尾部：journalctl 优先，退到 /var/log/messages、/var/log/syslog */
    public String systemLog(int lines) throws IOException {
        int count = (lines <= 0 || lines > 2000) ? 200 : lines;
        return run("""
                if command -v journalctl >/dev/null 2>&1; then
                  journalctl -n %d --no-pager 2>/dev/null
                elif [ -f /var/log/messages ]; then
                  tail -n %d /var/log/messages
                elif [ -f /var/log/syslog ]; then
                  tail -n %d /var/log/syslog
                else
                  echo "找不到可读的系统日志（journalctl / /var/log/messages / /var/log/syslog 都没有）"
                fi
                """.formatted(count, count, count));
    }

    /** 时间同步状态（证书、集群、定时任务全依赖系统时间准） */
    public String timeSyncStatus() throws IOException {
        return run("""
                echo "== 系统时间 =="
                date '+%Y-%m-%d %H:%M:%S %Z (%z)'
                echo
                (command -v timedatectl >/dev/null 2>&1 && timedatectl status 2>/dev/null) || true
                echo
                echo "== 时间同步服务 =="
                (command -v chronyc >/dev/null 2>&1 && chronyc tracking 2>/dev/null | head -n 12) || true
                (command -v ntpq >/dev/null 2>&1 && ntpq -p 2>/dev/null | head -n 10) || true
                ( (command -v chronyd >/dev/null 2>&1 || command -v ntpd >/dev/null 2>&1) || echo "（没有 chrony / ntpd，可能需要先装）" )
                """);
    }

    /** 立即校时 */
    public String syncTimeNow(LinuxEnv env) throws IOException {
        String prefix = rootPrefix(env, "校时");
        String inner = "(command -v chronyc >/dev/null 2>&1 && chronyc makestep) "
                + "|| (systemctl restart chronyd 2>/dev/null) "
                + "|| (systemctl restart chrony 2>/dev/null) "
                + "|| (systemctl restart ntpd 2>/dev/null) "
                + "|| (command -v ntpdate >/dev/null 2>&1 && ntpdate -u pool.ntp.org) "
                + "|| echo '没有可用的校时工具（chronyc / ntpdate）'; date '+%Y-%m-%d %H:%M:%S %Z'";
        return runEchoing(prefix + "sh -c " + q(inner));
    }

    /** SELinux 状态（Rocky / CentOS 上「服务起不来」的头号嫌疑） */
    public String selinuxStatus() throws IOException {
        return run("""
                if command -v getenforce >/dev/null 2>&1; then
                  echo "当前模式：$(getenforce)"
                  echo
                  (command -v sestatus >/dev/null 2>&1 && sestatus 2>/dev/null | head -n 10) || true
                  echo
                  echo "== /etc/selinux/config =="
                  grep -E '^[[:space:]]*SELINUX' /etc/selinux/config 2>/dev/null || echo "（读不到配置文件）"
                else
                  echo "这台机器没有 SELinux（getenforce 不存在）"
                fi
                """);
    }

    /**
     * 关闭 / 打开 SELinux。
     *
     * @param permanent false 只改运行时（setenforce，立即生效、重启后复原）；
     *                  true 改 /etc/selinux/config（重启后生效）
     */
    public String setSelinux(boolean enabled, boolean permanent, LinuxEnv env) throws IOException {
        String prefix = rootPrefix(env, "修改 SELinux");
        StringBuilder script = new StringBuilder();
        if (!permanent) {
            script.append(prefix).append("setenforce ").append(enabled ? "1" : "0").append(" 2>&1\n");
        } else {
            script.append(prefix).append("sed -i 's/^SELINUX=.*/SELINUX=")
                    .append(enabled ? "enforcing" : "disabled").append("/' /etc/selinux/config 2>&1\n")
                    .append("echo 配置文件已改为 SELINUX=").append(enabled ? "enforcing" : "disabled")
                    .append("，需要重启才生效\n")
                    .append("grep -E '^[[:space:]]*SELINUX' /etc/selinux/config 2>/dev/null\n");
        }
        script.append("\n-- 操作后状态 --\n")
                .append("(command -v getenforce >/dev/null 2>&1 && echo \"运行时：$(getenforce)\") || echo '没有 SELinux'\n");
        return runEchoing(script.toString());
    }

    /** 主机名、网卡地址、默认路由、/etc/hosts */
    public String hostInfo() throws IOException {
        return run("""
                echo "== 主机名 =="
                hostname
                (command -v hostnamectl >/dev/null 2>&1 && hostnamectl status 2>/dev/null | head -n 12) || true
                echo
                echo "== 网卡地址 =="
                (command -v ip >/dev/null 2>&1 && ip -brief address 2>/dev/null) || (command -v ifconfig >/dev/null 2>&1 && ifconfig -a 2>/dev/null | head -n 40)
                echo
                echo "== 默认路由 =="
                (command -v ip >/dev/null 2>&1 && ip route show default 2>/dev/null) || echo "（没有 ip 命令）"
                echo
                echo "== /etc/hosts =="
                grep -v '^#' /etc/hosts 2>/dev/null | grep -v '^$'
                """);
    }

    /** 修改主机名（hostnamectl 优先；顺带把新名字写进 /etc/hosts，避免解析退化） */
    public String setHostname(String name, LinuxEnv env) throws IOException {
        String hostName = validateHostName(name);
        String prefix = rootPrefix(env, "修改主机名");
        StringBuilder script = new StringBuilder();
        if (env.isSystemdUsable()) {
            script.append(prefix).append("hostnamectl set-hostname ").append(q(hostName)).append(" 2>&1\n");
        } else {
            script.append(prefix).append("sh -c ").append(q("echo " + hostName + " > /etc/hostname")).append(" 2>&1\n")
                    .append(prefix).append("hostname ").append(q(hostName)).append(" 2>&1\n");
        }
        script.append(prefix).append("sh -c ")
                .append(q("grep -q '[[:space:]]" + hostName + "$' /etc/hosts || echo '127.0.0.1 " + hostName + "' >> /etc/hosts"))
                .append(" 2>&1\n")
                .append("echo\n-- 当前主机名 --\nhostname\n");
        return runEchoing(script.toString());
    }

    /** 计划任务：当前用户与 root 的 crontab、/etc/cron.d、/etc/crontab、systemd timer */
    public String crontabList(LinuxEnv env) throws IOException {
        String sudo = sudoPrefix(env);
        return run("""
                echo "== 当前用户 crontab =="
                crontab -l 2>/dev/null || echo "（当前用户没有计划任务）"
                echo
                echo "== root crontab =="
                %scrontab -l 2>/dev/null || echo "（root 没有计划任务，或当前账号无权查看）"
                echo
                echo "== /etc/cron.d =="
                ls -l /etc/cron.d/ 2>/dev/null
                for f in /etc/cron.d/*; do
                  [ -f "$f" ] && { echo "--- $f ---"; cat "$f"; }
                done 2>/dev/null
                echo
                echo "== /etc/crontab =="
                grep -v '^#' /etc/crontab 2>/dev/null | grep -v '^$'
                echo
                echo "== systemd 定时器 =="
                (command -v systemctl >/dev/null 2>&1 && systemctl list-timers --no-pager 2>/dev/null | head -n 15) || true
                """.formatted(sudo));
    }

    /**
     * 电源操作：重启 / 关机 / 取消已排队的关机。
     * <p>
     * 界面必须先在确认弹窗里让用户看清目标机是哪台——这条命令一发，SSH 会话就断了。
     */
    public String powerAction(String action, LinuxEnv env) throws IOException {
        String prefix = rootPrefix(env, "重启或关机");
        String command = switch (StrUtil.trimToEmpty(action)) {
            case "reboot" -> prefix + "shutdown -r now 2>&1 || " + prefix + "reboot 2>&1";
            case "poweroff" -> prefix + "shutdown -h now 2>&1 || " + prefix + "poweroff 2>&1";
            case "cancel" -> prefix + "shutdown -c 2>&1";
            default -> throw new IOException("不支持的操作：" + action);
        };
        try {
            SshCommandRunner.Result result = runner.exec(command);
            return "已向目标机下发命令：\n$ " + command + "\n\n" + result.getOutput() + "\n";
        } catch (IOException e) {
            // 关机瞬间服务端把连接断开是正常现象，不当失败处理
            return "已向目标机下发命令：\n$ " + command + "\n\n"
                    + "连接在命令下发后被断开（关机 / 重启的正常现象）：" + e.getMessage() + "\n";
        }
    }

    /* ================================================================== */
    /* 通用执行与校验                                                       */
    /* ================================================================== */

    /**
     * 跑一条展示类命令并把输出原样返回。
     * <p>
     * 退出码非 0 不抛异常：查状态时 {@code systemctl is-active} 返回 3、{@code ufw status}
     * 在未启用时返回 1 都是正常结果，输出本身才是要看的东西，抛异常会把内容吞掉。
     */
    public String run(String command) throws IOException {
        SshCommandRunner.Result result = runner.exec(command);
        String out = result.getOutput();
        if (!result.isOk()) {
            out = appendLine(out, "（命令退出码 " + result.getExitCode() + "，上面就是它的完整输出）");
        }
        return out;
    }

    /** 同 {@link #run}，但在输出最前面把「执行了哪条命令」打出来，便于用户核对 */
    private String runEchoing(String command) throws IOException {
        SshCommandRunner.Result result = runner.exec(command);
        StringBuilder out = new StringBuilder();
        // 含 base64 的命令（改密码）不在界面上回显，避免把凭据材料摆出来
        if (!command.contains("base64 -d")) {
            out.append("$ ").append(command).append('\n');
        }
        out.append(result.getOutput());
        if (!result.isOk()) {
            out.append(StrUtil.isBlank(result.getOutput()) ? "" : "\n")
                    .append("（命令退出码 ").append(result.getExitCode()).append("）");
        }
        return out.toString();
    }

    private static String appendLine(String text, String line) {
        StringBuilder sb = new StringBuilder(StrUtil.nullToEmpty(text));
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
        return sb.append(line).append('\n').toString();
    }

    /** 需要 root 的动作先在这里拦：拿不到权限就给「怎么办」，而不是让远端报 permission denied */
    private String rootPrefix(LinuxEnv env, String action) throws IOException {
        String prefix = env.privilegePrefix();
        if (prefix == null) {
            throw new IOException("无法" + action + "：当前登录用户 "
                    + StrUtil.emptyToDefault(env.getLoginUser(), "?") + "（uid=" + env.getUid()
                    + "）不是 root，且目标机上不能免密 sudo。\n"
                    + "请改用 root 账号连接，或先在目标机给这个账号配好免密 sudo。");
        }
        return prefix;
    }

    /**
     * 只读动作的 sudo 前缀：能免密 sudo 就带上，不能就空着。
     * <p>
     * 有些查询（{@code firewall-cmd --list-all}）非 root 会直接拒绝，带上前缀能避免
     * 「明明配了 sudo，查规则还是报权限不足」。
     */
    private static String sudoPrefix(LinuxEnv env) {
        String prefix = env.privilegePrefix();
        return prefix == null ? "" : prefix;
    }

    private static void validatePort(int port) throws IOException {
        if (port < 1 || port > 65535) {
            throw new IOException("端口必须是 1~65535 之间的整数，收到：" + port);
        }
    }

    private static String validateProto(String proto) throws IOException {
        String protocol = StrUtil.trimToEmpty(proto).toLowerCase();
        if (!"tcp".equals(protocol) && !"udp".equals(protocol)) {
            throw new IOException("协议只支持 tcp / udp，收到：" + proto);
        }
        return protocol;
    }

    private static String validateUserName(String name) throws IOException {
        String userName = StrUtil.trimToEmpty(name);
        if (!USER_NAME.matcher(userName).matches()) {
            throw new IOException("用户名不合法：" + name
                    + "。只允许字母、数字、下划线、短横线，长度 1~32，且不能以数字或短横线开头");
        }
        if (RESERVED_USERS.contains(userName)) {
            throw new IOException(userName + " 属于系统账号名，不能新建（要改它的密码请用「重置密码」）");
        }
        return userName;
    }

    private static void validatePassword(String password) throws IOException {
        if (password == null || password.length() < 6) {
            throw new IOException("密码至少 6 位");
        }
        if (password.contains("\n") || password.contains("\r")) {
            throw new IOException("密码里不能有换行符");
        }
    }

    private static String validateUnit(String name) throws IOException {
        String unit = StrUtil.trimToEmpty(name);
        if (!UNIT_NAME.matcher(unit).matches()) {
            throw new IOException("服务名不合法：" + name + "。只允许字母、数字与 . _ - @ :");
        }
        return unit;
    }

    private static String validateHostName(String name) throws IOException {
        String hostName = StrUtil.trimToEmpty(name);
        if (hostName.length() > 64 || !HOST_NAME.matcher(hostName).matches()) {
            throw new IOException("主机名不合法：" + name + "。只允许字母数字与短横线，且不能以短横线开头或结尾");
        }
        return hostName;
    }

    /** {@code chpasswd} 的输入：{@code 用户名:密码}，整体 base64 后送过去 */
    private static String chpasswdCommand(String userName, String password) {
        String encoded = Base64.getEncoder()
                .encodeToString((userName + ":" + password).getBytes(StandardCharsets.UTF_8));
        return "echo " + encoded + " | base64 -d | chpasswd";
    }

    /** shell 单引号转义（同 {@code DockerExecutor.q}） */
    private static String q(String raw) {
        if (raw == null) {
            return "''";
        }
        return "'" + raw.replace("'", "'\\''") + "'";
    }
}
