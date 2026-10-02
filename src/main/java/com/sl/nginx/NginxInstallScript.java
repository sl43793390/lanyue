package com.sl.nginx;

/**
 * 内置的 nginx 安装脚本，<b>按目标发行版分多份</b>，结构与 {@code com.sl.docker.DockerInstallScript}
 * 同一套路：nginx 管理页检测不到 nginx 时，「一键安装」按 {@code /etc/os-release} 选定后下发：
 * <ul>
 *   <li><b>Debian / Ubuntu（含 22.04）</b>（apt）—— 发行版仓库自带 nginx，直接
 *       {@code apt-get install -y nginx}；</li>
 *   <li><b>CentOS 7.9</b>（yum）—— nginx 在 EPEL 源里。CentOS 7 已 EOL
 *       （2024-06-30），mirrorlist.centos.org 整个域名下线，先把还在引用死域名的系统源
 *       切到 centos-vault 归档（清华，阿里云兜底），再用归档地址装 epel-release，最后装 nginx；</li>
 *   <li><b>Rocky / Alma / RHEL / OEL / Anolis 8、9</b>（dnf）—— AppStream 仓库自带 nginx，
 *       无需 EPEL，直接 {@code dnf install -y nginx}；</li>
 *   <li><b>其余未知发行版</b> —— 通用脚本，运行时按 /etc/os-release 的 ID / ID_LIKE 分流。</li>
 * </ul>
 * <p>
 * 与 docker 安装脚本同样的原则：每份脚本由公共头（log/PATH/「已装过就跳过」）+ 分发版安装块 +
 * 公共尾（验装、打版本、配置检测、启动服务、等 master 进程、打结果标记）拼成。
 * 只做「装 + 起 + 验」，不改任何已有配置；输出每步带 {@code [install]} 前缀，
 * 最后以 {@link #RESULT_OK} / {@link #RESULT_FAILED} 收尾，websocket 侧靠标记判定成败。
 */
public final class NginxInstallScript {

    /** 界面与日志里标识安装是否成功的一行（websocket 侧按它判定，见 DockerTerminalRegistry.Spec） */
    public static final String RESULT_OK = "NGINX_INSTALL_RESULT=OK";
    public static final String RESULT_FAILED = "NGINX_INSTALL_RESULT=FAILED";

    /* ------------------------------------------------------------------ */
    /* 公共头 / 公共尾                                                      */
    /* ------------------------------------------------------------------ */

    /** 公共头：log 函数、PATH、「已装过就跳过安装块」（跳进对应发行版的安装块） */
    private static final String COMMON_HEAD = """
            # nginx 安装脚本（由运维平台下发）
            # 不用 set -e：某一步失败要继续往下试，最后统一看 command -v nginx
            log() { echo "[install] $*"; }
            nginx_running() {
              pgrep -x nginx >/dev/null 2>&1 || ps -ef | grep '[n]ginx: master' >/dev/null 2>&1
            }

            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            export DEBIAN_FRONTEND=noninteractive

            if command -v nginx >/dev/null 2>&1; then
              log "已检测到 nginx：$(command -v nginx)，跳过安装步骤"
            else
            """;

    /** 公共尾：安装块结束后统一收口——验装、配置检测、启动、等进程、打结果标记 */
    private static final String COMMON_TAIL = """
            fi

            if ! command -v nginx >/dev/null 2>&1; then
              log "安装失败：所有方式都没能装上 nginx"
              log "可手动排查：内网机器需要放通软件源，或离线拷贝 RPM/DEB 包安装"
              log "NGINX_INSTALL_RESULT=FAILED"
              exit 1
            fi

            log "nginx 已就位：$(command -v nginx)"
            log "版本：$(nginx -v 2>&1)"
            log "配置检测：$(nginx -t 2>&1)"

            # 启动 + 开机自启；systemd 不可用时退回 service / init 脚本
            if [ -d /run/systemd/system ] && command -v systemctl >/dev/null 2>&1; then
              systemctl enable nginx
              systemctl start nginx || log "systemctl 启动失败（多为此前遗留配置有误，可用 nginx -t 排查）"
            elif command -v service >/dev/null 2>&1; then
              service nginx start || log "service nginx start 失败"
            elif [ -x /etc/init.d/nginx ]; then
              /etc/init.d/nginx start
            else
              log "没有可用的服务管理器，跳过启动（可在本页面手动点「启动」）"
            fi

            # 等 master 进程起来，最多 10 秒
            i=0
            while [ $i -lt 5 ]; do
              nginx_running && break
              i=$((i + 1))
              sleep 2
            done

            if nginx_running; then
              log "nginx 服务已启动：$(ps -ef | grep '[n]ginx: master' | head -n 1)"
              log "默认配置文件：/etc/nginx/nginx.conf（源码编译安装在其它位置时以 nginx -V 输出为准）"
              log "NGINX_INSTALL_RESULT=OK"
              exit 0
            fi
            log "nginx 已安装但进程未起来（可能配置有误或 80 端口被占用），可稍后用本页面「启动」重试"
            log "NGINX_INSTALL_RESULT=FAILED"
            exit 1
            """;

    /* ------------------------------------------------------------------ */
    /* 各发行版的安装块                                                     */
    /* ------------------------------------------------------------------ */

    /** Debian / Ubuntu（含 22.04）：apt，发行版仓库自带 nginx */
    private static final String APT_BLOCK = """
                  log "使用 apt 安装（Debian / Ubuntu 系脚本）"
                  apt-get update || log "apt-get update 失败（不影响已缓存的索引，继续尝试安装）"
                  apt-get install -y nginx || log "apt 安装 nginx 失败"
            """;

    /**
     * CentOS 7.9：yum。nginx 在 EPEL 源里，而 CentOS 7 已 EOL——mirrorlist.centos.org
     * 已下线，裸机 yum 连 base 仓库都解析不了。所以顺序是：先把还在引用死域名的系统源
     * 切到清华 centos-vault 归档（7.9.2009，阿里云兜底），再用归档地址装 epel-release，
     * 最后装 nginx（及其依赖）。
     */
    private static final String CENTOS7_BLOCK = """
                  log "使用 yum 安装（CentOS 7 专用脚本，nginx 在 EPEL 源）"
                  # CentOS 7 已停止维护：mirrorlist.centos.org 已下线，凡是还在引用它的源一律换掉
                  if grep -rqsE 'mirror(list)?\\.centos\\.org' /etc/yum.repos.d/; then
                    log "检测到已失效的 mirrorlist.centos.org 源，切换到 centos-vault 归档"
                    BK=/etc/yum.repos.d.bak-$(date +%Y%m%d%H%M%S)
                    cp -a /etc/yum.repos.d "$BK" 2>/dev/null && log "原 repo 目录已备份到 $BK"
                    VAULT=https://mirrors.tuna.tsinghua.edu.cn/centos-vault/7.9.2009
                    if ! curl -fsSL --connect-timeout 5 -o /dev/null "$VAULT/os/x86_64/repodata/repomd.xml"; then
                      log "清华源不可达，改用阿里云 centos-vault"
                      VAULT=https://mirrors.aliyun.com/centos-vault/7.9.2009
                    fi
                    log "系统源使用归档地址：$VAULT"
                    cat > /etc/yum.repos.d/CentOS-Base.repo <<REPO
[base]
name=CentOS-7.9.2009 - Base
baseurl=$VAULT/os/\\$basearch/
gpgcheck=1
gpgkey=file:///etc/pki/rpm-gpg/RPM-GPG-KEY-CentOS-7
       https://mirrors.tuna.tsinghua.edu.cn/centos-vault/7.9.2009/os/x86_64/RPM-GPG-KEY-CentOS-7
enabled=1

[updates]
name=CentOS-7.9.2009 - Updates
baseurl=$VAULT/updates/\\$basearch/
gpgcheck=1
gpgkey=file:///etc/pki/rpm-gpg/RPM-GPG-KEY-CentOS-7
       https://mirrors.tuna.tsinghua.edu.cn/centos-vault/7.9.2009/os/x86_64/RPM-GPG-KEY-CentOS-7
enabled=1

[extras]
name=CentOS-7.9.2009 - Extras
baseurl=$VAULT/extras/\\$basearch/
gpgcheck=1
gpgkey=file:///etc/pki/rpm-gpg/RPM-GPG-KEY-CentOS-7
       https://mirrors.tuna.tsinghua.edu.cn/centos-vault/7.9.2009/os/x86_64/RPM-GPG-KEY-CentOS-7
enabled=1
REPO
                    # 其余还在引用死域名的 repo 文件（cr / fasttrack / Vault 等）改名禁用，可随时改回
                    for f in /etc/yum.repos.d/*.repo; do
                      case "$f" in */CentOS-Base.repo) continue ;; esac
                      if grep -qsE 'mirror(list)?\\.centos\\.org' "$f"; then
                        log "禁用失效源文件：$(basename "$f")"
                        mv "$f" "$f.disabled"
                      fi
                    done
                    yum clean all
                  fi
                  # EPEL：本地/镜像里已有 epel-release 就直接用；没有就按归档地址装
                  if ! rpm -q epel-release >/dev/null 2>&1; then
                    log "安装 EPEL 源（CentOS 7 已 EOL，走归档地址）"
                    yum install -y epel-release \\
                      || yum install -y https://dl.fedoraproject.org/pub/epel/epel-release-latest-7.noarch.rpm \\
                      || yum install -y https://archives.fedoraproject.org/pub/archive/epel/7/x86_64/Packages/e/epel-release-7-14.noarch.rpm \\
                      || log "EPEL 源没装上，nginx 大概率装不了"
                  fi
                  yum makecache fast || true
                  yum install -y nginx || log "yum 安装 nginx 失败"
            """;

    /** Rocky / Alma / RHEL / OEL / Anolis 8、9：dnf，AppStream 自带 nginx，无需 EPEL */
    private static final String DNF_BLOCK = """
                  log "使用 dnf 安装（Rocky / Alma / RHEL 系 8、9 专用脚本）"
                  dnf makecache || true
                  dnf install -y nginx || log "dnf 安装 nginx 失败"
            """;

    /** 未知发行版的通用安装块：运行时按 /etc/os-release 的 ID / ID_LIKE 分流 */
    private static final String UNIVERSAL_BLOCK = """
                  log "使用通用脚本（未知发行版，按 /etc/os-release 运行时分流）"
                  . /etc/os-release 2>/dev/null
                  OS_ID=$(echo "${ID:-}" | tr 'A-Z' 'a-z')
                  OS_LIKE=$(echo "${ID_LIKE:-}" | tr 'A-Z' 'a-z')
                  log "发行版：${PRETTY_NAME:-未知}（ID=$OS_ID ID_LIKE=$OS_LIKE）"
                  case " $OS_ID $OS_LIKE " in
                    *" ubuntu "*|*" debian "*|*" linuxmint "*|*" kylin "*|*" uos "*|*" deepin "*)
                      log "使用 apt 安装"
                      apt-get update || true
                      apt-get install -y nginx || log "apt 安装 nginx 失败"
                      ;;
                    *)
                      if command -v dnf >/dev/null 2>&1; then
                        log "使用 dnf 安装"
                        dnf makecache || true
                        dnf install -y nginx || log "dnf 安装 nginx 失败"
                      elif command -v yum >/dev/null 2>&1; then
                        log "使用 yum 安装（nginx 在 EPEL，先补 EPEL 源）"
                        yum install -y epel-release \\
                          || yum install -y https://dl.fedoraproject.org/pub/epel/epel-release-latest-7.noarch.rpm \\
                          || true
                        yum install -y nginx || log "yum 安装 nginx 失败"
                      else
                        log "没有 apt / dnf / yum，无法用包管理器安装"
                      fi
                      ;;
                  esac
            """;

    private NginxInstallScript() {
    }

    /** 组装一份完整脚本：公共头 + 发行版安装块 + 公共尾（尾部的 fi 闭合公共头的 else） */
    private static String assemble(String installBlock) {
        return COMMON_HEAD + installBlock.stripTrailing() + "\n" + COMMON_TAIL;
    }

    /**
     * 按目标系统选脚本。识别规则（依据 /etc/os-release 的 ID / VERSION_ID）：
     * <ul>
     *   <li>ubuntu / debian 系 → apt 专用脚本（22.04 同样走这份）；</li>
     *   <li>centos 且 7.x → CentOS 7 专用脚本（vault 归档 + EPEL）；</li>
     *   <li>rocky / almalinux / rhel / ol / anolis 系且 8.x / 9.x → dnf 专用脚本；</li>
     *   <li>其余 → 通用脚本。</li>
     * </ul>
     */
    public static String scriptFor(String osId, String versionId) {
        if (isDnfFamily(osId, versionId)) {
            return assemble(DNF_BLOCK);
        }
        if (isCentos7(osId, versionId)) {
            return assemble(CENTOS7_BLOCK);
        }
        if (isAptFamily(osId)) {
            return assemble(APT_BLOCK);
        }
        return assemble(UNIVERSAL_BLOCK);
    }

    /** 给用户看的脚本名（确认弹窗里的「已识别系统 → 使用 XX」一行） */
    public static String variantLabel(String osId, String versionId) {
        if (isDnfFamily(osId, versionId)) {
            return "RHEL 系 8/9 专用脚本（dnf，AppStream 自带 nginx）";
        }
        if (isCentos7(osId, versionId)) {
            return "CentOS 7 专用脚本（yum + centos-vault 归档 + EPEL）";
        }
        if (isAptFamily(osId)) {
            return "Debian / Ubuntu 专用脚本（apt）";
        }
        return "通用脚本（未知发行版，运行时按 /etc/os-release 自动分流）";
    }

    private static boolean isAptFamily(String osId) {
        String id = normalize(osId);
        return "ubuntu".equals(id) || "debian".equals(id) || "linuxmint".equals(id)
                || "kylin".equals(id) || "uos".equals(id) || "deepin".equals(id);
    }

    private static boolean isCentos7(String osId, String versionId) {
        return "centos".equals(normalize(osId)) && startsWithDigit(versionId, '7');
    }

    private static boolean isDnfFamily(String osId, String versionId) {
        String id = normalize(osId);
        boolean family = "rocky".equals(id) || "almalinux".equals(id) || "rhel".equals(id)
                || "ol".equals(id) || "anolis".equals(id) || "anolisos".equals(id);
        return family && (startsWithDigit(versionId, '8') || startsWithDigit(versionId, '9'));
    }

    private static String normalize(String osId) {
        return osId == null ? "" : osId.trim().toLowerCase();
    }

    /** 版本号是否以指定数字开头（7 / 8 / 9），空版本不算命中 */
    private static boolean startsWithDigit(String versionId, char digit) {
        return versionId != null && !versionId.isBlank() && versionId.trim().charAt(0) == digit;
    }
}
