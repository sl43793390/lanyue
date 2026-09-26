package com.sl.ui.remote;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sl.entity.ConnectionInfo;
import com.sl.mapper.ConnectionInfoMapper;
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
 * nginx 管理（远程应用管理 → nginx管理）：SSH 到目标机做服务管理与配置文件维护。
 * <p>
 * 功能范围（与卧龙确认过的版本）：
 * <ul>
 *   <li>服务管理：启动 / 停止（{@code nginx -s stop}）/ 重载配置（{@code -s reload}）/
 *       配置检测（{@code -t}）/ 查看运行状态（ps）；nginx 本体没有 restart 命令，
 *       重启 = stop + start，运维口径里单独一个「重载」更常用，这里不造 restart；</li>
 *   <li>配置文件：自动从 {@code nginx -V} 解析 --conf-path，也可手填路径；
 *       远程 cat 读出来可编辑，保存前先在远端备份（{@code <conf>.bak-时间戳}），
 *       再经 SFTP 写回，保存完询问是否立即 reload。</li>
 * </ul>
 * <p>
 * nginx 二进制探测顺序：<b>连接区填了「nginx目录」（包含 sbin/conf 的那个目录）就只按
 * {@code <dir>/sbin/nginx} 与 {@code <dir>/conf/nginx.conf} 取</b>——有就是成功，
 * 没有即检测失败，不回落默认位置；没填才走
 * PATH → /usr/local/nginx/sbin → /usr/local/openresty/nginx/sbin 的默认探测，
 * 都没有时给出「未安装」提示并禁用全部操作按钮，不发无效命令。
 * 连接区照搬容器和镜像管理的模式：下拉选机器（connection_info + remoteServerList.conf），
 * 服务器列表跳转进来时自动连接一次。
 */
@Service
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class NginxMgmtView extends ViewBase {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(NginxMgmtView.class);

    /** 探测不到 nginx 二进制时的哨兵输出，与真实路径区分 */
    private static final String NOT_FOUND = "NGINX_NOT_FOUND";

    private final transient ConnectionInfoMapper connectionInfoMapper;

    // ---- 连接区 ----
    private final ComboBox<ConnectionInfo> hostCombo = new ComboBox<>();
    /** nginx 安装目录（包含 sbin/conf 的那个）。填了就只在 <dir>/sbin/nginx 与 <dir>/conf/nginx.conf 找。 */
    private final TextField homeField = UiFactory.textField();
    private final Button connectBtn = UiFactory.primary("连接", this::connect);
    private final Span busyLabel = new Span();
    private final Span envLabel = new Span();

    /** 候选主机；预置主机不在里面时要补进去 */
    private List<ConnectionInfo> candidateHosts = new ArrayList<>();

    /** 共享连接池里的 SSH 连接（按 主机:端口:用户 复用，页面关闭不断开，空闲 10 分钟由池回收） */
    private transient SSHClientUtil ssh;

    // ---- 管理区（连接成功后可见） ----
    private final VerticalLayout manageArea = new VerticalLayout();
    private final Span statusLabel = new Span();
    private final Button startBtn = UiFactory.rowAction("启动", () -> confirmStart());
    private final Button stopBtn = UiFactory.rowDanger("停止", () -> confirmStop());
    private final Button reloadBtn = UiFactory.rowAction("重载配置", () -> runNginx("-s reload", "重载配置"));
    private final Button testBtn = UiFactory.rowAction("配置检测", () -> runNginx("-t", "配置检测"));
    private final Button statusBtn = UiFactory.rowAction("查看状态", this::showStatus);

    private final TextField confPathField = UiFactory.textField();
    private final Button loadConfBtn = UiFactory.button("读取配置", this::loadConfig);
    private final Button saveConfBtn = UiFactory.button("保存配置", () -> saveConfig(true));

    private final CodeEditor configEditor = new CodeEditor(CodeEditor.MODE_NGINX);
    private final TextArea outputArea = UiFactory.textArea();

    /** 探测到的 nginx 可执行文件路径；null = 未探测到 */
    private String nginxBin;
    /** 目标机是否正在运行（最近一次探测结果） */
    private boolean running;

    public NginxMgmtView(ConnectionInfoMapper connectionInfoMapper) {
        this.connectionInfoMapper = connectionInfoMapper;

        add(title("nginx管理"));
        add(subtitle("SSH 到目标服务器管理 nginx：启动/停止/重载配置/配置检测，以及在线编辑配置文件。"));

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
        homeField.setPlaceholder("留空则在默认位置探测，如 /usr/local/nginx");
        homeField.getElement().setAttribute("title",
                "nginx 安装目录（包含 sbin/conf 的那个目录）。填写后直接用 <目录>/sbin/nginx 与 <目录>/conf/nginx.conf");

        HorizontalLayout row = new HorizontalLayout(
                UiFactory.fieldRow("目标服务器", "80px", hostCombo),
                UiFactory.fieldRow("nginx目录", "80px", homeField),
                connectBtn, busyLabel);
        row.setClassName("view-toolbar");
        row.setAlignItems(FlexComponent.Alignment.CENTER);
        row.setFlexGrow(1, busyLabel);
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
     * 「应用管理」页切到 nginx 标签时调用：第一次才真正建连。
     * 为什么不在 attach 时自动连：本页作为应用管理的子标签是<b>构建即 attach</b>的
     * （父页面 addTab 时四个子页全部挂进 DOM），自动连会让"只想看看 jar 项目"的
     * 用户也背上一条 nginx 探测用的 SSH 连接，连不上还要吃一条错误提示。
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
            Dialogs.warn("nginx 目录必须是目标机上的绝对路径（以 / 开头）");
            return;
        }
        closeSsh();
        setBusy(true, "正在连接 " + info.getIdHost() + " …");
        manageArea.setVisible(false);
        envLabel.setText("");

        runAsync("连接 " + info.getIdHost(), () -> {
            // 走共享连接池：同机的其它页面（文件管理、监控等）复用同一条连接
            SSHClientUtil client = SshConnectionPool.acquire(info);
            // 探测顺序：填了 nginx 目录就只按 <dir>/sbin/nginx 找（找不到即检测失败，
            // 不再回落默认探测——用户填了目录就说明他知道 nginx 装在哪，
            // 回落到 PATH 反而可能摸到另一台非预期安装的 nginx）；没填才走默认探测
            String bin = null;
            String confPath = "";
            boolean homeHit = false;
            if (home != null) {
                bin = probeBinAt(client, home);
                homeHit = bin != null;
                if (homeHit) {
                    confPath = probeFileAt(client, home + "/conf/nginx.conf");
                }
            } else {
                bin = detectNginxBin(client);
            }
            String version = bin == null ? "" : execQuiet(client, quote(bin) + " -v 2>&1");
            if (confPath.isEmpty() && bin != null) {
                confPath = parseConfPath(execQuiet(client, quote(bin) + " -V 2>&1"));
            }
            boolean isRunning = bin != null && checkRunning(client);
            return new Object[]{client, bin, version, confPath, isRunning, homeHit};
        }, result -> {
            Object[] parts = (Object[]) result;
            ssh = (SSHClientUtil) parts[0];
            nginxBin = (String) parts[1];
            String version = (String) parts[2];
            String confPath = (String) parts[3];
            boolean isRunning = (Boolean) parts[4];
            boolean homeHit = (Boolean) parts[5];
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
        if (nginxBin == null) {
            manageArea.setVisible(false);
            String tried = home != null
                    ? "已按指定目录检测 " + home + "/sbin/nginx，未找到（填了目录就不回落 PATH 等默认位置）"
                    : "已尝试 PATH、/usr/local/nginx/sbin、/usr/local/openresty/nginx/sbin、/usr/sbin/nginx";
            envLabel.setText("已连接 " + host + "，但没有检测到 nginx（" + tried + "）。");
            Dialogs.warn("目标机没有检测到 nginx，无法管理");
            return;
        }
        manageArea.setVisible(true);
        confPathField.setValue(StrUtil.blankToDefault(confPath, "/etc/nginx/nginx.conf"));
        refreshRunningLabel(isRunning);
        String homeNote = home == null ? ""
                : homeHit ? "　（按指定目录 " + home + " 探测）" : "";
        envLabel.setText("已连接 " + host + "　nginx：" + quote(nginxBin)
                + "　" + StrUtil.trimToEmpty(version).replaceAll("\\s+", " ") + homeNote);
        appendOutput("已连接 " + host + "，nginx 就绪。");
    }

    private void refreshRunningLabel(boolean isRunning) {
        running = isRunning;
        statusLabel.setText(running ? "运行中" : "已停止");
        statusLabel.getStyle().set("color", running
                ? "var(--lumo-success-text-color)" : "var(--lumo-error-text-color)");
        startBtn.setEnabled(!running);
        stopBtn.setEnabled(running);
    }

    private void buildManageArea() {
        statusLabel.getStyle().set("font-weight", "600");

        HorizontalLayout opRow = new HorizontalLayout(
                statusLabel, startBtn, stopBtn, reloadBtn, testBtn, statusBtn);
        opRow.setSpacing(false);
        opRow.addClassName("row-actions");
        opRow.setAlignItems(FlexComponent.Alignment.CENTER);

        confPathField.setPlaceholder("nginx 配置文件绝对路径");
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

    private void confirmStart() {
        if (!hasPermission(Constants.UPDATE)) {
            Dialogs.warn("权限不足，无法操作 nginx");
            return;
        }
        if (running) {
            Dialogs.warn("nginx 已在运行中");
            return;
        }
        Dialogs.confirm("启动 nginx", "确认执行 " + quote(nginxBin) + " 吗？", () ->
                runNginx("", "启动 nginx"));
    }

    private void confirmStop() {
        if (!hasPermission(Constants.UPDATE)) {
            Dialogs.warn("权限不足，无法操作 nginx");
            return;
        }
        Dialogs.confirmDanger("停止 nginx",
                "确认执行 " + quote(nginxBin) + " -s stop 吗？正在对外提供的服务会立即中断。",
                "确认停止", () -> runNginx("-s stop", "停止 nginx"));
    }

    /** 执行一条 nginx 自身的管理命令（start/stop/reload/-t），结果进输出区并刷新运行状态。 */
    private void runNginx(String args, String actionName) {
        if (!hasPermission(Constants.UPDATE)) {
            Dialogs.warn("权限不足，无法操作 nginx");
            return;
        }
        SSHClientUtil client = ssh;
        if (client == null) {
            Dialogs.warn("请先连接目标服务器");
            return;
        }
        setBusy(true, actionName + " …");
        String cmd = quote(nginxBin) + (StrUtil.isBlank(args) ? "" : " " + args);
        runAsync(actionName, () -> execQuiet(client, cmd + " 2>&1"), output -> {
            setBusy(false, "");
            appendOutput("$ " + cmd + "\n" + StrUtil.trimToEmpty((String) output));
            Dialogs.success(actionName + "命令已执行，输出见下方");
            refreshAsync();
        }, e -> {
            setBusy(false, "");
            Dialogs.error(actionName + "失败：" + StrUtil.emptyToDefault(e.getMessage(), "未知错误"));
        });
    }

    /** 后台刷新一次运行状态（启动/停止/重载后调用）。 */
    private void refreshAsync() {
        SSHClientUtil client = ssh;
        if (client == null) {
            return;
        }
        runAsync("刷新状态", () -> checkRunning(client), isRunning ->
                refreshRunningLabel((Boolean) isRunning), e -> { });
    }

    private void showStatus() {
        SSHClientUtil client = ssh;
        if (client == null) {
            Dialogs.warn("请先连接目标服务器");
            return;
        }
        setBusy(true, "正在查询状态 …");
        runAsync("查看状态", () -> execQuiet(client,
                        "ps -ef | grep nginx | grep -v grep | head -n 10"), output -> {
            setBusy(false, "");
            String text = StrUtil.trimToEmpty((String) output);
            appendOutput("$ ps -ef | grep nginx\n" + (text.isEmpty() ? "（没有 nginx 进程）" : text));
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
        SSHClientUtil client = ssh;
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
     * 写回成功后询问是否立即 reload——改完配置不 reload 是这类页面最常被踩的坑。
     */
    private void saveConfig(boolean askReload) {
        if (!hasPermission(Constants.UPDATE)) {
            Dialogs.warn("权限不足，无法保存配置");
            return;
        }
        SSHClientUtil client = ssh;
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
                        File tmp = File.createTempFile("lanyue-nginx-conf-", ".conf");
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
                        if (askReload && nginxBin != null) {
                            Dialogs.confirm("重载配置", "配置已保存，立即执行 " + quote(nginxBin)
                                    + " -s reload 让新配置生效吗？", () -> runNginx("-s reload", "重载配置"));
                        }
                    }, e -> {
                        setBusy(false, "");
                        Dialogs.error("保存配置失败：" + StrUtil.emptyToDefault(e.getMessage(), "未知错误"));
                    });
                });
    }

    // ------------------------------------------------------------------
    // 探测与执行工具
    // ------------------------------------------------------------------

    private String hostLabel() {
        return hostCombo.getValue() == null ? "目标机" : hostCombo.getValue().getIdHost();
    }

    /** 探测 nginx 二进制：PATH → 常见安装路径，都没有返回 null。 */
    private static String detectNginxBin(SSHClientUtil client) {
        String out = execQuiet(client,
                "command -v nginx 2>/dev/null "
                        + "|| ls /usr/local/nginx/sbin/nginx /usr/local/openresty/nginx/sbin/nginx /usr/sbin/nginx 2>/dev/null "
                        + "| head -n 1 "
                        + "|| echo " + NOT_FOUND);
        String trimmed = StrUtil.trimToEmpty(out);
        String firstLine = trimmed.split("\\R", 2)[0].trim();
        if (firstLine.isEmpty() || firstLine.equals(NOT_FOUND) || !firstLine.startsWith("/")) {
            return null;
        }
        return firstLine;
    }

    /** 指定 nginx 目录下的二进制：<dir>/sbin/nginx 存在且可执行才返回路径，否则 null。 */
    private static String probeBinAt(SSHClientUtil client, String home) {
        String path = home + "/sbin/nginx";
        String out = execQuiet(client, "test -x " + quote(path) + " && echo OK || echo " + NOT_FOUND);
        return StrUtil.trimToEmpty(out).equals("OK") ? path : null;
    }

    /** 指定 nginx 目录下的配置：<dir>/conf/nginx.conf 存在返回路径，否则空串。 */
    private static String probeFileAt(SSHClientUtil client, String path) {
        String out = execQuiet(client, "test -f " + quote(path) + " && echo OK || echo " + NOT_FOUND);
        return StrUtil.trimToEmpty(out).equals("OK") ? path : "";
    }

    /** 归一化 nginx 目录输入：去掉首尾空白与结尾的 /；空白返回 null（= 未填）；非绝对路径返回哨兵 "?"。 */
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

    /** 从 nginx -V 输出里抠 --conf-path= 的值；抠不到返回空串。 */
    private static String parseConfPath(String versionOutput) {
        if (StrUtil.isBlank(versionOutput)) {
            return "";
        }
        for (String token : versionOutput.split("\\s+")) {
            if (token.startsWith("--conf-path=")) {
                return token.substring("--conf-path=".length());
            }
        }
        return "";
    }

    private static boolean checkRunning(SSHClientUtil client) {
        String out = execQuiet(client, "ps -ef | grep nginx | grep -v grep | head -n 1");
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
        }, "nginx-" + label);
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
        // 不真正断开——其它页面还能复用，空闲超 10 分钟由池自动回收
        ssh = null;
    }

    private static boolean hasPermission(String code) {
        com.sl.entity.User user = com.sl.security.CurrentUser.get();
        return user != null && user.hasPermission(code);
    }
}
