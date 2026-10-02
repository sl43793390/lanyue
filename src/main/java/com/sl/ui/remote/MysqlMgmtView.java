package com.sl.ui.remote;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sl.docker.DockerExecutor;
import com.sl.docker.DockerTerminalRegistry;
import com.sl.entity.ConnectionInfo;
import com.sl.mapper.ConnectionInfoMapper;
import com.sl.mysql.MysqlInstallScript;
import com.sl.ui.component.Dialogs;
import com.sl.ui.component.CodeEditor;
import com.sl.ui.component.UiFactory;
import com.sl.ui.component.ViewBase;
import com.sl.util.Constants;
import com.sl.util.SSHClientUtil;
import com.sl.util.SshConnectionPool;
import com.sl.util.Util;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.IFrame;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.spring.annotation.SpringComponent;
import com.vaadin.flow.spring.annotation.UIScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * MySQL 管理（远程应用管理 → MySQL管理）：SSH 到目标机做服务管理与配置文件维护，
 * 结构与 {@link NginxMgmtView} 同一套路，差异都在 MySQL 自己的语义上：
 * <ul>
 *   <li>服务管理：启动 / 停止 / <b>重启</b>（MySQL 是正经服务，有 restart；不像 nginx
 *       只有 -s reload）/ 查看运行状态。systemd 单元名按发行版可能是 mysqld / mysql /
 *       mariadb，连接时探测一次并复用；探测不到单元的机器退回 service 命令；</li>
 *   <li>运行判定：ps 找 mysqld / mariadbd 进程（mariadb 是 CentOS 7 上最常见
 *       的 MySQL 兼容实现，管理页把它当同一类东西对待）；</li>
 *   <li>配置文件：按 /etc/my.cnf → /etc/mysql/my.cnf 自动探测，也可手填路径；
 *       远程 cat 读出来可编辑（properties 高亮），保存前先在远端备份
 *       （{@code <conf>.bak-时间戳}），再经 SFTP 写回；改完 my.cnf 必须<b>重启</b>
 *       服务才生效，保存完询问是否立即重启（与 nginx 的 reload 区分开）；</li>
 *   <li>一键安装：探测不到 mysqld 时出「一键安装」，流程照搬 docker 镜像页——
 *       弹确认窗展示按系统选好的脚本原文，确认后 xterm 实时终端看安装进度，
 *       脚本见 {@link MysqlInstallScript}。</li>
 * </ul>
 * <p>
 * mysqld 二进制探测顺序：<b>连接区填了「MySQL目录」（包含 bin 的那个目录）就只按
 * {@code <dir>/bin/mysqld} 找</b>——有就是成功，没有即检测失败，不回落默认位置；
 * 没填才走 PATH → /usr/sbin/mysqld → /usr/sbin/mariadbd → /usr/local/mysql/bin/mysqld
 * 的默认探测，都没有时给出「未安装」提示并亮出「一键安装」按钮，不发无效命令。
 * 连接区照搬容器和镜像管理的模式：下拉选机器（connection_info + remoteServerList.conf）。
 */
@Service
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class MysqlMgmtView extends ViewBase {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(MysqlMgmtView.class);

    /** 探测不到 mysqld 二进制时的哨兵输出，与真实路径区分 */
    private static final String NOT_FOUND = "MYSQLD_NOT_FOUND";

    private final transient ConnectionInfoMapper connectionInfoMapper;

    // ---- 连接区 ----
    private final ComboBox<ConnectionInfo> hostCombo = new ComboBox<>();
    /** MySQL 安装目录（包含 bin/mysqld 的那个）。填了就只在 <dir>/bin/mysqld 找。 */
    private final TextField homeField = UiFactory.textField();
    private final Button connectBtn = UiFactory.primary("连接", this::connect);
    /** 探测不到 mysqld 时的入口：按目标系统选内置脚本一键安装（流程照搬 docker 镜像页） */
    private final Button installBtn = UiFactory.primary("一键安装", this::installMysqlRequested);
    private final Span busyLabel = new Span();
    private final Span envLabel = new Span();

    /** 候选主机；预置主机不在里面时要补进去 */
    private List<ConnectionInfo> candidateHosts = new ArrayList<>();

    /** 共享连接池里的 SSH 连接（按 主机:端口:用户 复用，页面关闭不断开，空闲 10 分钟由池回收） */
    private transient SSHClientUtil ssh;

    // ---- 管理区（连接成功后可见） ----
    private final VerticalLayout manageArea = new VerticalLayout();
    private final Span statusLabel = new Span();
    private final Button startBtn = UiFactory.rowAction("启动", () -> confirmServiceOp("start", "启动"));
    private final Button stopBtn = UiFactory.rowDanger("停止", () -> confirmServiceOp("stop", "停止"));
    private final Button restartBtn = UiFactory.rowAction("重启", () -> confirmServiceOp("restart", "重启"));
    private final Button statusBtn = UiFactory.rowAction("查看状态", this::showStatus);

    private final TextField confPathField = UiFactory.textField();
    private final Button loadConfBtn = UiFactory.button("读取配置", this::loadConfig);
    private final Button saveConfBtn = UiFactory.button("保存配置", () -> saveConfig(true));

    private final CodeEditor configEditor = new CodeEditor(CodeEditor.MODE_PROPERTIES);
    private final TextArea outputArea = UiFactory.textArea();

    /** 探测到的 mysqld 可执行文件路径；null = 未探测到 */
    private String mysqldBin;
    /** 探测到的 systemd 服务单元名（mysqld / mysql / mariadb）；null = 没有 systemd 单元，走 service */
    private String serviceUnit;
    /** 目标机是否正在运行（最近一次探测结果） */
    private boolean running;

    public MysqlMgmtView(ConnectionInfoMapper connectionInfoMapper) {
        this.connectionInfoMapper = connectionInfoMapper;

        add(title("MySQL管理"));
        add(subtitle("SSH 到目标服务器管理 MySQL / MariaDB：启动/停止/重启/查看状态，以及在线编辑 my.cnf 配置文件。"));

        add(buildConnectRow());
        envLabel.addClassName("docker-env-label");
        envLabel.getStyle().set("overflow-wrap", "anywhere");
        add(envLabel);

        buildManageArea();
        add(manageArea);
        manageArea.setVisible(false);

        loadCandidateHosts();
    }

    // ------------------------------------------------------------------
    // 连接区（模式与容器和镜像管理一致）
    // ------------------------------------------------------------------

    private Component buildConnectRow() {
        hostCombo.setWidth("260px");
        hostCombo.setPlaceholder("选择一台已配置的服务器");
        hostCombo.setItemLabelGenerator(item -> item.getIdHost() + " (" + item.getIdUser() + ")");
        hostCombo.setAllowCustomValue(false);

        homeField.setWidth("300px");
        homeField.setPlaceholder("留空则在默认位置探测，如 /usr/local/mysql");
        homeField.getElement().setAttribute("title",
                "MySQL 安装目录（包含 bin/mysqld 的那个目录）。填写后直接用 <目录>/bin/mysqld");

        HorizontalLayout row = new HorizontalLayout(
                UiFactory.fieldRow("目标服务器", "80px", hostCombo),
                UiFactory.fieldRow("MySQL目录", "80px", homeField),
                connectBtn, installBtn, busyLabel);
        row.setClassName("view-toolbar");
        row.setAlignItems(FlexComponent.Alignment.CENTER);
        row.setFlexGrow(1, busyLabel);
        installBtn.setVisible(false);
        return row;
    }

    /** 候选主机 = 数据库 connection_info + remoteServerList.conf，按 host:port 去重 */
    private void loadCandidateHosts() {
        List<ConnectionInfo> list = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try {
            List<ConnectionInfo> fromDb = connectionInfoMapper.selectList(new QueryWrapper<>());
            if (fromDb != null) {
                for (ConnectionInfo info : fromDb) {
                    addHost(list, seen, info);
                }
            }
        } catch (Exception e) {
            log.warn("读取数据库中的服务器列表失败：{}", e.getMessage());
        }
        try {
            for (String line : Util.getRemoteServerList()) {
                String[] split = line.split("=");
                if (split.length < 4) {
                    continue;
                }
                String keyPath = split.length > 4 ? split[4] : null;
                addHost(list, seen, new ConnectionInfo(split[0], split[3], split[1], split[2], keyPath));
            }
        } catch (Exception e) {
            log.warn("读取 remoteServerList.conf 失败：{}", e.getMessage());
        }
        candidateHosts = list;
        hostCombo.setItems(list);
        if (list.isEmpty()) {
            envLabel.setText("没有可用的服务器，请先到「远程应用管理 → 免登录服务器列表」添加机器。");
        }
    }

    private static void addHost(List<ConnectionInfo> list, Set<String> seen, ConnectionInfo info) {
        if (info == null || StrUtil.isBlank(info.getIdHost())) {
            return;
        }
        if (!seen.add(info.getIdHost() + ":" + portOf(info))) {
            return;
        }
        list.add(info);
    }

    private static String portOf(ConnectionInfo info) {
        return StrUtil.isBlank(info.getCdPort()) ? "22" : info.getCdPort().trim();
    }

    /** 由「应用管理」页注入目标机器；只用于预选下拉，不自动建连 */
    private transient ConnectionInfo presetHost;

    public void setPresetHost(ConnectionInfo info) {
        this.presetHost = info;
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        preselectPresetHost();
    }

    /** 预选「应用管理」页带来的机器；连接推迟到第一次切到本标签（lazyConnect）。 */
    private void preselectPresetHost() {
        if (presetHost == null) {
            return;
        }
        ConnectionInfo target = matchHost(candidateHosts, presetHost);
        if (target == null) {
            target = presetHost;
            candidateHosts.add(target);
            hostCombo.setItems(candidateHosts);
        }
        hostCombo.setValue(target);
    }

    /**
     * 「应用管理」页切到 MySQL 标签时调用：第一次才真正建连。
     * 原因与 {@link NginxMgmtView#lazyConnect} 相同：本页作为应用管理的子标签是
     * <b>构建即 attach</b> 的，自动连会让"只想看看 jar 项目"的用户也背上一条
     * MySQL 探测用的 SSH 连接。
     */
    public void lazyConnect() {
        if (connectRequested || ssh != null) {
            return;
        }
        connectRequested = true;
        connect();
    }

    private boolean connectRequested;

    private static ConnectionInfo matchHost(List<ConnectionInfo> list, ConnectionInfo wanted) {
        if (list == null || wanted == null || StrUtil.isBlank(wanted.getIdHost())) {
            return null;
        }
        String key = wanted.getIdHost() + ":" + portOf(wanted);
        for (ConnectionInfo info : list) {
            if (key.equals(info.getIdHost() + ":" + portOf(info))) {
                return info;
            }
        }
        return null;
    }

    private void connect() {
        ConnectionInfo info = hostCombo.getValue();
        if (info == null) {
            Dialogs.warn("请先选择目标服务器");
            return;
        }
        String home = normalizeDir(homeField.getValue());
        if ("?".equals(home)) {
            Dialogs.warn("MySQL 目录必须是目标机上的绝对路径（以 / 开头）");
            return;
        }
        closeSsh();
        setBusy(true, "正在连接 " + info.getIdHost() + " …");
        manageArea.setVisible(false);
        installBtn.setVisible(false);
        envLabel.setText("");

        runAsync("连接 " + info.getIdHost(), () -> {
            // 走共享连接池：同机的其它页面（文件管理、监控等）复用同一条连接
            SSHClientUtil client = SshConnectionPool.acquire(info);
            // 探测顺序：填了 MySQL 目录就只按 <dir>/bin/mysqld 找（找不到即检测失败，
            // 不再回落默认探测——用户填了目录就说明他知道 MySQL 装在哪）；没填才走默认探测
            String bin = null;
            String confPath = "";
            boolean homeHit = false;
            if (home != null) {
                bin = probeBinAt(client, home);
                homeHit = bin != null;
            } else {
                bin = detectMysqldBin(client);
            }
            if (bin != null) {
                confPath = probeConfPath(client);
            }
            String unit = bin == null ? null : detectServiceUnit(client);
            String version = bin == null ? "" : execQuiet(client, quote(bin) + " --version 2>&1");
            boolean isRunning = bin != null && checkRunning(client);
            return new Object[]{client, bin, version, confPath, isRunning, homeHit, unit};
        }, result -> {
            Object[] parts = (Object[]) result;
            ssh = (SSHClientUtil) parts[0];
            mysqldBin = (String) parts[1];
            String version = (String) parts[2];
            String confPath = (String) parts[3];
            boolean isRunning = (Boolean) parts[4];
            boolean homeHit = (Boolean) parts[5];
            serviceUnit = (String) parts[6];
            setBusy(false, "");
            applyDetectResult(version, confPath, isRunning, homeHit);
        }, e -> {
            setBusy(false, "");
            Dialogs.error("连接失败：" + StrUtil.emptyToDefault(e.getMessage(), e.getClass().getSimpleName()));
        });
    }

    private void applyDetectResult(String version, String confPath, boolean isRunning, boolean homeHit) {
        if (ssh == null) {
            return;
        }
        String home = normalizeDir(homeField.getValue());
        String host = hostCombo.getValue() == null ? "" : hostCombo.getValue().getIdHost();
        if (mysqldBin == null) {
            manageArea.setVisible(false);
            String tried = home != null
                    ? "已按指定目录检测 " + home + "/bin/mysqld，未找到（填了目录就不回落 PATH 等默认位置）"
                    : "已尝试 PATH、/usr/sbin/mysqld、/usr/sbin/mariadbd、/usr/local/mysql/bin/mysqld";
            envLabel.setText("已连接 " + host + "，但没有检测到 MySQL / MariaDB 服务端（" + tried + "）。"
                    + "可点「一键安装」用平台内置脚本在目标机上自动安装。");
            installBtn.setVisible(true);
            return;
        }
        installBtn.setVisible(false);
        manageArea.setVisible(true);
        confPathField.setValue(StrUtil.blankToDefault(confPath, "/etc/my.cnf"));
        refreshRunningLabel(isRunning);
        String homeNote = home == null ? ""
                : homeHit ? "　（按指定目录 " + home + " 探测）" : "";
        String unitNote = serviceUnit == null ? "（无 systemd 单元，操作走 service 命令）" : "　服务单元：" + serviceUnit;
        envLabel.setText("已连接 " + host + "　mysqld：" + quote(mysqldBin)
                + "　" + StrUtil.trimToEmpty(version).replaceAll("\\s+", " ")
                + homeNote + unitNote);
        appendOutput("已连接 " + host + "，MySQL 就绪。");
    }

    private void refreshRunningLabel(boolean isRunning) {
        running = isRunning;
        statusLabel.setText(running ? "运行中" : "已停止");
        statusLabel.getStyle().set("color", running
                ? "var(--lumo-success-text-color)" : "var(--lumo-error-text-color)");
        startBtn.setEnabled(!running);
        stopBtn.setEnabled(running);
        restartBtn.setEnabled(true);
    }

    private void buildManageArea() {
        statusLabel.getStyle().set("font-weight", "600");

        HorizontalLayout opRow = new HorizontalLayout(
                statusLabel, startBtn, stopBtn, restartBtn, statusBtn);
        opRow.setSpacing(false);
        opRow.addClassName("row-actions");
        opRow.setAlignItems(FlexComponent.Alignment.CENTER);

        confPathField.setPlaceholder("my.cnf 配置文件绝对路径");
        confPathField.setWidth("420px");
        HorizontalLayout confRow = new HorizontalLayout(
                UiFactory.fieldRow("配置文件", "80px", confPathField), loadConfBtn, saveConfBtn);
        confRow.setSpacing(true);
        confRow.setAlignItems(FlexComponent.Alignment.CENTER);

        configEditor.setHeight("500px");
        configEditor.setWidth("99%");
        configEditor.setVisible(false);

        outputArea.setReadOnly(true);
        outputArea.setHeight("160px");
        outputArea.setWidthFull();
        outputArea.getStyle().set("--lumo-font-family", "Consolas, 'Courier New', monospace");

        manageArea.add(opRow, confRow, configEditor, outputArea);
        manageArea.setSpacing(false);
        manageArea.getStyle().set("gap", "10px");
    }

    // ------------------------------------------------------------------
    // 服务操作
    // ------------------------------------------------------------------

    /** 服务操作确认 + 执行：unit 在就 systemctl，不在就 service 逐个试。 */
    private void confirmServiceOp(String op, String opName) {
        if (!hasPermission(Constants.UPDATE)) {
            Dialogs.warn("权限不足，无法操作 MySQL 服务");
            return;
        }
        String danger = "stop".equals(op) ? "正在对外提供的服务会立即中断。" : "";
        Dialogs.confirmDanger(opName + " MySQL 服务",
                "确认" + opName + " " + hostLabel() + " 上的 MySQL 服务吗？" + danger,
                "确认" + opName, () -> runServiceOp(op, opName));
    }

    private void runServiceOp(String op, String opName) {
        SSHClientUtil client = liveSsh();
        if (client == null) {
            Dialogs.warn("请先连接目标服务器");
            return;
        }
        setBusy(true, opName + "服务 …");
        String cmd = buildServiceCmd(op);
        runAsync(opName + "服务", () -> execQuiet(client, cmd + " 2>&1"), output -> {
            setBusy(false, "");
            appendOutput("$ " + cmd + "\n" + StrUtil.trimToEmpty((String) output));
            Dialogs.success(opName + "命令已执行，输出见下方");
            refreshAsync();
        }, e -> {
            setBusy(false, "");
            Dialogs.error(opName + "失败：" + StrUtil.emptyToDefault(e.getMessage(), "未知错误"));
        });
    }

    /** 拼服务操作命令：systemd 单元探测到了用 systemctl；否则 service 按常见单元名逐个试 */
    private String buildServiceCmd(String op) {
        if (serviceUnit != null) {
            return "systemctl " + op + " " + quote(serviceUnit);
        }
        return "service mysqld " + op + " 2>/dev/null || service mysql " + op + " 2>/dev/null"
                + " || service mariadb " + op;
    }

    /** 后台刷新一次运行状态（启动/停止/重启后调用）。 */
    private void refreshAsync() {
        SSHClientUtil client = liveSsh();
        if (client == null) {
            return;
        }
        runAsync("刷新状态", () -> checkRunning(client), isRunning ->
                refreshRunningLabel((Boolean) isRunning), e -> { });
    }

    private void showStatus() {
        SSHClientUtil client = liveSsh();
        if (client == null) {
            Dialogs.warn("请先连接目标服务器");
            return;
        }
        setBusy(true, "正在查询状态 …");
        runAsync("查看状态", () -> execQuiet(client,
                        "ps -ef | grep -E 'mysqld|mariadbd' | grep -v grep | head -n 10"), output -> {
            setBusy(false, "");
            String text = StrUtil.trimToEmpty((String) output);
            appendOutput("$ ps -ef | grep -E 'mysqld|mariadbd'\n"
                    + (text.isEmpty() ? "（没有 mysqld / mariadbd 进程）" : text));
            refreshRunningLabel(!text.isEmpty());
        }, e -> {
            setBusy(false, "");
            Dialogs.error("查询状态失败：" + StrUtil.emptyToDefault(e.getMessage(), "未知错误"));
        });
    }

    // ------------------------------------------------------------------
    // 配置文件
    // ------------------------------------------------------------------

    private void loadConfig() {
        SSHClientUtil client = liveSsh();
        if (client == null) {
            Dialogs.warn("请先连接目标服务器");
            return;
        }
        String path = StrUtil.trimToEmpty(confPathField.getValue());
        if (!path.startsWith("/")) {
            Dialogs.warn("请填写配置文件的绝对路径（以 / 开头）");
            return;
        }
        setBusy(true, "正在读取配置 …");
        runAsync("读取配置", () -> execQuiet(client, "cat " + quote(path) + " 2>&1"), output -> {
            setBusy(false, "");
            String text = (String) output;
            if (StrUtil.trimToEmpty(text).startsWith("cat:")) {
                configEditor.setVisible(false);
                Dialogs.error("读取失败：" + StrUtil.trimToEmpty(text));
                return;
            }
            configEditor.setVisible(true);
            configEditor.setValue(StrUtil.trimToEmpty(text));
            appendOutput("$ cat " + path + "\n（已读出 " + text.length() + " 字符，可在上方编辑）");
            Dialogs.success("配置已读取，编辑后点「保存配置」写回");
        }, e -> {
            setBusy(false, "");
            Dialogs.error("读取配置失败：" + StrUtil.emptyToDefault(e.getMessage(), "未知错误"));
        });
    }

    /**
     * 保存配置：先在远端备份（cp conf conf.bak-时间戳），再 SFTP 写回。
     * 写回成功后询问是否立即重启——my.cnf 不像 nginx 有 reload，改完必须重启才生效。
     */
    private void saveConfig(boolean askRestart) {
        if (!hasPermission(Constants.UPDATE)) {
            Dialogs.warn("权限不足，无法保存配置");
            return;
        }
        SSHClientUtil client = liveSsh();
        if (client == null) {
            Dialogs.warn("请先连接目标服务器");
            return;
        }
        String path = StrUtil.trimToEmpty(confPathField.getValue());
        if (!path.startsWith("/")) {
            Dialogs.warn("请填写配置文件的绝对路径（以 / 开头）");
            return;
        }
        if (configEditor.isVisible() && StrUtil.isBlank(configEditor.getValue())) {
            Dialogs.warn("配置内容为空；如果是想清空文件，请先确认远端备份可用");
            return;
        }
        String content = configEditor.isVisible() ? configEditor.getValue() : "";
        Dialogs.confirm("保存配置",
                "将把编辑后的内容写回 " + hostLabel() + ":" + path + "。\n"
                        + "写回前会在远端先备份为 " + path + ".bak-时间戳。确认保存吗？",
                () -> {
                    setBusy(true, "正在保存配置 …");
                    runAsync("保存配置", () -> {
                        // 备份失败不阻断写回（比如文件还不存在），但输出里要看得见
                        String backup = execQuiet(client, "[ -f " + quote(path) + " ] && cp "
                                + quote(path) + " " + quote(path) + ".bak-$(date +%Y%m%d%H%M%S) || true");
                        File tmp = File.createTempFile("lanyue-mysql-cnf-", ".cnf");
                        try {
                            Files.writeString(tmp.toPath(), content, StandardCharsets.UTF_8);
                            client.uploadFile(tmp.getAbsolutePath(), path, null);
                        } finally {
                            tmp.delete();
                        }
                        return backup;
                    }, output -> {
                        setBusy(false, "");
                        appendOutput("$ cp " + path + " " + path + ".bak-时间戳 && 写回\n"
                                + StrUtil.trimToEmpty((String) output));
                        Dialogs.success("配置已保存到 " + hostLabel() + ":" + path);
                        if (askRestart) {
                            Dialogs.confirm("重启服务", "my.cnf 要重启 MySQL 服务才生效，"
                                    + "现在就执行重启吗？（重启会短暂中断数据库连接）",
                                    () -> runServiceOp("restart", "重启"));
                        }
                    }, e -> {
                        setBusy(false, "");
                        Dialogs.error("保存配置失败：" + StrUtil.emptyToDefault(e.getMessage(), "未知错误"));
                    });
                });
    }

    // ------------------------------------------------------------------
    // 一键安装（流程照搬 docker 镜像页 / nginx 管理页：探发行版 → 确认弹窗展示脚本 → xterm 实时终端）
    // ------------------------------------------------------------------

    /**
     * 一键安装入口：探测不到 mysqld 时「一键安装」按钮才可见。点按钮先在后台
     * 识别目标系统版本（/etc/os-release）与登录用户 uid（决定要不要加 sudo -n），
     * 然后弹确认窗——编辑器里展示的就是「会在这台机器上跑的那份脚本」，不做暗箱操作。
     */
    private void installMysqlRequested() {
        if (!hasPermission(Constants.UPDATE)) {
            Dialogs.warn("权限不足，无法安装 MySQL");
            return;
        }
        ConnectionInfo info = hostCombo.getValue();
        if (info == null) {
            Dialogs.warn("请先选择目标服务器");
            return;
        }
        SSHClientUtil client = liveSsh();
        if (client == null) {
            Dialogs.warn("请先连接目标服务器");
            return;
        }
        setBusy(true, "正在识别目标系统版本 …");
        runAsync("识别目标系统", () -> {
            // 一次往返拿全 /etc/os-release 的关键字段，再来一次拿 uid（0 = root，免 sudo）
            String osOut = execQuiet(client,
                    ". /etc/os-release 2>/dev/null; "
                            + "echo \"OS_ID=${ID:-unknown}\"; echo \"OS_VER=${VERSION_ID:-}\"; "
                            + "echo \"OS_NAME=${PRETTY_NAME:-unknown}\"");
            String uid = StrUtil.trimToEmpty(execQuiet(client, "id -u"));
            boolean isRoot = "0".equals(uid) || ("root".equalsIgnoreCase(info.getIdUser()) && uid.isEmpty());
            return new Object[]{
                    new DockerExecutor.Distro(
                            StrUtil.emptyToDefault(osValue(osOut, "OS_ID"), "unknown"),
                            StrUtil.emptyToDefault(osValue(osOut, "OS_VER"), ""),
                            StrUtil.emptyToDefault(osValue(osOut, "OS_NAME"), "unknown")),
                    isRoot,
                    MysqlInstallScript.scriptFor(osValue(osOut, "OS_ID"), osValue(osOut, "OS_VER")),
                    MysqlInstallScript.variantLabel(osValue(osOut, "OS_ID"), osValue(osOut, "OS_VER"))};
        }, result -> {
            setBusy(false, "");
            Object[] parts = (Object[]) result;
            showInstallConfirmDialog(info, (DockerExecutor.Distro) parts[0], (Boolean) parts[1],
                    (String) parts[2], (String) parts[3]);
        }, e -> {
            setBusy(false, "");
            Dialogs.error("识别系统版本失败：" + StrUtil.emptyToDefault(e.getMessage(), "未知错误"));
        });
    }

    /**
     * 安装确认弹窗：目标机信息 + 识别到的发行版 + 脚本原文（CodeEditor shell 高亮、只读，
     * 可一键复制）。确认后进入实时安装终端。
     */
    private void showInstallConfirmDialog(ConnectionInfo info, DockerExecutor.Distro distro,
                                          boolean isRoot, String script, String scriptLabel) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("一键安装 MySQL");
        dialog.setWidth("980px");
        dialog.setHeight("800px");

        Span target = new Span("目标服务器：" + info.getIdHost()
                + "　登录用户 " + info.getIdUser() + "（uid=" + (isRoot ? "0" : "非 0") + "）"
                + "　执行方式：" + (isRoot ? "直接执行" : "sudo -n bash -c（需要免密 sudo）"));
        target.getStyle().set("overflow-wrap", "anywhere");

        Span distroLabel = new Span("已识别系统：" + distro.prettyName()
                + "（ID=" + distro.id()
                + " VERSION_ID=" + (distro.versionId().isEmpty() ? "?" : distro.versionId())
                + "）→ 使用" + scriptLabel);
        distroLabel.addClassName("view-section-title");
        distroLabel.getStyle().set("overflow-wrap", "anywhere");

        CodeEditor scriptEditor = new CodeEditor(CodeEditor.MODE_SHELL);
        scriptEditor.setValue(script);
        scriptEditor.setReadOnly(true);
        scriptEditor.setWidthFull();
        scriptEditor.setHeight("500px");

        Span hint = new Span("脚本只做「装 + 启动 + 验证」：按识别结果选专用脚本"
                + "（Ubuntu 走 apt，Rocky/RHEL 8、9 走 dnf 装 AppStream 的 mysql-server，"
                + "CentOS 7 先切 centos-vault 归档源再装 MySQL 社区源、拿不到退 MariaDB），"
                + "不改已有配置。MySQL 8 首次启动的 root 临时密码会打在终端里。"
                + "点「确认安装」会在实时终端里执行这份脚本，可全程看到进度与报错。");
        hint.addClassName("view-subtitle");
        hint.getStyle().set("overflow-wrap", "anywhere");

        VerticalLayout body = new VerticalLayout(target, distroLabel, UiFactory.group(
                new Span("执行内容："), UiFactory.copyButton(this, script)), scriptEditor, hint);
        body.setPadding(false);
        body.setHeightFull();
        body.getStyle().set("gap", "8px");
        dialog.add(body);

        Button cancel = UiFactory.button("取消", dialog::close);
        Button install = UiFactory.primary("确认安装", () -> {
            dialog.close();
            openInstallTerminal(info, isRoot, script);
        });
        dialog.getFooter().add(cancel, install);
        dialog.open();
    }

    /**
     * 安装实时终端：token 登记一条 INSTALL 规格（整段安装脚本转义后交给 bash -c），
     * 弹窗内嵌 terminal.html（复用 /ws/docker 端点，成功标记换成 MySQL 自己的），
     * websocket 建连即在目标机上 exec 脚本，输出实时滚进 xterm。
     * 安装结束（成功或失败）前「关闭」禁用、Esc / 点遮罩也关不掉，
     * 结束后由 websocket 侧的完成回调解锁关闭并重新探测 MySQL。
     */
    private void openInstallTerminal(ConnectionInfo info, boolean isRoot, String script) {
        try {
            // 整段脚本作为 bash -c 的参数一次执行，而不是 echo | bash 喂标准输入：
            // 后者会被脚本里读标准输入的命令（apt 确认等）把后面的脚本吃掉
            String installCommand = (isRoot ? "" : "sudo -n ") + "bash -c " + quote(script);
            DockerTerminalRegistry.Spec spec = new DockerTerminalRegistry.Spec(
                    DockerTerminalRegistry.Kind.INSTALL, info, installCommand);
            spec.setSuccessMarker(MysqlInstallScript.RESULT_OK);
            spec.setOutcomeNotices(
                    "安装脚本执行完成，MySQL 服务已就绪。",
                    "安装脚本已结束，但未确认安装成功，请根据上方输出排查。");
            String token = DockerTerminalRegistry.register(spec);

            Dialog dialog = new Dialog();
            dialog.setHeaderTitle("正在安装 MySQL：" + info.getIdHost());
            dialog.setWidth("1150px");
            dialog.setHeight("820px");
            // 安装中途不允许 Esc / 点击遮罩关掉：iframe 一摘，websocket 断开，脚本就被杀了
            dialog.setCloseOnEsc(false);
            dialog.setCloseOnOutsideClick(false);

            Span hint = new Span("安装脚本正在目标机上实时执行，进度与报错直接看下方终端；"
                    + "完整输出可用终端工具栏的「导出」保存。首次启动要初始化数据目录，"
                    + "可能需要几分钟，安装结束前请勿关闭本页面。");
            hint.addClassName("view-subtitle");
            hint.getStyle().set("overflow-wrap", "anywhere");

            // ro=1 只读（exec 通道没有输入）、eol=1 修正 LF 换行、norc=1 隐藏「重新连接」
            //（token 允许复用，重连会把安装脚本再跑一遍）
            IFrame frame = new IFrame("VAADIN/static/terminal/terminal.html?token=" + token
                    + "&ws=/ws/docker&ro=1&eol=1&norc=1");
            frame.setWidthFull();
            frame.setHeight("640px");
            frame.getElement().setAttribute("title", "MySQL 安装终端");

            VerticalLayout body = new VerticalLayout(hint, frame);
            body.setPadding(false);
            body.setSizeFull();
            body.setSpacing(false);
            body.getStyle().set("gap", "6px");
            dialog.add(body);

            Button close = UiFactory.button("安装中…", dialog::close);
            close.setEnabled(false);

            com.vaadin.flow.component.UI ui = getUI().orElse(null);
            spec.setCompleteListener((success, outputTail) -> {
                if (ui == null) {
                    return;
                }
                ui.access(() -> {
                    if (getUI().isEmpty()) {
                        return; // 页面已经关了，弹窗也随之不在了
                    }
                    close.setText("关闭");
                    close.setEnabled(true);
                    dialog.setHeaderTitle((success ? "MySQL 安装完成：" : "MySQL 安装未成功：")
                            + info.getIdHost());
                    if (success) {
                        Dialogs.success("MySQL 安装完成，正在重新检测…");
                    } else {
                        Dialogs.warn("安装脚本已结束，但未确认安装成功，请按终端输出排查。");
                    }
                    // 重新走一遍探测：装上了就点亮管理区，失败则保持「一键安装」入口
                    connect();
                });
            });
            spec.setFailListener(message -> {
                if (ui == null) {
                    return;
                }
                ui.access(() -> {
                    if (getUI().isEmpty()) {
                        return;
                    }
                    close.setText("关闭");
                    close.setEnabled(true);
                    Dialogs.error("安装终端建立失败：" + StrUtil.emptyToDefault(message, "未知错误"));
                });
            });

            // 弹窗关掉就回收 token
            dialog.addOpenedChangeListener(e -> {
                if (!dialog.isOpened()) {
                    DockerTerminalRegistry.release(token);
                }
            });
            dialog.getFooter().add(close);
            dialog.open();
        } catch (Exception e) {
            Dialogs.error("无法开始安装：" + StrUtil.emptyToDefault(e.getMessage(), "未知错误"));
        }
    }

    /** 从「. /etc/os-release; echo K=V」的输出里取指定键的值（取第一处，trim 后返回） */
    private static String osValue(String output, String key) {
        if (StrUtil.isBlank(output)) {
            return "";
        }
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(key + "=")) {
                return trimmed.substring(key.length() + 1).trim();
            }
        }
        return "";
    }

    // ------------------------------------------------------------------
    // 探测与执行工具
    // ------------------------------------------------------------------

    private String hostLabel() {
        return hostCombo.getValue() == null ? "目标机" : hostCombo.getValue().getIdHost();
    }

    /** 探测 mysqld 二进制：PATH → 常见安装路径（含 mariadbd），都没有返回 null。 */
    private static String detectMysqldBin(SSHClientUtil client) {
        String out = execQuiet(client,
                "command -v mysqld 2>/dev/null "
                        + "|| ls /usr/sbin/mysqld /usr/sbin/mariadbd /usr/local/mysql/bin/mysqld 2>/dev/null "
                        + "| head -n 1 "
                        + "|| echo " + NOT_FOUND);
        String trimmed = StrUtil.trimToEmpty(out);
        String firstLine = trimmed.split("\\R", 2)[0].trim();
        if (firstLine.isEmpty() || firstLine.equals(NOT_FOUND) || !firstLine.startsWith("/")) {
            return null;
        }
        return firstLine;
    }

    /** 指定 MySQL 目录下的服务端二进制：<dir>/bin/mysqld 存在且可执行才返回路径，否则 null。 */
    private static String probeBinAt(SSHClientUtil client, String home) {
        String path = home + "/bin/mysqld";
        String out = execQuiet(client, "test -x " + quote(path) + " && echo OK || echo " + NOT_FOUND);
        return StrUtil.trimToEmpty(out).equals("OK") ? path : null;
    }

    /** 按常见位置探测 my.cnf：/etc/my.cnf → /etc/mysql/my.cnf，都没有返回空串。 */
    private static String probeConfPath(SSHClientUtil client) {
        String out = execQuiet(client,
                "for f in /etc/my.cnf /etc/mysql/my.cnf; do "
                        + "[ -f \"$f\" ] && echo \"$f\" && break; done");
        return StrUtil.trimToEmpty(out).split("\\R", 2)[0].trim();
    }

    /** 探测 systemd 服务单元名（mysqld / mysql / mariadb），没有返回 null。 */
    private static String detectServiceUnit(SSHClientUtil client) {
        String out = execQuiet(client,
                "systemctl list-unit-files --type=service --no-legend 2>/dev/null "
                        + "| awk '$1 ~ /^(mysqld|mysql|mariadb)\\.service$/ {print $1; exit}'");
        String unit = StrUtil.trimToEmpty(out).replace(".service", "").trim();
        return unit.isEmpty() ? null : unit;
    }

    /** 归一化 MySQL 目录输入：去掉首尾空白与结尾的 /；空白返回 null（= 未填）；非绝对路径返回哨兵 "?"。 */
    private static String normalizeDir(String input) {
        String dir = StrUtil.trimToEmpty(input);
        if (dir.isEmpty()) {
            return null;
        }
        while (dir.length() > 1 && dir.endsWith("/")) {
            dir = dir.substring(0, dir.length() - 1);
        }
        return dir.startsWith("/") ? dir : "?";
    }

    private static boolean checkRunning(SSHClientUtil client) {
        String out = execQuiet(client, "ps -ef | grep -E 'mysqld|mariadbd' | grep -v grep | head -n 1");
        return StrUtil.isNotBlank(out);
    }

    /** 忽略异常的执行：探测类命令不允许把连接流程炸掉，失败按空输出处理。 */
    private static String execQuiet(SSHClientUtil client, String command) {
        try {
            return client.executeCommand(command);
        } catch (Exception e) {
            log.warn("命令执行失败 [{}]：{}", command, e.getMessage());
            return "";
        }
    }

    /** 路径带空格也能跑：统一加单引号（单引号里的单引号按 '\' 姿势转义）。 */
    private static String quote(String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }

    private void appendOutput(String text) {
        String current = outputArea.getValue();
        String stamp = java.time.LocalTime.now().withNano(0).toString();
        outputArea.setValue((StrUtil.isBlank(current) ? "" : current + "\n\n")
                + "[" + stamp + "] " + text);
    }

    private void setBusy(boolean busy, String text) {
        busyLabel.setText(StrUtil.emptyToDefault(text, ""));
        connectBtn.setEnabled(!busy);
    }

    /** 允许 lambda 抛受检异常的小任务接口（SSHClientUtil 的建连/读写都声明 throws IOException）。 */
    @FunctionalInterface
    private interface ThrowingSupplier {
        Object get() throws Exception;
    }

    private void runAsync(String label, ThrowingSupplier task,
                          java.util.function.Consumer<Object> onDone,
                          java.util.function.Consumer<Exception> onFailure) {
        com.vaadin.flow.component.UI ui = getUI().orElse(null);
        if (ui == null) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                Object result = task.get();
                ui.access(() -> onDone.accept(result));
            } catch (Exception e) {
                log.warn("{} 失败：{}", label, e.getMessage());
                ui.access(() -> onFailure.accept(e));
            }
        }, "mysql-" + label);
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        closeSsh();
        super.onDetach(detachEvent);
    }

    private void closeSsh() {
        // 连接归共享连接池（SshConnectionPool）管：这里只解除本页引用，
        // 不真正断开——其它页面还能复用，空闲超 15 分钟由池自动回收
        ssh = null;
    }

    /**
     * 取一条可用的 SSH 连接（与 {@link NginxMgmtView#liveSsh} 同一防坑逻辑）：
     * 共享连接池空闲回收后字段里指向的是死连接，每次用之前过一遍这里，
     * 连接没了就重新 acquire。注意本方法可能跑在后台线程上，里面不能碰 UI。
     */
    private SSHClientUtil liveSsh() {
        SSHClientUtil current = ssh;
        if (current == null) {
            return null;
        }
        if (current.isConnected()) {
            return current;
        }
        ConnectionInfo info = hostCombo.getValue();
        if (info == null) {
            return current;
        }
        try {
            ssh = SshConnectionPool.acquire(info);
            log.info("MySQL 页面的共享 SSH 连接已失效，已自动重连：{}", info.getIdHost());
            return ssh;
        } catch (Exception e) {
            log.warn("MySQL 页面自动重连失败：{}", e.getMessage());
            return current;
        }
    }

    private static boolean hasPermission(String code) {
        com.sl.entity.User user = com.sl.security.CurrentUser.get();
        return user != null && user.hasPermission(code);
    }
}
