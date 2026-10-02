package com.sl.mysql;

/**
 * 内置的 MySQL 服务端安装脚本，<b>按目标发行版分多份</b>，结构与
 * {@code com.sl.docker.DockerInstallScript} / {@code com.sl.nginx.NginxInstallScript}
 * 同一套路：MySQL 管理页检测不到 mysqld 时，「一键安装」按 {@code /etc/os-release}
 * 选定后下发：
 * <ul>
 *   <li><b>Debian / Ubuntu</b>（apt）—— 发行版仓库自带 mysql-server，直接装；</li>
 *   <li><b>Rocky / Alma / RHEL / OEL / Anolis 8、9</b>（dnf）—— AppStream 自带
 *       mysql-server（MySQL 8.0），无需第三方源；</li>
 *   <li><b>CentOS 7.9</b>（yum）—— 最麻烦的一档：base/extras 里的 mysql 已被 mariadb
 *       取代，真 MySQL 必须走 MySQL 社区源。CentOS 7 又已 EOL，所以顺序是：先修
 *       centos-vault 归档源，再装 MySQL 80 社区源（dev.mysql.com 不可达用阿里云镜像），
 *       装 mysql-community-server；社区源完全拿不到时退回 mariadb-server（协议兼容，
 *       脚本里会明确打日志告知），保证机器上至少有一个可用的 MySQL 兼容服务端；</li>
 *   <li><b>其余未知发行版</b> —— 通用脚本，运行时按 /etc/os-release 的 ID / ID_LIKE 分流。</li>
 * </ul>
 * <p>
 * 每份脚本由公共头（log/PATH/「已装过就跳过」）+ 分发版安装块 + 公共尾拼成。
 * 公共尾做「启动 + 等就绪 + 打结果标记」：启动按 systemd 单元名
 * （mysqld / mysql / mariadb）依次探测；就绪判定用 {@code mysqladmin ping}；
 * MySQL 8 首次启动生成的 root 临时密码会从 /var/log/mysqld.log 里捞出来打到终端，
 * 免得用户装完连不上。结果以 {@link #RESULT_OK} / {@link #RESULT_FAILED} 收尾，
 * websocket 侧靠标记判定成败。
 */
public final class MysqlInstallScript {

    /** 界面与日志里标识安装是否成功的一行（websocket 侧按它判定，见 DockerTerminalRegistry.Spec） */
    public static final String RESULT_OK = "MYSQL_INSTALL_RESULT=OK";
    public static final String RESULT_FAILED = "MYSQL_INSTALL_RESULT=FAILED";

    /* ------------------------------------------------------------------ */
    /* 公共头 / 公共尾                                                      */
    /* ------------------------------------------------------------------ */

    /** 公共头：log 函数、PATH、have_mysql 判定、「已装过就跳过安装块」 */
    private static final String COMMON_HEAD = """
            # MySQL 服务端安装脚本（由运维平台下发）
            # 不用 set -e：某一步失败要继续往下试，最后统一看 have_mysql
            log() { echo "[install] $*"; }
            have_mysql() {
              command -v mysqld >/dev/null 2>&1 || [ -x /usr/sbin/mysqld ] \\
                || command -v mariadbd >/dev/null 2>&1 || [ -x /usr/sbin/mariadbd ]
            }

            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            export DEBIAN_FRONTEND=noninteractive

            if have_mysql; then
              log "已检测到数据库服务端：$(command -v mysqld || echo /usr/sbin/mysqld)，跳过安装步骤"
            else
            """;

    /** 公共尾：安装块结束后统一收口——验装、启动、等就绪、捞临时密码、打结果标记 */
    private static final String COMMON_TAIL = """
            fi

            if ! have_mysql; then
              log "安装失败：所有方式都没能装上 MySQL / MariaDB 服务端"
              log "可手动排查：内网机器需要放通软件源，或离线拷贝 RPM/DEB 包安装"
              log "MYSQL_INSTALL_RESULT=FAILED"
              exit 1
            fi

            MYSQLD="$(command -v mysqld || echo /usr/sbin/mysqld)"
            log "数据库服务端已就位：$MYSQLD"
            log "版本：$("$MYSQLD" --version 2>&1)"

            # 启动 + 开机自启；单元名按发行版可能是 mysqld / mysql / mariadb，逐个探测
            UNIT=""
            if [ -d /run/systemd/system ] && command -v systemctl >/dev/null 2>&1; then
              for u in mysqld mysql mariadb; do
                if systemctl cat "$u.service" >/dev/null 2>&1; then
                  UNIT="$u"; break
                fi
              done
            fi
            if [ -n "$UNIT" ]; then
              systemctl enable "$UNIT"
              systemctl start "$UNIT" || log "systemctl start $UNIT 失败（journalctl -u $UNIT 看原因）"
            elif command -v service >/dev/null 2>&1; then
              service mysqld start || service mysql start || service mariadb start \\
                || log "service 启动失败（init 脚本可能不存在）"
            else
              log "没有可用的服务管理器，跳过启动（可在本页面手动点「启动」）"
            fi

            # 等服务就绪，最多 30 秒（首次启动要初始化数据目录，会比较慢）
            i=0
            while [ $i -lt 15 ]; do
              mysqladmin ping >/dev/null 2>&1 && break
              i=$((i + 1))
              sleep 2
            done

            if mysqladmin ping >/dev/null 2>&1; then
              log "MySQL 服务已就绪（mysqladmin ping 通）"
              # MySQL 8 首次启动会给 root@localhost 生成临时密码，捞出来打到终端
              for f in /var/log/mysqld.log /var/log/mysql/error.log; do
                if [ -f "$f" ]; then
                  TMP_PWD=$(grep 'temporary password' "$f" 2>/dev/null | tail -n 1)
                  if [ -n "$TMP_PWD" ]; then
                    log "首次启动临时密码：$TMP_PWD"
                    break
                  fi
                fi
              done
              log "MYSQL_INSTALL_RESULT=OK"
              exit 0
            fi
            log "服务端已安装但 mysqladmin ping 不通（首次初始化较慢或启动失败，可稍后用本页面「启动」重试）"
            log "MYSQL_INSTALL_RESULT=FAILED"
            exit 1
            """;

    /* ------------------------------------------------------------------ */
    /* 各发行版的安装块                                                     */
    /* ------------------------------------------------------------------ */

    /** Debian / Ubuntu：apt，发行版仓库自带 mysql-server */
    private static final String APT_BLOCK = """
                  log "使用 apt 安装（Debian / Ubuntu 系脚本）"
                  apt-get update || log "apt-get update 失败（不影响已缓存的索引，继续尝试安装）"
                  apt-get install -y mysql-server || log "apt 安装 mysql-server 失败"
            """;

    /**
     * CentOS 7.9：yum。base/extras 里的 mysql 早已被 mariadb 取代，真 MySQL 要走社区源；
     * 而 CentOS 7 已 EOL，mirrorlist.centos.org 下线，先修系统源再装社区源，
     * 社区源完全拿不到时退 mariadb-server（协议兼容，日志里明确告知）。
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
                  log "安装 MySQL 80 社区源（dev.mysql.com 不可达时用阿里云镜像）"
                  RM=/tmp/mysql80-community-release-el7.rpm
                  if ! curl -fsSL --connect-timeout 8 https://dev.mysql.com/get/mysql80-community-release-el7-11.noarch.rpm -o "$RM" \\
                     && ! curl -fsSL --connect-timeout 8 https://mirrors.aliyun.com/mysql/mysql80-community-release-el7-11.noarch.rpm -o "$RM"; then
                    log "MySQL 社区源拿不到（内网或源失效），退回安装 MariaDB（MySQL 协议兼容）"
                    yum install -y mariadb-server mariadb || log "mariadb-server 也没装上"
                  else
                    yum install -y "$RM" || true
                    rpm --import https://repo.mysql.com/RPM-GPG-KEY-mysql-2023 2>/dev/null \\
                      || rpm --import https://repo.mysql.com/RPM-GPG-KEY-mysql 2>/dev/null || true
                    yum makecache fast || true
                    yum install -y mysql-community-server || log "mysql-community-server 安装失败"
                  fi
                  # 客户端与 mysqladmin：社区源路径一般自带，缺了从发行版兜一个
                  yum install -y mysql || true
            """;

    /** Rocky / Alma / RHEL / OEL / Anolis 8、9：dnf，AppStream 自带 mysql-server（8.0） */
    private static final String DNF_BLOCK = """
                  log "使用 dnf 安装（Rocky / Alma / RHEL 系 8、9 专用脚本）"
                  dnf makecache || true
                  dnf install -y mysql-server || log "dnf 安装 mysql-server 失败"
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
                      apt-get install -y mysql-server || log "apt 安装 mysql-server 失败"
                      ;;
                    *)
                      if command -v dnf >/dev/null 2>&1; then
                        log "使用 dnf 安装"
                        dnf makecache || true
                        dnf install -y mysql-server || log "dnf 安装 mysql-server 失败"
                      elif command -v yum >/dev/null 2>&1; then
                        log "使用 yum 安装（base 仓库无真 MySQL，直接装兼容的 mariadb-server）"
                        yum install -y mariadb-server mariadb || log "yum 安装 mariadb-server 失败"
                      else
                        log "没有 apt / dnf / yum，无法用包管理器安装"
                      fi
                      ;;
                  esac
            """;

    private MysqlInstallScript() {
    }

    /** 组装一份完整脚本：公共头 + 发行版安装块 + 公共尾（尾部的 fi 闭合公共头的 else） */
    private static String assemble(String installBlock) {
        return COMMON_HEAD + installBlock.stripTrailing() + "\n" + COMMON_TAIL;
    }

    /**
     * 按目标系统选脚本。识别规则（依据 /etc/os-release 的 ID / VERSION_ID）：
     * <ul>
     *   <li>ubuntu / debian 系 → apt 专用脚本；</li>
     *   <li>centos 且 7.x → CentOS 7 专用脚本（vault 归档 + MySQL 社区源 + MariaDB 兜底）；</li>
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
            return "RHEL 系 8/9 专用脚本（dnf，AppStream 自带 mysql-server）";
        }
        if (isCentos7(osId, versionId)) {
            return "CentOS 7 专用脚本（vault 归档源 + MySQL 社区源，拿不到退 MariaDB）";
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
