package com.sl.docker.model;

import cn.hutool.core.util.StrUtil;

import java.io.Serializable;

/**
 * 目标机上 Docker Compose CLI 的探测结果。
 * <p>
 * Compose 有两种互不兼容的形态，必须探测清楚再用，不能写死：
 * <ul>
 *   <li><b>v2 插件</b>：{@code docker compose}（中间是空格），随 docker-ce 的
 *       {@code docker-compose-plugin} 一起装，是官方推荐形态；</li>
 *   <li><b>v1 独立二进制</b>：{@code docker-compose}（中间是短横线），已停止维护，
 *       Rocky 8 的 EPEL / CentOS 7 上很常见。</li>
 * </ul>
 * 两者子命令大体一致，但 {@code compose ls} / {@code --format json} 只有 v2 有，
 * 所有调用点都要先看 {@link #isV2()}。
 * <p>
 * <b>命令里必须带上 docker 前缀。</b>非 root 用户要 {@code sudo -n}，
 * 而 {@code sudo} 只作用于第一条命令，所以 {@code sudo -n /usr/bin/docker compose}
 * 是对的，{@code sudo -n /usr/bin/docker; docker compose} 是错的。
 */
public class ComposeCliInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 未检测到 compose 时给用户的说明（含按发行版区分的安装命令） */
    private String diagnosis = "";

    private boolean installed;
    /** 可直接拼进 shell 的完整命令，例如 {@code sudo -n /usr/bin/docker compose} */
    private String command = "docker compose";
    /** 探测到的 compose 版本号 */
    private String version = "";
    /** v2 / v1 / none */
    private String kind = "none";

    public boolean isInstalled() {
        return installed;
    }

    public void setInstalled(boolean installed) {
        this.installed = installed;
    }

    public boolean isV2() {
        return "v2".equals(kind);
    }

    public boolean isV1() {
        return "v1".equals(kind);
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = StrUtil.emptyToDefault(command, "docker compose");
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = StrUtil.emptyToDefault(version, "");
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = StrUtil.emptyToDefault(kind, "none");
    }

    public String getDiagnosis() {
        return diagnosis;
    }

    public void setDiagnosis(String diagnosis) {
        this.diagnosis = StrUtil.emptyToDefault(diagnosis, "");
    }

    /** 给界面显示的一行摘要 */
    public String summary() {
        if (!installed) {
            return "未检测到 Docker Compose";
        }
        return (isV2() ? "Compose v2 插件" : "Compose v1 独立二进制")
                + (StrUtil.isBlank(version) ? "" : " " + version)
                + "（`" + command + "`）";
    }

    /** 没有安装时的安装命令提示，按发行版区分 */
    public static String installHint(String osRelease) {
        String os = StrUtil.emptyToDefault(osRelease, "").toLowerCase();
        if (os.contains("ubuntu") || os.contains("debian")) {
            return "sudo apt-get update && sudo apt-get install -y docker-compose-plugin";
        }
        if (os.contains("rocky") || os.contains("centos") || os.contains("red hat")
                || os.contains("almalinux") || os.contains("oracle") || os.contains("fedora")) {
            return "sudo dnf install -y dnf-plugins-core && "
                    + "sudo dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo && "
                    + "sudo dnf install -y docker-compose-plugin";
        }
        return "参考 https://docs.docker.com/compose/install/linux/（推荐装 docker-compose-plugin）";
    }
}
