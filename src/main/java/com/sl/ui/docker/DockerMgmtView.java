package com.sl.ui.docker;

import cn.hutool.core.util.StrUtil;
import com.sl.docker.DockerDaemonDownException;
import com.sl.docker.DockerExecutor;
import com.sl.docker.DockerService;
import com.sl.docker.DockerTerminalRegistry;
import com.sl.docker.model.DockerContainer;
import com.sl.docker.model.DockerDaemonStatus;
import com.sl.docker.model.DockerImage;
import com.sl.entity.ConnectionInfo;
import com.sl.mapper.ConnectionInfoMapper;
import com.sl.ui.component.CodeEditor;
import com.sl.ui.component.Dialogs;
import com.sl.ui.component.UiFactory;
import com.sl.ui.component.ViewBase;
import com.sl.util.Constants;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.IFrame;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.server.VaadinSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Docker 容器和镜像管理。
 * <p>
 * 结构对应旧项目的 {@code DockerMgmtComponent + DockerContainerPage + DockerImagePage}：
 * 顶部选目标服务器 → 建立一条 SSH 通道（所有 docker 命令都走它）→
 * <b>先探测 docker 服务在不在</b> → 在才建子标签（容器 / 镜像）。
 * daemon 不在时不建页签、不发命令——以前"连上就建五个页签各自拉数据"的做法，
 * 在 docker 没启动的目标机上是一条自我循环的命令风暴（日志里 60ms 一条
 * {@code Cannot connect to the Docker daemon}），这个教训写进了旧代码注释，必须保留。
 * <p>
 * 本轮范围：容器（列表 / 筛选 / 启停重启暂停 / 删除 / 清理已停止 / 详情+日志）、
 * 镜像（列表 / 拉取 / 删除 / 清理悬空 / 导出）。创建容器、数据卷、网络、系统信息
 * 等子页还在迁移队列（菜单上的其他入口照常显示）。
 */
@Service
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class DockerMgmtView extends ViewBase {

    private static final Logger log = LoggerFactory.getLogger(DockerMgmtView.class);

    private static final String FILTER_RUNNING = "运行中";
    private static final String FILTER_STOPPED = "已停止";
    private static final String FILTER_ALL = "全部";

    private final transient ConnectionInfoMapper connectionInfoMapper;
    private final transient ApplicationContext applicationContext;

    // ---- 连接区 ----
    private final ComboBox<ConnectionInfo> hostCombo = new ComboBox<>();
    private final Button connectBtn = UiFactory.primary("连接", this::connect);
    private final Button checkBtn = UiFactory.button("检测服务状态", () -> checkDaemon(true));
    private final Span busyLabel = new Span();
    private final Span envLabel = new Span();

    // ---- daemon 占位 ----
    private final VerticalLayout daemonNotice = new VerticalLayout();
    private final Span noticeTitle = new Span();
    private final TextArea noticeText = UiFactory.textArea();
    private final Button noticeStartBtn = UiFactory.primary("启动 Docker 服务", this::startDaemonRequested);

    // ---- 子标签 ----
    private final Tabs subTabs = new Tabs();
    private final Div subContent = new Div();
    private final VerticalLayout containerPage = new VerticalLayout();
    private final VerticalLayout imagePage = new VerticalLayout();
    private final Map<Tab, VerticalLayout> tabPages = new LinkedHashMap<>();

    // ---- 容器页 ----
    private final Grid<DockerContainer> containerGrid = UiFactory.grid(DockerContainer.class);
    private final ComboBox<String> filterCombo = UiFactory.combo(List.of(FILTER_RUNNING, FILTER_STOPPED, FILTER_ALL), FILTER_ALL);
    private final Button createContainerBtn = UiFactory.primary("创建容器", this::showCreateContainerDialog);
    private final Span containerStatus = new Span();
    private List<DockerContainer> allContainers = new ArrayList<>();

    // ---- 镜像页 ----
    private final Grid<DockerImage> imageGrid = UiFactory.grid(DockerImage.class);
    private final Checkbox showAllBox = new Checkbox("显示中间层镜像（-a）");
    private final Span imageStatus = new Span();
    /** 镜像列表缓存：「创建容器」弹窗的镜像下拉直接用，免得每次开弹窗都查一遍 */
    private List<DockerImage> allImages = new ArrayList<>();

    /** 下拉候选主机；预置主机不在里面时要补进去 */
    private List<ConnectionInfo> candidateHosts = new ArrayList<>();
    /** 「免登录服务器列表」跳进来时带的机器，attach 后自动连接一次 */
    private ConnectionInfo presetHost;
    private boolean autoConnectPending;

    private transient DockerExecutor executor;
    private transient DockerService service;

    public DockerMgmtView(ConnectionInfoMapper connectionInfoMapper, ApplicationContext applicationContext) {
        this.connectionInfoMapper = connectionInfoMapper;
        this.applicationContext = applicationContext;

        add(title("容器和镜像管理"));
        add(subtitle("选择目标服务器并连接后管理 Docker。页面关闭时 SSH 通道自动断开。"));

        add(buildConnectRow());
        envLabel.addClassName("docker-env-label");
        envLabel.getStyle().set("overflow-wrap", "anywhere");
        add(envLabel);

        buildDaemonNotice();
        add(daemonNotice);

        buildSubTabs();
        add(subTabs);
        add(subContent);

        buildContainerPage();
        buildImagePage();
        switchTab(tabPages.keySet().iterator().next());

        loadCandidateHosts();
    }

    // ------------------------------------------------------------------
    // 连接区
    // ------------------------------------------------------------------

    private Component buildConnectRow() {
        hostCombo.setWidth("260px");
        hostCombo.setPlaceholder("选择一台已配置的服务器");
        hostCombo.setItemLabelGenerator(item -> item.getIdHost() + " (" + item.getIdUser() + ")");
        hostCombo.setAllowCustomValue(false);
        checkBtn.setEnabled(false);

        HorizontalLayout row = new HorizontalLayout(
                UiFactory.fieldRow("目标服务器", "80px", hostCombo),
                connectBtn, checkBtn, busyLabel);
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
            for (String line : com.sl.util.Util.getRemoteServerList()) {
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

    // ------------------------------------------------------------------
    // 预置主机（服务器列表跳转入口）
    // ------------------------------------------------------------------

    /** 由「免登录服务器列表」的跳转按钮调用；真正的连接等 attach 之后再发起。 */
    public void setPresetHost(ConnectionInfo info) {
        this.presetHost = info;
        this.autoConnectPending = info != null;
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        tryAutoConnect();
    }

    private void tryAutoConnect() {
        if (!autoConnectPending || presetHost == null) {
            return;
        }
        ConnectionInfo target = matchHost(candidateHosts, presetHost);
        if (target == null) {
            target = presetHost;
            candidateHosts.add(target);
            hostCombo.setItems(candidateHosts);
        }
        // 先落标记再动手：连接失败也不能反复重连
        autoConnectPending = false;
        hostCombo.setValue(target);
        connect();
    }

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

    // ------------------------------------------------------------------
    // 连接与 daemon 探测（全部在后台线程，结果经 UI.access 回页面）
    // ------------------------------------------------------------------

    private void connect() {
        ConnectionInfo info = hostCombo.getValue();
        if (info == null) {
            Dialogs.warn("请先选择目标服务器");
            return;
        }
        closeExecutor();
        setBusy(true, "正在连接 " + info.getIdHost() + " …");

        runAsync("连接 " + info.getIdHost(), () -> {
            DockerExecutor newExecutor = new DockerExecutor(info, "");
            // 连上就先问一次 daemon 在不在，别急着建页签发命令
            DockerDaemonStatus status = newExecutor.probeDaemon(true);
            return new Object[]{newExecutor, status};
        }, result -> {
            Object[] parts = (Object[]) result;
            executor = (DockerExecutor) parts[0];
            service = new DockerService(executor);
            setBusy(false, "");
            checkBtn.setEnabled(true);
            applyDaemonStatus((DockerDaemonStatus) parts[1]);
        }, this::onConnectFailure);
    }

    private void onConnectFailure(Exception e) {
        setBusy(false, "");
        Dialogs.error("连接失败：" + StrUtil.emptyToDefault(e.getMessage(), e.getClass().getSimpleName()));
    }

    private void checkDaemon(boolean notifyWhenOk) {
        if (executor == null || executor.isClosed()) {
            Dialogs.warn("请先选择目标服务器并点「连接」");
            return;
        }
        DockerExecutor current = executor;
        setBusy(true, "正在检测 docker 服务状态 …");
        runAsync("检测 docker 状态", () -> current.probeDaemon(true), raw -> {
            setBusy(false, "");
            DockerDaemonStatus status = (DockerDaemonStatus) raw;
            applyDaemonStatus(status);
            if (status.isDaemonRunning()) {
                if (notifyWhenOk) {
                    Dialogs.success("Docker 服务正常，服务端版本 " + StrUtil.emptyToDefault(status.getServerVersion(), "?"));
                }
            } else {
                showDaemonNotice(status);
            }
        }, e -> {
            setBusy(false, "");
            Dialogs.error("检测失败：" + StrUtil.emptyToDefault(e.getMessage(), "未知错误"));
        });
    }

    private void applyDaemonStatus(DockerDaemonStatus status) {
        if (executor == null) {
            return;
        }
        boolean running = status != null && status.isDaemonRunning();
        String prefixText = executor.getCommandPrefix();
        if (running) {
            envLabel.setText("已连接 " + executor.hostLabel() + "　docker 命令：`" + prefixText + "`　" + status.summary());
            daemonNotice.setVisible(false);
            subTabs.setVisible(true);
            subContent.setVisible(true);
            createContainerBtn.setEnabled(true);
            reloadAll();
        } else {
            envLabel.setText("已连接 " + executor.hostLabel() + "　docker 命令：`" + prefixText + "`　"
                    + (status == null ? "docker 状态未知" : status.summary()));
            subTabs.setVisible(false);
            subContent.setVisible(false);
            createContainerBtn.setEnabled(false);
            showDaemonNotice(status);
        }
    }

    private void buildDaemonNotice() {
        noticeText.setReadOnly(true);
        noticeText.setHeight("150px");
        noticeText.setWidthFull();
        daemonNotice.add(noticeTitle, noticeText, noticeStartBtn);
        daemonNotice.setPadding(true);
        daemonNotice.setSpacing(false);
        daemonNotice.getStyle().set("gap", "10px");
        daemonNotice.getStyle().set("border", "1px solid var(--lumo-warning-color-50pct)");
        daemonNotice.getStyle().set("border-radius", "8px");
        daemonNotice.getStyle().set("background-color", "var(--lumo-warning-color-10pct)");
        daemonNotice.setVisible(false);
    }

    private void showDaemonNotice(DockerDaemonStatus status) {
        if (status == null) {
            daemonNotice.setVisible(false);
            return;
        }
        daemonNotice.setVisible(true);
        if (!status.isDockerInstalled()) {
            noticeTitle.setText("目标机未安装 Docker");
            noticeText.setValue("当前系统没有检测到 docker，请先安装后再进行管理。\n\n" + status.diagnosis());
            noticeStartBtn.setVisible(false);
        } else {
            noticeTitle.setText("Docker 服务未运行，已暂停所有 docker 命令");
            noticeText.setValue(status.diagnosis() + "\n\n启动命令：\n" + status.getStartCommandPreview());
            noticeStartBtn.setVisible(true);
            noticeStartBtn.setEnabled(!status.buildStartCommands().isEmpty());
        }
    }

    /** 占位面板上的「启动 Docker 服务」：后台执行 systemd/sysv 启动命令，完成后重新探测 */
    private void startDaemonRequested() {
        if (executor == null || executor.isClosed()) {
            Dialogs.warn("请先连接目标服务器");
            return;
        }
        setBusy(true, "正在启动 docker 服务 …");
        runAsync("启动 docker 服务", () -> {
            DockerExecutor.DaemonStartResult result = executor.startDaemon();
            return result;
        }, result -> {
            setBusy(false, "");
            DockerExecutor.DaemonStartResult r = (DockerExecutor.DaemonStartResult) result;
            Dialogs.info(StrUtil.emptyToDefault(r.getReport(), "启动命令已执行"));
            checkDaemon(false);
        }, e -> {
            setBusy(false, "");
            Dialogs.error("启动失败：" + StrUtil.emptyToDefault(e.getMessage(), "未知错误"));
        });
    }

    // ------------------------------------------------------------------
    // 子标签（Tabs + 内容容器自拼；子页切换不销毁）
    // ------------------------------------------------------------------

    private void buildSubTabs() {
        subTabs.addClassName("docker-subtabs");
        Tab containerTab = new Tab("容器管理");
        Tab imageTab = new Tab("镜像管理");
        tabPages.put(containerTab, containerPage);
        tabPages.put(imageTab, imagePage);
        subTabs.add(containerTab, imageTab);
        subTabs.addSelectedChangeListener(e -> switchTab(e.getSelectedTab()));
        subContent.setWidthFull();
    }

    private void switchTab(Tab tab) {
        if (tab == null) {
            return;
        }
        subContent.removeAll();
        VerticalLayout page = tabPages.get(tab);
        if (page != null) {
            subContent.add(page);
        }
    }

    private void reloadAll() {
        reloadContainers();
        reloadImages();
    }

    // ------------------------------------------------------------------
    // 容器页
    // ------------------------------------------------------------------

    private void buildContainerPage() {
        containerPage.setPadding(false);
        containerPage.setSpacing(false);
        containerPage.getStyle().set("gap", "8px");

        filterCombo.setWidth("130px");
        Button refresh = UiFactory.button("刷新", this::reloadContainers);
        Button batchDelete = UiFactory.danger("批量删除", this::batchDeleteContainers);
        Button prune = UiFactory.button("清理已停止", this::pruneStopped);
        createContainerBtn.setEnabled(false);
        filterCombo.addValueChangeListener(e -> applyContainerFilter());

        containerGrid.setSelectionMode(Grid.SelectionMode.MULTI);
        containerGrid.addColumn(DockerContainer::getName).setHeader("名称").setAutoWidth(true);
        containerGrid.addColumn(DockerContainer::getImage).setHeader("镜像").setAutoWidth(true);
        // 状态列：中文状态 + docker 原始 Status（"Up 3 hours" 能看到跑了多久）
        containerGrid.addComponentColumn(c -> {
            Span state = new Span(c.getStateLabel());
            if (c.isRunning()) {
                state.getStyle().set("color", "var(--lumo-success-text-color)");
            } else if (c.isPaused()) {
                state.getStyle().set("color", "var(--lumo-warning-text-color)");
            } else {
                state.getStyle().set("color", "var(--lumo-tertiary-text-color)");
            }
            Span detail = new Span(StrUtil.emptyToDefault(c.getStatus(), ""));
            detail.getStyle().set("color", "var(--lumo-secondary-text-color)");
            HorizontalLayout cell = new HorizontalLayout(state, detail);
            cell.setSpacing(false);
            cell.getStyle().set("gap", "8px");
            return cell;
        }).setHeader("状态").setAutoWidth(true);
        containerGrid.addColumn(c -> StrUtil.emptyToDefault(c.getPorts(), "-")).setHeader("端口映射").setAutoWidth(true);
        containerGrid.addColumn(DockerContainer::getCreatedAt).setHeader("创建时间").setAutoWidth(true);
        containerGrid.addComponentColumn(this::buildContainerActions).setHeader("操作").setAutoWidth(true);

        HorizontalLayout bar = new HorizontalLayout(filterCombo, createContainerBtn, refresh, batchDelete, prune,
                spacer(), containerStatus);
        bar.setClassName("view-toolbar");
        bar.setAlignItems(FlexComponent.Alignment.CENTER);
        bar.getStyle().set("flex-wrap", "wrap");

        containerPage.add(bar, containerGrid);
        containerPage.setFlexGrow(1, containerGrid);
    }

    private Component buildContainerActions(DockerContainer c) {
        HorizontalLayout actions = new HorizontalLayout();
        actions.setSpacing(false);
        actions.addClassName("row-actions");
        actions.add(UiFactory.rowAction("详情", () -> showContainerDetail(c)));
        if (c.isUp()) {
            actions.add(UiFactory.rowAction("停止", () -> simpleContainerAction("停止容器 " + c.getName(), () -> service.stopContainer(c.getId()))));
        } else {
            actions.add(UiFactory.rowAction("启动", () -> simpleContainerAction("启动容器 " + c.getName(), () -> service.startContainer(c.getId()))));
        }
        if (c.isRunning()) {
            actions.add(UiFactory.rowAction("重启", () -> simpleContainerAction("重启容器 " + c.getName(), () -> service.restartContainer(c.getId()))));
            actions.add(UiFactory.rowAction("暂停", () -> simpleContainerAction("暂停容器 " + c.getName(), () -> service.pauseContainer(c.getId()))));
        }
        if (c.isPaused()) {
            actions.add(UiFactory.rowAction("恢复", () -> simpleContainerAction("恢复容器 " + c.getName(), () -> service.unpauseContainer(c.getId()))));
        }
        actions.add(UiFactory.rowDanger("删除", () -> confirmDeleteContainers(List.of(c))));
        return actions;
    }

    private void reloadContainers() {
        if (service == null) {
            return;
        }
        runAsync("读取容器列表", () -> service.listContainers(true), raw -> {
            @SuppressWarnings("unchecked")
            List<DockerContainer> containers = (List<DockerContainer>) raw;
            allContainers = containers;
            applyContainerFilter();
        }, this::onDockerFailure);
    }

    private void applyContainerFilter() {
        String filter = filterCombo.getValue();
        List<DockerContainer> visible = new ArrayList<>();
        for (DockerContainer c : allContainers) {
            if (FILTER_RUNNING.equals(filter) && !c.isUp()) {
                continue;
            }
            if (FILTER_STOPPED.equals(filter) && c.isUp()) {
                continue;
            }
            visible.add(c);
        }
        containerGrid.setItems(visible);

        int running = 0;
        int paused = 0;
        for (DockerContainer c : allContainers) {
            if (c.isRunning()) {
                running++;
            } else if (c.isPaused()) {
                paused++;
            }
        }
        containerStatus.setText("共 " + allContainers.size() + " 个容器：运行 " + running + "，暂停 " + paused
                + "，停止 " + (allContainers.size() - running - paused)
                + "（当前显示 " + visible.size() + " 个）");
    }

    private void simpleContainerAction(String action, ThrowingTask task) {
        setBusy(true, action + " …");
        runAsync(action, () -> {
            task.run();
            return null;
        }, v -> {
            setBusy(false, "");
            reloadContainers();
        }, e -> {
            setBusy(false, "");
            Dialogs.error(action + " 失败：" + reason(e));
        });
    }

    private void batchDeleteContainers() {
        if (!hasPermission(Constants.DELETE)) {
            Dialogs.warn("权限不足，无法删除容器");
            return;
        }
        Set<DockerContainer> selected = containerGrid.getSelectedItems();
        if (selected.isEmpty()) {
            Dialogs.warn("请先勾选要删除的容器");
            return;
        }
        confirmDeleteContainers(new ArrayList<>(selected));
    }

    private void confirmDeleteContainers(List<DockerContainer> targets) {
        if (!hasPermission(Constants.DELETE)) {
            Dialogs.warn("权限不足，无法删除容器");
            return;
        }
        StringBuilder names = new StringBuilder();
        for (DockerContainer c : targets) {
            names.append(c.getName()).append("（").append(c.getShortId()).append("）\n");
        }
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("删除容器（" + targets.size() + " 个）");
        Checkbox forceBox = new Checkbox("强制删除（-f，运行中也会被强杀）");
        Checkbox volumeBox = new Checkbox("同时删除匿名数据卷（-v）");
        VerticalLayout body = new VerticalLayout(new Span(names.toString().stripTrailing()), forceBox, volumeBox);
        body.setPadding(false);
        dialog.add(body);
        Button cancel = UiFactory.button("取消", dialog::close);
        Button confirm = UiFactory.danger("确认删除", () -> {
            boolean force = forceBox.getValue();
            boolean withVolumes = volumeBox.getValue();
            dialog.close();
            setBusy(true, "正在删除容器 …");
            runAsync("删除容器", () -> {
                StringBuilder report = new StringBuilder();
                for (DockerContainer c : targets) {
                    try {
                        service.removeContainer(c.getId(), force, withVolumes);
                        report.append("已删除 ").append(c.getName()).append("；");
                        log.info("用户 {} 删除了容器 {}（force={}）", com.sl.security.CurrentUser.id(), c.getName(), force);
                    } catch (Exception e) {
                        report.append(c.getName()).append(" 删除失败（").append(reason(e)).append("）；");
                    }
                }
                return report.toString();
            }, report -> {
                setBusy(false, "");
                Dialogs.info(report.toString());
                reloadContainers();
            }, e -> {
                setBusy(false, "");
                Dialogs.error("删除失败：" + reason(e));
            });
        });
        dialog.getFooter().add(cancel, confirm);
        dialog.open();
    }

    private void pruneStopped() {
        if (!hasPermission(Constants.DELETE)) {
            Dialogs.warn("权限不足，无法清理容器");
            return;
        }
        Dialogs.confirmDanger("清理已停止容器",
                "将执行 docker container prune -f，删除所有已停止的容器及其匿名卷。操作不可撤销。",
                "执行清理", () -> {
                    setBusy(true, "正在清理已停止容器 …");
                    runAsync("清理已停止容器", () -> service.prune("containers"), result -> {
                        setBusy(false, "");
                        Dialogs.info("清理完成：" + StrUtil.emptyToDefault((String) result, "无输出"));
                        reloadContainers();
                    }, e -> {
                        setBusy(false, "");
                        Dialogs.error("清理失败：" + reason(e));
                    });
                });
    }

    /**
     * 详情：弹窗里两个标签。
     * <ul>
     *   <li>「运行日志」——内嵌 xterm 终端（terminal.html 复用 {@code /ws/docker} 端点的
     *       LOGS 模式），打开即自动在目标机执行 {@code docker logs -f --tail 500 <容器>}，
     *       日志持续滚动，至少保留最近 500 行；视图只读，Ctrl+F 可搜索。</li>
     *   <li>「docker inspect」——inspect 原文只读展示。</li>
     * </ul>
     * inspect 先在后台读好再开弹窗；日志流不需要预取——通道建好前页面上有提示，
     * terminal.html 自己处理「正在连接」状态。
     */
    private void showContainerDetail(DockerContainer c) {
        setBusy(true, "读取容器详情 …");
        runAsync("读取容器详情 " + c.getName(), () -> service.inspect(c.getId()), inspect -> {
            setBusy(false, "");
            openDetailDialog(c, (String) inspect);
        }, e -> {
            setBusy(false, "");
            Dialogs.error("读取详情失败：" + reason(e));
        });
    }

    private void openDetailDialog(DockerContainer c, String inspect) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("容器详情：" + c.getName());
        dialog.setWidth("1200px");
        dialog.setHeight("785px");

        Tabs detailTabs = new Tabs();
        Tab logsTab = new Tab("运行日志");
        Tab inspectTab = new Tab("docker inspect");
        detailTabs.add(logsTab, inspectTab);

        // ---- Tab1：xterm 终端跟踪容器日志 ----
        // token 登记一次、iframe 关闭时回收；terminal.html 的 ro=1（只读）、eol=1（docker logs
        // 没有 tty，输出只有 LF，不转义会被 xterm 画成阶梯）。--tail 500 兜底历史行数。
        DockerTerminalRegistry.Spec spec = new DockerTerminalRegistry.Spec(
                DockerTerminalRegistry.Kind.LOGS, executor.getInfo(), executor.getCommandPrefix(),
                c.getId(), c.getName(), 500, false, "/bin/sh");
        String token = DockerTerminalRegistry.register(spec);
        IFrame logsFrame = new IFrame("VAADIN/static/terminal/terminal.html?token=" + token
                + "&ws=/ws/docker&ro=1&eol=1");
        logsFrame.setWidthFull();
        logsFrame.setHeight("560px");
        logsFrame.getElement().setAttribute("title", "容器日志终端");
        Span logsHint = new Span("自动跟踪 docker logs -f --tail 500，新日志持续滚动；只读，Ctrl+F 搜索，工具栏可导出。");
        logsHint.addClassName("view-subtitle");
        VerticalLayout logsPage = new VerticalLayout(logsHint, logsFrame);
        logsPage.setSizeFull();
        logsPage.setPadding(false);
        logsPage.setSpacing(false);
        logsPage.getStyle().set("gap", "6px");

        // ---- Tab2：docker inspect 原文 ----
        TextArea inspectArea = UiFactory.textArea();
        inspectArea.setValue(StrUtil.blankToDefault(inspect, "（无输出）"));
        inspectArea.setReadOnly(true);
        inspectArea.setWidthFull();
        inspectArea.setHeight("560px");
        inspectArea.getElement().getStyle().set("font-family", "var(--lumo-font-family-monospace, monospace)");
        inspectArea.getElement().getStyle().set("font-size", "var(--lumo-font-size-m)");
        VerticalLayout inspectPage = new VerticalLayout(inspectArea);
        inspectPage.setSizeFull();
        inspectPage.setPadding(false);
        inspectPage.setVisible(false);

        // 切换用 setVisible 隐藏而不是移除：移除会把 iframe 连同日志流一起销毁，切回来得重连
        detailTabs.addSelectedChangeListener(e -> {
            boolean logsSelected = e.getSelectedTab() == logsTab;
            logsPage.setVisible(logsSelected);
            inspectPage.setVisible(!logsSelected);
        });

        Div content = new Div(logsPage, inspectPage);
        content.setWidthFull();

        VerticalLayout body = new VerticalLayout(detailTabs, content);
        body.setSizeFull();
        body.setPadding(false);
        body.setSpacing(false);
        body.getStyle().set("gap", "6px");
        dialog.add(body);
        dialog.getFooter().add(UiFactory.button("关闭", dialog::close));
        // 弹窗关掉就回收 token：iframe 摘除后 websocket 随之断开，/ws/docker 会自己清掉 SSH 通道
        dialog.addOpenedChangeListener(e -> {
            if (!dialog.isOpened()) {
                DockerTerminalRegistry.release(token);
            }
        });
        dialog.open();
    }

    // ------------------------------------------------------------------
    // 镜像页
    // ------------------------------------------------------------------

    private void buildImagePage() {
        imagePage.setPadding(false);
        imagePage.setSpacing(false);
        imagePage.getStyle().set("gap", "8px");

        Button refresh = UiFactory.button("刷新", this::reloadImages);
        Button pull = UiFactory.primary("拉取镜像", this::showPullDialog);
        Button prune = UiFactory.button("清理悬空镜像", this::confirmPruneImages);
        showAllBox.addValueChangeListener(e -> reloadImages());

        imageGrid.setSelectionMode(Grid.SelectionMode.SINGLE);
        imageGrid.addColumn(DockerImage::getShortId).setHeader("镜像 ID").setAutoWidth(true);
        imageGrid.addColumn(DockerImage::getRepository).setHeader("仓库").setAutoWidth(true);
        imageGrid.addColumn(DockerImage::getTag).setHeader("标签").setAutoWidth(true);
        imageGrid.addColumn(DockerImage::getSize).setHeader("大小").setAutoWidth(true);
        imageGrid.addColumn(DockerImage::getCreatedAt).setHeader("创建时间").setAutoWidth(true);
        imageGrid.addComponentColumn(this::buildImageActions).setHeader("操作").setAutoWidth(true);

        HorizontalLayout bar = new HorizontalLayout(refresh, pull, prune, showAllBox, spacer(), imageStatus);
        bar.setClassName("view-toolbar");
        bar.setAlignItems(FlexComponent.Alignment.CENTER);
        bar.getStyle().set("flex-wrap", "wrap");

        imagePage.add(bar, imageGrid);
        imagePage.setFlexGrow(1, imageGrid);
    }

    private Component buildImageActions(DockerImage image) {
        HorizontalLayout actions = new HorizontalLayout();
        actions.setSpacing(false);
        actions.addClassName("row-actions");
        actions.add(UiFactory.rowAction("导出", () -> exportImage(image)));
        actions.add(UiFactory.rowDanger("删除", () -> confirmDeleteImages(List.of(image))));
        return actions;
    }

    private void reloadImages() {
        if (service == null) {
            return;
        }
        boolean all = showAllBox.getValue();
        runAsync("读取镜像列表", () -> service.listImages(all), raw -> {
            @SuppressWarnings("unchecked")
            List<DockerImage> images = (List<DockerImage>) raw;
            allImages = images;
            imageGrid.setItems(images);
            long dangling = images.stream().filter(DockerImage::isDangling).count();
            imageStatus.setText("共 " + images.size() + " 个镜像，悬空镜像 " + dangling + " 个");
        }, this::onDockerFailure);
    }

    private void showPullDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("拉取镜像");
        dialog.setWidth("560px");
        TextField refField = UiFactory.textField("镜像地址", "nginx:1.25 或 registry.example.com/team/app:v1.2.3");
        refField.setWidthFull();
        Span hint = new Span("不写 registry 时默认从 Docker Hub 拉取；内网仓库请写全地址。");
        hint.addClassName("view-subtitle");
        VerticalLayout body = new VerticalLayout(refField, hint);
        body.setPadding(false);
        dialog.add(body);
        Button cancel = UiFactory.button("取消", dialog::close);
        Button pull = UiFactory.primary("开始拉取", () -> {
            String reference = StrUtil.trim(refField.getValue());
            if (StrUtil.isBlank(reference)) {
                Dialogs.warn("请填写镜像地址");
                return;
            }
            dialog.close();
            setBusy(true, "拉取镜像 " + reference + " …");
            runAsync("拉取镜像", () -> service.pullImage(reference), raw -> {
                setBusy(false, "");
                Dialogs.success("拉取完成：" + StrUtil.emptyToDefault(tail((String) raw), "成功"));
                reloadImages();
            }, e -> {
                setBusy(false, "");
                Dialogs.error("拉取失败：" + reason(e));
            });
        });
        dialog.getFooter().add(cancel, pull);
        dialog.open();
    }

    /**
     * 「创建容器」弹窗：上方下拉选已存在的镜像（可一键复制名称），
     * 下方粘贴整条 docker run / create 命令，点「创建容器」在目标机上执行。
     * <p>
     * 执行走 {@link DockerService#runDockerCommand(String)}：它会把
     * {@code sudo docker}、{@code \} 续行、shell 提示符规范化掉，
     * 并且只放行 run / create 两个子命令（界面不开任意 shell 后门）。
     */
    private void showCreateContainerDialog() {
        if (service == null) {
            Dialogs.warn("请先连接目标服务器");
            return;
        }

        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("创建容器");
        dialog.setWidth("880px");
        dialog.setHeight("800px");

        ComboBox<String> imageCombo = new ComboBox<>();
        imageCombo.setItems(existingImageRefs());
        // 仓库里没有列出的镜像（刚 docker load 进来还没刷新列表）也允许手输
        imageCombo.setAllowCustomValue(true);
        imageCombo.setPlaceholder("选择或输入镜像名，如 nginx:1.25");
        imageCombo.setWidth("500px");
        Button copyBtn = UiFactory.button("复制镜像名", () -> {
            String reference = StrUtil.trimToEmpty(imageCombo.getValue());
            if (reference.isEmpty()) {
                Dialogs.warn("请先选择镜像");
                return;
            }
            copyToClipboard(reference);
        });
        HorizontalLayout imageRow = new HorizontalLayout(
                UiFactory.fieldRow("已有镜像", "80px", imageCombo), copyBtn);
        imageRow.setAlignItems(FlexComponent.Alignment.CENTER);
        imageRow.getStyle().set("gap", "8px");

        Span cmdLabel = new Span("创建命令（完整的 docker run / docker create 命令）");
        cmdLabel.addClassName("view-section-title");
        CodeEditor commandEditor = new CodeEditor(CodeEditor.MODE_SHELL);
        commandEditor.setWidthFull();
        commandEditor.setHeight("565px");
        Span hint = new Span("只执行 docker run / create；从文档复制来的 sudo 前缀、\\ 续行、$ 提示符会自动处理。");
        hint.addClassName("view-subtitle");

        VerticalLayout body = new VerticalLayout(imageRow, cmdLabel, commandEditor, hint);
        body.setPadding(false);
        dialog.add(body);

        Button cancel = UiFactory.button("取消", dialog::close);
        Button create = UiFactory.primary("创建容器", () -> {
            String command = commandEditor.getValue();
            if (StrUtil.isBlank(command)) {
                Dialogs.warn("请先粘贴 docker run 命令");
                return;
            }
            dialog.close();
            setBusy(true, "正在创建容器 …");
            runAsync("创建容器", () -> service.runDockerCommand(command), output -> {
                setBusy(false, "");
                Dialogs.success("容器创建成功" + shortContainerId((String) output));
                reloadContainers();
            }, e -> {
                setBusy(false, "");
                Dialogs.error("创建失败：" + reason(e));
            });
        });
        dialog.getFooter().add(cancel, create);
        dialog.open();
    }

    /** 镜像下拉候选：当前列表里非悬空、名字完整的镜像引用（仓库:标签） */
    private List<String> existingImageRefs() {
        List<String> refs = new ArrayList<>();
        for (DockerImage image : allImages) {
            String reference = StrUtil.trimToEmpty(image.getReference());
            if (!image.isDangling() && !reference.isEmpty() && !reference.contains("<none>")) {
                refs.add(reference);
            }
        }
        return refs;
    }

    /** docker run/create 成功输出就是新容器 ID（64 位 hex），界面只展示前 12 位 */
    private static String shortContainerId(String output) {
        String trimmed = StrUtil.trimToEmpty(output);
        if (trimmed.isEmpty()) {
            return "";
        }
        String firstLine = trimmed.split("\\R", 2)[0].trim();
        if (firstLine.matches("[0-9a-fA-F]{20,}")) {
            return "，容器 ID " + firstLine.substring(0, 12);
        }
        return "：" + tail(trimmed);
    }

    /**
     * 往浏览器剪贴板写文本。
     * <p>
     * 注意 {@code executeJs} 的收参方式：Flow 客户端把它交给
     * {@code new Function($0, $1, ..., 表达式)}，最后一个参数是<b>函数体</b>而不是
     * 函数表达式——之前写 {@code "(t) => {...}"} 等于造出一个函数然后立刻丢弃，
     * 函数体一行都不会执行，「复制」按钮看起来就是坏的。
     * <p>
     * {@code navigator.clipboard} 只在安全上下文（HTTPS / localhost）存在，
     * 内网 IP+HTTP 访问时走 {@code execCommand} 兜底（点击事件 5 秒内的用户激活仍有效）。
     */
    private void copyToClipboard(String text) {
        getUI().ifPresent(ui -> ui.getPage().executeJs("""
                const value = $0;
                const fallbackCopy = (v) => {
                    const ta = document.createElement('textarea');
                    ta.value = v;
                    ta.style.position = 'fixed';
                    ta.style.top = '-1000px';
                    document.body.appendChild(ta);
                    ta.select();
                    try { document.execCommand('copy'); } catch (e) {}
                    document.body.removeChild(ta);
                };
                if (window.isSecureContext && navigator.clipboard && navigator.clipboard.writeText) {
                    navigator.clipboard.writeText(value).catch(() => fallbackCopy(value));
                } else {
                    fallbackCopy(value);
                }
                """, text));
        Dialogs.success("已复制：" + text);
    }

    private void confirmDeleteImages(List<DockerImage> targets) {
        if (!hasPermission(Constants.DELETE)) {
            Dialogs.warn("权限不足，无法删除镜像");
            return;
        }
        StringBuilder names = new StringBuilder();
        for (DockerImage image : targets) {
            names.append(image.getReference()).append("（").append(image.getShortId()).append("）\n");
        }
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("删除镜像");
        Checkbox forceBox = new Checkbox("强制删除（-f，被容器引用的镜像需要勾选）");
        VerticalLayout body = new VerticalLayout(new Span(names.toString().stripTrailing()), forceBox);
        body.setPadding(false);
        dialog.add(body);
        Button cancel = UiFactory.button("取消", dialog::close);
        Button confirm = UiFactory.danger("确认删除", () -> {
            boolean force = forceBox.getValue();
            dialog.close();
            setBusy(true, "正在删除镜像 …");
            runAsync("删除镜像", () -> {
                StringBuilder report = new StringBuilder();
                for (DockerImage image : targets) {
                    try {
                        service.removeImage(image.getId(), force);
                        report.append("已删除 ").append(image.getReference()).append("；");
                    } catch (Exception e) {
                        report.append(image.getReference()).append(" 删除失败（").append(reason(e)).append("）；");
                    }
                }
                return report.toString();
            }, report -> {
                setBusy(false, "");
                Dialogs.info(report.toString());
                reloadImages();
            }, e -> {
                setBusy(false, "");
                Dialogs.error("删除失败：" + reason(e));
            });
        });
        dialog.getFooter().add(cancel, confirm);
        dialog.open();
    }

    private void confirmPruneImages() {
        if (!hasPermission(Constants.DELETE)) {
            Dialogs.warn("权限不足，无法清理镜像");
            return;
        }
        Dialogs.confirmDanger("清理悬空镜像",
                "将执行 docker image prune -f，删除所有仓库/标签为 <none> 的悬空镜像。悬空镜像不被任何容器引用，删除是安全的。",
                "执行清理", () -> {
                    setBusy(true, "正在清理悬空镜像 …");
                    runAsync("清理悬空镜像", () -> service.pruneImages(false), result -> {
                        setBusy(false, "");
                        Dialogs.info("清理完成：" + StrUtil.emptyToDefault((String) result, "无输出"));
                        reloadImages();
                    }, e -> {
                        setBusy(false, "");
                        Dialogs.error("清理失败：" + reason(e));
                    });
                });
    }

    /**
     * 导出镜像：后台 {@code docker save} 落到本机临时目录，完成后弹出下载入口。
     * 旧实现用 FileDownloader 直接流式回浏览器，Vaadin 24 移除了 FileDownloader，
     * 这里改为落临时文件 + Anchor 下载（大镜像多一步本地落盘，但链路简单可靠）。
     */
    private void exportImage(DockerImage image) {
        String reference = image.getReference();
        String tarName = reference.replace('/', '_').replace(':', '_') + ".tar";
        setBusy(true, "正在导出镜像 " + reference + "（大镜像需要几分钟）…");
        runAsync("导出镜像 " + reference, () -> service.saveImage(reference, tarName), raw -> {
            setBusy(false, "");
            File tar = (File) raw;
            Dialog dialog = new Dialog();
            dialog.setHeaderTitle("镜像已导出");
            Span info = new Span("已保存到服务器临时目录：" + tar.getAbsolutePath()
                    + "（" + UiFactory.formatFileSize(tar.length()) + "）");
            info.getStyle().set("overflow-wrap", "anywhere");
            VerticalLayout body = new VerticalLayout(info, UiFactory.download(tar, "下载 " + tarName));
            body.setPadding(false);
            dialog.add(body);
            dialog.getFooter().add(UiFactory.button("关闭", dialog::close));
            dialog.open();
        }, e -> {
            setBusy(false, "");
            Dialogs.error("导出失败：" + reason(e));
        });
    }

    // ------------------------------------------------------------------
    // 异步骨架与工具方法
    // ------------------------------------------------------------------

    /** 后台任务开始/结束时更新状态提示，并禁掉连接按钮避免并发建链 */
    private void setBusy(boolean busy, String text) {
        busyLabel.setText(StrUtil.emptyToDefault(text, ""));
        connectBtn.setEnabled(!busy);
    }

    private void runAsync(String label, ThrowingSupplier task, Consumer<Object> onDone,
                          Consumer<Exception> onFailure) {
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
        }, "docker-" + label);
        thread.setDaemon(true);
        thread.start();
    }

    /** docker 类故障的统一处理：连接类故障不自动重试（防命令风暴），只提示；普通失败兜底刷新一次 */
    private void onDockerFailure(Exception e) {
        if (e instanceof DockerDaemonDownException) {
            setBusy(false, "");
            Dialogs.warn(StrUtil.emptyToDefault(e.getMessage(), "docker 服务不可用"));
            if (e instanceof DockerDaemonDownException down && down.getStatus() != null) {
                showDaemonNotice(down.getStatus());
            }
            return;
        }
        setBusy(false, "");
        Dialogs.error("操作失败：" + reason(e));
    }

    private static String reason(Exception e) {
        return StrUtil.emptyToDefault(e.getMessage(), e.getClass().getSimpleName());
    }

    private static String tail(String output) {
        if (StrUtil.isBlank(output)) {
            return "";
        }
        String[] lines = output.split("\\R");
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, lines.length - 3); i < lines.length; i++) {
            if (!sb.isEmpty()) {
                sb.append(" / ");
            }
            sb.append(lines[i].trim());
        }
        return sb.toString();
    }

    private static boolean hasPermission(String code) {
        com.sl.entity.User user = com.sl.security.CurrentUser.get();
        return user != null && user.hasPermission(code);
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        // 页面关掉就断开这条 SSH 通道，否则每开一次标签都会在目标机上留一个 ssh 会话
        closeExecutor();
        super.onDetach(detachEvent);
    }

    private void closeExecutor() {
        if (executor != null) {
            executor.close();
            executor = null;
            service = null;
        }
        subTabs.setVisible(false);
        subContent.setVisible(false);
        checkBtn.setEnabled(false);
        createContainerBtn.setEnabled(false);
    }

    /** 允许抛异常的无参任务（ThrowingSupplier 的 SAM 是 get） */
    @FunctionalInterface
    private interface ThrowingSupplier {
        Object get() throws Exception;
    }

    /** 允许抛异常的无返回任务 */
    @FunctionalInterface
    private interface ThrowingTask {
        void run() throws Exception;
    }
}
