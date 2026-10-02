package com.sl.docker;

/**
 * 内置的 Docker Engine / Docker Compose 安装脚本，<b>按目标发行版分多份</b>，
 * 由 {@link DockerExecutor#probeDistro()} 读 /etc/os-release 选定后下发：
 * <ul>
 *   <li><b>Ubuntu 22.04 / 24.04</b>（apt）—— 先装发行版仓库的 docker.io，
 *       缺 compose 再从 Docker 官方源补 docker-compose-plugin；</li>
 *   <li><b>CentOS 7.9</b>（yum）—— docker-ce 官方 el7 源 + compose 插件，
 *       官方源不可达自动退回阿里云镜像（CentOS 7 已 EOL，这一步几乎是必经之路）；</li>
 *   <li><b>Rocky / Alma / RHEL / OEL / Anolis 8、9</b>（dnf）—— docker-ce 源
 *       （el8/el9 共用 centos 路径）+ compose 插件，同样带阿里云镜像兜底；</li>
 *   <li><b>其余未知发行版</b> —— 通用脚本，运行时按 /etc/os-release 的
 *       ID / ID_LIKE 分流，装不上再退回 get.docker.com 官方一键脚本。</li>
 * </ul>
 * <p>
 * <b>为什么不是一句 {@code curl -fsSL https://get.docker.com | sh}。</b>
 * 机房里的机器大多在内网，要么连不上 download.docker.com / get.docker.com，
 * 要么只能连发行版自己的源（或国内镜像）。专用脚本把「该系统最可能成功的那条路」
 * 放在第一位，镜像兜底放第二位，失败原因直接打在终端里。
 * <p>
 * 每份脚本都由三段拼成：公共头（log/PATH/「已装过就跳过」）+ 分发版的安装块 +
 * 公共尾（启动服务、把登录用户加进 docker 组、等 daemon 就绪、验证、打结果标记）。
 * 只做「装 + 起 + 验」，不碰任何已有配置：不写 {@code daemon.json}、不改镜像加速、
 * 不动已有的容器与数据。
 * <p>
 * <b>输出是可读的。</b>每一步都打 {@code [install] …} 前缀，实时终端把输出原样滚出，
 * 最后以 {@link #RESULT_OK} / {@link #RESULT_FAILED} 结果标记收尾，websocket 侧
 * 靠这个标记判定成败。
 */
public final class DockerInstallScript {

    /** 界面与日志里标识安装是否成功的一行 */
    public static final String RESULT_OK = "DOCKER_INSTALL_RESULT=OK";
    public static final String RESULT_FAILED = "DOCKER_INSTALL_RESULT=FAILED";

    /* ------------------------------------------------------------------ */
    /* 公共头 / 公共尾                                                      */
    /* ------------------------------------------------------------------ */

    /** 公共头：log 函数、PATH、已装过就跳过安装块（跳进对应发行版的安装块） */
    private static final String COMMON_HEAD = """
            # Docker Engine + Compose 安装脚本（由运维平台下发）
            # 不用 set -e：某一步失败要继续往下试，最后统一看 command -v docker
            log() { echo "[install] $*"; }

            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

            if command -v docker >/dev/null 2>&1; then
              log "已检测到 docker：$(command -v docker)，跳过安装步骤"
            else
            """;

    /** 公共尾：安装块结束后统一收口——验装、启动、加组、等就绪、打结果标记 */
    private static final String COMMON_TAIL = """
            fi

            if ! command -v docker >/dev/null 2>&1; then
              log "安装失败：所有方式都没能装上 docker"
              log "可手动排查：内网机器需要放通软件源，或离线拷贝 RPM/DEB 包安装"
              log "DOCKER_INSTALL_RESULT=FAILED"
              exit 1
            fi

            log "docker 已就位：$(command -v docker)"

            # 启动 + 开机自启；systemd 不可用时退回 service / init 脚本
            if [ -d /run/systemd/system ] && command -v systemctl >/dev/null 2>&1; then
              systemctl enable docker
              systemctl start docker
            elif command -v service >/dev/null 2>&1; then
              service docker start
            elif [ -x /etc/init.d/docker ]; then
              /etc/init.d/docker start
            else
              log "没有可用的服务管理器，跳过启动（可能需要手动执行 dockerd &）"
            fi

            # 把登录用户加进 docker 组：非 root 用户默认没权限访问 /var/run/docker.sock，
            # 加组之后不用 sudo 也能跑 docker（重新登录后生效）
            TARGET_USER="${SUDO_USER:-$(whoami)}"
            if [ -n "$TARGET_USER" ] && [ "$TARGET_USER" != "root" ] && getent group docker >/dev/null 2>&1; then
              usermod -aG docker "$TARGET_USER" 2>/dev/null && log "已把 $TARGET_USER 加入 docker 组（重新登录后免 sudo）"
            fi

            # 等 daemon 就绪，最多 30 秒
            i=0
            while [ $i -lt 15 ]; do
              if docker info >/dev/null 2>&1; then break; fi
              i=$((i + 1))
              sleep 2
            done

            if docker info >/dev/null 2>&1; then
              log "docker 服务已就绪：$(docker version --format '{{.Server.Version}}' 2>/dev/null)"
              log "docker compose：$(docker compose version 2>/dev/null || echo '未安装（不影响 docker 本身，可后补）')"
              log "DOCKER_INSTALL_RESULT=OK"
              exit 0
            fi
            log "docker 已安装，但 daemon 还没就绪（systemctl status docker 看原因）"
            log "DOCKER_INSTALL_RESULT=FAILED"
            exit 1
            """;

    /* ------------------------------------------------------------------ */
    /* 各发行版的安装块                                                     */
    /* ------------------------------------------------------------------ */

    /** Ubuntu 22.04 / 24.04：apt，docker.io 优先、docker-ce 源兜底、补 compose 插件 */
    private static final String APT_BLOCK = """
                  log "使用 apt 安装（Ubuntu 22.04 / 24.04 专用脚本）"
                  export DEBIAN_FRONTEND=noninteractive
                  # 官方源优先；连不上就退回阿里云镜像（内网 / 跨境网络常见）
                  add_docker_apt_repo() {
                    apt-get install -y ca-certificates curl gnupg
                    install -m 0755 -d /etc/apt/keyrings
                    curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc \\
                      || curl -fsSL https://mirrors.aliyun.com/docker-ce/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
                    chmod a+r /etc/apt/keyrings/docker.asc
                    local codename
                    codename=$(. /etc/os-release && echo "${VERSION_CODENAME:-jammy}")
                    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${codename} stable" \\
                      > /etc/apt/sources.list.d/docker.list
                    apt-get update
                  }
                  apt-get update
                  apt-get install -y docker.io || log "发行版仓库没装上 docker.io，改用 Docker 官方源"
                  if ! command -v docker >/dev/null 2>&1; then
                    add_docker_apt_repo
                    apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
                  fi
                  # compose 插件：docker.io 路径在 22.04 上不带 compose，单独补装
                  if command -v docker >/dev/null 2>&1 && ! docker compose version >/dev/null 2>&1; then
                    log "补装 docker compose 插件"
                    add_docker_apt_repo
                    apt-get install -y docker-compose-plugin || log "compose 插件没装上（不影响 docker 本身，可后补）"
                  fi
            """;

    /**
     * CentOS 7.9：yum。CentOS 7 已 EOL（2024-06-30），mirrorlist.centos.org 整个域名下线，
     * 裸机 yum 连 base 仓库都解析不了——所以先把还在引用死域名的系统源切到清华
     * centos-vault（7.9.2009 归档，阿里云兜底），再装 docker-ce（el7 源官方仍在，
     * 连不上退阿里云）。
     */
    private static final String CENTOS7_BLOCK = """
                  log "使用 yum 安装（CentOS 7 专用脚本）"
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
                  # docker-ce 的 el7 仓库官方仍在维护下载；连不上时退回阿里云镜像
                  yum install -y yum-utils || true
                  if ! curl -fsSL https://download.docker.com/linux/centos/docker-ce.repo -o /etc/yum.repos.d/docker-ce.repo; then
                    log "官方 docker-ce 源不可达，改用阿里云镜像"
                    curl -fsSL https://mirrors.aliyun.com/docker-ce/linux/centos/docker-ce.repo -o /etc/yum.repos.d/docker-ce.repo
                  fi
                  yum makecache fast || true
                  yum install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin \\
                    || { log "当前源安装失败，换阿里云镜像源重试"
                         curl -fsSL https://mirrors.aliyun.com/docker-ce/linux/centos/docker-ce.repo -o /etc/yum.repos.d/docker-ce.repo
                         yum makecache fast || true
                         yum install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin; }
                  if ! command -v docker >/dev/null 2>&1; then
                    log "docker-ce 没装上，改用发行版自带的 docker 包"
                    yum install -y docker || true
                  fi
            """;

    /** Rocky / Alma / RHEL / OEL / Anolis 8、9：dnf，docker-ce 源（el8/el9 共用 centos 路径）+ compose 插件 */
    private static final String DNF_BLOCK = """
                  log "使用 dnf 安装（Rocky / Alma / RHEL 系 8、9 专用脚本）"
                  if ! curl -fsSL https://download.docker.com/linux/centos/docker-ce.repo -o /etc/yum.repos.d/docker-ce.repo; then
                    log "官方 docker-ce 源不可达，改用阿里云镜像"
                    curl -fsSL https://mirrors.aliyun.com/docker-ce/linux/centos/docker-ce.repo -o /etc/yum.repos.d/docker-ce.repo
                  fi
                  dnf makecache || true
                  dnf install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
            """;

    /**
     * 未知发行版的通用安装块：运行时按 /etc/os-release 的 ID / ID_LIKE 分流，
     * 都不行再退回 get.docker.com 官方一键脚本。
     */
    private static final String UNIVERSAL_BLOCK = """
                  log "使用通用脚本（未知发行版，按 /etc/os-release 运行时分流）"
                  . /etc/os-release 2>/dev/null
                  OS_ID=$(echo "${ID:-}" | tr 'A-Z' 'a-z')
                  OS_LIKE=$(echo "${ID_LIKE:-}" | tr 'A-Z' 'a-z')
                  log "发行版：${PRETTY_NAME:-未知}（ID=$OS_ID ID_LIKE=$OS_LIKE）"
                  case " $OS_ID $OS_LIKE " in
                    *" ubuntu "*|*" debian "*|*" linuxmint "*|*" kylin "*|*" uos "*|*" deepin "*)
                      export DEBIAN_FRONTEND=noninteractive
                      log "使用 apt 安装（先试发行版仓库的 docker.io）"
                      apt-get update
                      apt-get install -y docker.io
                      if ! command -v docker >/dev/null 2>&1; then
                        log "docker.io 没装上，改用 docker-ce"
                        apt-get install -y ca-certificates curl gnupg
                        install -m 0755 -d /etc/apt/keyrings
                        curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc 2>/dev/null
                        apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
                      fi
                      ;;
                    *)
                      if command -v dnf >/dev/null 2>&1; then
                        log "使用 dnf 安装"
                        dnf install -y yum-utils
                        dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
                        dnf install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
                        if ! command -v docker >/dev/null 2>&1; then
                          log "docker-ce 没装上，改用发行版自带的 docker 包"
                          dnf install -y docker
                        fi
                      elif command -v yum >/dev/null 2>&1; then
                        log "使用 yum 安装"
                        yum install -y yum-utils
                        yum-config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
                        yum install -y docker-ce docker-ce-cli containerd.io
                        if ! command -v docker >/dev/null 2>&1; then
                          log "docker-ce 没装上，改用发行版自带的 docker 包"
                          yum install -y docker
                        fi
                      else
                        log "没有 apt / dnf / yum，跳过包管理器安装"
                      fi
                      ;;
                  esac

                  if ! command -v docker >/dev/null 2>&1; then
                    log "包管理器方式没装上，改用 Docker 官方一键脚本（需要能访问 get.docker.com）"
                    if command -v curl >/dev/null 2>&1; then
                      curl -fsSL https://get.docker.com | sh
                    elif command -v wget >/dev/null 2>&1; then
                      wget -qO- https://get.docker.com | sh
                    else
                      log "机器上没有 curl 也没有 wget，无法使用官方脚本"
                    fi
                  fi
            """;

    private DockerInstallScript() {
    }

    /** 组装一份完整脚本：公共头 + 发行版安装块 + 公共尾（尾部的 fi 闭合公共头的 else） */
    private static String assemble(String installBlock) {
        return COMMON_HEAD + installBlock.stripTrailing() + "\n" + COMMON_TAIL;
    }

    /**
     * 按目标系统选脚本。识别规则（依据 /etc/os-release 的 ID / VERSION_ID）：
     * <ul>
     *   <li>ubuntu → Ubuntu 专用脚本（22.04 / 24.04 都走 VERSION_CODENAME，同一份）；</li>
     *   <li>centos 且 7.x → CentOS 7 专用脚本；</li>
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
        if (isUbuntu(osId)) {
            return assemble(APT_BLOCK);
        }
        return assemble(UNIVERSAL_BLOCK);
    }

    /** 给用户看的脚本名（确认弹窗里的「已识别系统 → 使用 XX」一行） */
    public static String variantLabel(String osId, String versionId) {
        if (isDnfFamily(osId, versionId)) {
            return "RHEL 系 8/9 专用脚本（dnf + docker-ce 源）";
        }
        if (isCentos7(osId, versionId)) {
            return "CentOS 7 专用脚本（yum + docker-ce 源）";
        }
        if (isUbuntu(osId)) {
            return "Ubuntu 专用脚本（apt，适用 22.04 / 24.04）";
        }
        return "通用脚本（未知发行版，运行时按 /etc/os-release 自动分流）";
    }

    /** 通用脚本（不再单独选型的场景兼容入口，与 scriptFor("unknown", "") 等价） */
    public static String script() {
        return assemble(UNIVERSAL_BLOCK);
    }

    private static boolean isUbuntu(String osId) {
        return "ubuntu".equals(normalize(osId));
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
