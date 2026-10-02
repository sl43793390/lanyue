package com.sl.ops.model;

import cn.hutool.core.util.StrUtil;

import java.io.Serializable;
import java.util.Set;

/**
 * 目标机的「运维环境快照」：一次探测拿回来，页面上的每个动作据此决定用哪条命令。
 * <p>
 * 需要它是因为同一件事在不同发行版上命令不同（防火墙 firewalld / ufw / 裸 iptables、
 * sudo 组在 RHEL 系叫 wheel 在 Debian 系叫 sudo），而这些差异只能现查不能写死。
 * 探测脚本见 {@link com.sl.ops.LinuxOpsService#probe()}，只读、不需要 root。
 */
public class LinuxEnv implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 防火墙实现类型 */
    public static final String FW_FIREWALLD = "firewalld";
    public static final String FW_UFW = "ufw";
    public static final String FW_IPTABLES = "iptables";
    public static final String FW_NONE = "none";

    private String osRelease = "";
    private String distroId = "";
    /** /etc/os-release 里的 VERSION_ID（centos 是 7，rocky 是 8/9，ubuntu 是 22.04/24.04） */
    private String versionId = "";
    private String uid = "";
    private String loginUser = "";
    private String home = "";
    private String hostname = "";
    private String kernel = "";
    private String uptime = "";
    private String selinux = "";

    private boolean sudoAvailable;
    private boolean systemdUsable;
    /** wheel 组存在（RHEL / CentOS / Rocky / openEuler） */
    private boolean wheelGroup;
    /** sudo 组存在（Debian / Ubuntu） */
    private boolean sudoGroup;
    /** 普通用户数量（UID >= 1000） */
    private int normalUserCount;

    private String firewallCmd = "";
    private String ufwCmd = "";
    private String iptablesCmd = "";
    private String firewalldActive = "";
    private String ufwActive = "";
    /** crontab 命令路径；为空说明目标机没装 cron（Rocky/Ubuntu 最小化安装常见） */
    private String crontabCmd = "";

    /** 探测本身失败时的原因（命令没跑起来 / 连接断了） */
    private String probeError = "";

    public boolean isRoot() {
        return "0".equals(StrUtil.trimToEmpty(uid));
    }

    /**
     * 目标机在用哪套防火墙。
     * <p>
     * 同时装着 firewalld 与 ufw 的机器很少见，但确实存在（Ubuntu 上装过 firewalld）。
     * 判定顺序是「谁在跑听谁的」：两个都在跑或都没跑时，优先 firewalld——它是
     * CentOS / Rocky 系的默认实现，也是运维最常打交道的那个。
     */
    public String firewallKind() {
        boolean hasFirewalld = StrUtil.isNotBlank(firewallCmd);
        boolean hasUfw = StrUtil.isNotBlank(ufwCmd);
        boolean firewalldRunning = isFirewalldActive();
        boolean ufwRunning = isUfwActive();
        if (firewalldRunning || ufwRunning) {
            return firewalldRunning ? FW_FIREWALLD : FW_UFW;
        }
        if (hasFirewalld) {
            return FW_FIREWALLD;
        }
        if (hasUfw) {
            return FW_UFW;
        }
        return StrUtil.isNotBlank(iptablesCmd) ? FW_IPTABLES : FW_NONE;
    }

    public boolean isFirewalldActive() {
        return "active".equalsIgnoreCase(StrUtil.trimToEmpty(firewalldActive));
    }

    public boolean isUfwActive() {
        return "active".equalsIgnoreCase(StrUtil.trimToEmpty(ufwActive));
    }

    public boolean isFirewallActive() {
        String kind = firewallKind();
        if (FW_FIREWALLD.equals(kind)) {
            return isFirewalldActive();
        }
        if (FW_UFW.equals(kind)) {
            return isUfwActive();
        }
        // 裸 iptables 判断"开没开"没有统一口径：默认策略 ACCEPT 就算没开
        return false;
    }

    /** 防火墙那一组按钮的标题：一眼看出用的是哪套、现在是开是关 */
    public String firewallLabel() {
        String kind = firewallKind();
        if (FW_NONE.equals(kind)) {
            return "未检测到 firewalld / ufw / iptables";
        }
        String name = switch (kind) {
            case FW_FIREWALLD -> "firewalld";
            case FW_UFW -> "ufw";
            default -> "iptables";
        };
        if (FW_IPTABLES.equals(kind)) {
            return "iptables（本页只能查看规则，开关请按 `iptables -P INPUT ACCEPT` 自行处理）";
        }
        return name + "（当前" + (isFirewallActive() ? "运行中" : "未运行") + "）";
    }

    /** 当前账号能不能改防火墙 / 装软件 / 建用户：root 或免密 sudo */
    public boolean canEscalate() {
        return isRoot() || sudoAvailable;
    }

    /** 探测到了 SELinux（getenforce 存在）。Ubuntu / Debian 默认不带，这类机器上 SELinux 相关按钮应该置灰 */
    public boolean hasSelinux() {
        return StrUtil.isNotBlank(selinux);
    }

    /**
     * 发行版家族：RHEL 系（centos / rocky / rhel / almalinux / ol / fedora 及国产衍生）
     * 还是 Debian 系（debian / ubuntu / uos / deepin）。
     * <p>
     * 没有现成命令差异可以靠「探测谁存在」兜底的场景（比如提示用什么包管理器装 cron），
     * 用家族判断给一句对症的提示。
     */
    public String familyId() {
        String id = StrUtil.trimToEmpty(distroId).toLowerCase();
        if (Set.of("centos", "rocky", "rhel", "almalinux", "ol", "fedora",
                "opencloudos", "anolis", "openeuler", "tencentos").contains(id)) {
            return "rhel";
        }
        if (Set.of("debian", "ubuntu", "uos", "deepin").contains(id)) {
            return "debian";
        }
        return "other";
    }

    /** 权限前缀：root 为空串，非 root 有免密 sudo 时是 {@code sudo -n }，都没有则返回 null */
    public String privilegePrefix() {
        if (isRoot()) {
            return "";
        }
        return sudoAvailable ? "sudo -n " : null;
    }

    /** 能授予 sudo 的组名：RHEL 系是 wheel，Debian 系是 sudo；都没有返回空串 */
    public String sudoGroupName() {
        if (wheelGroup) {
            return "wheel";
        }
        if (sudoGroup) {
            return "sudo";
        }
        return "";
    }

    public String summary() {
        if (StrUtil.isNotBlank(probeError)) {
            return "环境探测失败：" + probeError;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(StrUtil.emptyToDefault(osRelease, "未知系统"));
        if (StrUtil.isNotBlank(hostname)) {
            sb.append("　主机 ").append(hostname);
        }
        sb.append("　登录用户 ").append(StrUtil.emptyToDefault(loginUser, "?")).append("(uid=").append(uid).append(")");
        sb.append("　sudo ").append(sudoAvailable ? "免密可用" : "不可用");
        sb.append("　防火墙 ").append(firewallLabel());
        if (StrUtil.isNotBlank(selinux)) {
            sb.append("　SELinux ").append(selinux);
        }
        return sb.toString();
    }

    /* ---------------- getter / setter ---------------- */

    public String getOsRelease() {
        return osRelease;
    }

    public void setOsRelease(String osRelease) {
        this.osRelease = StrUtil.emptyToDefault(osRelease, "");
    }

    public String getDistroId() {
        return distroId;
    }

    public void setDistroId(String distroId) {
        this.distroId = StrUtil.emptyToDefault(distroId, "");
    }

    public String getVersionId() {
        return versionId;
    }

    public void setVersionId(String versionId) {
        this.versionId = StrUtil.emptyToDefault(versionId, "");
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

    public String getHome() {
        return home;
    }

    public void setHome(String home) {
        this.home = StrUtil.emptyToDefault(home, "");
    }

    public String getHostname() {
        return hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = StrUtil.emptyToDefault(hostname, "");
    }

    public String getKernel() {
        return kernel;
    }

    public void setKernel(String kernel) {
        this.kernel = StrUtil.emptyToDefault(kernel, "");
    }

    public String getUptime() {
        return uptime;
    }

    public void setUptime(String uptime) {
        this.uptime = StrUtil.emptyToDefault(uptime, "");
    }

    public String getSelinux() {
        return selinux;
    }

    public void setSelinux(String selinux) {
        this.selinux = StrUtil.emptyToDefault(selinux, "");
    }

    public boolean isSudoAvailable() {
        return sudoAvailable;
    }

    public void setSudoAvailable(boolean sudoAvailable) {
        this.sudoAvailable = sudoAvailable;
    }

    public boolean isSystemdUsable() {
        return systemdUsable;
    }

    public void setSystemdUsable(boolean systemdUsable) {
        this.systemdUsable = systemdUsable;
    }

    public boolean isWheelGroup() {
        return wheelGroup;
    }

    public void setWheelGroup(boolean wheelGroup) {
        this.wheelGroup = wheelGroup;
    }

    public boolean isSudoGroup() {
        return sudoGroup;
    }

    public void setSudoGroup(boolean sudoGroup) {
        this.sudoGroup = sudoGroup;
    }

    public int getNormalUserCount() {
        return normalUserCount;
    }

    public void setNormalUserCount(int normalUserCount) {
        this.normalUserCount = normalUserCount;
    }

    public String getFirewallCmd() {
        return firewallCmd;
    }

    public void setFirewallCmd(String firewallCmd) {
        this.firewallCmd = StrUtil.emptyToDefault(firewallCmd, "");
    }

    public String getUfwCmd() {
        return ufwCmd;
    }

    public void setUfwCmd(String ufwCmd) {
        this.ufwCmd = StrUtil.emptyToDefault(ufwCmd, "");
    }

    public String getIptablesCmd() {
        return iptablesCmd;
    }

    public void setIptablesCmd(String iptablesCmd) {
        this.iptablesCmd = StrUtil.emptyToDefault(iptablesCmd, "");
    }

    public String getFirewalldActive() {
        return firewalldActive;
    }

    public void setFirewalldActive(String firewalldActive) {
        this.firewalldActive = StrUtil.emptyToDefault(firewalldActive, "");
    }

    public String getUfwActive() {
        return ufwActive;
    }

    public void setUfwActive(String ufwActive) {
        this.ufwActive = StrUtil.emptyToDefault(ufwActive, "");
    }

    public String getCrontabCmd() {
        return crontabCmd;
    }

    public void setCrontabCmd(String crontabCmd) {
        this.crontabCmd = StrUtil.emptyToDefault(crontabCmd, "");
    }

    public String getProbeError() {
        return probeError;
    }

    public void setProbeError(String probeError) {
        this.probeError = StrUtil.emptyToDefault(probeError, "");
    }
}
