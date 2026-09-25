package com.sl.ui.remote;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sl.entity.ConnectionInfo;
import com.sl.entity.ServerGroup;
import com.sl.mapper.ConnectionInfoMapper;
import com.sl.mapper.ServerGroupMapper;
import com.sl.ui.component.Dialogs;
import com.sl.ui.component.TabHost;
import com.sl.ui.remote.RemoteAppMgmtView;
import com.sl.ui.remote.RemoteFileView;
import com.sl.ui.remote.RemoteMonitorView;
import com.sl.ui.remote.SshTerminalView;
import com.sl.ui.component.UiFactory;
import com.sl.ui.component.ViewBase;
import com.sl.ui.docker.DockerComposeView;
import com.sl.ui.docker.DockerMgmtView;
import com.sl.util.Constants;
import com.sl.util.SSHClientUtil;
import com.sl.util.Util;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Hr;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.server.streams.UploadHandler;
import com.vaadin.flow.spring.annotation.SpringComponent;
import com.vaadin.flow.spring.annotation.UIScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 免登录服务器列表。
 * <p>
 * 连接信息来自两个来源（与旧项目一致，不额外造第三份配置）：
 * <ol>
 *   <li>数据库 {@code connection_info} 表——「添加机器」弹窗写入的，带分组（{@code cd_group}）；</li>
 *   <li>classpath 下 {@code remoteServerList.conf}——免登录配置，
 *       格式 {@code ip=用户=密码=端口[=私钥文件名]}，只能改文件不能在界面删，固定归默认分组。</li>
 * </ol>
 * 展示不再用表格，按分组分卡片：每个分组一个带浅色边框的竖排容器，
 * 组名在最上方，组内一台机器一行（主机 / 端口 / 用户 / 备注悬浮全文 / 行内操作按钮）；
 * 分组卡片之间用浅绿色分割线隔开。工具栏可以新建分组、按分组筛选，
 * 「添加机器」弹窗顶部可选分组，不选就落默认分组。
 */
@Service
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class RemoteServerListView extends ViewBase {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(RemoteServerListView.class);

    /** 虚拟的默认分组：表里不建这一行，所有没分组的机器（含配置文件来源）都归它 */
    public static final String DEFAULT_GROUP = "默认分组";

    /** 分组筛选下拉框里「看全部分组」的哨兵值 */
    private static final String FILTER_ALL = "全部分组";

    /** 一台机器的展示模型：数据库与配置文件两种来源统一成一行 */
    public record ServerRow(ConnectionInfo info, boolean fromDb) {
    }

    private final transient ConnectionInfoMapper connectionInfoMapper;
    private final transient ServerGroupMapper serverGroupMapper;
    private final transient org.springframework.context.ApplicationContext applicationContext;

    /** 工具栏上的分组筛选下拉框：值 {@code null} 或 {@link #FILTER_ALL} 都表示看全部 */
    private final ComboBox<String> filterCombo = new ComboBox<>();
    private final Span statusLabel = new Span();

    /** 分组展示容器：reload 后整树重建（结构简单，不值得做增量刷新） */
    private final VerticalLayout groupContainer = new VerticalLayout();

    /** 当前全部分组名（默认分组永远在最前）与全部机器行，renderGroups 据此渲染 */
    private transient List<String> groupNames = new ArrayList<>();
    private transient List<ServerRow> allRows = new ArrayList<>();

    public RemoteServerListView(ConnectionInfoMapper connectionInfoMapper,
                                ServerGroupMapper serverGroupMapper,
                                org.springframework.context.ApplicationContext applicationContext) {
        this.connectionInfoMapper = connectionInfoMapper;
        this.serverGroupMapper = serverGroupMapper;
        this.applicationContext = applicationContext;

        add(title("免登录服务器列表"));
        add(subtitle("数据库与 remoteServerList.conf 里的机器按分组列出，可直达各管理功能页。"));

        filterCombo.setPlaceholder("按分组筛选");
        filterCombo.setWidth("220px");
        filterCombo.setClearButtonVisible(true);
        filterCombo.addValueChangeListener(e -> renderGroups());

        HorizontalLayout bar = toolbar(
                UiFactory.primary("刷新列表", this::reload),
                UiFactory.primary("添加机器", this::showAddDialog),
                UiFactory.button("新建分组", this::showNewGroupDialog),
                spacer(),
                filterCombo,
                statusLabel);
        add(bar);

        groupContainer.addClassName("server-group-container");
        groupContainer.setPadding(false);
        groupContainer.setSpacing(false);
        // 容器本身不限宽，宽度交外层布局拉伸（FlexLayout 默认 flex-start 的老坑不走这里）
        groupContainer.setDefaultHorizontalComponentAlignment(FlexComponent.Alignment.STRETCH);
        add(groupContainer);

        reload();
    }

    // ------------------------------------------------------------------
    // 数据加载
    // ------------------------------------------------------------------

    /** 分组定义 + 机器列表 + 机器归属合并，然后按当前筛选重画。 */
    private void reload() {
        loadGroups();

        List<ServerRow> rows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        try {
            List<ConnectionInfo> fromDb = connectionInfoMapper.selectList(new QueryWrapper<>());
            if (fromDb != null) {
                for (ConnectionInfo info : fromDb) {
                    addRow(rows, seen, info, true);
                }
            }
        } catch (Exception e) {
            log.warn("读取数据库中的服务器列表失败：{}", e.getMessage());
        }

        try {
            for (String line : Util.getRemoteServerList()) {
                // 配置格式：ip=用户=密码=端口[=私钥文件名]，私钥可省略，所以必须做长度校验
                String[] split = line.split("=");
                if (split.length < 4) {
                    log.warn("服务器配置格式不正确，已跳过该行：{}", line);
                    continue;
                }
                String keyPath = split.length > 4 ? split[4] : null;
                addRow(rows, seen, new ConnectionInfo(split[0], split[3], split[1], split[2], keyPath), false);
            }
        } catch (Exception e) {
            log.warn("读取 remoteServerList.conf 失败：{}", e.getMessage());
        }

        allRows = rows;

        // 刷新筛选下拉框：保留当前选中项（还在列表里的话）
        String selected = filterCombo.getValue();
        List<String> filterItems = new ArrayList<>();
        filterItems.add(FILTER_ALL);
        filterItems.addAll(groupNames);
        filterCombo.setItems(filterItems);
        filterCombo.setValue(selected != null && filterItems.contains(selected) ? selected : FILTER_ALL);

        renderGroups();
        statusLabel.setText("共 " + allRows.size() + " 台机器（数据库 "
                + allRows.stream().filter(ServerRow::fromDb).count() + "，配置文件 "
                + allRows.stream().filter(row -> !row.fromDb()).count() + "，"
                + groupNames.size() + " 个分组）");
    }

    /** 读分组定义。默认分组是虚拟的、永远排第一；库里可能与默认分组重名，去重并忽略。 */
    private void loadGroups() {
        List<String> groups = new ArrayList<>();
        groups.add(DEFAULT_GROUP);
        try {
            List<ServerGroup> fromDb = serverGroupMapper.selectList(
                    new QueryWrapper<ServerGroup>().orderByAsc("group_name"));
            if (fromDb != null) {
                for (ServerGroup group : fromDb) {
                    String name = group.getGroupName();
                    if (StrUtil.isNotBlank(name) && !groups.contains(name)) {
                        groups.add(name);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("读取服务器分组失败：{}", e.getMessage());
        }
        groupNames = groups;
    }

    private static void addRow(List<ServerRow> rows, Set<String> seen, ConnectionInfo info, boolean fromDb) {
        if (info == null || StrUtil.isBlank(info.getIdHost())) {
            return;
        }
        String key = info.getIdHost() + ":" + StrUtil.blankToDefault(info.getCdPort(), "22").trim()
                + ":" + StrUtil.nullToEmpty(info.getIdUser());
        if (!seen.add(key)) {
            return;
        }
        rows.add(new ServerRow(info, fromDb));
    }

    /** 一台机器的归属分组：数据库来源按 cd_group，空白/配置文件来源都算默认分组。 */
    private static String groupOf(ServerRow row) {
        String group = row.info().getCdGroup();
        return row.fromDb() && StrUtil.isNotBlank(group) ? group.trim() : DEFAULT_GROUP;
    }

    // ------------------------------------------------------------------
    // 分组卡片渲染
    // ------------------------------------------------------------------

    private void renderGroups() {
        groupContainer.removeAll();

        String selected = filterCombo.getValue();
        boolean filtering = selected != null && !FILTER_ALL.equals(selected);

        List<String> visibleGroups = new ArrayList<>();
        for (String group : groupNames) {
            if (!filtering || group.equals(selected)) {
                visibleGroups.add(group);
            }
        }

        if (visibleGroups.isEmpty()) {
            groupContainer.add(UiFactory.emptyHint("没有符合条件的服务器分组"));
            return;
        }

        boolean first = true;
        for (int i = 0; i < visibleGroups.size(); i++) {
            String group = visibleGroups.get(i);
            if (!first) {
                // 分组卡片之间的浅绿色分割线，样式见 styles.css .server-group-divider
                Hr divider = UiFactory.divider();
                divider.addClassName("server-group-divider");
                groupContainer.add(divider);
            }
            first = false;
            groupContainer.add(buildGroupCard(group, i));
        }
    }

    /** 一个分组一张卡：浅色边框容器，顶部组名，组内一台机器一行。 */
    private VerticalLayout buildGroupCard(String groupName, int index) {
        List<ServerRow> rows = new ArrayList<>();
        for (ServerRow row : allRows) {
            if (groupOf(row).equals(groupName)) {
                rows.add(row);
            }
        }

        VerticalLayout card = new VerticalLayout();
        card.addClassName("server-group-card");
        card.setPadding(true);
        card.setSpacing(false);
        card.getStyle().set("gap", "0px");

        HorizontalLayout header = new HorizontalLayout();
        header.addClassName("server-group-header");
        header.setSpacing(false);
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.getStyle().set("gap", "8px");
        // 分组圆点蓝绿交替，纯装饰，但让相邻分组一眼能区分
        Span dot = new Span();
        dot.addClassName(index % 2 == 0 ? "server-group-dot-blue" : "server-group-dot-green");
        Span name = new Span(groupName);
        name.addClassName("server-group-name");
        Span count = new Span(rows.size() + " 台机器");
        count.addClassName("server-group-count");
        header.add(dot, name, count);
        card.add(header);

        if (rows.isEmpty()) {
            card.add(UiFactory.emptyHint("该分组暂无机器，点上方「添加机器」并选择该分组即可"));
            return card;
        }
        for (ServerRow row : rows) {
            card.add(buildMachineRow(row));
        }
        return card;
    }

    /** 组内一行 = 一台机器：主机 / 端口 / 用户 / 备注（悬浮显示全文）/ 行内操作。 */
    private HorizontalLayout buildMachineRow(ServerRow row) {
        ConnectionInfo info = row.info();

        HorizontalLayout line = new HorizontalLayout();
        line.addClassName("server-machine-row");
        line.setWidthFull();
        line.setSpacing(false);
        line.setAlignItems(FlexComponent.Alignment.CENTER);
        line.getStyle().set("gap", "16px");

        Span host = new Span(StrUtil.nullToEmpty(info.getIdHost()));
        host.addClassName("server-machine-host");

        Span port = new Span("端口 " + StrUtil.blankToDefault(info.getCdPort(), "22"));
        port.addClassName("server-machine-meta");

        Span user = new Span("用户 " + StrUtil.nullToEmpty(info.getIdUser()));
        user.addClassName("server-machine-meta");

        // 备注可能很长：行内截断省略，完整内容放原生 title（Span 没有 setTooltipText）
        Span desc = new Span(StrUtil.blankToDefault(info.getDesc(), "无备注"));
        desc.addClassName("server-machine-desc");
        desc.getElement().setAttribute("title",
                StrUtil.blankToDefault(info.getDesc(), "这台机器没有填写备注"));
        line.setFlexGrow(1, desc);

        line.add(host, port, user, desc, buildRowActions(row));
        return line;
    }

    private Component buildRowActions(ServerRow row) {
        ConnectionInfo info = row.info();

        // 行内操作多且文字长，透明底蓝字挤在一起极易点错（用户反馈过）：
        // 统一浅色底 + 8px 间隔，删除保持红色。
        Button dockerBtn = UiFactory.button("容器和镜像管理", () -> openDocker(info));
        Button composeBtn = UiFactory.button("Compose 管理", () -> openCompose(info));
        Button appBtn = UiFactory.button("应用管理", () -> openAppMgmt(info));
        Button sshBtn = UiFactory.button("SSH 终端", () -> openSshTerminal(info));
        Button fileBtn = UiFactory.button("文件管理", () -> openFileMgmt(info));
        Button monitorBtn = UiFactory.rowAction("指标监控", () -> openMonitor(info));

        HorizontalLayout actions = new HorizontalLayout(dockerBtn, composeBtn, appBtn, sshBtn, fileBtn, monitorBtn);
        actions.setWidth("50%");
        actions.addClassName("row-actions");
        actions.setSpacing(false);
        actions.setAlignItems(FlexComponent.Alignment.CENTER);
        if (row.fromDb()) {
            actions.add(UiFactory.rowDanger("删除", () -> confirmDelete(row)));
        }
        return actions;
    }

    // ------------------------------------------------------------------
    // 跳转：带连接信息一步到位打开对应功能页
    // ------------------------------------------------------------------

    private void openDocker(ConnectionInfo info) {
        TabHost host = TabHost.current();
        if (host == null) {
            return;
        }
        // Supplier 而不是现成组件：同名标签已存在时根本不会创建新实例，
        // 也就不会为了一个要被丢弃的实例去建 SSH 通道
        host.open("Docker-" + info.getIdHost(), () -> {
            DockerMgmtView view = applicationContext.getBean(DockerMgmtView.class);
            view.setPresetHost(info);
            return view;
        });
    }

    private void openCompose(ConnectionInfo info) {
        TabHost host = TabHost.current();
        if (host == null) {
            return;
        }
        host.open("Compose-" + info.getIdHost(), () -> {
            DockerComposeView view = applicationContext.getBean(DockerComposeView.class);
            view.setPresetHost(info);
            return view;
        });
    }

    private void openAppMgmt(ConnectionInfo info) {
        TabHost host = TabHost.current();
        if (host == null) {
            return;
        }
        host.open("应用管理-" + info.getIdHost(), () -> {
            RemoteAppMgmtView view = applicationContext.getBean(RemoteAppMgmtView.class);
            view.setPresetHost(info);
            return view;
        });
    }

    private void openSshTerminal(ConnectionInfo info) {
        TabHost host = TabHost.current();
        if (host == null) {
            return;
        }
        // Supplier：同名标签已存在时根本不会创建新实例，也就不会重复登记 token
        host.open("SSH终端-" + info.getIdHost(), () -> {
            SshTerminalView view = applicationContext.getBean(SshTerminalView.class);
            view.setPresetHost(info);
            return view;
        });
    }

    private void openFileMgmt(ConnectionInfo info) {
        TabHost host = TabHost.current();
        if (host == null) {
            return;
        }
        host.open("文件管理-" + info.getIdHost(), () -> {
            RemoteFileView view = applicationContext.getBean(RemoteFileView.class);
            view.setPresetHost(info);
            return view;
        });
    }

    private void openMonitor(ConnectionInfo info) {
        TabHost host = TabHost.current();
        if (host == null) {
            return;
        }
        host.open("指标监控-" + info.getIdHost(), () -> {
            RemoteMonitorView view = applicationContext.getBean(RemoteMonitorView.class);
            view.setPresetHost(info);
            return view;
        });
    }

    // ------------------------------------------------------------------
    // 分组管理
    // ------------------------------------------------------------------

    private void showNewGroupDialog() {
        if (!hasPermission(Constants.ADD)) {
            Dialogs.warn("权限不足，无法创建分组");
            return;
        }
        new NewGroupDialog().open();
    }

    /** 「新建分组」弹窗：输入分组名，重名 / 默认分组名 / 超长都拦在保存前。 */
    private class NewGroupDialog extends Dialog {

        private final TextField nameField = UiFactory.textField("分组名称", "如：生产环境", "500px");

        NewGroupDialog() {
            setHeaderTitle("新建分组");
            setWidth("620px");
            setCloseOnEsc(true);
            setCloseOnOutsideClick(false);

            VerticalLayout form = new VerticalLayout(nameField);
            form.setPadding(false);
            form.setSpacing(false);
            form.getStyle().set("gap", "10px");
            add(form);

            Button cancel = Dialogs.cancelButton(this::close);
            Button save = Dialogs.primaryButton("创建分组", this::save);
            getFooter().add(Dialogs.dialogActions(cancel, save));
        }

        private void save() {
            String name = StrUtil.trim(nameField.getValue());
            if (StrUtil.isBlank(name)) {
                Dialogs.warn("请填写分组名称");
                return;
            }
            if (name.length() > 20) {
                Dialogs.warn("分组名称最多 20 个字");
                return;
            }
            if (DEFAULT_GROUP.equals(name)) {
                Dialogs.warn("「默认分组」是内置分组，不需要创建");
                return;
            }
            if (groupNames.contains(name)) {
                Dialogs.warn("分组「" + name + "」已经存在");
                return;
            }
            try {
                serverGroupMapper.insert(new ServerGroup(name));
            } catch (Exception e) {
                log.warn("创建分组 {} 失败：{}", name, e.getMessage());
                Dialogs.warn("创建失败，这个分组名可能刚被其他人建过");
                return;
            }
            close();
            reload();
            Dialogs.success("已创建分组「" + name + "」，添加机器时可以选它");
        }
    }

    // ------------------------------------------------------------------
    // 删除（只有数据库来源可删）
    // ------------------------------------------------------------------

    private void confirmDelete(ServerRow row) {
        if (!hasPermission(Constants.DELETE)) {
            Dialogs.warn("权限不足，无法删除服务器记录");
            return;
        }
        ConnectionInfo info = row.info();
        Dialogs.confirmDanger("删除服务器",
                "确认删除 " + info.getIdHost() + "（用户 " + info.getIdUser() + "）的连接信息吗？"
                        + "删除后需要重新添加才能管理这台机器。",
                "确认删除", () -> {
                    QueryWrapper<ConnectionInfo> wrapper = new QueryWrapper<>();
                    // 复合主键三列必须全部对上，只按 host 删会把同 IP 不同端口的一起带走
                    wrapper.eq("id_host", info.getIdHost())
                            .eq("cd_port", info.getCdPort())
                            .eq("id_user", info.getIdUser());
                    int deleted = connectionInfoMapper.delete(wrapper);
                    reload();
                    if (deleted > 0) {
                        Dialogs.success("已删除 " + info.getIdHost() + " 的连接信息");
                    } else {
                        Dialogs.warn("删除失败，记录可能已被其他人移除");
                    }
                });
    }

    // ------------------------------------------------------------------
    // 添加机器
    // ------------------------------------------------------------------

    private void showAddDialog() {
        if (!hasPermission(Constants.ADD)) {
            Dialogs.warn("权限不足，无法添加服务器");
            return;
        }
        new AddServerDialog().open();
    }

    /**
     * 「添加机器」弹窗。
     * <p>
     * 保存前先做一次真实连通性验证：配了私钥先试私钥、失败回落密码
     * （旧实现里按 Integer 端口去匹配构造器，走的是密码分支，「上传了私钥」
     * 这条路其实从没验证过——这里统一走 {@link SSHClientUtil#connect}）。
     * 验证用的连接用完立即关闭，不做持久连接。
     * <p>
     * 顶部分组下拉框：不选（或选默认分组）就落 {@link #DEFAULT_GROUP}。
     */
    private class AddServerDialog extends Dialog {

        private final ComboBox<String> groupField = new ComboBox<>("分组");
        private final TextField hostField = UiFactory.textField("主机", "192.168.1.10 或 host.example.com","500px");
        private final TextField portField = UiFactory.textField("端口","","500px");
        private final TextField userField = UiFactory.textField("用户名","","500px");
        private final PasswordField passField = UiFactory.passwordField("密码","500px");
        private final TextField descField = UiFactory.textField("备注","","500px");
        private final Checkbox keyCheck = new Checkbox("使用私钥登录（上传后优先用私钥认证）");

        /** 上传的私钥落盘路径，随连接信息一起存库 */
        private String uploadedKeyPath;

        AddServerDialog() {
            setHeaderTitle("添加机器");
            setWidth("760px");
            setCloseOnEsc(false);
            setCloseOnOutsideClick(false);

            groupField.setItems(groupNames);
            groupField.setWidth("500px");
            groupField.setClearButtonVisible(true);
            groupField.setPlaceholder("不选则归入默认分组");

            portField.setValue("22");

            VerticalLayout form = new VerticalLayout(
                    groupField,
                    hostField,
                    new HorizontalLayout(portField),
                    userField, passField, keyCheck, buildKeyUpload(), descField);
            form.setPadding(false);
            form.setSpacing(false);
            form.getStyle().set("gap", "10px");
            add(form);

            keyCheck.addValueChangeListener(e -> {
                passField.setVisible(!e.getValue());
                uploadedKeyPath = null;
            });

            Button cancel = UiFactory.button("取消", this::close);
            Button save = UiFactory.primary("验证并保存", this::save);
            getFooter().add(cancel, save);
        }

        /** 私钥上传：落到运行目录 keys/ 下，cd_key_path 存相对路径（与旧 FileUploader 一致的语义） */
        private Upload buildKeyUpload() {
            File keyDir = new File("keys");
            if (!keyDir.isDirectory() && !keyDir.mkdirs()) {
                log.warn("私钥目录 {} 创建失败，上传的私钥将无法保存", keyDir.getAbsolutePath());
            }
            Upload upload = new Upload(UploadHandler.toFile(
                    (metadata, file) -> {
                        uploadedKeyPath = "keys/" + file.getName();
                        getUI().ifPresent(ui -> ui.access(() ->
                                Dialogs.success("私钥已上传：" + metadata.fileName())));
                    },
                    metadata -> {
                        String safe = metadata.fileName().replaceAll("[\\\\/]", "_");
                        return new File(keyDir, System.currentTimeMillis() + "_" + safe);
                    }));
            upload.setMaxFiles(1);
            upload.addFileRejectedListener(e ->
                    Dialogs.warn("私钥上传被拒绝：" + e.getErrorMessage()));
            upload.getStyle().set("max-width", "100%");
            return upload;
        }

        private void save() {
            String host = StrUtil.trim(hostField.getValue());
            String port = StrUtil.blankToDefault(StrUtil.trim(portField.getValue()), "22");
            String user = StrUtil.trim(userField.getValue());
            String password = passField.getValue();
            String desc = StrUtil.trim(descField.getValue());
            // 不选分组 → 默认分组
            String group = StrUtil.blankToDefault(StrUtil.trim(groupField.getValue()), DEFAULT_GROUP);
            if (!groupNames.contains(group)) {
                Dialogs.warn("所选分组「" + group + "」不存在，请重新选择或先创建分组");
                return;
            }

            if (StrUtil.isBlank(host) || StrUtil.isBlank(user)) {
                Dialogs.warn("请填写主机与用户名");
                return;
            }
            if (!port.matches("\\d{1,5}")) {
                Dialogs.warn("端口必须是数字");
                return;
            }
            if (keyCheck.getValue() && StrUtil.isBlank(uploadedKeyPath)) {
                Dialogs.warn("勾选了私钥登录但还没有上传私钥文件");
                return;
            }
            if (desc.length() > 30) {
                Dialogs.warn("备注最多写 30 个字");
                return;
            }

            ConnectionInfo candidate = new ConnectionInfo(host, port, user, password,
                    keyCheck.getValue() ? uploadedKeyPath : null, desc);
            candidate.setCdGroup(group);
            try {
                // 只验证一次连通性，用完立即关闭；密钥可用性也是这一步真正测出来的
                SSHClientUtil client = SSHClientUtil.connect(candidate);
                client.closeConnection();
            } catch (Exception ex) {
                log.warn("验证连接 {} 失败：{}", host, ex.getMessage());
                Dialogs.error("连接验证失败：" + StrUtil.emptyToDefault(ex.getMessage(), ex.getClass().getSimpleName())
                        + "。仍要保存请先解决连通性问题。");
                return;
            }

            try {
                connectionInfoMapper.insert(candidate);
            } catch (Exception e) {
                log.warn("保存连接信息失败：{}", e.getMessage());
                Dialogs.warn("保存失败，这条连接信息可能已经存在（同一主机 + 端口 + 用户）");
                return;
            }
            close();
            reload();
            Dialogs.success("已添加 " + host + "（分组：" + group + "）");
        }
    }

    // ------------------------------------------------------------------
    // 权限
    // ------------------------------------------------------------------

    private static boolean hasPermission(String code) {
        com.sl.entity.User user = com.sl.security.CurrentUser.get();
        return user != null && user.hasPermission(code);
    }
}
