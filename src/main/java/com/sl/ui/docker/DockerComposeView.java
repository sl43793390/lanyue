package com.sl.ui.docker;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sl.docker.ComposePreferenceStore;
import com.sl.docker.ComposeService;
import com.sl.docker.DockerDaemonDownException;
import com.sl.docker.DockerExecutor;
import com.sl.docker.model.ComposeBaseDirEntry;
import com.sl.docker.model.ComposeContainer;
import com.sl.docker.model.ComposeProject;
import com.sl.docker.model.ComposeUiPreference;
import com.sl.entity.ConnectionInfo;
import com.sl.mapper.ConnectionInfoMapper;
import com.sl.ui.component.Dialogs;
import com.sl.ui.component.UiFactory;
import com.sl.ui.component.ViewBase;
import com.sl.util.Constants;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.server.VaadinSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Docker-Compose 管理。
 * <p>
 * 对应旧项目的 {@code DockerComposeComponent}（项目列表）+ {@code ComposeProjectWindow}
 * 的一部分（项目级生命周期动作、配置文件查看与编辑）。与旧实现的差别：
 * <ul>
 *   <li>项目级操作（up / down / restart）与容器列表直接放在列表行内，不再先开一层项目窗口——
 *       日常「重启一个项目」「看看跑起来没有」这类高频操作少一次点击；</li>
 *   <li>compose 文件的编辑收进同一个弹窗（多文件切换），改完保存即写回目标机；
 *       模板创建 / Git 同步 / 服务级 scale 等低频操作还在迁移队列。</li>
 * </ul>
 * 连接模型与 {@link DockerMgmtView} 相同：每个标签独占一条 SSH 通道，
 * daemon 不在时不发命令，页面关闭自动断开。
 */
@Service
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class DockerComposeView extends ViewBase {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(DockerComposeView.class);

    private final transient ConnectionInfoMapper connectionInfoMapper;
    private final transient ApplicationContext applicationContext;
    private final transient ComposePreferenceStore prefStore;

    // 工具条字段一律不带组件内 label：用 UiFactory.fieldRow 的水平「标签+控件」组合，
    // 天然与相邻按钮上下对齐（组件内 label 的排版已在全局 CSS 里废弃）。
    private final ComboBox<ConnectionInfo> hostCombo = new ComboBox<>();
    private final TextField baseDirField = UiFactory.textField();
    /** 历史项目根目录下拉：这台机器上用过的根目录，选中即切换并刷新项目列表 */
    private final com.vaadin.flow.component.combobox.ComboBox<ComposeBaseDirEntry> baseDirCombo =
            new com.vaadin.flow.component.combobox.ComboBox<>();
    private final Button connectBtn = UiFactory.primary("连接", this::connect);
    private final Button createBtn = UiFactory.button("新建项目", () -> openCreateDialog());
    private final Span busyLabel = new Span();
    private final Span envLabel = new Span();
    private final Span statusLabel = new Span();

    private final Grid<ComposeProject> projectGrid = UiFactory.grid(ComposeProject.class);

    private List<ConnectionInfo> candidateHosts = new ArrayList<>();
    private ConnectionInfo presetHost;
    private boolean autoConnectPending;
    /** 当前登录用户的 compose 偏好（上次主机 + 每台机器的历史根目录），连接成功时读一次，改动即写回 */
    private transient ComposeUiPreference preference;
    /** 程序性改动 baseDirCombo 时置位，避免它自己的监听器又去刷一遍列表 */
    private boolean updatingBaseDirCombo;

    private transient DockerExecutor executor;
    private transient ComposeService service;

    public DockerComposeView(ConnectionInfoMapper connectionInfoMapper, ApplicationContext applicationContext,
                             ComposePreferenceStore prefStore) {
        this.connectionInfoMapper = connectionInfoMapper;
        this.applicationContext = applicationContext;
        this.prefStore = prefStore;

        add(title("Docker-Compose 管理"));
        add(subtitle("扫描目标机上的 compose 项目，可启停、看容器、改配置。页面关闭时 SSH 通道自动断开。"));

        add(buildConnectRow());
        envLabel.addClassName("docker-env-label");
        envLabel.getStyle().set("overflow-wrap", "anywhere");
        add(envLabel);

        buildGrid();
        HorizontalLayout bar = toolbar(spacer(), statusLabel);
        VerticalLayout fill = fill(bar, projectGrid);
        add(fill);
        setFlexGrow(1, fill);

        loadCandidateHosts();
    }

    // ------------------------------------------------------------------
    // 连接区（与 DockerMgmtView 同一套规则）
    // ------------------------------------------------------------------

    private com.vaadin.flow.component.Component buildConnectRow() {
        // 宽度总和必须留在一行内：.view-toolbar 带 flex-wrap，超宽会把按钮挤到第二行，
        // 看起来就像"输入框和按钮没对齐"（round5 验证踩过）。
        hostCombo.setWidth("240px");
        hostCombo.setPlaceholder("选择一台已配置的服务器");
        hostCombo.setItemLabelGenerator(item -> item.getIdHost() + " (" + item.getIdUser() + ")");
        hostCombo.setAllowCustomValue(false);
        baseDirField.setWidth("200px");
        baseDirField.setPlaceholder("留空用默认 ~/logviewer-compose");
        baseDirField.addKeyDownListener(com.vaadin.flow.component.Key.ENTER, e -> reloadProjects());
        createBtn.setEnabled(false);
        baseDirCombo.setWidth("330px");
        baseDirCombo.setPlaceholder("这台机器上用过的目录");
        baseDirCombo.setTooltipText("选中即切换并刷新下面的项目列表；随扫描自动记住新目录");
        baseDirCombo.setItemLabelGenerator(ComposeBaseDirEntry::caption);
        baseDirCombo.addValueChangeListener(e -> {
            if (updatingBaseDirCombo || e.getValue() == null) {
                return;
            }
            String dir = StrUtil.trimToEmpty(e.getValue().getDir());
            if (!dir.isEmpty() && !dir.equals(StrUtil.trimToEmpty(baseDirField.getValue()))) {
                baseDirField.setValue(dir);
                reloadProjects();
            }
        });

        HorizontalLayout row = new HorizontalLayout(
                UiFactory.fieldRow("目标服务器", "80px", hostCombo),
                UiFactory.fieldRow("项目根目录", "80px", baseDirField),
                UiFactory.fieldRow("历史目录", "68px", baseDirCombo),
                connectBtn, createBtn, busyLabel);
        row.setClassName("view-toolbar");
        row.setAlignItems(FlexComponent.Alignment.CENTER);
        row.setFlexGrow(1, busyLabel);
        return row;
    }

    private void loadCandidateHosts() {
        List<ConnectionInfo> list = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try {
            List<ConnectionInfo> fromDb = connectionInfoMapper.selectList(new QueryWrapper<>());
            if (fromDb != null) {
                for (ConnectionInfo info : fromDb) {
                    if (info != null && StrUtil.isNotBlank(info.getIdHost())
                            && seen.add(info.getIdHost() + ":" + StrUtil.blankToDefault(info.getCdPort(), "22"))) {
                        list.add(info);
                    }
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
                ConnectionInfo info = new ConnectionInfo(split[0], split[3], split[1], split[2], keyPath);
                if (seen.add(info.getIdHost() + ":" + StrUtil.blankToDefault(info.getCdPort(), "22"))) {
                    list.add(info);
                }
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
        ConnectionInfo target = null;
        String key = presetHost.getIdHost() + ":" + StrUtil.blankToDefault(presetHost.getCdPort(), "22");
        for (ConnectionInfo info : candidateHosts) {
            if (key.equals(info.getIdHost() + ":" + StrUtil.blankToDefault(info.getCdPort(), "22"))) {
                target = info;
                break;
            }
        }
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
        closeExecutor();
        setBusy(true, "正在连接 " + info.getIdHost() + " …");
        runAsync("连接 " + info.getIdHost(), () -> {
            DockerExecutor newExecutor = new DockerExecutor(info, "");
            ComposeService newService = new ComposeService(newExecutor);
            // 连上顺手取一次 compose CLI 信息，写进状态行；CLI 不在也能连，只是提示
            String cliText;
            try {
                var cli = newService.cliInfo();
                cliText = cli.summary();
            } catch (Exception e) {
                cliText = "compose CLI 不可用：" + StrUtil.emptyToDefault(e.getMessage(), "未知错误");
            }
            String defaultBaseDir = newService.defaultBaseDir();
            return new Object[]{newExecutor, newService, cliText, defaultBaseDir};
        }, result -> {
            Object[] parts = (Object[]) result;
            executor = (DockerExecutor) parts[0];
            service = (ComposeService) parts[1];
            setBusy(false, "");
            createBtn.setEnabled(true);
            envLabel.setText("已连接 " + executor.hostLabel() + "　" + parts[2]);
            if (StrUtil.isBlank(baseDirField.getValue())) {
                baseDirField.setValue(StrUtil.nullToEmpty((String) parts[3]));
            }
            // 偏好按"主机:端口"记，连接成功先读一次、记下"上次用的这台机器"
            String hostKey = executor.hostLabel();
            try {
                preference = prefStore.load(currentUser());
                preference.setLastHost(hostKey);
                prefStore.save(currentUser(), preference);
            } catch (Exception e) {
                log.warn("读写 compose 偏好失败：{}", e.getMessage());
            }
            refreshBaseDirCombo(hostKey);
            reloadProjects();
        }, e -> {
            setBusy(false, "");
            Dialogs.error("连接失败：" + StrUtil.emptyToDefault(e.getMessage(), e.getClass().getSimpleName()));
        });
    }

    // ------------------------------------------------------------------
    // 项目列表
    // ------------------------------------------------------------------

    private void buildGrid() {
        projectGrid.addColumn(ComposeProject::getName).setHeader("项目").setAutoWidth(true);
        projectGrid.addColumn(ComposeProject::getDirectory).setHeader("目录").setAutoWidth(true);
        projectGrid.addColumn(ComposeProject::getFilesText).setHeader("配置文件").setAutoWidth(true);
        projectGrid.addComponentColumn(p -> {
            Span badge = new Span(p.getStatusLabel());
            badge.getElement().getThemeList().add("badge");
            if (!p.getStatusLabel().equals("运行中")) {
                badge.getElement().getThemeList().add("contrast");
            }
            return badge;
        }).setHeader("状态").setAutoWidth(true);
        projectGrid.addColumn(ComposeProject::getContainerCountText).setHeader("容器").setAutoWidth(true);
        projectGrid.addColumn(p -> StrUtil.nullToEmpty(p.getDescription())).setHeader("描述").setAutoWidth(true);
        projectGrid.addColumn(p -> p.isManaged() ? "平台管理" : "外部项目").setHeader("来源").setAutoWidth(true);
        projectGrid.addComponentColumn(this::buildRowActions).setHeader("操作").setAutoWidth(true);
    }

    private com.vaadin.flow.component.Component buildRowActions(ComposeProject project) {
        HorizontalLayout actions = new HorizontalLayout();
        actions.setSpacing(false);
        actions.getStyle().set("gap", "2px");

        actions.add(UiFactory.small("启动", () -> projectAction("启动项目 " + project.getName(), () -> service.up(project, null, false, false, true))));
        actions.add(UiFactory.small("重启", () -> projectAction("重启项目 " + project.getName(), () -> service.redeploy(project, false))));
        actions.add(UiFactory.small("停止", () -> confirmDown(project)));
        actions.add(UiFactory.small("容器", () -> showContainers(project)));
        actions.add(UiFactory.small("配置", () -> showFiles(project)));

        Button delete = UiFactory.small("删除", () -> confirmDelete(project));
        delete.getElement().getThemeList().add("error");
        actions.add(delete);
        return actions;
    }

    /**
     * 新建 Compose 项目：弹窗里选模板/空白/上传 yml，创建成功后刷新列表
     * （列表刷新本身会把新目录连同项目数记进历史下拉）。
     */
    private void openCreateDialog() {
        if (service == null) {
            Dialogs.warn("请先连接目标服务器");
            return;
        }
        if (!hasPermission(Constants.ADD)) {
            Dialogs.warn("权限不足，无法新建项目");
            return;
        }
        new ComposeCreateDialog(service, StrUtil.trimToEmpty(baseDirField.getValue()),
                project -> reloadProjects()).open();
    }

    /** 历史目录下拉按当前主机刷新：把当前输入框里的目录也补进去（尚未记录过时）。 */
    private void refreshBaseDirCombo(String hostKey) {
        if (preference == null) {
            return;
        }
        updatingBaseDirCombo = true;
        try {
            List<ComposeBaseDirEntry> entries = new ArrayList<>(preference.dirsOf(hostKey));
            String current = StrUtil.trimToEmpty(baseDirField.getValue());
            if (!current.isEmpty() && entries.stream().noneMatch(e -> current.equals(e.getDir()))) {
                entries.add(0, new ComposeBaseDirEntry(current, -1, null));
            }
            baseDirCombo.setItems(entries);
            entries.stream()
                    .filter(e -> current.equals(e.getDir()))
                    .findFirst()
                    .ifPresent(baseDirCombo::setValue);
        } finally {
            updatingBaseDirCombo = false;
        }
    }

    private void reloadProjects() {
        if (service == null) {
            Dialogs.warn("请先连接目标服务器");
            return;
        }
        String baseDir = StrUtil.trim(baseDirField.getValue());
        setBusy(true, "正在扫描项目 …");
        runAsync("扫描 compose 项目", () -> service.listProjects(baseDir), result -> {
            setBusy(false, "");
            List<ComposeProject> list = (List<ComposeProject>) result;
            projectGrid.setItems(list);
            long running = list.stream().filter(p -> "运行中".equals(p.getStatusLabel())).count();
            statusLabel.setText("共 " + list.size() + " 个项目，运行中 " + running + " 个");
            if (preference != null && service != null) {
                String hostKey = executor.hostLabel();
                try {
                    preference.rememberDir(hostKey, baseDir, list.size());
                    prefStore.save(currentUser(), preference);
                } catch (Exception e) {
                    log.warn("保存 compose 偏好失败：{}", e.getMessage());
                }
                refreshBaseDirCombo(hostKey);
            }
        }, this::onComposeFailure);
    }

    private void projectAction(String action, ThrowingTask task) {
        setBusy(true, action + " …");
        runAsync(action, () -> {
            String output = (String) task.run();
            return output;
        }, output -> {
            setBusy(false, "");
            Dialogs.success(action + " 完成：" + StrUtil.emptyToDefault(tail((String) output), "成功"));
            reloadProjects();
        }, e -> {
            setBusy(false, "");
            Dialogs.error(action + " 失败：" + reason(e));
        });
    }

    private void confirmDown(ComposeProject project) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("停止项目 " + project.getName());
        Checkbox volumesBox = new Checkbox("同时删除数据卷（-v，数据会丢失）");
        Checkbox imagesBox = new Checkbox("同时删除本地构建的镜像（--rmi local）");
        VerticalLayout body = new VerticalLayout(
                new Span("将执行 docker compose down，停止并移除该项目的全部容器。"),
                volumesBox, imagesBox);
        body.setPadding(false);
        dialog.add(body);
        Button cancel = UiFactory.button("取消", dialog::close);
        Button confirm = UiFactory.danger("确认停止", () -> {
            boolean volumes = volumesBox.getValue();
            boolean images = imagesBox.getValue();
            dialog.close();
            setBusy(true, "停止项目 " + project.getName() + " …");
            runAsync("停止项目", () -> service.down(project, volumes, images), output -> {
                setBusy(false, "");
                Dialogs.success("已停止：" + StrUtil.emptyToDefault(tail((String) output), "成功"));
                reloadProjects();
            }, e -> {
                setBusy(false, "");
                Dialogs.error("停止失败：" + reason(e));
            });
        });
        dialog.getFooter().add(cancel, confirm);
        dialog.open();
    }

    private void confirmDelete(ComposeProject project) {
        if (!hasPermission(Constants.DELETE)) {
            Dialogs.warn("权限不足，无法删除项目");
            return;
        }
        Dialogs.confirmDanger("删除项目 " + project.getName(),
                "将停止并移除该项目的全部容器，然后删除配置目录 " + project.getDirectory() + "。操作不可撤销。",
                "确认删除", () -> {
                    setBusy(true, "删除项目 " + project.getName() + " …");
                    runAsync("删除项目", () -> service.deleteProject(project, false, false, true), output -> {
                        setBusy(false, "");
                        Dialogs.info(StrUtil.emptyToDefault((String) output, "已删除"));
                        reloadProjects();
                    }, e -> {
                        setBusy(false, "");
                        Dialogs.error("删除失败：" + reason(e));
                    });
                });
    }

    // ------------------------------------------------------------------
    // 容器列表
    // ------------------------------------------------------------------

    private void showContainers(ComposeProject project) {
        setBusy(true, "读取容器列表 …");
        runAsync("读取项目容器", () -> service.listProjectContainers(project.getName()), raw -> {
            setBusy(false, "");
            @SuppressWarnings("unchecked")
            List<ComposeContainer> containers = (List<ComposeContainer>) raw;
            Dialog dialog = new Dialog();
            dialog.setHeaderTitle("容器：" + project.getName() + "（" + containers.size() + " 个）");
            dialog.setWidth("820px");

            Grid<ComposeContainer> grid = UiFactory.grid(ComposeContainer.class);
            grid.setItems(containers);
            grid.addColumn(ComposeContainer::getService).setHeader("服务").setAutoWidth(true);
            grid.addColumn(ComposeContainer::getName).setHeader("容器名").setAutoWidth(true);
            grid.addColumn(ComposeContainer::getStateLabel).setHeader("状态").setAutoWidth(true);
            grid.addColumn(c -> StrUtil.emptyToDefault(c.getPorts(), "-")).setHeader("端口").setAutoWidth(true);

            VerticalLayout body = new VerticalLayout(grid);
            body.setSizeFull();
            body.setPadding(false);
            dialog.add(body);
            dialog.getFooter().add(UiFactory.button("关闭", dialog::close));
            dialog.open();
        }, this::onComposeFailure);
    }

    // ------------------------------------------------------------------
    // 配置文件查看 / 编辑
    // ------------------------------------------------------------------

    private void showFiles(ComposeProject project) {
        setBusy(true, "读取配置文件 …");
        runAsync("读取项目文件", () -> service.readProjectFiles(project), raw -> {
            setBusy(false, "");
            @SuppressWarnings("unchecked")
            Map<String, String> files = (Map<String, String>) raw;
            if (files.isEmpty()) {
                Dialogs.warn("该项目没有可编辑的配置文件");
                return;
            }
            openFilesDialog(project, files);
        }, this::onComposeFailure);
    }

    private void openFilesDialog(ComposeProject project, Map<String, String> files) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("配置文件：" + project.getName());
        dialog.setWidth("880px");
        dialog.setHeight("680px");

        com.vaadin.flow.component.combobox.ComboBox<String> fileCombo =
                new com.vaadin.flow.component.combobox.ComboBox<>();
        fileCombo.setItems(files.keySet().toArray(new String[0]));
        fileCombo.setWidth("320px");
        fileCombo.setAllowCustomValue(false);

        TextArea editor = UiFactory.textArea();
        editor.setWidthFull();
        editor.setHeightFull();
        editor.getElement().getStyle().set("font-family", "var(--lumo-font-family-monospace, monospace)");
        editor.getElement().getStyle().set("font-size", "var(--lumo-font-size-xs)");

        fileCombo.addValueChangeListener(e -> {
            String content = files.get(e.getValue());
            editor.setValue(content == null ? "" : content);
        });
        fileCombo.setValue(files.keySet().iterator().next());

        Button save = UiFactory.primary("保存到服务器", () -> {
            String fileName = fileCombo.getValue();
            String content = editor.getValue();
            Dialogs.confirm("保存配置文件",
                    "确认把 " + fileName + " 写回 " + project.getDirectory() + " 吗？"
                            + "改动会立即生效于下一次启动/重启，正在运行的容器不会自动重建。",
                    () -> {
                        setBusy(true, "保存 " + fileName + " …");
                        runAsync("保存配置文件", () -> {
                            service.writeFile(project.getDirectory(), fileName, content);
                            return "ok";
                        }, ok -> {
                            setBusy(false, "");
                            files.put(fileName, content);
                            Dialogs.success("已保存 " + fileName);
                        }, this::onComposeFailure);
                    });
        });

        HorizontalLayout layout = UiFactory.group(new Span("文件"), fileCombo, save);
        VerticalLayout body = new VerticalLayout(layout, editor);
        body.setSizeFull();
        body.setPadding(false);
        body.setSpacing(false);
        body.getStyle().set("gap", "6px");
        dialog.add(body);
        dialog.getFooter().add(UiFactory.button("关闭", dialog::close));
        dialog.open();
    }

    // ------------------------------------------------------------------
    // 异步骨架与工具（与 DockerMgmtView 相同的规则）
    // ------------------------------------------------------------------

    private void setBusy(boolean busy, String text) {
        busyLabel.setText(StrUtil.emptyToDefault(text, ""));
        connectBtn.setEnabled(!busy);
    }

    private void runAsync(String label, ThrowingSupplier task, Consumer<Object> onDone, Consumer<Exception> onFailure) {
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
        }, "compose-" + label);
        thread.setDaemon(true);
        thread.start();
    }

    private void onComposeFailure(Exception e) {
        setBusy(false, "");
        if (e instanceof DockerDaemonDownException down && down.getStatus() != null) {
            Dialogs.warn(StrUtil.emptyToDefault(e.getMessage(), "docker 服务不可用"));
            return;
        }
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

    /** 偏好按登录用户隔离（AppSettingStore 的 kv 键后缀）。 */
    private static String currentUser() {
        return com.sl.security.CurrentUser.idOrSystemUser();
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        closeExecutor();
        super.onDetach(detachEvent);
    }

    private void closeExecutor() {
        if (executor != null) {
            executor.close();
            executor = null;
            service = null;
        }
        createBtn.setEnabled(false);
        baseDirCombo.setItems();
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        Object get() throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingTask {
        Object run() throws Exception;
    }
}
