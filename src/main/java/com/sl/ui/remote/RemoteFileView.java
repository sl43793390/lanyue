package com.sl.ui.remote;

import cn.hutool.core.util.StrUtil;
import com.sl.entity.ConnectionInfo;
import com.sl.ui.component.Dialogs;
import com.sl.ui.component.UiFactory;
import com.sl.ui.component.ViewBase;
import com.sl.util.Constants;
import com.sl.util.SSHClientUtil;
import com.vaadin.flow.component.AttachEvent;
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

import java.io.File;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 远程文件管理（SFTP）。
 * <p>
 * 对应旧项目 {@code com.so.component.remote.RemoteFileMgmtComponent}：
 * 选一台机器，浏览远端目录、上传、下载、重命名、新建目录、删除。
 * 连接与文件操作都走 {@link SSHClientUtil}（sshj）：目录列表用 SFTP {@code ls}，
 * 上传/下载/改名/建目录走 SFTP，删除目录用 {@code rm -rf}（SFTP 没有递归删除）。
 * <p>
 * 与旧实现的差异：
 * <ul>
 *   <li>路径穿越在这里不是风险而是功能（用户本来就要去任意目录），但文件名一律过
 *       {@link UiFactory#safeFileName}——上传的那一侧，名字来自浏览器，必须防目录跳转；</li>
 *   <li>所有 SFTP/命令操作都是阻塞调用，统一后台线程 + {@code ui.access} 回 UI；</li>
 *   <li>删除目录前弹 confirmDanger（旧实现删除目录没有二次确认）。</li>
 * </ul>
 */
@SpringComponent
@UIScope
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class RemoteFileView extends ViewBase {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(RemoteFileView.class);

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 行数据：sshj 的 RemoteResourceInfo 直接进 Grid 会在序列化时带着连接到处跑，包一层 */
    private record FileRow(String name, boolean dir, long size, String mtime, String path) {
        String sizeText() {
            return dir ? "-" : humanSize(size);
        }
    }

    private transient ConnectionInfo presetHost;
    private transient SSHClientUtil ssh;

    private final TextField pathField = new TextField();
    private final Span statusLabel = new Span();
    private final Grid<FileRow> grid = UiFactory.grid(FileRow.class);
    /** Grid 的列是「行数据 → 组件」的纯函数，DTO 不持 UI 字段；当前目录放这儿 */
    private String currentPath = "/";

    public void setPresetHost(ConnectionInfo info) {
        this.presetHost = info;
    }

    private boolean built = false;

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        if (built) {
            return; // 标签切换（setVisible）不会重复 onAttach，这里只防异常路径
        }
        built = true;
        build();
    }

    /** 真正的 UI 构建：presetHost 就绪后调用一次。 */
    private void build() {
        if (presetHost == null) {
            add(new com.vaadin.flow.component.html.Paragraph(
                    "缺少连接信息：请从「免登录服务器列表」的行内按钮打开本页。"));
            return;
        }
        String host = presetHost.getIdHost();
        add(title("文件管理（" + host + "）"));
        add(subtitle("浏览 " + host + " 的文件系统（SFTP）：上传、下载、重命名、新建目录、删除。"));

        pathField.addClassName("file-path-field");
        pathField.setWidthFull();
        pathField.setPlaceholder("输入远端绝对路径后回车");
        pathField.addKeyDownListener(com.vaadin.flow.component.Key.ENTER, e -> navigate(pathField.getValue()));
        Button goBtn = UiFactory.primary("跳转", () -> navigate(pathField.getValue()));
        Button upBtn = UiFactory.button("上一级", this::goUp);
        Button mkdirBtn = UiFactory.button("新建目录", () -> promptNewDir(currentPath));
        Button reloadBtn = UiFactory.button("刷新", () -> navigate(currentPath));

        HorizontalLayout bar = toolbar(pathField, goBtn, upBtn, mkdirBtn, reloadBtn, spacer(), statusLabel);
        add(bar);

        buildGrid();
        VerticalLayout fill = fill(grid);
        add(fill);
        setFlexGrow(1, fill);

        openInitial();
    }

    /** 打开页面先解析登录用户的 home（SFTP 会话的当前目录就是它），失败再退回根目录。 */
    private void openInitial() {
        getUI().ifPresent(ui -> new Thread(() -> {
            String home = "/";
            try {
                home = ensureSsh().getSftpClient().canonicalize(".");
            } catch (Exception e) {
                log.warn("解析远程 home 失败，退回根目录：{}", e.getMessage());
            }
            String target = home;
            ui.access(() -> navigate(target));
        }, "file-home-" + presetHost.getIdHost()).start());
    }

    private void buildGrid() {
        grid.addComponentColumn(this::nameCell).setHeader("名称").setAutoWidth(true).setFlexGrow(1);
        grid.addColumn(FileRow::sizeText).setHeader("大小").setAutoWidth(true);
        grid.addColumn(FileRow::mtime).setHeader("修改时间").setAutoWidth(true);
        grid.addComponentColumn(this::actionsCell).setHeader("操作").setAutoWidth(true);
        grid.addClassName("standard-grid");
    }

    private Component nameCell(FileRow row) {
        Span name = new Span(row.name());
        name.addClassName("file-name");
        if (row.dir()) {
            name.getStyle().set("color", "var(--lumo-primary-text-color)");
            name.getStyle().set("cursor", "pointer");
            name.addClickListener(e -> navigate(row.path()));
            name.setTitle("点击进入目录");
        }
        Span type = new Span(row.dir() ? "目录" : "文件");
        type.getStyle().set("font-size", "var(--lumo-font-size-xs)");
        type.getStyle().set("color", "var(--lumo-tertiary-text-color)");
        HorizontalLayout cell = new HorizontalLayout(name, type);
        cell.setSpacing(false);
        cell.getStyle().set("gap", "6px");
        cell.setAlignItems(FlexComponent.Alignment.CENTER);
        return cell;
    }

    private Component actionsCell(FileRow row) {
        HorizontalLayout actions = new HorizontalLayout();
        actions.setSpacing(false);
        actions.addClassName("row-actions");
        actions.setAlignItems(FlexComponent.Alignment.CENTER);
        if (!row.dir()) {
            actions.add(UiFactory.rowAction("下载", () -> download(row)));
        }
        actions.add(UiFactory.rowAction("重命名", () -> promptRename(row)));
        actions.add(UiFactory.rowDanger("删除", () -> confirmDelete(row)));
        // 上传只挂在目录行上：目标就是那个目录，比"传到当前目录"更直观
        if (row.dir()) {
            actions.add(buildUpload(row.path()));
        }
        return actions;
    }

    // ------------------------------------------------------------------
    // 连接与目录浏览
    // ------------------------------------------------------------------

    private synchronized SSHClientUtil ensureSsh() throws IOException {
        if (ssh == null) {
            ssh = SSHClientUtil.connect(presetHost);
        }
        return ssh;
    }

    private void navigate(String target) {
        String path = normalize(target);
        if (path == null) {
            Dialogs.warn("请输入绝对路径（以 / 开头）");
            return;
        }
        Dialogs.info("正在读取目录……");
        getUI().ifPresent(ui -> new Thread(() -> {
            List<FileRow> rows = new ArrayList<>();
            String failure = null;
            try {
                for (net.schmizz.sshj.sftp.RemoteResourceInfo info : ensureSsh().listFiles(path)) {
                    // sshj 0.31：大小/时间在 FileAttributes 上，不在 RemoteResourceInfo 上
                    net.schmizz.sshj.sftp.FileAttributes attrs = info.getAttributes();
                    long mtimeSec = attrs.getMtime();
                    String mtime = mtimeSec <= 0 ? "-"
                            : TIME_FMT.format(java.time.Instant.ofEpochSecond(mtimeSec)
                                    .atZone(ZoneId.systemDefault()));
                    rows.add(new FileRow(info.getName(), info.isDirectory(),
                            info.isDirectory() ? 0 : attrs.getSize(),
                            mtime, joinPath(path, info.getName())));
                }
            } catch (Exception e) {
                failure = e.getMessage();
                log.warn("读取远程目录 {} 失败：{}", path, e.getMessage());
            }
            List<FileRow> finalRows = rows;
            String finalFailure = failure;
            ui.access(() -> {
                if (finalFailure != null) {
                    Dialogs.error("读取目录失败：" + finalFailure);
                    return;
                }
                currentPath = path;
                pathField.setValue(path);
                grid.setItems(finalRows);
                statusLabel.setText("共 " + finalRows.size() + " 项");
            });
        }, "file-ls-" + presetHost.getIdHost()).start());
    }

    private void goUp() {
        String parent = currentPath.equals("/") ? null
                : currentPath.substring(0, currentPath.lastIndexOf('/'));
        navigate(StrUtil.isBlank(parent) ? "/" : parent);
    }

    /** 绝对路径归一化：去尾部斜杠（根目录除外）。相对路径直接拒——SFTP 的工作目录不可靠。 */
    private static String normalize(String input) {
        String path = StrUtil.trimToEmpty(input);
        if (!path.startsWith("/")) {
            return null;
        }
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private static String joinPath(String dir, String name) {
        return dir.equals("/") ? "/" + name : dir + "/" + name;
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double v = bytes;
        for (String unit : new String[]{"KB", "MB", "GB", "TB"}) {
            v /= 1024;
            if (v < 1024) {
                return String.format("%.1f %s", v, unit);
            }
        }
        return String.format("%.1f PB", v / 1024);
    }

    // ------------------------------------------------------------------
    // 上传 / 下载
    // ------------------------------------------------------------------

    /** 上传到指定目录（只从目录行的按钮发起）。 */
    private Upload buildUpload(String targetDir) {
        UploadHandler handler = UploadHandler.toFile(
                (metadata, file) -> {
                    String fileName = UiFactory.safeFileName(metadata.fileName());
                    getUI().ifPresent(ui -> new Thread(() -> {
                        String failure = null;
                        try {
                            ensureSsh().uploadFile(file.getAbsolutePath(), joinPath(targetDir, fileName), null);
                        } catch (Exception e) {
                            failure = e.getMessage();
                        } finally {
                            file.delete();
                        }
                        String finalFailure = failure;
                        ui.access(() -> {
                            if (finalFailure == null) {
                                Dialogs.success("已上传到 " + targetDir + "：" + fileName);
                                navigate(targetDir);
                            } else {
                                Dialogs.error("上传失败：" + StrUtil.emptyToDefault(finalFailure, "未知错误"));
                            }
                        });
                    }, "file-upload").start());
                },
                metadata -> {
                    if (!hasPermission(Constants.UPLOAD)) {
                        throw new IOException("权限不足，禁止上传");
                    }
                    return File.createTempFile("lanyue-upload-", "-" + UiFactory.safeFileName(metadata.fileName()));
                });
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

    /**
     * 下载到服务器本地临时目录，再用浏览器下载链接交给用户。
     * 远端 → 平台 → 浏览器两段式（旧项目同款），临时文件由用户点完链接后留在
     * downloads 目录里，下次覆盖同名不堆积（下载目录按主机分桶）。
     */
    private void download(FileRow row) {
        Dialogs.info("正在下载……");
        String localDir = System.getProperty("java.io.tmpdir") + File.separator
                + "lanyue-downloads" + File.separator + presetHost.getIdHost();
        File local = new File(localDir, row.name());
        getUI().ifPresent(ui -> new Thread(() -> {
            String failure = null;
            try {
                ensureSsh().downloadFile(row.path(), local.getAbsolutePath());
            } catch (Exception e) {
                failure = e.getMessage();
            }
            String finalFailure = failure;
            ui.access(() -> {
                if (finalFailure != null) {
                    Dialogs.error("下载失败：" + finalFailure);
                    return;
                }
                Dialog downloadDialog = new Dialog();
                downloadDialog.setHeaderTitle("下载完成");
                VerticalLayout content = new VerticalLayout(
                        new Span(row.name() + "（" + humanSize(local.length()) + "）"),
                        UiFactory.downloadOrHint(local, "保存到本机"));
                content.setPadding(true);
                downloadDialog.add(content);
                downloadDialog.getFooter().add(Dialogs.cancelButton(downloadDialog::close));
                downloadDialog.open();
            });
        }, "file-download-" + row.name()).start());
    }

    // ------------------------------------------------------------------
    // 重命名 / 新建目录 / 删除
    // ------------------------------------------------------------------

    private void promptRename(FileRow row) {
        if (!hasPermission(Constants.UPDATE)) {
            Dialogs.warn("权限不足，无法重命名");
            return;
        }
        TextField nameField = UiFactory.textField("新名称", row.name());
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("重命名");
        VerticalLayout content = new VerticalLayout(nameField);
        content.setPadding(true);
        dialog.add(content);
        dialog.getFooter().add(Dialogs.cancelButton(dialog::close),
                Dialogs.primaryButton("保存", () -> {
                    String newName = StrUtil.trimToNull(nameField.getValue());
                    if (newName == null || newName.contains("/")) {
                        Dialogs.warn("名称不能为空，且不能包含 /");
                        return;
                    }
                    String target;
                    try {
                        target = joinPath(currentPath, UiFactory.safeFileName(newName));
                    } catch (IOException e) {
                        Dialogs.warn("名称不合法：" + e.getMessage());
                        return;
                    }
                    dialog.close();
                    runSftp("重命名", () -> ensureSsh().getSftpClient().rename(row.path(), target));
                }));
        dialog.open();
        nameField.focus();
    }

    private void promptNewDir(String base) {
        if (!hasPermission(Constants.ADD)) {
            Dialogs.warn("权限不足，无法新建目录");
            return;
        }
        TextField nameField = UiFactory.textField("目录名", "在 " + base + " 下创建");
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("新建目录");
        VerticalLayout content = new VerticalLayout(nameField);
        content.setPadding(true);
        dialog.add(content);
        dialog.getFooter().add(Dialogs.cancelButton(dialog::close),
                Dialogs.primaryButton("创建", () -> {
                    String name = StrUtil.trimToNull(nameField.getValue());
                    if (name == null || name.contains("/")) {
                        Dialogs.warn("目录名不能为空，且不能包含 /");
                        return;
                    }
                    String target;
                    try {
                        target = joinPath(base, UiFactory.safeFileName(name));
                    } catch (IOException e) {
                        Dialogs.warn("目录名不合法：" + e.getMessage());
                        return;
                    }
                    dialog.close();
                    runSftp("新建目录", () -> ensureSsh().getSftpClient().mkdir(target));
                }));
        dialog.open();
        nameField.focus();
    }

    private void confirmDelete(FileRow row) {
        if (!hasPermission(Constants.DELETE)) {
            Dialogs.warn("权限不足，无法删除");
            return;
        }
        String what = row.dir() ? "目录（含全部内容）" : "文件";
        Dialogs.confirmDanger("删除" + what,
                "确认删除 " + presetHost.getIdHost() + ":" + row.path() + " 吗？此操作不可恢复。",
                "确认删除", () -> {
                    if (row.dir()) {
                        // SFTP 没有递归删除；目录树交给 rm -rf，路径加单引号防空格拆词
                        runSftp("删除", () -> ensureSsh().executeCommand(
                                "rm -rf '" + row.path().replace("'", "'\\''") + "'"));
                    } else {
                        runSftp("删除", () -> ensureSsh().getSftpClient().rm(row.path()));
                    }
                });
    }

    /** SFTP/命令小操作的统一外壳：后台线程执行，成功后刷新当前目录。 */
    private void runSftp(String actionName, SftpTask task) {
        getUI().ifPresent(ui -> new Thread(() -> {
            String failure = null;
            try {
                task.run();
            } catch (Exception e) {
                failure = e.getMessage();
                log.warn("{}失败：{}", actionName, e.getMessage());
            }
            String finalFailure = failure;
            ui.access(() -> {
                if (finalFailure == null) {
                    Dialogs.success(actionName + "完成");
                    navigate(currentPath);
                } else {
                    Dialogs.error(actionName + "失败：" + finalFailure);
                }
            });
        }, "file-" + actionName).start());
    }

    /** 允许 lambda 抛受检异常的小任务接口（exec/SFTP 方法都声明 throws IOException）。 */
    @FunctionalInterface
    private interface SftpTask {
        void run() throws Exception;
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        if (ssh != null) {
            ssh.closeConnection();
            ssh = null;
        }
        super.onDetach(detachEvent);
    }

    private static boolean hasPermission(String code) {
        com.sl.entity.User user = com.sl.security.CurrentUser.get();
        return user != null && user.hasPermission(code);
    }
}
