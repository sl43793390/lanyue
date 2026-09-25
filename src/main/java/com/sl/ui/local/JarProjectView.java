package com.sl.ui.local;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.sl.entity.ConnectionInfo;
import com.sl.entity.ProjectList;
import com.sl.mapper.ProjectsMapper;
import com.sl.ui.component.Dialogs;
import com.sl.ui.component.ViewBase;
import com.sl.ui.component.UiFactory;
import com.sl.util.Constants;
import com.sl.util.SSHClientUtil;
import com.sl.util.SshConnectionPool;
import com.sl.util.Util;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
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
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * jar 项目管理（本机）。
 * <p>
 * 对应旧项目 {@code com.so.component.management.JarMgmtComponent}：
 * {@code projects} 表里 {@code id_host='localhost'} 的行，一行一个 jar 包，
 * 行内按钮负责启停、查状态、改配置、删记录、往项目目录传新包。
 * <p>
 */
@SpringComponent
@UIScope
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class JarProjectView extends ViewBase {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(JarProjectView.class);

    /** 为 null 时是本机模式（从菜单打开）；从服务器列表行内按钮进来则带目标机器，走 SSH */
    private transient ConnectionInfo presetHost;

    private final transient ProjectsMapper projectsMapper;

    public void setPresetHost(ConnectionInfo info) {
        this.presetHost = info;
    }

    /** 数据行的 id_host：本机固定 localhost，远程用目标主机名（与旧项目远程组件一致） */
    private String host() {
        return presetHost == null ? "localhost" : presetHost.getIdHost();
    }

    private boolean remote() {
        return presetHost != null;
    }

    /** 远程 SSH 连接：走共享连接池（懒建、跨页面复用，最后使用 10 分钟后池自动断开）。 */
    private synchronized SSHClientUtil ensureSsh() throws IOException {
        if (presetHost == null) {
            return null;
        }
        return SshConnectionPool.acquire(presetHost);
    }

    /**
     * 统一的命令执行入口：本机走 {@link Util#executeNewFlow}（bash 交互式 stdin），
     * 远程走 SSH exec。多行命令在两边语义等价（顺序执行、共享工作目录）。
     * 只用于输出行数有界的命令（启动/kill/ps 这类），长输出走 openCommandStream。
     * <p>
     * 兼容性口径：这里发出的命令（ps -ef | grep、kill -9、nohup、chmod、sh server.sh、
     * awk 取 PID）全部落在 POSIX sh + procps-ng 的公共交集，已在
     * CentOS 7.9 / RockyLinux 8 / RockyLinux 9 / Ubuntu 22.04 核对过，无发行版分支。
     * 新增命令时别用 systemctl、ss、pgrep 的差异化参数（见 server.sh 头注释）。
     */
    private List<String> runShell(List<String> commands) {
        if (!remote()) {
            return Util.executeNewFlow(commands);
        }
        try {
            String out = ensureSsh().executeCommand(String.join("; ", commands));
            return new ArrayList<>(Arrays.asList(out.split("\r?\n")));
        } catch (Exception e) {
            log.warn("远程命令执行失败：{}", e.getMessage());
            return List.of("[远程命令执行失败] " + e.getMessage());
        }
    }

    private final Grid<ProjectList> grid = UiFactory.grid(ProjectList.class);
    private final TextField nameField = UiFactory.textField();
    private final TextField tagField = UiFactory.textField();
    private final Span statusLabel = new Span();

    public JarProjectView(ProjectsMapper projectsMapper) {
        this.projectsMapper = projectsMapper;

        add(title(remote() ? "jar项目管理（" + host() + "）" : "jar项目管理"));
        add(subtitle(remote()
                ? "管理 " + host() + " 上的 jar 包：启停、查状态、修改配置、上传新包，命令通过 SSH 远程执行。"
                : "管理本机 localhost 上的 jar 包：启停、查状态、修改配置、上传新包。"
                + "启动/停止在本机 shell 执行，结果请到日志搜索界面查看。"));

        Button searchBtn = UiFactory.primary("搜索", this::reload);
        Button addBtn = UiFactory.button("添加项目", () -> {
            if (!hasPermission(Constants.ADD)) {
                Dialogs.warn("权限不足，无法添加项目");
                return;
            }
            new ProjectDialog(null).open();
        });
        HorizontalLayout bar = UiFactory.group(new Span("名称"), nameField, new Span("标签"), tagField, searchBtn, spacer(), statusLabel, addBtn);
        add(bar);

        buildGrid();
        VerticalLayout fill = fill(grid);
        add(fill);
        setFlexGrow(1, fill);
    }

    /**
     * 首刷放在 attach 而不是构造器：远程模式下「应用管理」页是先
     * {@code getBean}（构造器执行）再 {@code setPresetHost} 的，
     * 构造器里 reload 时 {@code host()} 还是 localhost，查出来必然是空列表，
     * 症状就是「重开应用管理只看到默认模板行，数据库里的项目全没了」。
     * attach 时 presetHost 必已注入（工厂先注入再挂树），此时查询才是真数据。
     */
    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        reload();
    }

    // ------------------------------------------------------------------
    // 表格
    // ------------------------------------------------------------------

    private void buildGrid() {
        grid.addColumn(row -> isTemplate(row) ? "（默认模板）" : StrUtil.nullToEmpty(row.getIdProject()))
                .setHeader("ID").setAutoWidth(true);
        grid.addColumn(row -> StrUtil.nullToEmpty(row.getCdTag())).setHeader("标签").setAutoWidth(true);
        grid.addColumn(row -> StrUtil.nullToEmpty(row.getNameProject())).setHeader("名称").setAutoWidth(true);
        grid.addColumn(row -> StrUtil.nullToEmpty(row.getCdDescription())).setHeader("描述").setAutoWidth(true);
        grid.addColumn(row -> StrUtil.nullToEmpty(row.getCdParentPath())).setHeader("所在路径").setAutoWidth(true);
        // 操作列必须固定宽 + 不参与压缩：autoWidth 只在首次渲染时测量，之后 reload
        // 进来的数据行比首次测量时宽（比如首刷只有模板行的「复制」），列宽不会重算，
        // 按钮就被裁掉一半；flexGrow 默认 1，窗口一窄它还会被等比压缩。实测内容宽 368px。
        grid.addComponentColumn(this::buildRowActions).setHeader("操作")
                .setAutoWidth(false).setWidth("400px").setFlexGrow(0);
    }

    private Component buildRowActions(ProjectList p) {
        if (isTemplate(p)) {
            // 默认项目模板行：只提供「复制」，复制后弹窗修改保存为新项目
            Button copyBtn = UiFactory.rowAction("复制", () -> {
                if (!hasPermission(Constants.ADD)) {
                    Dialogs.warn("权限不足，无法复制项目");
                    return;
                }
                new ProjectDialog(p, true).open();
            });
            HorizontalLayout actions = new HorizontalLayout(copyBtn);
            actions.setSpacing(false);
            actions.addClassName("row-actions");
            actions.setAlignItems(FlexComponent.Alignment.CENTER);
            return actions;
        }
        // 行内按钮统一浅色底 + 8px 间隔（.row-actions 容器样式，与免登录服务器列表一致）
        Button startBtn = UiFactory.rowAction("启动", () -> confirmStart(p));
        Button stopBtn = UiFactory.rowAction("停止", () -> confirmStop(p));
        Button statusBtn = UiFactory.rowAction("查看状态", () -> checkStatus(p, startBtn, stopBtn));
        Button editBtn = UiFactory.rowAction("修改", () -> {
            if (!hasPermission(Constants.UPDATE)) {
                Dialogs.warn("权限不足，无法修改项目");
                return;
            }
            new ProjectDialog(p).open();
        });
        Button deleteBtn = UiFactory.rowDanger("删除", () -> confirmDelete(p));

        HorizontalLayout actions = new HorizontalLayout(startBtn, stopBtn, statusBtn, editBtn, deleteBtn);
        actions.setSpacing(false);
        actions.addClassName("row-actions");
        actions.setAlignItems(FlexComponent.Alignment.CENTER);
        actions.add(buildUpload(p));
        return actions;
    }

    // ------------------------------------------------------------------
    // 数据
    // ------------------------------------------------------------------

    private void reload() {
        QueryWrapper<ProjectList> wrapper = new QueryWrapper<>();
        wrapper.eq("id_host", host());
        // TextField 空值是 "" 而不是 null，必须用 StrUtil 判
        if (StrUtil.isNotBlank(nameField.getValue())) {
            wrapper.like("name_project", nameField.getValue().trim());
        }
        if (StrUtil.isNotBlank(tagField.getValue())) {
            wrapper.like("cd_tag", tagField.getValue().trim());
        }
        List<ProjectList> rows = projectsMapper.selectList(wrapper);
        // 预置的「默认项目」模板固定显示在第一行：内存对象不落库，
        // 所以每台主机（含远程模式）都有，也不会被搜索过滤掉
        List<ProjectList> display = new ArrayList<>();
        display.add(buildDefaultTemplate());
        display.addAll(rows);
        grid.setItems(display);
        statusLabel.setText("共 " + rows.size() + " 个项目");
    }

    /** 预置的默认项目模板：目录固定 /opt/tomcat，只提供「复制」，复制后修改保存为新项目。 */
    private static ProjectList buildDefaultTemplate() {
        ProjectList t = new ProjectList();
        t.setNameProject("默认jar项目");
        t.setCdParentPath("/opt/tomcat");
        t.setCdTag("默认模板");
        t.setCdDescription("预置的默认项目模板（目录 /opt/tomcat）。点「复制」→ 弹窗修改 → 保存为你的项目。");
        // idHost 为 null 是模板行的标记：数据库里的行 idHost 必有值
        return t;
    }

    private boolean isTemplate(ProjectList p) {
        return p.getIdHost() == null;
    }

    // ------------------------------------------------------------------
    // 启动 / 停止 / 状态
    // ------------------------------------------------------------------

    private void confirmStart(ProjectList p) {
        Dialogs.confirm("启动服务", "确认在 " + p.getCdParentPath() + " 下启动 " + p.getNameProject() + " 吗？",
                () -> start(p));
    }

    /**
     * 启动命令解析（保持旧语义，注释里的两个坑必须跟着走）：
     * <ol>
     *   <li>配了 JVM 参数或 jar 参数 → 拼默认命令。JVM 参数必须写在 -jar 之前，
     *       否则会被 java 当成程序参数，JVM 选项不生效；</li>
     *   <li>配了自定义启动命令 → 原样执行；</li>
     *   <li>都没配 → 用项目目录下自动生成的 server.sh 启动。</li>
     * </ol>
     */
    private void start(ProjectList p) {
        String cmd;
        if (StrUtil.isNotBlank(p.getJvmParam()) || StrUtil.isNotBlank(p.getJarParam())) {
            cmd = buildStartCommand(p);
        } else if (StrUtil.isNotBlank(p.getCdCommand())) {
            cmd = p.getCdCommand().trim();
        } else {
            cmd = "chmod 777 server.sh;sh server.sh start " + StrUtil.nullToEmpty(p.getNameProject());
        }
        // server.sh 的生成/上传也是阻塞调用（远程要过 SFTP），一并挪进后台线程
        runInBackground(p, cmd, "启动命令已执行", true);
    }

    /**
     * 拼装默认的 jar 启动命令。
     * <p>
     * 重定向顺序必须是 {@code > app.log 2>&1}：写成 {@code 2>&1 > app.log} 时
     * stderr 仍指向旧的控制台，异常堆栈不会进日志文件。
     */
    private static String buildStartCommand(ProjectList p) {
        StringBuilder sb = new StringBuilder("nohup java");
        if (StrUtil.isNotBlank(p.getJvmParam())) {
            sb.append(" ").append(p.getJvmParam().trim());
        }
        sb.append(" -jar ").append(StrUtil.nullToEmpty(p.getNameProject()).trim());
        if (StrUtil.isNotBlank(p.getJarParam())) {
            sb.append(" ").append(p.getJarParam().trim());
        }
        sb.append(" > app.log 2>&1 &");
        return sb.toString();
    }

    /**
     * 项目目录下没有 server.sh 就生成一份并加执行权限。
     * 本机直接写文件；远程先探测远端有没有脚本，没有就经 SFTP 推过去：
     * exec 通道里没有 stdin 可喂，写远端文件只能走 SFTP（与旧项目一致）。
     */
    private void ensureServerScript(ProjectList p) throws Exception {
        String scriptPath = StrUtil.removeSuffix(p.getCdParentPath(), "/") + "/server.sh";
        if (remote()) {
            String exists = ensureSsh().executeCommand("[ -f " + scriptPath + " ] && echo Y || echo N");
            if (exists == null || !exists.contains("Y")) {
                File tmp = File.createTempFile("lanyue-server-sh", ".sh");
                try {
                    FileUtil.writeLines(Util.getConfigFileAsLineByClasspathResource("server.sh"), tmp, "utf-8");
                    ensureSsh().uploadFile(tmp.getAbsolutePath(), scriptPath, null);
                } finally {
                    tmp.delete();
                }
            }
        } else {
            File script = new File(p.getCdParentPath(), "server.sh");
            if (!script.exists()) {
                log.info("生成默认启动脚本 {}", script.getAbsolutePath());
                FileUtil.writeLines(Util.getConfigFileAsLineByClasspathResource("server.sh"), script, "utf-8");
            }
        }
        runShell(Arrays.asList("cd " + p.getCdParentPath(), "chmod 755 server.sh"));
    }

    private void confirmStop(ProjectList p) {
        Dialogs.confirmDanger("停止服务",
                "将对 " + p.getNameProject() + " 执行 kill -9（按进程名匹配），确认停止吗？",
                "确认停止", () ->
                        runInBackground(p,
                                "kill -9 `ps -ef | grep " + p.getNameProject() + " | grep -v grep | awk '{print $2}'`",
                                "停止命令已执行"));
    }

    /**
     * 状态检测：查完除弹窗提示外，还把该行的启动/停止钮联动成状态展示——
     * 运行中 → 启动钮变绿显示「运行中」；已停止 → 停止钮变红显示「已停止」。
     */
    private void checkStatus(ProjectList p, Button startBtn, Button stopBtn) {
        Dialogs.info("正在查询状态……");
        String projectName = StrUtil.nullToEmpty(p.getNameProject());
        String parentPath = p.getCdParentPath();
        getUI().ifPresent(ui -> new Thread(() -> {
            List<String> res = runShell(Arrays.asList("cd " + parentPath, "ps -ef | grep java"));
            boolean running = res.stream().anyMatch(line -> line.contains(projectName));
            ui.access(() -> {
                if (running) {
                    Dialogs.success("[" + projectName + "] 服务运行中");
                    UiFactory.markRowRunning(startBtn, stopBtn);
                } else {
                    Dialogs.warn("[" + projectName + "] 服务已停止，请到日志搜索界面查看日志");
                    UiFactory.markRowStopped(startBtn, stopBtn);
                }
                statusLabel.setText("最近状态查询：" + projectName + (running ? "（运行中）" : "（已停止）"));
            });
        }, "jar-status-" + projectName).start());
    }

    /**
     * 阻塞的本机 shell 调用统一挪到后台线程。
     * {@code executeNewFlow} 要等命令跑完才返回，放 UI 线程会把整个页面卡死
     * （启动脚本里若有阻塞式等待，页面就永远转圈）。
     */
    private void runInBackground(ProjectList p, String cmd, String doneMsg) {
        runInBackground(p, cmd, doneMsg, false);
    }

    private void runInBackground(ProjectList p, String cmd, String doneMsg, boolean ensureScript) {
        log.info("执行命令[{}]：cd {} && {}", p.getIdProject(), p.getCdParentPath(), cmd);
        Dialogs.info("命令已提交，请稍候……");
        String parentPath = p.getCdParentPath();
        getUI().ifPresent(ui -> new Thread(() -> {
            if (ensureScript) {
                try {
                    ensureServerScript(p);
                } catch (Exception e) {
                    log.warn("准备 server.sh 失败：{}", e.getMessage());
                }
            }
            List<String> res = runShell(Arrays.asList("cd " + parentPath, cmd));
            log.info("命令[{}]输出：{}", cmd, res);
            ui.access(() -> statusLabel.setText("[" + p.getIdProject() + "] " + doneMsg));
        }, "jar-mgmt-" + p.getIdProject()).start());
    }

    // ------------------------------------------------------------------
    // 删除 / 上传
    // ------------------------------------------------------------------

    private void confirmDelete(ProjectList p) {
        if (!hasPermission(Constants.DELETE)) {
            Dialogs.warn("权限不足，无法删除项目");
            return;
        }
        Dialogs.confirmDanger("删除项目",
                "确认删除 " + p.getIdProject() + "（" + StrUtil.nullToDefault(p.getNameProject(), "未命名") + "）的记录吗？"
                        + "只删数据库记录，不会动磁盘上的 jar 包。",
                "确认删除", () -> {
                    // 复合主键 (id_host, id_project) 两列都要带上，只按 id_project 删会带走其他主机上的同 id 项目
                    QueryWrapper<ProjectList> wrapper = new QueryWrapper<>();
                    wrapper.eq("id_host", host()).eq("id_project", p.getIdProject());
                    projectsMapper.delete(wrapper);
                    reload();
                    Dialogs.success("已删除 " + p.getIdProject());
                });
    }

    /**
     * 行内上传控件：传到该行项目的目录里。
     * 权限与目标目录校验写在 FileFactory 里——它在<b>上传请求线程</b>执行，
     * 是真正的服务端校验；「不渲染控件」只是不给入口，请求本身可以构造。
     */
    private Upload buildUpload(ProjectList p) {
        String targetDir = StrUtil.removeSuffix(p.getCdParentPath(), "/");
        UploadHandler handler = remote()
                ? remoteUploadHandler(p.getIdProject(), targetDir)
                : localUploadHandler(targetDir);
        Upload upload = new Upload(handler);
        upload.setI18n(UiFactory.UPLOAD_I18N);
        upload.setDropAllowed(false);
        upload.setMaxFiles(1);
        // 不清文件列表 → maxFilesReached → 按钮永久置灰，同一行传不了第二个文件
        upload.addAllFinishedListener(event -> upload.clearFileList());
        upload.addFileRejectedListener(event ->
                Dialogs.warn("上传被拒绝：" + StrUtil.emptyToDefault(event.getErrorMessage(), "不满足上传限制")));
        upload.getElement().setAttribute("title", "上传 jar 包/脚本到 " + targetDir);
        return upload;
    }

    /** 本机模式：上传流直接写进项目目录（权限校验在 FileFactory 里，请求可被构造）。 */
    private UploadHandler localUploadHandler(String targetDir) {
        return UploadHandler.toFile(
                (metadata, file) -> getUI().ifPresent(ui -> ui.access(() -> {
                    statusLabel.setText("已上传 " + file.getName() + " → " + targetDir);
                    Dialogs.success("已上传到 " + targetDir + "：" + file.getName());
                })),
                metadata -> {
                    if (!hasPermission(Constants.UPLOAD)) {
                        throw new IOException("权限不足，禁止上传");
                    }
                    File dir = new File(targetDir);
                    if (!dir.isDirectory()) {
                        throw new IOException("项目目录不存在：" + targetDir);
                    }
                    return new File(dir, UiFactory.safeFileName(metadata.fileName()));
                });
    }

    /**
     * 远程模式：Upload 的流必须先落在本地文件系统，落完由后台线程 SFTP 推到远端，
     * 推完删临时文件。FileFactory 在上传请求线程执行，权限校验同样在这一层拦。
     */
    private UploadHandler remoteUploadHandler(String projectId, String targetDir) {
        return UploadHandler.toFile(
                (metadata, file) -> {
                    String fileName = UiFactory.safeFileName(metadata.fileName());
                    getUI().ifPresent(ui -> new Thread(() -> {
                        String failure = null;
                        try {
                            ensureSsh().uploadFile(file.getAbsolutePath(), targetDir + "/" + fileName, null);
                        } catch (Exception e) {
                            failure = e.getMessage();
                        } finally {
                            file.delete();
                        }
                        String finalFailure = failure;
                        ui.access(() -> {
                            if (finalFailure == null) {
                                statusLabel.setText("已上传 " + fileName + " → " + host() + ":" + targetDir);
                                Dialogs.success("已上传到 " + host() + ":" + targetDir + "：" + fileName);
                            } else {
                                Dialogs.error("上传失败：" + StrUtil.emptyToDefault(finalFailure, "未知错误"));
                            }
                        });
                    }, "jar-upload-" + projectId).start());
                },
                metadata -> {
                    if (!hasPermission(Constants.UPLOAD)) {
                        throw new IOException("权限不足，禁止上传");
                    }
                    return File.createTempFile("lanyue-upload-", "-" + UiFactory.safeFileName(metadata.fileName()));
                });
    }

    // ------------------------------------------------------------------
    // 添加 / 修改弹窗
    // ------------------------------------------------------------------

    /** 新建时 existing 为 null；编辑时 idProject 置灰不可改（它是主键的一部分）。 */
    private class ProjectDialog extends Dialog {

        private final TextField idField = UiFactory.textField("项目ID", "如 order-service","500px");
        private final TextField nameField2 = UiFactory.textField("项目名称", "jar/war 包名称：xxx.jar","500px");
        private final TextField pathField = UiFactory.textField("项目所在路径", "不含 jar 包名称","500px");
        private final TextField tagField2 = UiFactory.textField("项目tag", "用于对项目进行分类","500px");
        private final TextField cmdField = UiFactory.textField("启动命令", "自定义启动命令，可为空","500px");
        private final TextArea jvmArea = UiFactory.textArea("JVM参数","500px");
        private final TextField jarParamField = UiFactory.textField("jar包参数", "跟在 jar 包名后面的参数","500px");
        private final TextField descField = UiFactory.textField("项目描述","","500px");

        ProjectDialog(ProjectList existing) {
            this(existing, false);
        }

        /**
         * @param copyMode true = 从默认项目模板复制：预填模板内容，项目 ID 留空由用户填，
         *                 保存走 insert 生成一条新记录，不影响模板本身。
         */
        ProjectDialog(ProjectList prefill, boolean copyMode) {
            setHeaderTitle(copyMode ? "复制默认项目" : (prefill == null ? "添加项目" : "修改项目"));
            setWidth("700px");

            if (prefill != null) {
                if (copyMode) {
                    idField.setPlaceholder("请输入新项目ID");
                } else {
                    idField.setValue(StrUtil.nullToEmpty(prefill.getIdProject()));
                    idField.setEnabled(false);
                }
                nameField2.setValue(StrUtil.nullToEmpty(prefill.getNameProject()));
                pathField.setValue(StrUtil.nullToEmpty(prefill.getCdParentPath()));
                tagField2.setValue(StrUtil.nullToEmpty(prefill.getCdTag()));
                cmdField.setValue(StrUtil.nullToEmpty(prefill.getCdCommand()));
                jvmArea.setValue(StrUtil.nullToEmpty(prefill.getJvmParam()));
                jarParamField.setValue(StrUtil.nullToEmpty(prefill.getJarParam()));
                descField.setValue(StrUtil.nullToEmpty(prefill.getCdDescription()));
            }

            VerticalLayout form = new VerticalLayout(
                    idField, nameField2, pathField, tagField2, cmdField, jvmArea, jarParamField, descField);
            form.setPadding(false);
            form.setSpacing(false);
            form.getStyle().set("gap", "10px");
            add(form);

            getFooter().add(Dialogs.cancelButton(this::close),
                    Dialogs.primaryButton("保存", () -> save(prefill != null && !copyMode)));
        }

        private void save(boolean update) {
            String id = StrUtil.trim(idField.getValue());
            String path = StrUtil.trim(pathField.getValue());
            if (StrUtil.isBlank(id) || StrUtil.isBlank(path)) {
                Dialogs.warn("项目ID、项目所在路径不能为空！");
                return;
            }
            ProjectList pro = new ProjectList();
            pro.setIdHost(host());
            pro.setIdProject(id);
            pro.setNameProject(nameField2.getValue());
            pro.setCdParentPath(path);
            pro.setCdTag(tagField2.getValue());
            pro.setCdCommand(cmdField.getValue());
            pro.setJvmParam(jvmArea.getValue());
            pro.setJarParam(jarParamField.getValue());
            pro.setCdDescription(descField.getValue());

            try {
                if (!update) {
                    // 主键是否重复必须先查一步，直接 insert 会抛原始的主键冲突异常
                    QueryWrapper<ProjectList> check = new QueryWrapper<>();
                    check.eq("id_host", host()).eq("id_project", id);
                    if (projectsMapper.selectOne(check) != null) {
                        Dialogs.warn("项目ID不能重复！");
                        return;
                    }
                    projectsMapper.insert(pro);
                } else {
                    UpdateWrapper<ProjectList> wrapper = new UpdateWrapper<>();
                    wrapper.eq("id_host", host()).eq("id_project", id);
                    projectsMapper.update(pro, wrapper);
                }
            } catch (Exception e) {
                log.error("保存项目失败", e);
                Dialogs.error("保存失败：" + e.getMessage());
                return;
            }
            close();
            reload();
            Dialogs.success("保存成功");
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
