package com.sl.ui.remote;

import cn.hutool.core.util.StrUtil;
import com.sl.config.PureTextProperties;
import com.sl.entity.ConnectionInfo;
import com.sl.ui.component.Dialogs;
import com.sl.ui.component.LoadingOverlay;
import com.sl.ui.component.TabHost;
import com.sl.ui.component.CodeEditor;
import com.sl.ui.component.UiFactory;
import com.sl.ui.component.ViewBase;
import com.sl.util.CharsetDetector;
import com.sl.util.Constants;
import com.sl.util.SSHClientUtil;
import com.sl.util.SshConnectionPool;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.util.unit.DataSize;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 远程文件管理（SFTP）。
 * <p>
 * 对应旧项目 {@code com.so.component.remote.RemoteFileMgmtComponent}：
 * 选一台机器，浏览远端目录、上传、下载、重命名、新建目录、删除、批量删除、执行命令；
 * 另有本页新增的「编辑」：纯文本、大小不超过 {@code pure.text.type.maxsize} 的文件
 * （扩展名白名单见 {@code pure.text.type}）可以弹 textarea 在线改，保存前先探测编码，
 * 写回用同一组 charset + BOM，保证不会改出乱码。
 * 连接与文件操作都走 {@link SshConnectionPool} 共享连接池（底层 sshj）：
 * 目录列表用 SFTP {@code ls}，上传/下载/改名/建目录走 SFTP，
 * 删除目录与执行命令用 {@code exec}。连接不随页面关闭断开，由池统一管理，
 * 空闲 10 分钟自动回收。
 * <p>
 * 默认不显示以 {@code .} 开头的隐藏文件，工具栏的「显示隐藏文件」勾选后展示；
 * 上传的大小上限取自 {@code spring.servlet.multipart.max-file-size}，在控件上
 * 做客户端预检，超限直接弹窗提示（后台错误也统一弹窗）。
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

    /** 上传大小上限（spring.servlet.multipart.max-file-size，默认 100MB），超限在客户端就弹窗拦截 */
    private final transient DataSize maxUploadSize;

    /** 纯文本编辑白名单（application.properties: pure.text.type / pure.text.type.maxsize） */
    private final transient PureTextProperties pureText;

    /** 开 SSH 终端标签要用：从容器里取 SshTerminalView 原型 bean */
    private final transient org.springframework.context.ApplicationContext applicationContext;

    private final TextField pathField = new TextField();
    private final Span statusLabel = new Span();
    private final Grid<FileRow> grid = UiFactory.grid(FileRow.class);
    /** 勾选后展示以 . 开头的隐藏文件，默认不显示 */
    private final Checkbox showHiddenFiles = new Checkbox("显示隐藏文件");
    /** 连接/读目录的等待遮罩：SSH 建连可能要 10~30 秒，没有可见反馈用户会以为功能坏了 */
    private final LoadingOverlay loadingOverlay = new LoadingOverlay("正在建立连接，请稍候……");
    /** Grid 的列是「行数据 → 组件」的纯函数，DTO 不持 UI 字段；当前目录放这儿 */
    private String currentPath = "/";

    public void setPresetHost(ConnectionInfo info) {
        this.presetHost = info;
    }

    public RemoteFileView(PureTextProperties pureTextProperties,
                          org.springframework.context.ApplicationContext applicationContext,
                          @Value("${spring.servlet.multipart.max-file-size:100MB}") DataSize maxUploadSize) {
        this.pureText = pureTextProperties;
        this.applicationContext = applicationContext;
        this.maxUploadSize = maxUploadSize;
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
        showHiddenFiles.setTooltipText("勾选后显示以 . 开头的隐藏文件");
        showHiddenFiles.addValueChangeListener(e -> navigate(currentPath));
        Button execBtn = UiFactory.button("执行命令", this::openExecDialog);
        Button sshTerminalBtn = UiFactory.button("跳转到SSH终端", this::openSshTerminal);
        Button batchDeleteBtn = UiFactory.danger("批量删除", this::confirmBatchDelete);

        HorizontalLayout bar = toolbar(pathField, goBtn, upBtn, mkdirBtn, reloadBtn, showHiddenFiles,
                execBtn, sshTerminalBtn, spacer(), batchDeleteBtn, statusLabel);
        add(bar);

        buildGrid();
        VerticalLayout fill = fill(grid);
        // 遮罩是 position:absolute 盖满最近的 relative 祖先，这里给 fill 挂上定位
        fill.getStyle().set("position", "relative");
        fill.add(loadingOverlay);
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
        // 多选模式：表格左侧自动出现勾选列，配合「批量删除」按钮
        grid.setSelectionMode(Grid.SelectionMode.MULTI);
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
            // 纯文本且不超过大小上限的文件才给「编辑」入口（白名单在 application.properties）
            if (pureText.isEditable(row.name(), row.size())) {
                actions.add(UiFactory.rowAction("编辑", () -> openEditor(row)));
            }
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
        // 连接进共享池（SshConnectionPool）：按 主机:端口:用户 复用，页面关闭不断开，
        // 最后一次使用后保留 10 分钟，超时由池自动断开
        return SshConnectionPool.acquire(presetHost);
    }

    private void navigate(String target) {
        String path = normalize(target);
        if (path == null) {
            Dialogs.warn("请输入绝对路径（以 / 开头）");
            return;
        }
        // 等待遮罩取代以前那条一闪而过的 Notification：读目录期间整个内容区可见地"忙"着
        loadingOverlay.show("正在读取目录 " + path + " ……");
        getUI().ifPresent(ui -> new Thread(() -> {
            List<FileRow> rows = new ArrayList<>();
            String failure = null;
            boolean showHidden = showHiddenFiles.getValue();
            try {
                for (net.schmizz.sshj.sftp.RemoteResourceInfo info : ensureSsh().listFiles(path)) {
                    // 默认不显示隐藏文件：以 . 开头的一律跳过（勾选「显示隐藏文件」后展示）
                    if (!showHidden && info.getName().startsWith(".")) {
                        continue;
                    }
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
                loadingOverlay.hide();
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
        // 客户端预检大小上限：超过 spring.servlet.multipart.max-file-size 的文件
        // 在浏览器端就拒绝，触发下面的 fileRejectedListener 弹窗提示，不会白传一半
        upload.setMaxFileSize((int) Math.min(maxUploadSize.toBytes(), Integer.MAX_VALUE));
        upload.addAllFinishedListener(event -> upload.clearFileList());
        upload.addFileRejectedListener(event ->
                Dialogs.warn("上传被拒绝：" + StrUtil.emptyToDefault(event.getErrorMessage(), "不满足上传限制")
                        + "（大小上限 " + maxUploadSize.toMegabytes() + "MB）"));
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
    // 在线编辑（纯文本小文件）
    // ------------------------------------------------------------------

    /**
     * 打开编辑器：先把远端文件拉到本地临时目录，探测编码后弹 textarea 窗口。
     * 全程后台线程，下载成功才弹窗。
     */
    private void openEditor(FileRow row) {
        if (!hasPermission(Constants.UPDATE)) {
            Dialogs.warn("权限不足，无法编辑文件");
            return;
        }
        getUI().ifPresent(ui -> new Thread(() -> {
            File tmp = null;
            CharsetDetector.TextSnapshot snapshot = null;
            String failure = null;
            try {
                tmp = File.createTempFile("lanyue-edit-", "-" + UiFactory.safeFileName(row.name()));
                ensureSsh().downloadFile(row.path(), tmp.getAbsolutePath());
                snapshot = CharsetDetector.load(tmp);
            } catch (Exception e) {
                failure = e.getMessage();
                log.warn("拉取待编辑文件 {} 失败：{}", row.path(), e.getMessage());
            }
            File finalTmp = tmp;
            CharsetDetector.TextSnapshot finalSnapshot = snapshot;
            String finalFailure = failure;
            ui.access(() -> {
                if (finalFailure != null || finalSnapshot == null) {
                    if (finalTmp != null) {
                        finalTmp.delete();
                    }
                    Dialogs.error("读取文件失败：" + StrUtil.emptyToDefault(finalFailure, "未知错误"));
                    return;
                }
                showEditorDialog(row, finalTmp, finalSnapshot);
            });
        }, "file-edit-" + row.name()).start());
    }

    /** 编辑弹窗：CodeMirror 编辑器（语法模式按文件扩展名选择），下方「放弃」和「保存」。 */
    private void showEditorDialog(FileRow row, File tmp, CharsetDetector.TextSnapshot snapshot) {
        Dialog dialog = new Dialog();
        dialog.setWidth("900px");
        dialog.setResizable(true);
        dialog.setHeaderTitle("编辑文件：" + row.name());

        CodeEditor editor = new CodeEditor(CodeEditor.suggestMode(row.name()));
        editor.setWidthFull();
        editor.setHeight("55vh");
        editor.setValue(snapshot.content());

        Span hint = new Span("路径：" + row.path()
                + "　编码：" + snapshot.label()
                + "　大小：" + humanSize(row.size()));
        hint.addClassName("search-status");

        VerticalLayout content = new VerticalLayout(hint, editor);
        content.setPadding(true);
        content.setSpacing(false);
        content.getStyle().set("gap", "8px");
        dialog.add(content);
        dialog.getFooter().add(
                Dialogs.cancelButton(() -> {
                    tmp.delete();
                    dialog.close();
                }),
                Dialogs.primaryButton("保存", () ->
                        saveEditedFile(row, tmp, snapshot, editor.getValue(), dialog)));
        dialog.open();
        editor.focusEditor();
    }

    /**
     * 保存：按打开时探测到的编码 + BOM 写回（同一组 charset，改内容不改编码，杜绝乱码），
     * 先写本地临时文件再 SFTP 传回原路径。
     */
    private void saveEditedFile(FileRow row, File tmp,
                                CharsetDetector.TextSnapshot snapshot, String content, Dialog dialog) {
        if (!hasPermission(Constants.UPDATE)) {
            Dialogs.warn("权限不足，无法保存文件");
            return;
        }
        if (StrUtil.isBlank(content) && row.size() > 0) {
            Dialogs.warn("内容为空；如果是想清空文件，请先确认远端有备份");
            return;
        }
        File outTmp;
        try {
            outTmp = File.createTempFile("lanyue-edit-save-", ".tmp");
            Files.write(outTmp.toPath(),
                    CharsetDetector.encode(content, snapshot.charset(), snapshot.bom()));
        } catch (IOException e) {
            Dialogs.error("保存失败：" + e.getMessage());
            return;
        }
        dialog.close();
        runSftp("保存", () -> {
            try {
                ensureSsh().uploadFile(outTmp.getAbsolutePath(), row.path(), null);
            } finally {
                outTmp.delete();
                tmp.delete();
            }
        });
    }

    // ------------------------------------------------------------------
    // 重命名 / 新建目录 / 删除
    // ------------------------------------------------------------------

    private void promptRename(FileRow row) {
        if (!hasPermission(Constants.UPDATE)) {
            Dialogs.warn("权限不足，无法重命名");
            return;
        }
        TextField nameField = UiFactory.textField("新名称", row.name(),"450px");
        Dialog dialog = new Dialog();
        dialog.setWidth("700px");
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
        TextField nameField = UiFactory.textField("目录名", "在 " + base + " 下创建","500px");
        Dialog dialog = new Dialog();
        dialog.setWidth("700px");
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

    // ------------------------------------------------------------------
    // 执行命令（在当前显示的目录下执行）
    // ------------------------------------------------------------------

    /** 「执行命令」弹窗：命令在当前浏览的目录（currentPath）下执行，输出就地展示。 */
    private void openExecDialog() {
        if (!hasPermission(Constants.UPDATE)) {
            Dialogs.warn("权限不足，无法执行命令");
            return;
        }
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("执行命令");
        dialog.setWidth("860px");
        dialog.setHeight("750px");

        TextField cmdField = UiFactory.textField("命令", "如：ls -l、du -sh *、tail -n 100 xxx.log", "640px");
        Span hint = new Span("命令将在当前显示的目录 " + currentPath + " 下执行（内部以 cd 进入该目录再执行）。"
                + "输入完按回车即执行，命令报错信息（stderr）也会显示在输出里，执行完自动刷新目录。");
        hint.addClassName("view-subtitle");

        TextArea outputArea = UiFactory.textArea();
        outputArea.setWidthFull();
        outputArea.setHeight("450px");
        outputArea.setReadOnly(true);
        outputArea.getStyle().set("--lumo-font-family", "Consolas, 'Courier New', monospace");

        Button runBtn = new Button("执行");
        runBtn.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_PRIMARY);
        Runnable runAction = () -> runExecCommand(cmdField, outputArea, runBtn);
        runBtn.addClickListener(event -> runAction.run());
        // 回车直接执行，不用再去找「执行」按钮
        cmdField.addKeyDownListener(com.vaadin.flow.component.Key.ENTER, event -> runAction.run());

        VerticalLayout content = new VerticalLayout(cmdField, hint, new Span("输出："), outputArea);
        content.setPadding(false);
        content.setSpacing(false);
        content.getStyle().set("gap", "10px");
        dialog.add(content);
        dialog.getFooter().add(runBtn, Dialogs.cancelButton(dialog::close));
        dialog.open();
        cmdField.focus();
    }

    /** 执行弹窗里的命令：合并 stderr（报错信息必须看得见），跑完刷新目录数据。 */
    private void runExecCommand(TextField cmdField, TextArea outputArea, Button runBtn) {
        String cmd = StrUtil.trimToNull(cmdField.getValue());
        if (cmd == null) {
            Dialogs.warn("请输入要执行的命令");
            return;
        }
        // cd 进当前目录再执行；目录名带单引号等特殊字符也要安全拼接
        String remote = "cd '" + currentPath.replace("'", "'\\''") + "' && " + cmd;
        runBtn.setEnabled(false);
        outputArea.setValue("正在执行……");
        getUI().ifPresent(ui -> new Thread(() -> {
            String result;
            try {
                // executeCommand 只收 stdout，命令不存在时 stderr 上的
                // 「command not found」会被丢掉，界面就误显示「无输出」；
                // 这里用合流版本，报错信息原样进输出区
                result = ensureSsh().executeCommandMerged(remote);
            } catch (Exception e) {
                log.warn("在 {} 下执行命令失败：{}", currentPath, e.getMessage());
                result = "[执行失败] " + e.getMessage();
            }
            String finalResult = result == null || result.isBlank() ? "(命令无输出)" : result;
            ui.access(() -> {
                runBtn.setEnabled(true);
                outputArea.setValue(finalResult);
                // 命令可能增删改了目录内容（mkdir/touch/rm…），执行完刷新一次
                navigate(currentPath);
            });
        }, "file-exec").start());
    }

    /** 从文件管理页一键开本机的 SSH 终端标签（与服务器列表行内按钮同一打开路径）。 */
    private void openSshTerminal() {
        TabHost host = TabHost.current();
        if (host == null) {
            Dialogs.warn("当前页面不在主框架内，无法打开终端");
            return;
        }
        // Supplier：同名标签已存在时切回旧的，不会重复登记 token
        host.open("SSH终端-" + presetHost.getIdHost(), () -> {
            SshTerminalView view = applicationContext.getBean(SshTerminalView.class);
            view.setPresetHost(presetHost);
            return view;
        });
    }

    // ------------------------------------------------------------------
    // 批量删除（表格左侧勾选 + 批量删除按钮）
    // ------------------------------------------------------------------

    private void confirmBatchDelete() {
        if (!hasPermission(Constants.DELETE)) {
            Dialogs.warn("权限不足，无法删除");
            return;
        }
        Set<FileRow> selected = grid.getSelectedItems();
        if (selected.isEmpty()) {
            Dialogs.warn("请先在表格左侧勾选要删除的文件或目录");
            return;
        }
        long dirCount = selected.stream().filter(FileRow::dir).count();
        Dialogs.confirmDanger("批量删除",
                "确认删除选中的 " + selected.size() + " 项"
                        + (dirCount > 0 ? "（其中 " + dirCount + " 个目录将连同全部内容一起删除）" : "")
                        + "吗？此操作不可恢复。",
                "确认删除", () -> {
                    List<FileRow> targets = new ArrayList<>(selected);
                    runSftp("批量删除", () -> {
                        for (FileRow row : targets) {
                            if (row.dir()) {
                                // SFTP 没有递归删除；目录树交给 rm -rf，路径加单引号防空格拆词
                                ensureSsh().executeCommand(
                                        "rm -rf '" + row.path().replace("'", "'\\''") + "'");
                            } else {
                                ensureSsh().getSftpClient().rm(row.path());
                            }
                        }
                    });
                    grid.deselectAll();
                });
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        // 连接已交给共享连接池（SshConnectionPool）管理，页面关闭不断开，
        // 其它页面可继续复用，空闲 10 分钟后由池自动回收
        super.onDetach(detachEvent);
    }

    private static boolean hasPermission(String code) {
        com.sl.entity.User user = com.sl.security.CurrentUser.get();
        return user != null && user.hasPermission(code);
    }
}
