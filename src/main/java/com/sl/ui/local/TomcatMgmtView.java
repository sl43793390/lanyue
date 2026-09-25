package com.sl.ui.local;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.sl.entity.ConnectionInfo;
import com.sl.entity.TomcatInfoEntity;
import com.sl.mapper.TomcatInfoMapper;
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
 * Tomcat 管理（本机）。
 * <p>
 * 对应旧项目 {@code com.so.component.management.TomcatListComponent}：
 * {@code tomcat_info} 表里 {@code id_host='localhost'} 的行，一行一个 Tomcat 实例，
 * 行内按钮执行 {@code bin/startup.sh} / {@code bin/shutdown.sh}、按进程命令行查状态、
 * 改配置、删记录、往 {@code webapps} 目录传 war 包。
 * <p>
 * 与旧实现的差异与修正：
 * <ul>
 *   <li>查询/删除全部用 Wrapper 带全主键 {@code (id_host, tomcat_id)}；</li>
 *   <li>旧的「修改」是 delete + insert 两步，中间失败会把记录弄丢，这里改成一条 UPDATE；</li>
 *   <li>启停/查状态挪到后台线程（{@code startup.sh} 会阻塞到 Tomcat 起完，UI 线程等不起）；</li>
 *   <li>上传权限校验放进 FileFactory（上传请求线程里真正拦一道）。</li>
 * </ul>
 */
@SpringComponent
@UIScope
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class TomcatMgmtView extends ViewBase {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(TomcatMgmtView.class);

    /** 为 null 时是本机模式（从菜单打开）；从服务器列表行内按钮进来则带目标机器，走 SSH */
    private transient ConnectionInfo presetHost;
    /** 远程模式的 SSH 连接：懒建（第一次执行命令时），页面关闭时断开 */
    private transient SSHClientUtil ssh;

    private final transient TomcatInfoMapper tomcatInfoMapper;

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

    private final Grid<TomcatInfoEntity> grid = UiFactory.grid(TomcatInfoEntity.class);
    private final TextField nameField = UiFactory.textField();
    private final TextField tagField = UiFactory.textField();
    private final Span statusLabel = new Span();

    public TomcatMgmtView(TomcatInfoMapper tomcatInfoMapper) {
        this.tomcatInfoMapper = tomcatInfoMapper;

        add(title(remote() ? "Tomcat管理（" + host() + "）" : "Tomcat管理"));
        add(subtitle(remote()
                ? "管理 " + host() + " 上的 Tomcat 实例：启停、查状态、修改配置、上传 war 包，命令通过 SSH 远程执行。"
                : "管理本机 localhost 上的 Tomcat 实例：启停、查状态、修改配置、上传 war 包。"
                + "Tomcat 主目录下需包含 bin、webapps、conf、lib 等目录。"));

        Button searchBtn = UiFactory.primary("搜索", this::reload);
        Button addBtn = UiFactory.button("添加实例", () -> {
            if (!hasPermission(Constants.ADD)) {
                Dialogs.warn("权限不足，无法添加实例");
                return;
            }
            new TomcatDialog(null).open();
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
        grid.addColumn(row -> isTemplate(row) ? "（默认模板）" : StrUtil.nullToEmpty(row.getTomcatId()))
                .setHeader("ID").setAutoWidth(true);
        grid.addColumn(row -> StrUtil.nullToEmpty(row.getTag())).setHeader("标签").setAutoWidth(true);
        grid.addColumn(row -> StrUtil.nullToEmpty(row.getNameTomcat())).setHeader("名称").setAutoWidth(true);
        grid.addColumn(row -> StrUtil.nullToEmpty(row.getCdDescription())).setHeader("描述").setAutoWidth(true);
        grid.addColumn(row -> StrUtil.nullToEmpty(row.getTomcatPath())).setHeader("主目录").setAutoWidth(true);
        grid.addComponentColumn(this::buildRowActions).setHeader("操作").setAutoWidth(true);
    }

    private Component buildRowActions(TomcatInfoEntity t) {
        if (isTemplate(t)) {
            // 默认项目模板行：只提供「复制」，复制后弹窗修改保存为新实例
            Button copyBtn = UiFactory.rowAction("复制", () -> {
                if (!hasPermission(Constants.ADD)) {
                    Dialogs.warn("权限不足，无法复制实例");
                    return;
                }
                new TomcatDialog(t, true).open();
            });
            HorizontalLayout actions = new HorizontalLayout(copyBtn);
            actions.setSpacing(false);
            actions.addClassName("row-actions");
            actions.setAlignItems(FlexComponent.Alignment.CENTER);
            return actions;
        }
        // 行内按钮统一浅色底 + 8px 间隔（.row-actions 容器样式，与免登录服务器列表一致）
        Button startBtn = UiFactory.rowAction("启动", () -> confirmStart(t));
        Button stopBtn = UiFactory.rowAction("停止", () -> confirmStop(t));
        Button statusBtn = UiFactory.rowAction("查看状态", () -> checkStatus(t, startBtn, stopBtn));
        Button editBtn = UiFactory.rowAction("修改", () -> {
            if (!hasPermission(Constants.UPDATE)) {
                Dialogs.warn("权限不足，无法修改实例");
                return;
            }
            new TomcatDialog(t).open();
        });
        Button deleteBtn = UiFactory.rowDanger("删除", () -> confirmDelete(t));

        HorizontalLayout actions = new HorizontalLayout(startBtn, stopBtn, statusBtn, editBtn, deleteBtn);
        actions.setSpacing(false);
        actions.addClassName("row-actions");
        actions.setAlignItems(FlexComponent.Alignment.CENTER);
        actions.add(buildUpload(t));
        return actions;
    }

    // ------------------------------------------------------------------
    // 数据
    // ------------------------------------------------------------------

    private void reload() {
        QueryWrapper<TomcatInfoEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("id_host", host());
        if (StrUtil.isNotBlank(nameField.getValue())) {
            wrapper.like("name_tomcat", nameField.getValue().trim());
        }
        if (StrUtil.isNotBlank(tagField.getValue())) {
            wrapper.like("tag", tagField.getValue().trim());
        }
        List<TomcatInfoEntity> rows = tomcatInfoMapper.selectList(wrapper);
        // 预置的「默认项目」模板固定显示在第一行：内存对象不落库，
        // 所以每台主机（含远程模式）都有，也不会被搜索过滤掉
        List<TomcatInfoEntity> display = new ArrayList<>();
        display.add(buildDefaultTemplate());
        display.addAll(rows);
        grid.setItems(display);
        statusLabel.setText("共 " + rows.size() + " 个实例");
    }

    /** 预置的默认项目模板：主目录固定 /opt/tomcat，只提供「复制」，复制后修改保存为新实例。 */
    private static TomcatInfoEntity buildDefaultTemplate() {
        TomcatInfoEntity t = new TomcatInfoEntity();
        t.setNameTomcat("默认Tomcat实例");
        t.setTomcatPath("/opt/tomcat");
        t.setTag("默认模板");
        t.setCdDescription("预置的默认项目模板（主目录 /opt/tomcat）。点「复制」→ 弹窗修改 → 保存为你的实例。");
        // idHost 为 null 是模板行的标记：数据库里的行 idHost 必有值
        return t;
    }

    private boolean isTemplate(TomcatInfoEntity t) {
        return t.getIdHost() == null;
    }

    // ------------------------------------------------------------------
    // 启动 / 停止 / 状态
    // ------------------------------------------------------------------

    private void confirmStart(TomcatInfoEntity t) {
        Dialogs.confirm("启动 Tomcat", "确认在 " + t.getTomcatPath() + " 下执行 bin/startup.sh 吗？",
                () -> runInBackground(t, "./bin/startup.sh", "启动命令已执行"));
    }

    private void confirmStop(TomcatInfoEntity t) {
        Dialogs.confirmDanger("停止 Tomcat",
                "确认在 " + t.getTomcatPath() + " 下执行 bin/shutdown.sh 吗？",
                "确认停止", () -> runInBackground(t, "./bin/shutdown.sh", "停止命令已执行"));
    }

    /**
     * 状态检测：从进程命令行里找 Tomcat 主目录（catalina 启动后命令行里带 catalina.home，
     * 与主目录一致，所以「命令行包含主目录路径」就能断定在跑）。
     * 查完联动该行的启动/停止钮为状态展示。
     */
    private void checkStatus(TomcatInfoEntity t, Button startBtn, Button stopBtn) {
        Dialogs.info("正在查询状态……");
        String binPath = StrUtil.removeSuffix(t.getTomcatPath(), "/");
        String name = StrUtil.blankToDefault(t.getNameTomcat(), t.getTomcatId());
        getUI().ifPresent(ui -> new Thread(() -> {
            List<String> res = runShell(Arrays.asList("ps -ef | grep java"));
            boolean running = res.stream().anyMatch(line -> line.contains(binPath));
            ui.access(() -> {
                if (running) {
                    Dialogs.success("[" + name + "] 服务运行中");
                    UiFactory.markRowRunning(startBtn, stopBtn);
                } else {
                    Dialogs.warn("[" + name + "] 服务已停止，请到日志搜索界面查看日志");
                    UiFactory.markRowStopped(startBtn, stopBtn);
                }
                statusLabel.setText("最近状态查询：" + name + (running ? "（运行中）" : "（已停止）"));
            });
        }, "tomcat-status-" + t.getTomcatId()).start());
    }

    /** 阻塞的本机 shell 调用统一挪到后台线程，回 UI 线程只做提示。 */
    private void runInBackground(TomcatInfoEntity t, String cmd, String doneMsg) {
        log.info("执行命令[{}]：cd {} && {}", t.getTomcatId(), t.getTomcatPath(), cmd);
        Dialogs.info("命令已提交，请稍候……");
        String path = t.getTomcatPath();
        getUI().ifPresent(ui -> new Thread(() -> {
            List<String> res = runShell(Arrays.asList("cd " + path, cmd));
            log.info("命令[{}]输出：{}", cmd, res);
            ui.access(() -> statusLabel.setText("[" + t.getTomcatId() + "] " + doneMsg));
        }, "tomcat-mgmt-" + t.getTomcatId()).start());
    }

    // ------------------------------------------------------------------
    // 删除 / 上传
    // ------------------------------------------------------------------

    private void confirmDelete(TomcatInfoEntity t) {
        if (!hasPermission(Constants.DELETE)) {
            Dialogs.warn("权限不足，无法删除实例");
            return;
        }
        Dialogs.confirmDanger("删除实例",
                "确认删除 " + t.getTomcatId() + "（" + StrUtil.nullToDefault(t.getNameTomcat(), "未命名") + "）的记录吗？"
                        + "只删数据库记录，不会动磁盘上的 Tomcat 目录。",
                "确认删除", () -> {
                    QueryWrapper<TomcatInfoEntity> wrapper = new QueryWrapper<>();
                    wrapper.eq("id_host", host()).eq("tomcat_id", t.getTomcatId());
                    tomcatInfoMapper.delete(wrapper);
                    reload();
                    Dialogs.success("已删除 " + t.getTomcatId());
                });
    }

    /** war 包上传到该行实例的 webapps 目录（本机模式目录不存在时创建；远程模式走 SFTP）。 */
    private Upload buildUpload(TomcatInfoEntity t) {
        // 远程路径必须用正斜杠：File.separator 在 Windows 服务上是反斜杠，SFTP 不认
        String targetDir = StrUtil.removeSuffix(t.getTomcatPath(), "/")
                + (remote() ? "/webapps" : File.separator + "webapps");
        UploadHandler handler = remote()
                ? remoteUploadHandler(t.getTomcatId(), targetDir)
                : localUploadHandler(targetDir);
        Upload upload = new Upload(handler);
        upload.setI18n(UiFactory.UPLOAD_I18N);
        upload.setDropAllowed(false);
        upload.setMaxFiles(1);
        upload.addAllFinishedListener(event -> upload.clearFileList());
        upload.addFileRejectedListener(event ->
                Dialogs.warn("上传被拒绝：" + StrUtil.emptyToDefault(event.getErrorMessage(), "不满足上传限制")));
        upload.getElement().setAttribute("title", "上传 war 包到 " + targetDir);
        return upload;
    }

    /** 本机模式：上传流直接写进 webapps 目录。 */
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
                    if (!dir.isDirectory() && !dir.mkdirs()) {
                        throw new IOException("webapps 目录不存在且创建失败：" + targetDir);
                    }
                    return new File(dir, UiFactory.safeFileName(metadata.fileName()));
                });
    }

    /** 远程模式：先落本机临时文件，再由后台线程 SFTP 推到远端 webapps。 */
    private UploadHandler remoteUploadHandler(String tomcatId, String targetDir) {
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
                    }, "tomcat-upload-" + tomcatId).start());
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

    private class TomcatDialog extends Dialog {

        private final TextField idField = UiFactory.textField("实例ID","","500px");
        private final TextField nameField2 = UiFactory.textField("Tomcat名称", "可以为空","500px");
        private final TextField pathField = UiFactory.textField("Tomcat的主目录", "需包含 bin、webapps、conf、lib","500px");
        private final TextField tagField2 = UiFactory.textField("tag","","500px");
        private final TextField webappField = UiFactory.textField("webapps目录", "可以为空，上传 war 默认进主目录/webapps","500px");
        private final TextField descField = UiFactory.textField("描述","","500px");

        TomcatDialog(TomcatInfoEntity existing) {
            this(existing, false);
        }

        /**
         * @param copyMode true = 从默认项目模板复制：预填模板内容，实例 ID 留空由用户填，
         *                 保存走 insert 生成一条新记录，不影响模板本身。
         */
        TomcatDialog(TomcatInfoEntity prefill, boolean copyMode) {
            setHeaderTitle(copyMode ? "复制默认项目" : (prefill == null ? "添加实例" : "修改实例"));
            setWidth("700px");

            if (prefill != null) {
                if (copyMode) {
                    idField.setPlaceholder("请输入新实例ID");
                } else {
                    idField.setValue(StrUtil.nullToEmpty(prefill.getTomcatId()));
                    idField.setEnabled(false);
                }
                nameField2.setValue(StrUtil.nullToEmpty(prefill.getNameTomcat()));
                pathField.setValue(StrUtil.nullToEmpty(prefill.getTomcatPath()));
                tagField2.setValue(StrUtil.nullToEmpty(prefill.getTag()));
                webappField.setValue(StrUtil.nullToEmpty(prefill.getWebappPath()));
                descField.setValue(StrUtil.nullToEmpty(prefill.getCdDescription()));
            }

            VerticalLayout form = new VerticalLayout(idField, nameField2, pathField, tagField2, webappField, descField);
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
                Dialogs.warn("实例ID、Tomcat主目录不能为空！");
                return;
            }
            TomcatInfoEntity pro = new TomcatInfoEntity();
            pro.setIdHost(host());
            pro.setTomcatId(id);
            pro.setNameTomcat(nameField2.getValue());
            pro.setTomcatPath(path);
            pro.setTag(tagField2.getValue());
            pro.setWebappPath(webappField.getValue());
            pro.setCdDescription(descField.getValue());

            try {
                if (!update) {
                    QueryWrapper<TomcatInfoEntity> check = new QueryWrapper<>();
                    check.eq("id_host", host()).eq("tomcat_id", id);
                    if (tomcatInfoMapper.selectOne(check) != null) {
                        Dialogs.warn("实例ID不能重复！");
                        return;
                    }
                    tomcatInfoMapper.insert(pro);
                } else {
                    // 旧实现是 delete+insert 两步，中间失败记录就没了；一条 UPDATE 等价且安全
                    UpdateWrapper<TomcatInfoEntity> wrapper = new UpdateWrapper<>();
                    wrapper.eq("id_host", host()).eq("tomcat_id", id);
                    tomcatInfoMapper.update(pro, wrapper);
                }
            } catch (Exception e) {
                log.error("保存实例失败", e);
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
