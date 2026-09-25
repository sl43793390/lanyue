package com.sl.ui.local;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.sl.entity.ConnectionInfo;
import com.sl.entity.CommonProjectMgmt;
import com.sl.mapper.CommonProjectMgmtMapper;
import com.sl.ui.component.Dialogs;
import com.sl.ui.component.ViewBase;
import com.sl.ui.component.UiFactory;
import com.sl.util.Constants;
import com.sl.util.SSHClientUtil;
import com.sl.util.Util;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
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
 * 通用项目管理（本机）。
 * <p>
 * 对应旧项目 {@code com.so.component.management.CommonProjecttMgmtLocal}：
 * {@code common_project_mgmt} 表里 {@code id_host='localhost'} 的行。
 * 与 jar/Tomcat 管理不同，这一页不预设应用形态——启动/停止/重启/刷新/查状态
 * 五条命令都由用户自己配置（nginx、redis 这类没有统一脚本的工具就是为它准备的）。
 * <p>
 * 与旧实现的差异与修正：
 * <ul>
 *   <li>查询/删除/更新用 Wrapper 带全主键 {@code (id_host, id_project)}；</li>
 *   <li>状态判断修掉了恒为「已停止」的 bug：旧代码拿命令输出跟命令文本比
 *       （{@code res.contains(p.getCmdStatus())}），永远比不中，现在按脚本目录或项目名匹配；</li>
 *   <li>五条命令都是阻塞的本机 shell 调用，统一挪到后台线程；</li>
 *   <li>上传目标就是「脚本存放目录」本身——旧代码拼了个 {@code .../webapps}，
 *       是从 Tomcat 页复制来的，通用项目（如 nginx）没有这个子目录，已修正。</li>
 * </ul>
 */
@SpringComponent
@UIScope
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class CommonProjectView extends ViewBase {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(CommonProjectView.class);

    /** 为 null 时是本机模式（从菜单打开）；从服务器列表行内按钮进来则带目标机器，走 SSH */
    private transient ConnectionInfo presetHost;
    /** 远程模式的 SSH 连接：懒建（第一次执行命令时），页面关闭时断开 */
    private transient SSHClientUtil ssh;

    private final transient CommonProjectMgmtMapper projectMapper;

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

    /** 远程 SSH 连接（懒建）。可能被后台线程并发调用，加锁防重复建连。 */
    private synchronized SSHClientUtil ensureSsh() throws IOException {
        if (presetHost == null) {
            return null;
        }
        if (ssh == null) {
            ssh = SSHClientUtil.connect(presetHost);
        }
        return ssh;
    }

    /**
     * 统一的命令执行入口：本机走 {@link Util#executeNewFlow}（bash 交互式 stdin），
     * 远程走 SSH exec。多行命令在两边语义等价（顺序执行、共享工作目录）。
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

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        if (ssh != null) {
            ssh.closeConnection();
            ssh = null;
        }
        super.onDetach(detachEvent);
    }

    private final Grid<CommonProjectMgmt> grid = UiFactory.grid(CommonProjectMgmt.class);
    private final TextField nameField = UiFactory.textField();
    private final TextField tagField = UiFactory.textField();
    private final Span statusLabel = new Span();

    public CommonProjectView(CommonProjectMgmtMapper projectMapper) {
        this.projectMapper = projectMapper;

        add(title(remote() ? "通用项目管理（" + host() + "）" : "通用项目管理"));
        add(subtitle(remote()
                ? "管理 " + host() + " 上的任意服务：启动/停止/重启/刷新/查状态五条命令自行配置，命令通过 SSH 远程执行。"
                : "管理本机 localhost 上的任意服务：启动/停止/重启/刷新/查状态五条命令自行配置，"
                + "适合 nginx、redis 等没有统一脚本形态的工具。"));

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
        grid.addColumn(row -> StrUtil.nullToEmpty(row.getCdPath())).setHeader("脚本目录").setAutoWidth(true);
        grid.addComponentColumn(this::buildRowActions).setHeader("操作").setAutoWidth(true);
    }

    private Component buildRowActions(CommonProjectMgmt p) {
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
        Button startBtn = UiFactory.rowAction("启动", () -> confirmRun(p, p.getCmdStart(), "启动服务"));
        Button stopBtn = UiFactory.rowAction("停止", () -> confirmRun(p, p.getCmdStop(), "停止服务"));
        Button restartBtn = UiFactory.rowAction("重启", () -> confirmRun(p, p.getCmdRestart(), "重启服务"));
        Button refreshBtn = UiFactory.rowAction("刷新配置", () -> confirmRun(p, p.getCmdRefresh(), "刷新配置"));
        Button statusBtn = UiFactory.rowAction("查看状态", () -> checkStatus(p, startBtn, stopBtn));
        Button editBtn = UiFactory.rowAction("修改", () -> {
            if (!hasPermission(Constants.UPDATE)) {
                Dialogs.warn("权限不足，无法修改项目");
                return;
            }
            new ProjectDialog(p).open();
        });
        Button deleteBtn = UiFactory.rowDanger("删除", () -> confirmDelete(p));

        HorizontalLayout actions = new HorizontalLayout(startBtn, stopBtn, restartBtn, refreshBtn, statusBtn, editBtn, deleteBtn);
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
        QueryWrapper<CommonProjectMgmt> wrapper = new QueryWrapper<>();
        wrapper.eq("id_host", host());
        if (StrUtil.isNotBlank(nameField.getValue())) {
            wrapper.like("name_project", nameField.getValue().trim());
        }
        if (StrUtil.isNotBlank(tagField.getValue())) {
            wrapper.like("cd_tag", tagField.getValue().trim());
        }
        List<CommonProjectMgmt> rows = projectMapper.selectList(wrapper);
        // 预置的「默认项目」模板固定显示在第一行：内存对象不落库，
        // 所以每台主机（含远程模式）都有，也不会被搜索过滤掉
        List<CommonProjectMgmt> display = new ArrayList<>();
        display.add(buildDefaultTemplate());
        display.addAll(rows);
        grid.setItems(display);
        statusLabel.setText("共 " + rows.size() + " 个项目");
    }

    /**
     * 预置的默认项目模板：目录固定 /opt/tomcat，只提供「复制」。
     * 五条命令按 Tomcat 的目录形态预填（/opt/tomcat/bin 下的脚本），
     * 复制后按实际服务修改。
     */
    private static CommonProjectMgmt buildDefaultTemplate() {
        CommonProjectMgmt t = new CommonProjectMgmt();
        t.setNameProject("默认项目");
        t.setCdPath("/opt/tomcat");
        t.setCmdStart("./bin/startup.sh");
        t.setCmdStop("./bin/shutdown.sh");
        t.setCmdRestart("./bin/shutdown.sh; sleep 3; ./bin/startup.sh");
        t.setCmdStatus("ps -ef | grep tomcat");
        t.setCdTag("默认模板");
        t.setCdDescription("预置的默认项目模板（目录 /opt/tomcat）。点「复制」→ 弹窗修改 → 保存为你的项目。");
        // idHost 为 null 是模板行的标记：数据库里的行 idHost 必有值
        return t;
    }

    private boolean isTemplate(CommonProjectMgmt p) {
        return p.getIdHost() == null;
    }

    // ------------------------------------------------------------------
    // 命令执行
    // ------------------------------------------------------------------

    private void confirmRun(CommonProjectMgmt p, String cmd, String actionName) {
        if (StrUtil.isBlank(cmd)) {
            Dialogs.warn("没有配置" + actionName + "命令，请先在「修改」里补上");
            return;
        }
        Dialogs.confirm(actionName, "确认在 " + p.getCdPath() + " 下执行「" + cmd + "」吗？",
                () -> runInBackground(p, cmd, actionName + "命令已执行"));
    }

    /**
     * 阻塞的本机 shell 调用统一挪到后台线程。
     * 命令按「cd 目录; 命令」拼进同一个 shell 会话，与旧实现一致——
     * 很多启动脚本假设工作目录就是项目目录。
     */
    private void runInBackground(CommonProjectMgmt p, String cmd, String doneMsg) {
        log.info("执行命令[{}]：cd {} && {}", p.getIdProject(), p.getCdPath(), cmd);
        Dialogs.info("命令已提交，请稍候……");
        String path = p.getCdPath();
        getUI().ifPresent(ui -> new Thread(() -> {
            List<String> res = runShell(Arrays.asList("cd " + path + ";" + cmd));
            log.info("命令[{}]输出：{}", cmd, res);
            ui.access(() -> statusLabel.setText("[" + p.getIdProject() + "] " + doneMsg));
        }, "common-mgmt-" + p.getIdProject()).start());
    }

    /**
     * 状态检测：执行用户配置的 cmdStatus，在输出里找脚本目录或项目名。
     * <p>
     * 旧代码这里拿输出跟命令文本比（恒为 false，永远显示「已停止」），
     * 已改为匹配进程输出里真实会出现的内容。查完联动该行的启动/停止钮为状态展示。
     */
    private void checkStatus(CommonProjectMgmt p, Button startBtn, Button stopBtn) {
        if (StrUtil.isBlank(p.getCmdStatus())) {
            Dialogs.warn("没有配置查看状态命令，请先在「修改」里补上");
            return;
        }
        Dialogs.info("正在查询状态……");
        String binPath = StrUtil.removeSuffix(p.getCdPath(), "/");
        String name = StrUtil.nullToEmpty(p.getNameProject());
        getUI().ifPresent(ui -> new Thread(() -> {
            List<String> res = runShell(Arrays.asList(p.getCmdStatus()));
            boolean running = res.stream().anyMatch(line ->
                    line.contains(binPath) || (StrUtil.isNotBlank(name) && line.contains(name)));
            ui.access(() -> {
                if (running) {
                    Dialogs.success("[" + StrUtil.blankToDefault(name, p.getIdProject()) + "] 服务运行中");
                    UiFactory.markRowRunning(startBtn, stopBtn);
                } else {
                    Dialogs.warn("[" + StrUtil.blankToDefault(name, p.getIdProject()) + "] 服务已停止");
                    UiFactory.markRowStopped(startBtn, stopBtn);
                }
                statusLabel.setText("最近状态查询：" + StrUtil.blankToDefault(name, p.getIdProject())
                        + (running ? "（运行中）" : "（已停止）"));
            });
        }, "common-status-" + p.getIdProject()).start());
    }

    // ------------------------------------------------------------------
    // 删除 / 上传
    // ------------------------------------------------------------------

    private void confirmDelete(CommonProjectMgmt p) {
        if (!hasPermission(Constants.DELETE)) {
            Dialogs.warn("权限不足，无法删除项目");
            return;
        }
        Dialogs.confirmDanger("删除项目",
                "确认删除 " + p.getIdProject() + "（" + StrUtil.nullToDefault(p.getNameProject(), "未命名") + "）的记录吗？"
                        + "只删数据库记录，不会动磁盘上的文件。",
                "确认删除", () -> {
                    QueryWrapper<CommonProjectMgmt> wrapper = new QueryWrapper<>();
                    wrapper.eq("id_host", host()).eq("id_project", p.getIdProject());
                    projectMapper.delete(wrapper);
                    reload();
                    Dialogs.success("已删除 " + p.getIdProject());
                });
    }

    /** 上传目标就是「脚本存放目录」本身（不是 webapps——那是 Tomcat 页复制粘贴的遗留错误）。 */
    private Upload buildUpload(CommonProjectMgmt p) {
        String targetDir = StrUtil.removeSuffix(p.getCdPath(), "/");
        UploadHandler handler = remote()
                ? remoteUploadHandler(p.getIdProject(), targetDir)
                : localUploadHandler(targetDir);
        Upload upload = new Upload(handler);
        upload.setI18n(UiFactory.UPLOAD_I18N);
        upload.setDropAllowed(false);
        upload.setMaxFiles(1);
        upload.addAllFinishedListener(event -> upload.clearFileList());
        upload.addFileRejectedListener(event ->
                Dialogs.warn("上传被拒绝：" + StrUtil.emptyToDefault(event.getErrorMessage(), "不满足上传限制")));
        upload.getElement().setAttribute("title", "上传文件到 " + targetDir);
        return upload;
    }

    /** 本机模式：上传流直接写进脚本目录。 */
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
                        throw new IOException("脚本目录不存在：" + targetDir);
                    }
                    return new File(dir, UiFactory.safeFileName(metadata.fileName()));
                });
    }

    /** 远程模式：先落本机临时文件，再由后台线程 SFTP 推到远端脚本目录。 */
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
                    }, "common-upload-" + projectId).start());
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

    private class ProjectDialog extends Dialog {

        private final TextField idField = UiFactory.textField("项目ID","","500px");
        private final TextField nameField2 = UiFactory.textField("项目名称","","500px");
        private final TextField pathField = UiFactory.textField("脚本存放目录", "确保该目录脚本有执行权限","500px");
        private final TextField tagField2 = UiFactory.textField("tag","","500px");
        private final TextField cmdStart = UiFactory.textField("启动命令", "示例：sh server.sh start","500px");
        private final TextField cmdStop = UiFactory.textField("停止命令", "示例：sh server.sh stop","500px");
        private final TextField cmdRestart = UiFactory.textField("重启命令", "示例：sh server.sh restart","500px");
        private final TextField cmdRefresh = UiFactory.textField("刷新配置命令", "示例：sh server.sh refresh","500px");
        private final TextField cmdStatus = UiFactory.textField("查看状态命令", "示例：sh server.sh status","500px");
        private final TextField descField = UiFactory.textField("描述","","500px");

        ProjectDialog(CommonProjectMgmt existing) {
            this(existing, false);
        }

        /**
         * @param copyMode true = 从默认项目模板复制：预填模板内容，项目 ID 留空由用户填，
         *                 保存走 insert 生成一条新记录，不影响模板本身。
         */
        ProjectDialog(CommonProjectMgmt prefill, boolean copyMode) {
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
                pathField.setValue(StrUtil.nullToEmpty(prefill.getCdPath()));
                tagField2.setValue(StrUtil.nullToEmpty(prefill.getCdTag()));
                cmdStart.setValue(StrUtil.nullToEmpty(prefill.getCmdStart()));
                cmdStop.setValue(StrUtil.nullToEmpty(prefill.getCmdStop()));
                cmdRestart.setValue(StrUtil.nullToEmpty(prefill.getCmdRestart()));
                cmdRefresh.setValue(StrUtil.nullToEmpty(prefill.getCmdRefresh()));
                cmdStatus.setValue(StrUtil.nullToEmpty(prefill.getCmdStatus()));
                descField.setValue(StrUtil.nullToEmpty(prefill.getCdDescription()));
            }

            VerticalLayout form = new VerticalLayout(idField, nameField2, pathField, tagField2,
                    cmdStart, cmdStop, cmdRestart, cmdRefresh, cmdStatus, descField);
            form.setPadding(false);
            form.setSpacing(false);
            form.getStyle().set("gap", "8px");
            add(form);

            getFooter().add(Dialogs.cancelButton(this::close),
                    Dialogs.primaryButton("保存", () -> save(prefill != null && !copyMode)));
        }

        private void save(boolean update) {
            String id = StrUtil.trim(idField.getValue());
            String path = StrUtil.trim(pathField.getValue());
            if (StrUtil.isBlank(id) || StrUtil.isBlank(path)) {
                Dialogs.warn("项目ID、脚本存放目录不能为空！");
                return;
            }
            CommonProjectMgmt pro = new CommonProjectMgmt();
            pro.setIdHost(host());
            pro.setIdProject(id);
            pro.setNameProject(nameField2.getValue());
            pro.setCdPath(path);
            pro.setCdTag(tagField2.getValue());
            pro.setCmdStart(cmdStart.getValue());
            pro.setCmdStop(cmdStop.getValue());
            pro.setCmdRestart(cmdRestart.getValue());
            pro.setCmdRefresh(cmdRefresh.getValue());
            pro.setCmdStatus(cmdStatus.getValue());
            pro.setCdDescription(descField.getValue());

            try {
                if (!update) {
                    QueryWrapper<CommonProjectMgmt> check = new QueryWrapper<>();
                    check.eq("id_host", host()).eq("id_project", id);
                    if (projectMapper.selectOne(check) != null) {
                        Dialogs.warn("项目ID不能重复！");
                        return;
                    }
                    projectMapper.insert(pro);
                } else {
                    UpdateWrapper<CommonProjectMgmt> wrapper = new UpdateWrapper<>();
                    wrapper.eq("id_host", host()).eq("id_project", id);
                    projectMapper.update(pro, wrapper);
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
