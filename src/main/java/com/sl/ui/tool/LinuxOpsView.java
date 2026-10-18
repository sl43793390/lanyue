package com.sl.ui.tool;

import cn.hutool.core.util.StrUtil;
import com.sl.entity.ConnectionInfo;
import com.sl.mapper.ConnectionInfoMapper;
import com.sl.ops.LinuxOpsService;
import com.sl.ops.model.LinuxEnv;
import com.sl.ui.component.CodeEditor;
import com.sl.ui.component.Dialogs;
import com.sl.ui.component.HostCandidates;
import com.sl.ui.component.UiFactory;
import com.sl.ui.component.ViewBase;
import com.sl.util.SshCommandRunner;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 常用 Linux 命令。
 * <p>
 * 把运维日常要 ssh 上去敲的那些事收成按钮：防火墙开关与放行端口、用户与 sudo 授权、
 * 服务启停、系统巡检（内存 / 磁盘 / 进程 / 端口 / 日志 / 时间同步 / SELinux 状态）、
 * crontab 在线编辑、主机名修改。改系统安全策略一类的高危动作不在本页提供——
 * 真要改请登录目标机手动执行，页面只做查询与常规运维。
 * <p>
 * <b>命令不是写死的。</b>连上目标机先做一次环境探测（{@link LinuxOpsService#probe()}），
 * 拿到发行版、是不是 root、免密 sudo 有没有、防火墙是 firewalld 还是 ufw、有没有 SELinux，
 * 页面据此决定用哪条命令、哪些按钮能点——同一套界面在 Rocky 8 与 Ubuntu 22 上都要能用。
 * <p>
 * <b>权限与校验。</b>需要 root 的动作（建用户、关防火墙、改主机名）在探测到当前账号
 * 没有升级权限时直接置灰，不让用户点下去再被远端回一句 permission denied；
 * 所有外部输入（用户名 / 服务名 / 端口 / 主机名）都在领域层过白名单正则。
 * <p>
 * <b>连接自愈。</b>命令通道走 {@link SshCommandRunner}，共享连接被空闲回收（15 分钟）之后
 * 下一次操作会自动重连，页面开着不动很久也不会「点一下就报错」。
 * <p>
 * <b>布局。</b>连接区是一张通栏卡片（选机器 + 连接 + 环境信息条）；连接成功后下方按功能
 * 分组的操作卡片网格（两列自适应、窄屏单列），每组一枚彩色图标 + 标题，按钮行走紧凑间距。
 */
@Service
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class LinuxOpsView extends ViewBase {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(LinuxOpsView.class);

    private final transient ConnectionInfoMapper connectionInfoMapper;

    // ---- 连接区 ----
    private final ComboBox<ConnectionInfo> hostCombo = new ComboBox<>();
    private final Button connectBtn = UiFactory.primary("连接", this::connect);
    private final Span busyLabel = new Span();
    private final Span envLabel = new Span();
    /** 未连接时的引导卡：连接成功后隐藏，操作区接手 */
    private final Div emptyCard = new Div();

    // ---- 操作区 ----
    private final Div opsArea = new Div();
    private final Span firewallTitle = new Span();
    /** 防火墙卡头右侧的元信息：哪一套防火墙、开没开（连接后由探测结果填充） */
    private final Span firewallMeta = new Span();
    /** 需要 root（或有免密 sudo）的动作，探测到权限不够时统一置灰 */
    private final List<Button> privilegedButtons = new ArrayList<>();

    private final TextField firewallPortField = UiFactory.textField();
    private final ComboBox<String> firewallProtoCombo = new ComboBox<>();
    private final TextField unitField = UiFactory.textField();
    private final TextField portQueryField = UiFactory.textField();
    private final TextField hostnameField = UiFactory.textField();
    /** crontab 编辑的目标用户：自己不需要提权，别人的（root）需要 root / 免密 sudo */
    private final ComboBox<String> cronUserCombo = new ComboBox<>();

    private static final String CRON_USER_CURRENT = "当前登录用户";

    private List<ConnectionInfo> candidateHosts = new ArrayList<>();
    private ConnectionInfo presetHost;
    private boolean autoConnectPending;

    private transient SshCommandRunner runner;
    private transient LinuxOpsService service;
    private LinuxEnv env;

    public LinuxOpsView(ConnectionInfoMapper connectionInfoMapper) {
        this.connectionInfoMapper = connectionInfoMapper;

        add(title("常用 Linux 命令"));
        add(subtitle("连上目标机后直接执行日常运维动作：防火墙、用户与 sudo 授权、服务启停、系统巡检、"
                + "定时任务、主机名。命令按目标机的发行版自动选择，需要 root 的动作在权限不足时会置灰。"
                + "SSH 连接空闲断开后，下一次操作会自动重连。"));

        add(buildConnectCard());

        buildOpsArea();
        opsArea.setVisible(false);
        add(opsArea);
        add(buildEmptyCard());

        loadCandidateHosts();
    }

    // ------------------------------------------------------------------
    // 连接区（一张通栏卡片：选机器 + 连接 + 环境信息条）
    // ------------------------------------------------------------------

    private Component buildConnectCard() {
        hostCombo.setWidth("260px");
        hostCombo.setPlaceholder("选择一台已配置的服务器");
        hostCombo.setItemLabelGenerator(item -> item.getIdHost() + " (" + item.getIdUser() + ")");
        hostCombo.setAllowCustomValue(false);

        Div head = new Div();
        head.addClassName("ops-connect-head");
        Icon plug = new Icon(VaadinIcon.PLUG);
        plug.addClassName("ops-card-icon");
        plug.addClassName("ops-icon-blue");
        Span headTitle = new Span("目标服务器");
        headTitle.addClassName("ops-connect-title");
        head.add(plug, headTitle);

        Div row = new Div(UiFactory.fieldRow("服务器", "72px", hostCombo), connectBtn, busyLabel);
        row.addClassName("ops-connect-row");
        busyLabel.addClassName("ops-busy");

        envLabel.addClassName("docker-env-label");
        envLabel.getStyle().set("overflow-wrap", "anywhere");
        envLabel.setText("尚未连接。选择一台服务器，点「连接」后这里会显示目标机的环境信息。");
        Div envBar = new Div(new Icon(VaadinIcon.INFO), envLabel);
        envBar.addClassName("ops-env-bar");

        Div card = new Div(head, row, envBar);
        card.addClassName("ops-connect-card");
        return card;
    }

    private void loadCandidateHosts() {
        candidateHosts = HostCandidates.load(connectionInfoMapper);
        hostCombo.setItems(candidateHosts);
        if (candidateHosts.isEmpty()) {
            envLabel.setText("没有可用的服务器，请先到「远程应用管理 → 免登录服务器列表」添加机器。");
        }
    }

    /** 由服务器列表等入口预置目标机；真正的连接等 attach 之后发起 */
    public void setPresetHost(ConnectionInfo info) {
        this.presetHost = info;
        this.autoConnectPending = info != null;
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        if (!autoConnectPending || presetHost == null) {
            return;
        }
        ConnectionInfo target = HostCandidates.match(candidateHosts, presetHost);
        if (target == null) {
            target = presetHost;
            candidateHosts.add(target);
            hostCombo.setItems(candidateHosts);
        }
        autoConnectPending = false;
        hostCombo.setValue(target);
        connect();
    }

    private void connect() {
        ConnectionInfo info = hostCombo.getValue();
        if (info == null) {
            Dialogs.warn("请先选择目标服务器");
            return;
        }
        closeRunner();
        setBusy(true, "正在连接 " + info.getIdHost() + " 并检测环境 …");
        emptyCard.setVisible(false);
        envLabel.setText("正在连接并读取目标机环境 …");

        runAsync("连接 " + info.getIdHost(), () -> {
            SshCommandRunner newRunner = new SshCommandRunner(info);
            LinuxOpsService newService = new LinuxOpsService(newRunner);
            // 连上先探一次环境：这一步同时验证了连接可用（认证失败会在这里抛出来）
            LinuxEnv probe = newService.probe();
            return new Object[]{newRunner, newService, probe};
        }, result -> {
            Object[] parts = (Object[]) result;
            runner = (SshCommandRunner) parts[0];
            service = (LinuxOpsService) parts[1];
            env = (LinuxEnv) parts[2];
            setBusy(false, "");
            applyEnv();
        }, e -> {
            setBusy(false, "");
            emptyCard.setVisible(true);
            envLabel.setText("尚未连接。选择一台服务器，点「连接」后这里会显示目标机的环境信息。");
            Dialogs.error("连接失败：" + StrUtil.emptyToDefault(e.getMessage(), e.getClass().getSimpleName()));
        });
    }

    /** 探测结果落到界面上：环境信息条、防火墙元信息、按钮可用性 */
    private void applyEnv() {
        if (env == null) {
            return;
        }
        opsArea.setVisible(true);
        emptyCard.setVisible(false);
        boolean canEscalate = env.canEscalate();
        String reason = "当前登录用户不是 root，且目标机上不能免密 sudo，这个动作做不了";
        for (Button button : privilegedButtons) {
            button.setEnabled(canEscalate);
            // 换一台权限足够的机器重连时，把上次留下的「权限不足」提示清掉，
            // 否则按钮能点了，tooltip 还挂着红字说明
            button.setTooltipText(canEscalate ? "" : reason);
        }
        // 非 root 且无免密 sudo 的账号看不了别人的 crontab，把「root」选项收掉
        if (!canEscalate) {
            cronUserCombo.setItems(List.of(CRON_USER_CURRENT));
            cronUserCombo.setValue(CRON_USER_CURRENT);
        }
        if (StrUtil.isNotBlank(env.getProbeError())) {
            envLabel.setText("已连接 " + hostLabel() + "，但环境探测没读全：" + env.getProbeError()
                    + "（只读动作仍可用，需要 root 的动作已置灰）");
            return;
        }
        envLabel.setText("已连接 " + hostLabel() + "　" + env.summary());
        firewallMeta.setText(env.firewallLabel());
        if (!canEscalate) {
            Dialogs.warn("当前登录用户 " + env.getLoginUser() + "（uid=" + env.getUid()
                    + "）不是 root 且不能免密 sudo，涉及改配置的动作已置灰。");
        }
    }

    private String hostLabel() {
        return runner == null ? "" : runner.hostLabel();
    }

    // ------------------------------------------------------------------
    // 操作区（分组卡片网格）
    // ------------------------------------------------------------------

    private void buildOpsArea() {
        opsArea.addClassName("ops-area");

        opsArea.add(buildFirewallCard());
        opsArea.add(buildUserCard());
        opsArea.add(buildServiceCard());
        opsArea.add(buildInspectCard());
        opsArea.add(buildCrontabCard());
        opsArea.add(buildHostCard());
    }

    /** 未连接时的引导卡 */
    private Div buildEmptyCard() {
        Icon plug = new Icon(VaadinIcon.PLUG);
        plug.addClassName("ops-card-icon");
        plug.addClassName("ops-icon-blue");
        Span desc = new Span("从上方选择一台已配置的服务器并点「连接」。连接成功后，这里会按功能分组列出"
                + "防火墙、用户权限、服务管理、系统巡检、定时任务、主机名等日常运维操作；"
                + "具体用哪条命令，会根据目标机的发行版自动选择。");
        desc.addClassName("ops-empty-desc");
        emptyCard.addClassName("ops-card");
        emptyCard.addClassName("ops-empty-card");
        emptyCard.add(plug, cardTitle("还没有连接服务器"), desc);
        return emptyCard;
    }

    /**
     * 带右侧元信息的分组卡片：彩色圆形图标 + 标题 + 卡头右侧元信息 + 若干行动作。
     * <p>
     * 之所以不跟 {@link #opsCard} 共用名字：两者差在第四个参数，而动作行是 varargs，
     * 同名重载在「传了 4 个以上参数」时编译器无法消歧，只能换个方法名。
     *
     * @param meta 卡头右侧的元信息（如防火墙类型与运行状态）
     */
    private Div opsCardWithMeta(VaadinIcon icon, String tone, Component title, Component meta, Component... rows) {
        Icon badge = new Icon(icon);
        badge.addClassName("ops-card-icon");
        badge.addClassName("ops-icon-" + tone);

        Div head = new Div(badge, title, meta);
        head.addClassName("ops-card-head");

        Div body = new Div();
        body.addClassName("ops-card-body");
        body.add(rows);

        Div card = new Div(head, body);
        card.addClassName("ops-card");
        return card;
    }

    /**
     * 分组卡片：彩色圆形图标 + 标题 + 若干行动作。
     * <p>
     * 布局全部走 CSS 类（{@code .ops-card} / {@code .ops-row}），Java 侧只搭组件树，
     * 不再内联算 flex 比例——内联样式一旦多了，改一处漏一处的概率极高。
     */
    private Div opsCard(VaadinIcon icon, String tone, Component title, Component... rows) {
        Icon badge = new Icon(icon);
        badge.addClassName("ops-card-icon");
        badge.addClassName("ops-icon-" + tone);

        Div head = new Div(badge, title);
        head.addClassName("ops-card-head");

        Div body = new Div();
        body.addClassName("ops-card-body");
        body.add(rows);

        Div card = new Div(head, body);
        card.addClassName("ops-card");
        return card;
    }

    /** 卡片标题：沿用全局小节标题的字号字重，再叠加卡片内的排版类 */
    private Span cardTitle(String text) {
        Span title = new Span(text);
        title.addClassName("view-section-title");
        title.addClassName("ops-card-title");
        return title;
    }

    /** 卡片内的一行动作按钮：紧凑间距，flex 换行 */
    private Div opsRow(Component... items) {
        Div row = new Div();
        row.addClassName("ops-row");
        row.add(items);
        return row;
    }

    /** 需要 root 的按钮：注册进 privilegedButtons，探测后发现没权限会统一置灰 */
    private Button privileged(String text, Runnable action) {
        Button button = UiFactory.rowAction(text, action);
        privilegedButtons.add(button);
        return button;
    }

    private Component buildFirewallCard() {
        firewallTitle.addClassName("view-section-title");
        firewallTitle.addClassName("ops-card-title");
        firewallTitle.setText("防火墙");
        firewallMeta.addClassName("ops-card-meta");

        firewallProtoCombo.setItems(List.of("tcp", "udp"));
        firewallProtoCombo.setValue("tcp");
        firewallProtoCombo.setWidth("90px");
        firewallPortField.setPlaceholder("8080");
        firewallPortField.setWidth("110px");

        return opsCardWithMeta(VaadinIcon.SHIELD, "blue", firewallTitle, firewallMeta,
                opsRow(
                        UiFactory.rowAction("查看状态", () -> query("防火墙状态", () -> service.firewallDetail(env))),
                        privileged("开启防火墙", () -> confirmFirewall(true)),
                        privileged("关闭防火墙", () -> confirmFirewall(false)),
                        UiFactory.rowAction("重载规则", () -> query("重载防火墙规则", () -> service.firewallReload(env)))),
                opsRow(new Span("端口"), firewallPortField, firewallProtoCombo,
                        privileged("放行端口", this::openFirewallPort),
                        privileged("移除端口", this::closeFirewallPort)));
    }

    private Component buildUserCard() {
        return opsCard(VaadinIcon.USERS, "purple", cardTitle("用户与权限"),
                opsRow(
                        UiFactory.rowAction("查看用户列表", () -> query("用户列表", () -> service.listUsers())),
                        privileged("创建用户…", this::openCreateUserDialog),
                        privileged("重置密码…", this::openResetPasswordDialog),
                        privileged("加入 sudo 组…", () -> openSudoDialog(true)),
                        privileged("移出 sudo 组…", () -> openSudoDialog(false)),
                        privileged("锁定账号…", () -> openLockDialog(true)),
                        privileged("解锁账号…", () -> openLockDialog(false)),
                        privileged("删除用户…", this::openDeleteUserDialog)));
    }

    private Component buildServiceCard() {
        unitField.setPlaceholder("服务名，如 nginx / docker");
        unitField.setWidth("240px");
        return opsCard(VaadinIcon.COGS, "green", cardTitle("服务管理"),
                opsRow(new Span("服务名"), unitField,
                        UiFactory.rowAction("查看状态", () -> serviceOp("状态", () -> service.serviceStatus(unit(), env))),
                        privileged("启动", () -> serviceAction("启动", "start")),
                        privileged("停止", this::stopServiceConfirmed),
                        privileged("重启", () -> serviceAction("重启", "restart")),
                        privileged("开机自启", () -> serviceAction("设为开机自启", "enable")),
                        privileged("取消自启", () -> serviceAction("取消开机自启", "disable")),
                        UiFactory.rowAction("运行中的服务", () -> query("运行中的服务", () -> service.listRunningServices(env)))));
    }

    private Component buildInspectCard() {
        portQueryField.setPlaceholder("8080");
        portQueryField.setWidth("110px");
        return opsCard(VaadinIcon.DASHBOARD, "orange", cardTitle("系统巡检"),
                opsRow(
                        UiFactory.rowAction("系统概览", () -> query("系统概览", () -> service.overview())),
                        UiFactory.rowAction("磁盘空间", () -> query("磁盘空间与 inode", () -> service.diskUsage())),
                        UiFactory.rowAction("目录占用 Top", () -> query("目录占用 Top", () -> service.topDirectories())),
                        UiFactory.rowAction("进程 Top(CPU)", () -> query("进程占用 Top(CPU)", () -> service.topProcesses("cpu"))),
                        UiFactory.rowAction("进程 Top(内存)", () -> query("进程占用 Top(内存)", () -> service.topProcesses("mem"))),
                        UiFactory.rowAction("监听端口", () -> query("监听端口", () -> service.listeningPorts()))),
                opsRow(new Span("端口占用"), portQueryField,
                        UiFactory.rowAction("查询", this::queryPortOwner),
                        UiFactory.rowAction("登录用户", () -> query("登录用户", () -> service.loggedInUsers())),
                        UiFactory.rowAction("系统日志(200行)", () -> query("系统日志", () -> service.systemLog(200))),
                        UiFactory.rowAction("时间同步状态", () -> query("时间同步状态", () -> service.timeSyncStatus())),
                        privileged("立即校时", () -> query("立即校时", () -> service.syncTimeNow(env))),
                        UiFactory.rowAction("SELinux 状态", () -> query("SELinux 状态", () -> service.selinuxStatus()))));
    }

    /** 定时任务：按用户查看 crontab 与系统级定时配置，支持在线编辑（保存前自动备份） */
    private Component buildCrontabCard() {
        cronUserCombo.setItems(List.of(CRON_USER_CURRENT, "root"));
        cronUserCombo.setValue(CRON_USER_CURRENT);
        cronUserCombo.setWidth("170px");
        return opsCard(VaadinIcon.CALENDAR, "teal", cardTitle("定时任务（crontab）"),
                opsRow(new Span("目标用户"), cronUserCombo,
                        UiFactory.rowAction("查看计划任务", () -> query("计划任务", () -> service.crontabList(cronTarget(), env))),
                        privileged("编辑 crontab…", this::openCrontabEditor)));
    }

    private Component buildHostCard() {
        hostnameField.setPlaceholder("新主机名，如 app-node-01");
        hostnameField.setWidth("240px");
        return opsCard(VaadinIcon.SERVER, "blue", cardTitle("主机名与网络"),
                opsRow(
                        UiFactory.rowAction("主机名 / 网卡 / 路由", () -> query("主机与网络信息", () -> service.hostInfo())),
                        new Span("新主机名"), hostnameField,
                        privileged("修改主机名", this::confirmSetHostname)));
    }

    /** 编辑目标：选「当前登录用户」传空串，选具体用户传用户名 */
    private String cronTarget() {
        String value = StrUtil.trimToEmpty(cronUserCombo.getValue());
        return CRON_USER_CURRENT.equals(value) ? "" : value;
    }

    // ------------------------------------------------------------------
    // 防火墙动作
    // ------------------------------------------------------------------

    private void confirmFirewall(boolean enable) {
        if (!checkEnv()) {
            return;
        }
        String action = enable ? "开启" : "关闭";
        Dialogs.confirm(action + "防火墙",
                "将在 " + hostLabel() + " 上" + action + "防火墙（" + env.firewallLabel() + "）。\n"
                        + (enable
                        ? "开启后没放行的端口会立即被拒绝，确认业务端口都已放行。"
                        : "会同时取消开机自启，机器重启后也不会再自动开启。"),
                () -> query(action + "防火墙", () -> service.setFirewallEnabled(enable, env)));
    }

    private void openFirewallPort() {
        if (!checkEnv()) {
            return;
        }
        Integer port = parsePort(firewallPortField, "放行端口");
        if (port == null) {
            return;
        }
        String proto = StrUtil.emptyToDefault(firewallProtoCombo.getValue(), "tcp");
        query("放行端口", () -> service.firewallOpenPort(port, proto, env));
    }

    private void closeFirewallPort() {
        if (!checkEnv()) {
            return;
        }
        Integer port = parsePort(firewallPortField, "移除端口");
        if (port == null) {
            return;
        }
        String proto = StrUtil.emptyToDefault(firewallProtoCombo.getValue(), "tcp");
        Dialogs.confirm("移除端口放行规则",
                "将从 " + hostLabel() + " 的防火墙里移除 " + port + "/" + proto + " 的放行规则。",
                () -> query("移除端口", () -> service.firewallClosePort(port, proto, env)));
    }

    // ------------------------------------------------------------------
    // 用户动作
    // ------------------------------------------------------------------

    private void openCreateUserDialog() {
        if (!checkEnv() || !ensurePrivilege("创建用户")) {
            return;
        }
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("创建用户");
        dialog.setWidth("640px");

        TextField nameField = UiFactory.textField("用户名", "字母开头，如 deploy");
        nameField.setWidthFull();
        PasswordField passwordField = new PasswordField("初始密码");
        passwordField.setPlaceholder("至少 6 位");
        passwordField.setWidthFull();
        PasswordField confirmField = new PasswordField("确认密码");
        confirmField.setPlaceholder("再输一遍");
        confirmField.setWidthFull();

        // 只有当前登录用户是 root 才能授予 sudo：加组要改 /etc/group，普通用户没这个权限
        boolean isRoot = env.isRoot();
        String sudoGroup = StrUtil.emptyToDefault(env.sudoGroupName(), "wheel");
        Checkbox sudoBox = new Checkbox("授予 sudo 权限");
        sudoBox.setEnabled(isRoot);
        Span sudoHint = new Span(isRoot
                ? "勾选后把新用户加入 " + sudoGroup + " 组，重新登录后即可 sudo（sudo 时需要输入它自己的密码）。"
                + (StrUtil.isBlank(env.sudoGroupName()) ? "注意：这台机器上没找到 sudo/wheel 组，实际会跳过授权。" : "")
                : "当前是普通用户连接，无法授予 sudo（改组成员需要 root）。需要的话请用 root 账号连接，"
                + "或事后在目标机执行 usermod -aG " + sudoGroup + " <用户>。");
        sudoHint.addClassName("view-subtitle");
        sudoHint.getStyle().set("overflow-wrap", "anywhere");

        VerticalLayout body = new VerticalLayout(nameField, passwordField, confirmField, sudoBox, sudoHint);
        body.setPadding(false);
        body.getStyle().set("gap", "10px");
        dialog.add(body);

        dialog.getFooter().add(UiFactory.button("取消", dialog::close), UiFactory.primary("创建用户", () -> {
            String name = StrUtil.trimToEmpty(nameField.getValue());
            String password = passwordField.getValue();
            if (StrUtil.isBlank(name)) {
                Dialogs.warn("请填写用户名");
                return;
            }
            if (!password.equals(confirmField.getValue())) {
                Dialogs.warn("两次输入的密码不一致");
                return;
            }
            boolean grantSudo = sudoBox.getValue();
            dialog.close();
            query("创建用户", () -> service.createUser(name, password, grantSudo, env));
        }));
        dialog.open();
    }

    private void openResetPasswordDialog() {
        if (!checkEnv() || !ensurePrivilege("重置密码")) {
            return;
        }
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("重置用户密码");
        TextField nameField = UiFactory.textField("用户名", "要改密码的账号");
        nameField.setWidthFull();
        PasswordField passwordField = new PasswordField("新密码");
        passwordField.setPlaceholder("至少 6 位");
        passwordField.setWidthFull();
        PasswordField confirmField = new PasswordField("确认密码");
        confirmField.setPlaceholder("再输一遍");
        confirmField.setWidthFull();
        Span hint = new Span("密码以 base64 传给目标机的 chpasswd，不会以明文出现在命令与日志里。");
        hint.addClassName("view-subtitle");

        VerticalLayout body = new VerticalLayout(nameField, passwordField, confirmField, hint);
        body.setPadding(false);
        body.getStyle().set("gap", "10px");
        dialog.add(body);
        dialog.getFooter().add(UiFactory.button("取消", dialog::close), UiFactory.primary("重置密码", () -> {
            String name = StrUtil.trimToEmpty(nameField.getValue());
            String password = passwordField.getValue();
            if (StrUtil.isBlank(name)) {
                Dialogs.warn("请填写用户名");
                return;
            }
            if (!password.equals(confirmField.getValue())) {
                Dialogs.warn("两次输入的密码不一致");
                return;
            }
            dialog.close();
            query("重置密码", () -> service.resetPassword(name, password, env));
        }));
        dialog.open();
    }

    private void openSudoDialog(boolean grant) {
        if (!checkEnv() || !ensurePrivilege((grant ? "授予" : "移除") + " sudo")) {
            return;
        }
        String group = StrUtil.emptyToDefault(env.sudoGroupName(), "(这台机器上没有 wheel / sudo 组)");
        String action = grant ? "加入 sudo 组" : "移出 sudo 组";
        promptUserName(action,
                "目标机上 sudo 权限挂在 " + group + " 组上，"
                        + (grant ? "加进去之后该账号重新登录即可 sudo。" : "移出之后该账号失去 sudo 权限。"),
                grant ? "加入" : "移出",
                name -> query(action, () -> service.setSudo(name, grant, env)));
    }

    private void openLockDialog(boolean lock) {
        if (!checkEnv() || !ensurePrivilege(lock ? "锁定账号" : "解锁账号")) {
            return;
        }
        String action = lock ? "锁定账号" : "解锁账号";
        promptUserName(action,
                lock ? "锁定后该账号无法登录（家目录与数据保留），适合人员离岗时先停用。"
                        : "解锁后账号恢复登录。",
                lock ? "锁定" : "解锁",
                name -> query(action, () -> service.setUserLocked(name, lock, env)));
    }

    private void openDeleteUserDialog() {
        if (!checkEnv() || !ensurePrivilege("删除用户")) {
            return;
        }
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("删除用户");
        dialog.setWidth("620px");
        TextField nameField = UiFactory.textField("用户名", "要删除的账号");
        nameField.setWidthFull();
        Checkbox homeBox = new Checkbox("同时删除家目录与邮件（userdel -r）");
        Span hint = new Span("删除后无法恢复。只是想停用账号的话，用「锁定账号」更安全。");
        hint.addClassName("view-subtitle");
        VerticalLayout body = new VerticalLayout(nameField, homeBox, hint);
        body.setPadding(false);
        body.getStyle().set("gap", "10px");
        dialog.add(body);
        dialog.getFooter().add(UiFactory.button("取消", dialog::close), UiFactory.danger("确认删除", () -> {
            String name = StrUtil.trimToEmpty(nameField.getValue());
            if (StrUtil.isBlank(name)) {
                Dialogs.warn("请填写用户名");
                return;
            }
            boolean removeHome = homeBox.getValue();
            dialog.close();
            query("删除用户", () -> service.deleteUser(name, removeHome, env));
        }));
        dialog.open();
    }

    /** 单输入框（用户名）的通用弹窗 */
    private void promptUserName(String title, String hint, String confirmText, Consumer<String> action) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(title);
        dialog.setWidth("560px");
        TextField nameField = UiFactory.textField("用户名", "目标机上已有的账号");
        nameField.setWidthFull();
        Span hintSpan = new Span(hint);
        hintSpan.addClassName("view-subtitle");
        hintSpan.getStyle().set("overflow-wrap", "anywhere");
        VerticalLayout body = new VerticalLayout(nameField, hintSpan);
        body.setPadding(false);
        body.getStyle().set("gap", "10px");
        dialog.add(body);
        dialog.getFooter().add(UiFactory.button("取消", dialog::close), UiFactory.primary(confirmText, () -> {
            String name = StrUtil.trimToEmpty(nameField.getValue());
            if (StrUtil.isBlank(name)) {
                Dialogs.warn("请填写用户名");
                return;
            }
            dialog.close();
            action.accept(name);
        }));
        dialog.open();
    }

    // ------------------------------------------------------------------
    // 服务与主机
    // ------------------------------------------------------------------

    private String unit() {
        return StrUtil.trimToEmpty(unitField.getValue());
    }

    private void serviceOp(String what, ThrowingSupplier task) {
        if (!requireUnit()) {
            return;
        }
        query("服务" + what, task);
    }

    private void serviceAction(String label, String action) {
        if (!requireUnit()) {
            return;
        }
        String unitName = unit();
        query(label + "服务 " + unitName, () -> service.serviceAction(unitName, action, env));
    }

    private void stopServiceConfirmed() {
        if (!requireUnit()) {
            return;
        }
        String unitName = unit();
        Dialogs.confirm("停止服务 " + unitName,
                "将在 " + hostLabel() + " 上停止 " + unitName + "，依赖它的业务会中断。",
                () -> query("停止服务 " + unitName, () -> service.serviceAction(unitName, "stop", env)));
    }

    private boolean requireUnit() {
        if (!checkEnv()) {
            return false;
        }
        if (StrUtil.isBlank(unit())) {
            Dialogs.warn("请先填写服务名（systemctl list-unit-files | grep 关键字 可以查）");
            return false;
        }
        return true;
    }

    private void queryPortOwner() {
        if (!checkEnv()) {
            return;
        }
        Integer port = parsePort(portQueryField, "端口占用查询");
        if (port == null) {
            return;
        }
        query("端口占用", () -> service.portOwner(port));
    }

    private void confirmSetHostname() {
        if (!checkEnv() || !ensurePrivilege("修改主机名")) {
            return;
        }
        String name = StrUtil.trimToEmpty(hostnameField.getValue());
        if (StrUtil.isBlank(name)) {
            Dialogs.warn("请填写新主机名");
            return;
        }
        Dialogs.confirm("修改主机名",
                "将把 " + env.getHostname() + " 的主机名改成 " + name + "，并写入 /etc/hosts。"
                        + "监控面板、日志里看到的机器名会随之变化。",
                () -> query("修改主机名", () -> service.setHostname(name, env)));
    }

    // ------------------------------------------------------------------
    // crontab 在线编辑
    // ------------------------------------------------------------------

    /**
     * crontab 在线编辑：先取原文预填，保存时整体替换。
     * 改自己的 crontab 不需要任何提权；改别人的（比如 root 的）由 {@link #privileged} 统一管权限。
     */
    private void openCrontabEditor() {
        if (!checkEnv()) {
            return;
        }
        String target = cronTarget();
        boolean self = StrUtil.isBlank(target) || target.equals(env.getLoginUser());
        if (!self && !ensurePrivilege("编辑用户 " + target + " 的 crontab")) {
            return;
        }
        setBusy(true, "正在读取 crontab …");
        runAsync("读取 crontab", () -> service.crontabRead(target, env), content -> {
            setBusy(false, "");
            showCrontabEditor(target, String.valueOf(content));
        }, e -> {
            setBusy(false, "");
            Dialogs.error("读取 crontab 失败：" + StrUtil.emptyToDefault(e.getMessage(), e.getClass().getSimpleName()));
        });
    }

    private void showCrontabEditor(String target, String content) {
        String owner = StrUtil.isBlank(target) ? env.getLoginUser() : target;
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("编辑 crontab（用户：" + owner + "）");
        dialog.setWidth("min(1000px, 94vw)");

        // crontab 每行末尾都是一条 shell 命令，用 shell 语法高亮；
        // CodeEditor 的 getValue() 永远是最新值，不需要 TextArea 那套 EAGER 失焦竞态规避
        CodeEditor editor = new CodeEditor(CodeEditor.MODE_SHELL);
        editor.setValue(StrUtil.emptyToDefault(content, ""));
        editor.setWidthFull();
        editor.setHeight("440px");

        Span hint = new Span("每行一条，格式：分 时 日 月 周 命令（如 0 2 * * * /opt/backup.sh）。"
                + "保存会整体替换用户 " + owner + " 的 crontab，原内容会先备份到目标机（~/crontab.bak-时间戳 或 /tmp/）。"
                + "清空全部内容保存 = 删除该用户的所有计划任务。");
        hint.addClassName("view-subtitle");
        hint.getStyle().set("overflow-wrap", "anywhere");

        VerticalLayout body = new VerticalLayout(editor, hint);
        body.setPadding(false);
        body.getStyle().set("gap", "10px");
        dialog.add(body);
        dialog.getFooter().add(
                // 复制的是编辑器里的当前内容（用户在弹窗里改过之后，复制出去的就是改过的版本）
                UiFactory.button("复制", () -> {
                    String current = editor.getValue();
                    if (StrUtil.isBlank(current)) {
                        Dialogs.warn("编辑器是空的，没有内容可复制");
                        return;
                    }
                    UiFactory.copyToClipboard(this, current);
                    Dialogs.success("已复制到剪贴板");
                }),
                UiFactory.button("取消", dialog::close),
                UiFactory.primary("保存", () -> {
                    String edited = editor.getValue();
                    log.info("crontab 编辑器保存触发：user={} contentLen={}", owner, edited == null ? -1 : edited.length());
                    dialog.close();
                    Dialogs.confirm("保存 crontab",
                            "将整体替换用户 " + owner + " 在 " + hostLabel() + " 上的 crontab（共 "
                                    + edited.lines().count() + " 行），原内容会先备份到目标机。确认保存？",
                            () -> query("保存 crontab", () -> service.crontabSave(target, edited, env)));
                }));
        dialog.open();
    }

    // ------------------------------------------------------------------
    // 执行骨架：后台跑 → 输出弹窗
    // ------------------------------------------------------------------

    /** 所有动作的统一出口：后台线程执行，结果（或失败原因）进输出弹窗 */
    private void query(String label, ThrowingSupplier task) {
        if (!checkEnv()) {
            return;
        }
        setBusy(true, label + " …");
        runAsync(label, task, raw -> {
            setBusy(false, "");
            showOutput(label, String.valueOf(raw));
            notifyIfReconnected();
        }, e -> {
            setBusy(false, "");
            showOutput(label + " —— 失败", StrUtil.emptyToDefault(e.getMessage(), e.getClass().getSimpleName()));
        });
    }

    /** 输出弹窗：CodeEditor 只读渲染（等宽 + 行号），带复制（命令输出经常要贴给别人看） */
    private void showOutput(String title, String text) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(title);
        dialog.setWidth("min(1000px, 94vw)");
        CodeEditor editor = new CodeEditor(CodeEditor.MODE_PLAIN);
        String content = StrUtil.isEmpty(text) ? "（命令没有任何输出）" : text;
        editor.setValue(content);
        editor.setReadOnly(true);
        editor.setWidthFull();
        editor.setHeight("560px");
        VerticalLayout body = new VerticalLayout(editor);
        body.setPadding(false);
        dialog.add(body);
        dialog.getFooter().add(UiFactory.copyButton(this, content), UiFactory.button("关闭", dialog::close));
        dialog.open();
    }

    private void notifyIfReconnected() {
        SshCommandRunner current = runner;
        if (current != null && current.consumeReconnectNotice()) {
            Dialogs.info("SSH 连接空闲超时已断开，已自动重连到 " + current.hostLabel());
        }
    }

    private boolean checkEnv() {
        if (service == null || env == null) {
            Dialogs.warn("请先选择目标服务器并点「连接」");
            return false;
        }
        return true;
    }

    /** 需要 root 的动作：权限不够时给一句能照着做的提示，而不是让远端回 permission denied */
    private boolean ensurePrivilege(String action) {
        if (env.canEscalate()) {
            return true;
        }
        Dialogs.warn("无法" + action + "：当前登录用户 " + env.getLoginUser() + "（uid=" + env.getUid()
                + "）不是 root，且目标机上不能免密 sudo。请改用 root 账号连接。");
        return false;
    }

    private Integer parsePort(TextField field, String action) {
        String text = StrUtil.trimToEmpty(field.getValue());
        if (text.isEmpty()) {
            Dialogs.warn(action + "：请先填写端口号");
            return null;
        }
        try {
            int port = Integer.parseInt(text);
            if (port < 1 || port > 65535) {
                Dialogs.warn("端口必须是 1~65535 之间的整数");
                return null;
            }
            return port;
        } catch (NumberFormatException e) {
            Dialogs.warn("端口必须是数字，收到：" + text);
            return null;
        }
    }

    private void setBusy(boolean busy, String text) {
        busyLabel.setText(StrUtil.emptyToDefault(text, ""));
        connectBtn.setEnabled(!busy);
    }

    private void runAsync(String label, ThrowingSupplier task, Consumer<Object> onDone, Consumer<Exception> onFailure) {
        UI ui = getUI().orElse(null);
        if (ui == null) {
            // 视图已经不在 UI 上（未 attach 或已 detach）：任务跑不了。
            // 必须把 busy 状态收回来，否则「正在连接 …」会一直挂着、连接按钮永远是灰的。
            log.warn("{} 无法执行：视图当前不在 UI 上", label);
            setBusy(false, "");
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
        }, "linux-ops-" + label);
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        closeRunner();
        super.onDetach(detachEvent);
    }

    private void closeRunner() {
        if (runner != null) {
            runner.close();
            runner = null;
            service = null;
            env = null;
        }
        opsArea.setVisible(false);
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        Object get() throws Exception;
    }
}
