package com.sl.ui.remote;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sl.entity.ConnectionInfo;
import com.sl.mapper.ConnectionInfoMapper;
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
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
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
import com.vaadin.flow.spring.annotation.VaadinSessionScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 免登录服务器列表。
 * <p>
 * 连接信息来自两个来源（与旧项目一致，不额外造第三份配置）：
 * <ol>
 *   <li>数据库 {@code connection_info} 表——「添加机器」弹窗写入的；</li>
 *   <li>classpath 下 {@code remoteServerList.conf}——免登录配置，
 *       格式 {@code ip=用户=密码=端口[=私钥文件名]}，只能改文件不能在界面删。</li>
 * </ol>
 * 每一行是一台机器，行内按钮直达对应功能页：Docker / Compose 本轮已迁移，
 * 会带着这一行的连接信息一步到位打开；应用管理 / SSH 终端 / 文件管理 / 指标监控
 * 还在迁移队列里，点击时明确提示而不是静默无响应。
 * <p>
 * 与旧实现 {@code com.so.component.remote.RemoteServerListComponent} 的差别：
 * 绝对定位的一行一台（{@code AbsoluteLayout + "left:150px"}）换成 Grid；
 * 「删除配置文件里的机器」旧实现靠 {@code delete()==0} 的返回值兜底提示，这里
 * 先标记来源，配置文件来源的直接不给删除按钮，省一次注定失败的点击。
 */
@Service
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class RemoteServerListView extends ViewBase {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(RemoteServerListView.class);

    /** 一台机器的展示模型：数据库与配置文件两种来源统一成一行 */
    public record ServerRow(ConnectionInfo info, boolean fromDb) {
    }

    private final transient ConnectionInfoMapper connectionInfoMapper;
    private final transient org.springframework.context.ApplicationContext applicationContext;

    private final Grid<ServerRow> grid = UiFactory.grid(ServerRow.class);
    private final Span statusLabel = new Span();

    public RemoteServerListView(ConnectionInfoMapper connectionInfoMapper,
                                org.springframework.context.ApplicationContext applicationContext) {
        this.connectionInfoMapper = connectionInfoMapper;
        this.applicationContext = applicationContext;

        add(title("免登录服务器列表"));
        add(subtitle("数据库与 remoteServerList.conf 里的机器统一列出，可直达各管理功能页。"));

        HorizontalLayout bar = toolbar(
                UiFactory.primary("刷新列表", this::reload),
                UiFactory.primary("添加机器", this::showAddDialog),
                spacer(),
                statusLabel);
        add(bar);

        buildGrid();
        VerticalLayout fill = fill(grid);
        add(fill);
        setFlexGrow(1, fill);

        reload();
    }

    // ------------------------------------------------------------------
    // 表格
    // ------------------------------------------------------------------

    private void buildGrid() {
        grid.addColumn(row -> StrUtil.blankToDefault(row.info().getIdHost(),"")).setHeader("主机").setAutoWidth(true)
                .setComparator((a, b) -> StrUtil.nullToEmpty(a.info().getIdHost())
                        .compareTo(StrUtil.nullToEmpty(b.info().getIdHost())));
        grid.addColumn(row -> StrUtil.blankToDefault(row.info().getCdPort(), "22")).setHeader("端口").setAutoWidth(true);
        grid.addColumn(row -> StrUtil.nullToEmpty(row.info().getIdUser())).setHeader("用户").setAutoWidth(true);
        grid.addColumn(row -> StrUtil.nullToEmpty(row.info().getDesc())).setHeader("备注").setAutoWidth(true);
        grid.addComponentColumn(this::buildRowActions).setHeader("操作").setAutoWidth(true);
    }

    private Component buildRowActions(ServerRow row) {
        ConnectionInfo info = row.info();

        // 行内操作多且文字长，透明底蓝字挤在一起极易点错（用户反馈过）：
        // 统一浅色底 + 8px 间隔，删除保持红色。
        Button dockerBtn = UiFactory.rowAction("容器和镜像管理", () -> openDocker(info));
        Button composeBtn = UiFactory.rowAction("Compose 管理", () -> openCompose(info));
        Button appBtn = UiFactory.rowAction("应用管理", () -> openAppMgmt(info));
        Button sshBtn = UiFactory.rowAction("SSH 终端", () -> openSshTerminal(info));
        Button fileBtn = UiFactory.rowAction("文件管理", () -> openFileMgmt(info));
        Button monitorBtn = UiFactory.rowAction("指标监控", () -> openMonitor(info));

        HorizontalLayout actions = new HorizontalLayout(dockerBtn, composeBtn, appBtn, sshBtn, fileBtn, monitorBtn);
        actions.addClassName("row-actions");
        actions.setSpacing(false);
        actions.setAlignItems(FlexComponent.Alignment.CENTER);
        if (row.fromDb()) {
            Button deleteBtn = UiFactory.rowDanger("删除", () -> confirmDelete(row));
            actions.add(deleteBtn, monitorBtn);
        } else {
            actions.add(monitorBtn);
        }
        return actions;
    }

    // ------------------------------------------------------------------
    // 数据加载
    // ------------------------------------------------------------------

    /** 数据库 + 配置文件合并。按 host:port:用户 去重，数据库优先（界面可维护的优先展示）。 */
    private void reload() {
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

        grid.setItems(rows);
        statusLabel.setText("共 " + rows.size() + " 台机器（数据库 "
                + rows.stream().filter(ServerRow::fromDb).count() + "，配置文件 "
                + rows.stream().filter(row -> !row.fromDb()).count() + "）");
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
     */
    private class AddServerDialog extends Dialog {

        private final TextField hostField = UiFactory.textField("主机", "192.168.1.10 或 host.example.com","500px");
        private final TextField portField = UiFactory.textField("端口","","500px");
        private final TextField userField = UiFactory.textField("用户名","500px");
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

            portField.setValue("22");

            VerticalLayout form = new VerticalLayout(
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
            Dialogs.success("已添加 " + host);
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
