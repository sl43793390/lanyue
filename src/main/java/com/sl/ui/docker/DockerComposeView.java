package com.sl.ui.docker;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sl.docker.ComposeService;
import com.sl.docker.DockerDaemonDownException;
import com.sl.docker.DockerExecutor;
import com.sl.docker.model.ComposeContainer;
import com.sl.docker.model.ComposeProject;
import com.sl.entity.ComposeProjectEntity;
import com.sl.entity.ConnectionInfo;
import com.sl.mapper.ComposeProjectMapper;
import com.sl.mapper.ConnectionInfoMapper;
import com.sl.ui.component.CodeEditor;
import com.sl.ui.component.Dialogs;
import com.sl.ui.component.UiFactory;
import com.sl.ui.component.ViewBase;
import com.sl.util.Constants;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Docker-Compose 管理。
 * <p>
 * 项目清单来自登记表 {@code compose_project}（所有用户创建过的项目，不按人过滤），
 * 不再按根目录扫描：连上目标机后读登记记录，运行状态（是否在跑 / 容器数）每次
 * 点「刷新」时现查目标机并就地补齐。外来项目（{@code docker compose ls} 发现、
 * 不是本系统创建的）同样会合并进列表，能看、能启停。
 * <p>
 * 对应旧项目的 {@code DockerComposeComponent}（项目列表）+ {@code ComposeProjectWindow}
 * 的一部分（项目级生命周期动作、配置文件查看与编辑）。与旧实现的差别：
 * <ul>
 *   <li>项目级操作（up / down / restart）与容器列表直接放在列表行内，不再先开一层项目窗口——
 *       日常「重启一个项目」「看看跑起来没有」这类高频操作少一次点击；</li>
 *   <li>compose 文件的编辑收进同一个弹窗（多文件切换），改完保存即写回目标机。</li>
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
    private final transient ComposeProjectMapper composeProjectMapper;
    private final transient ApplicationContext applicationContext;

    // 工具条字段一律不带组件内 label：用 UiFactory.fieldRow 的水平「标签+控件」组合，
    // 天然与相邻按钮上下对齐（组件内 label 的排版已在全局 CSS 里废弃）。
    private final com.vaadin.flow.component.combobox.ComboBox<ConnectionInfo> hostCombo =
            new com.vaadin.flow.component.combobox.ComboBox<>();
    /**
     * 项目根目录：只作为「新建项目」的默认存放位置（连接成功后预填目标机默认值），
     * 不再决定列表内容——列表跟这个目录没关系，跟登记表有关系。
     */
    private final TextField baseDirField = UiFactory.textField();
    private final Button connectBtn = UiFactory.primary("连接", this::connect);
    private final Button refreshBtn = UiFactory.button("刷新", this::reloadProjects);
    private final Button createBtn = UiFactory.button("新建项目", () -> openCreateDialog());
    private final Button importBtn = UiFactory.button("添加已有项目", this::openImportDialog);
    private final Span busyLabel = new Span();
    private final Span envLabel = new Span();
    private final Span statusLabel = new Span();

    private final Grid<ComposeProject> projectGrid = UiFactory.grid(ComposeProject.class);

    private List<ConnectionInfo> candidateHosts = new ArrayList<>();
    private ConnectionInfo presetHost;
    private boolean autoConnectPending;

    private transient DockerExecutor executor;
    private transient ComposeService service;

    public DockerComposeView(ConnectionInfoMapper connectionInfoMapper,
                             ComposeProjectMapper composeProjectMapper,
                             ApplicationContext applicationContext) {
        this.connectionInfoMapper = connectionInfoMapper;
        this.composeProjectMapper = composeProjectMapper;
        this.applicationContext = applicationContext;

        add(title("Docker-Compose 管理"));
        add(subtitle("展示所有用户创建过的 compose 项目，可启停、看容器、改配置。"
                + "运行状态点「刷新」现查目标机。页面关闭时 SSH 通道自动断开。"));

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
        baseDirField.setWidth("220px");
        baseDirField.setPlaceholder("新建项目根目录，留空用 ~/logviewer-compose");
        refreshBtn.setEnabled(false);
        createBtn.setEnabled(false);
        importBtn.setEnabled(false);

        HorizontalLayout row = new HorizontalLayout(
                UiFactory.fieldRow("目标服务器", "80px", hostCombo),
                UiFactory.fieldRow("项目根目录", "80px", baseDirField),
                connectBtn, refreshBtn, createBtn, importBtn, busyLabel);
        row.setClassName("view-toolbar");
        row.setAlignItems(FlexComponent.Alignment.CENTER);
        row.setFlexGrow(1, busyLabel);
        return row;
    }

    private void loadCandidateHosts() {
        List<ConnectionInfo> list = com.sl.ui.component.HostCandidates.load(connectionInfoMapper);
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
            refreshBtn.setEnabled(true);
            createBtn.setEnabled(true);
            importBtn.setEnabled(true);
            envLabel.setText("已连接 " + executor.hostLabel() + "　" + parts[2]);
            if (StrUtil.isBlank(baseDirField.getValue())) {
                baseDirField.setValue(StrUtil.nullToEmpty((String) parts[3]));
            }
            reloadProjects();
        }, e -> {
            setBusy(false, "");
            Dialogs.error("连接失败：" + StrUtil.emptyToDefault(e.getMessage(), e.getClass().getSimpleName()));
        });
    }

    // ------------------------------------------------------------------
    // 项目列表（登记表 + 目标机实时状态）
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
        // 空值渲染成 —：描述留空的项目/外来项目（没有创建人）整格空白看起来像列坏了
        projectGrid.addColumn(p -> StrUtil.emptyToDefault(p.getDescription(), "—"))
                .setHeader("描述").setAutoWidth(true);
        projectGrid.addColumn(p -> StrUtil.emptyToDefault(p.getCreatedBy(), "—"))
                .setHeader("创建人").setAutoWidth(true);
        projectGrid.addColumn(p -> p.isManaged() ? "平台管理" : "外部项目").setHeader("来源").setAutoWidth(true);
        projectGrid.addComponentColumn(this::buildRowActions).setHeader("操作").setAutoWidth(true);
    }

    private com.vaadin.flow.component.Component buildRowActions(ComposeProject project) {
        HorizontalLayout actions = new HorizontalLayout();
        actions.setSpacing(false);
        // 挂 row-actions 让操作列按钮统一走 styles.css 的间距与悬浮加深样式
        actions.addClassName("row-actions");

        actions.add(UiFactory.small("启动", () -> projectAction("启动项目 " + project.getName(), () -> service.up(project, null, false, false, true))));
        actions.add(UiFactory.small("重启", () -> projectAction("重启项目 " + project.getName(), () -> service.redeploy(project, false))));
        actions.add(UiFactory.small("停止", () -> confirmDown(project)));
        actions.add(UiFactory.small("容器", () -> showContainers(project)));
        actions.add(UiFactory.small("配置", () -> showFiles(project)));
        actions.add(UiFactory.rowIcon(com.vaadin.flow.component.icon.VaadinIcon.FOLDER_OPEN_O,
                "跳转到文件管理", () -> openFileMgmt(project)));

        Button delete = UiFactory.small("删除", () -> confirmDelete(project));
        delete.getElement().getThemeList().add("error");
        actions.add(delete);
        return actions;
    }

    /**
     * 刷新：读登记表里这台主机的全部项目（所有用户创建过的），再连一次目标机
     * 取运行状态（容器数 / 是否在跑），外来项目一并合并展示。
     */
    private void reloadProjects() {
        if (service == null) {
            Dialogs.warn("请先连接目标服务器");
            return;
        }
        String hostKey = executor.hostLabel();
        setBusy(true, "正在读取项目状态 …");
        runAsync("读取 compose 项目", () -> {
            List<ComposeProjectEntity> records = composeProjectMapper.selectList(
                    new QueryWrapper<ComposeProjectEntity>()
                            .eq("id_host", hostKey)
                            .orderByAsc("create_time"));
            List<ComposeProject> registered = new ArrayList<>();
            for (ComposeProjectEntity record : records) {
                if (record == null || StrUtil.isBlank(record.getName())) {
                    continue;
                }
                ComposeProject project = new ComposeProject();
                project.setName(record.getName());
                project.setDirectory(StrUtil.nullToEmpty(record.getCdDirectory()));
                project.setDescription(StrUtil.nullToEmpty(record.getCdDescription()));
                project.setManaged(true);
                project.setCreatedAt(StrUtil.nullToEmpty(record.getCreateTime()));
                project.setCreatedBy(StrUtil.nullToEmpty(record.getIdUser()));
                List<String> files = new ArrayList<>();
                for (String name : StrUtil.nullToEmpty(record.getCdFiles()).split(",")) {
                    if (StrUtil.isNotBlank(name)) {
                        files.add(name.trim());
                    }
                }
                if (files.isEmpty()) {
                    files.add(ComposeService.DEFAULT_FILE);
                }
                project.setFiles(files);
                registered.add(project);
            }
            return service.listWithStatus(registered);
        }, result -> {
            setBusy(false, "");
            @SuppressWarnings("unchecked")
            List<ComposeProject> list = (List<ComposeProject>) result;
            projectGrid.setItems(list);
            long running = list.stream().filter(p -> "运行中".equals(p.getStatusLabel())).count();
            statusLabel.setText("共 " + list.size() + " 个项目，运行中 " + running + " 个"
                    + "（状态更新于 " + new SimpleDateFormat("HH:mm:ss").format(new Date()) + "）");
            notifyIfReconnected();
        }, this::onComposeFailure);
    }

    /**
     * 页面开着超过 15 分钟时，共享连接池会把这条空闲 SSH 回收掉——以前这一步之后
     * 点「刷新」会直接报「SSH 连接未建立」，现在由 {@code SshCommandRunner} 自动重连，
     * 这里只补一句提示，让用户知道刚才发生过重连（不是网页卡了）。
     */
    private void notifyIfReconnected() {
        DockerExecutor current = executor;
        if (current != null && current.consumeReconnectNotice()) {
            Dialogs.info("SSH 连接空闲超时已断开，已自动重连到 " + current.hostLabel());
        }
    }

    /**
     * 新建 Compose 项目：弹窗里选模板/空白/上传 yml，创建成功后写入登记表并刷新列表。
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
        // 请求线程上先取好创建人：onCreated 回调在 ComposeCreateDialog 的后台线程
        // ui.access 里执行，那时再调 currentUser() 拿到的是 null（ThreadLocal）
        String userId = currentUser();
        new ComposeCreateDialog(service, StrUtil.trimToEmpty(baseDirField.getValue()), project -> {
            saveProjectRecord(project, userId);
            reloadProjects();
        }).open();
    }

    /**
     * 新建成功后写登记表；同主机同名（重复创建）就更新目录 / 文件 / 描述。
     * <p>
     * {@code userId} 必须由调用方在<b>请求线程</b>上取好传进来：本方法都从
     * {@code ui.access} 回调（后台线程）里被调，{@link com.sl.security.CurrentUser}
     * 读的 SecurityContextHolder 是 ThreadLocal，后台线程上取到的是 null
     * （2026-09-30 「写入 compose 项目登记失败 … CurrentUser.get() is null」即此因）。
     */
    private void saveProjectRecord(ComposeProject project, String userId) {
        try {
            String hostKey = executor.hostLabel();
            QueryWrapper<ComposeProjectEntity> wrapper = new QueryWrapper<ComposeProjectEntity>()
                    .eq("id_host", hostKey)
                    .eq("name", project.getName());
            ComposeProjectEntity entity = composeProjectMapper.selectOne(wrapper);
            if (entity == null) {
                entity = new ComposeProjectEntity();
                entity.setIdHost(hostKey);
                entity.setName(project.getName());
                entity.setIdUser(userId);
                entity.setCreateTime(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
                entity.setCdDirectory(project.getDirectory());
                entity.setCdFiles(String.join(",", project.getFiles()));
                entity.setCdDescription(StrUtil.nullToEmpty(project.getDescription()));
                composeProjectMapper.insert(entity);
            } else {
                entity.setCdDirectory(project.getDirectory());
                entity.setCdFiles(String.join(",", project.getFiles()));
                entity.setCdDescription(StrUtil.nullToEmpty(project.getDescription()));
                composeProjectMapper.update(entity, wrapper);
            }
        } catch (Exception e) {
            // 登记失败不拦创建：目标机上的项目已经建成了，只是列表暂时看不到
            log.warn("写入 compose 项目登记失败：{}", e.getMessage());
            Dialogs.warn("项目已创建，但写入登记表失败：" + reason(e));
        }
    }

    /**
     * 添加已有项目：目标机上已经存在一个带 docker-compose.yml 的目录，
     * 用户输入路径与描述，其余信息（项目名 = 目录名末段、配置文件清单）现查目标机获取，
     * 校验通过后写登记表，让该项目进入列表正常管理。
     */
    private void openImportDialog() {
        if (service == null) {
            Dialogs.warn("请先连接目标服务器");
            return;
        }
        if (!hasPermission(Constants.ADD)) {
            Dialogs.warn("权限不足，无法添加项目");
            return;
        }
        Dialog dialog = new Dialog();
        dialog.setWidth("700px");
        dialog.setHeaderTitle("添加已有项目");
        TextField dirField = UiFactory.textField("项目目录", "目标机上的绝对路径，如 /opt/my-app","500px");
        TextField descField = UiFactory.textField("项目描述", "可选，列表里能一眼认出来","500px");
        Span hint = new Span("添加前会检查该目录下是否存在 docker-compose.yml；"
                + "项目名取目录名的最后一段（自动转成 compose 合法字符）。");
        hint.getStyle().set("font-size", "var(--lumo-font-size-xs)");
        hint.getStyle().set("color", "var(--lumo-secondary-text-color)");
        hint.getStyle().set("overflow-wrap", "anywhere");
        VerticalLayout body = new VerticalLayout(dirField, descField, hint);
        body.setPadding(false);
        dialog.add(body);
        Button cancel = UiFactory.button("取消", dialog::close);
        Button add = UiFactory.primary("添加", () -> doImport(dialog, dirField.getValue(), descField.getValue()));
        dialog.getFooter().add(cancel, add);
        dialog.open();
    }

    /** 校验目录与 compose 文件在后台线程做；通过后关闭弹窗、写登记表并刷新列表。 */
    private void doImport(Dialog dialog, String rawDir, String description) {
        String dir = StrUtil.trimToEmpty(rawDir);
        while (dir.length() > 1 && dir.endsWith("/")) {
            dir = dir.substring(0, dir.length() - 1);
        }
        if (dir.isEmpty()) {
            Dialogs.warn("请输入项目目录");
            return;
        }
        String finalDir = dir;
        // 请求线程上先取好用户：下面的回调在后台线程 ui.access 里执行，
        // SecurityContextHolder（ThreadLocal）在那边是空的，currentUser() 会 NPE
        String userId = currentUser();
        setBusy(true, "检查项目目录 …");
        runAsync("检查已有项目", () -> {
            if (!service.directoryExists(finalDir)) {
                return new Object[]{false, "目标机上不存在目录 " + finalDir, null};
            }
            DockerExecutor.CmdResult check = executor.exec(
                    "[ -f " + DockerExecutor.q(finalDir + "/" + ComposeService.DEFAULT_FILE) + " ] && echo yes");
            if (!check.getOutput().contains("yes")) {
                return new Object[]{false,
                        "目录 " + finalDir + " 下没有找到 docker-compose.yml，请确认路径后再添加", null};
            }
            String name = composeNameFromDir(finalDir);
            try {
                ComposeService.checkName(name);
            } catch (Exception e) {
                return new Object[]{false, "从目录名推不出合法项目名：" + reason(e), null};
            }
            List<String> files = service.detectComposeFiles(finalDir);
            if (files.isEmpty()) {
                files = List.of(ComposeService.DEFAULT_FILE);
            }
            ComposeProject project = new ComposeProject();
            project.setName(name);
            project.setDirectory(finalDir);
            project.setDescription(StrUtil.trimToEmpty(description));
            project.setFiles(files);
            project.setManaged(true);
            return new Object[]{true, null, project};
        }, result -> {
            Object[] parts = (Object[]) result;
            if (!Boolean.TRUE.equals(parts[0])) {
                setBusy(false, "");
                Dialogs.warn((String) parts[1]);
                return;
            }
            ComposeProject project = (ComposeProject) parts[2];
            dialog.close();
            setBusy(false, "");
            saveProjectRecord(project, userId);
            Dialogs.success("已添加项目 " + project.getName() + "（" + project.getDirectory()
                    + "，配置文件：" + project.getFilesText() + "）");
            reloadProjects();
        }, e -> {
            setBusy(false, "");
            Dialogs.error("添加失败：" + reason(e));
        });
    }

    /** 目录名末段 → compose 项目名：小写、非法字符换短横线、掐掉首尾短横线，最长 39 位。 */
    private static String composeNameFromDir(String dir) {
        String seg = dir;
        int idx = seg.lastIndexOf('/');
        if (idx >= 0) {
            seg = seg.substring(idx + 1);
        }
        seg = seg.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-");
        while (seg.startsWith("-")) {
            seg = seg.substring(1);
        }
        while (seg.endsWith("-")) {
            seg = seg.substring(0, seg.length() - 1);
        }
        if (seg.length() > 39) {
            seg = seg.substring(0, 39);
        }
        return seg;
    }

    /** 删除成功后把登记记录一并清掉，不然列表里会留一行永远"未启动"的死项目。 */
    private void deleteProjectRecord(ComposeProject project) {
        try {
            composeProjectMapper.delete(new QueryWrapper<ComposeProjectEntity>()
                    .eq("id_host", executor.hostLabel())
                    .eq("name", project.getName()));
        } catch (Exception e) {
            log.warn("删除 compose 项目登记失败：{}", e.getMessage());
        }
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
                        deleteProjectRecord(project);
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

    /**
     * 操作列的文件夹图标：跳到该目标机的「文件管理」页，直接落在项目目录——
     * compose 弹窗只能编辑登记过的那几个 yml，改 .env、nginx conf 这类其它配置文件
     * 还得去文件管理页，跳过去默认打开项目目录省一次手动输路径。
     */
    private void openFileMgmt(ComposeProject project) {
        if (executor == null) {
            Dialogs.warn("请先连接目标服务器");
            return;
        }
        com.sl.ui.component.TabHost host = com.sl.ui.component.TabHost.current();
        if (host == null) {
            Dialogs.warn("当前页面不在主框架内，无法打开文件管理");
            return;
        }
        ConnectionInfo info = executor.getInfo();
        // 标题带项目名做去重键：每个项目各占一个文件管理标签（各自停在各自的目录），
        // 重复点同一项目的图标是切回旧标签，不会越开越多
        host.open("文件管理-" + info.getIdHost() + "-" + project.getName(), () -> {
            com.sl.ui.remote.RemoteFileView view =
                    applicationContext.getBean(com.sl.ui.remote.RemoteFileView.class);
            view.setPresetHost(info);
            view.setPresetDirectory(project.getDirectory());
            return view;
        });
    }

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
            grid.setSelectionMode(Grid.SelectionMode.NONE);
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
        dialog.setWidth("1100px");
        dialog.setHeight("750px");

        com.vaadin.flow.component.combobox.ComboBox<String> fileCombo =
                new com.vaadin.flow.component.combobox.ComboBox<>();
        fileCombo.setItems(files.keySet().toArray(new String[0]));
        fileCombo.setWidth("320px");
        fileCombo.setAllowCustomValue(false);

        // 项目统一的 CodeMirror 代码编辑器，语法模式跟着所选文件扩展名走
        CodeEditor editor = new CodeEditor();

        fileCombo.addValueChangeListener(e -> {
            editor.setMode(CodeEditor.suggestMode(e.getValue()));
            String content = files.get(e.getValue());
            editor.setValue(content == null ? "" : content);
        });
        // ComboBox 对"值没变"的 setValue 不发事件（ComposeCreateDialog.refreshFileCombo
        // 里同一条注释）：初始选中第一项时显式同步编辑器，不依赖监听器碰运气
        String first = files.keySet().iterator().next();
        fileCombo.setValue(first);
        editor.setMode(CodeEditor.suggestMode(first));
        String firstContent = files.get(first);
        editor.setValue(firstContent == null ? "" : firstContent);

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
        // 编辑器高度靠 flex-grow 撑（CodeEditor 的 host 是 display:block + min-height:0）；
        // 纵向 flex 里写 height:100% 不扣兄弟行高度，会把工具行顶出弹窗（ComposeCreateDialog 同坑）
        body.setDefaultHorizontalComponentAlignment(FlexComponent.Alignment.STRETCH);
        body.setFlexGrow(1, editor);
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

    /** 新建项目的创建人记登录用户；列表展示不按人隔离。 */
    private static String currentUser() {
        return com.sl.security.CurrentUser.get().getUserId();
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
        refreshBtn.setEnabled(false);
        createBtn.setEnabled(false);
        importBtn.setEnabled(false);
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
