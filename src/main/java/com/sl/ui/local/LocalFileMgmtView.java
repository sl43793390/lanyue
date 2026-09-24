package com.sl.ui.local;

import com.sl.entity.RemoteFileInfo;
import com.sl.entity.User;
import com.sl.security.CurrentUser;
import com.sl.ui.component.Dialogs;
import com.sl.ui.component.UiFactory;
import com.sl.ui.component.ViewBase;
import com.sl.util.Constants;
import com.sl.util.Util;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.upload.FileRejectedEvent;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.component.upload.UploadI18N;
import com.vaadin.flow.server.streams.UploadHandler;
import com.vaadin.flow.server.streams.UploadMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import cn.hutool.core.util.StrUtil;

/**
 * 本地文件管理：浏览运行本平台的服务器上的文件系统。
 * <p>
 * 从旧项目的 {@code LocalFileMgmtComponent} 重写而来。功能集合保持一致：
 * 进目录 / 返回上级 / 每行上传到该目录 / 下载文件 / 删除文件，
 * 外加一个旧代码里隐含但没做出来明面开关的「是否显示隐藏文件」。
 * <p>
 * <b>重写时改掉的几处</b>：
 * <ul>
 *   <li>上传控件换掉了。<b>旧项目的 {@code FileUploader} + {@code Upload(Receiver)} 在
 *       Vaadin 24 里已经不可用</b>——{@code Receiver} 自 24.8 起标了
 *       {@code @Deprecated(forRemoval = true)}，{@code FileUploader} 同时实现了
 *       {@code Upload.Receiver}/{@code SucceededListener} 等 Vaadin 8 接口，所以它没有跟着
 *       基建批次一起迁过来，而是按 {@link UploadHandler#toFile} 重写：
 *       字节直接落到目标目录，成功后在 UI 线程回调整理列表并提示。
 *       旧实现里「上传成功后回写 {@code projects.jar_name}」那段属于 jar 管理模块的职责，
 *       这里不该有，已去掉。</li>
 *   <li>{@code listFiles()} 在目录不可读时返回 {@code null}，旧实现直接
 *       {@code Arrays.asList(files)} → NPE。这里判空并给出可读提示。</li>
 *   <li>软链接。旧代码用 {@code NOFOLLOW_LINKS} 读属性后就直接判定类型，于是
 *       「指向文件的软链」被当成目录渲染成可点链接，点进去才报「目录不存在」。
 *       这里对软链跟随一次，按目标判定类型；断链当普通文件（进不去，但看得见）。</li>
 *   <li>权限列。旧实现拼出来的是 {@code [R W X][R][R]}（{@code List.toString()}
 *       去掉逗号但没去空格），这里改用 {@code rwxr-xr-x} 这种标准写法。</li>
 *   <li>「删除目录」的拒绝时机。旧实现是先弹确认框、用户点了确认才说
 *       「暂不支持删除目录」，白让人点一次；这里直接拦下。</li>
 *   <li>{@code Panel} + {@code contentLayout.setHeight("700px")} 换成
 *       {@code ViewBase} + 表格吃满剩余高度，屏幕高一点就能多看几十行。</li>
 *   <li>顶部那行只读的路径 Label 改成可编辑输入框：旧界面只能从家目录一层层点进去，
 *       要到 {@code /opt/app/logs} 得点五六次，而运维手里本来就有路径。</li>
 * </ul>
 * <p>
 * 列表数据复用 {@link RemoteFileInfo}（旧项目也是复用的）：它本来叫「远程」文件信息，
 * 但字段就是文件名/权限/属主/大小/时间/类型，本地目录一样对得上，
 * 没必要为了本地再定义一份几乎相同的实体。
 */
@Service
@Scope("prototype")
public class LocalFileMgmtView extends ViewBase {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(LocalFileMgmtView.class);

    /**
     * 上传控件的文案。{@code vaadin-upload} 的按钮文字取自 i18n 的 {@code addFiles}
     * （默认是英文的 "Upload File"），不设就是满屏英文按钮。
     * <p>
     * 做成静态常量共享：同一个目录下有多少个子目录就有多少个上传控件，
     * 每个都建一份 i18n 对象纯属浪费，而且它本身是不可变配置。
     */
    private static final UploadI18N UPLOAD_I18N = new UploadI18N()
            .setAddFiles(new UploadI18N.AddFiles().setOne("上传").setMany("上传"))
            .setDropFiles(new UploadI18N.DropFiles().setOne("拖到此处").setMany("拖到此处"));

    /** 默认排序：目录在前，同类型内按名称（忽略大小写）。 */
    private static final Comparator<RemoteFileInfo> DEFAULT_ORDER =
            Comparator.comparingInt((RemoteFileInfo item) -> Boolean.TRUE.equals(item.getIsFile()) ? 1 : 0)
                    .thenComparing(RemoteFileInfo::getFileName, String.CASE_INSENSITIVE_ORDER);

    private final Grid<RemoteFileInfo> grid = UiFactory.grid(RemoteFileInfo.class);

    /**
     * 当前路径，兼「跳到某目录」的输入框。
     * <p>
     * 旧界面这里是只读 Label，只能从家目录一层层点进去——查 {@code /opt/app/logs}
     * 要点五六次，而运维从来是手里已经有路径的。做成可编辑输入框后，
     * 复制粘贴即达，同时它仍然照常显示当前目录。
     */
    private final TextField pathField = new TextField();
    private final Span statusLabel = new Span();
    private final Checkbox showHidden = new Checkbox("显示隐藏文件");
    private final Button backButton;

    /**
     * 导航历史，<b>最后一个元素是当前目录</b>。
     * <p>
     * 旧实现里这个栈被 {@code navigateTo} 和点击事件各 push 一次，导致每个目录入栈两遍，
     * 「返回」会回到当前目录本身。这里只由 {@link #navigateTo} 维护。
     */
    private final LinkedList<String> pathHistory = new LinkedList<>();

    private String currentDir;

    public LocalFileMgmtView() {
        backButton = UiFactory.tertiary("← 上级目录", this::goBack);
        addClassName("local-file-view");
        configureGrid();
        buildLayout();
        // 进来先落到起始目录（Linux 上 root 就是 /root，其他是用户主目录）
        navigateTo(null);
    }

    // ------------------------------------------------------------------
    // 界面
    // ------------------------------------------------------------------

    private void buildLayout() {
        add(title("本地文件管理"));
        add(subtitle("浏览本机文件系统。点子目录名进入，「上传」把文件放进对应目录，删除只对文件开放。"));
        add(UiFactory.divider());
        add(buildNavBar());
        add(grid);
        // 表格吃掉剩余高度：目录里几千个文件时只在表格内部滚动，页面本身不滚
        setFlexGrow(1, grid);
    }

    private HorizontalLayout buildNavBar() {
        pathField.addClassName("file-path-field");
        pathField.setPlaceholder("输入目录路径后回车，例如 /opt/app/logs");
        pathField.setClearButtonVisible(true);
        pathField.setWidth("420px");
        pathField.getElement().setAttribute("title", "可直接输入任意目录路径跳转；回车或点「打开」");
        // 敲完路径直接回车，不用再挪去点按钮
        pathField.addKeyPressListener(Key.ENTER, event -> openTypedPath());

        statusLabel.addClassName("search-status");

        showHidden.addClassName("file-hidden-toggle");
        showHidden.getElement().setAttribute("title", "默认不列出以点开头的隐藏文件（如 .env、.bashrc）");
        showHidden.addValueChangeListener(event -> reloadCurrentDir());

        HorizontalLayout bar = toolbar(UiFactory.button("打开", this::openTypedPath),
                backButton, pathField,  showHidden, statusLabel);
        bar.getStyle().set("flex-wrap", "wrap");
        bar.getStyle().set("gap", "12px");
        // 路径框占掉剩余宽度：路径可能很长，优先给它空间
        bar.setFlexGrow(1, pathField);
        return bar;
    }

    /** 处理路径输入框里手输的跳转。 */
    private void openTypedPath() {
        String typed = pathField.getValue();
        if (StrUtil.isBlank(typed)) {
            Dialogs.warn("请先输入目录路径");
            pathField.setValue(currentDir == null ? "" : currentDir);
            return;
        }
        if (!navigateTo(typed.trim())) {
            // 跳转失败时把输入框还原成当前目录，否则界面上会显示一个并不存在的"当前目录"
            pathField.setValue(currentDir == null ? "" : currentDir);
        }
    }

    private void configureGrid() {
        Grid.Column<RemoteFileInfo> nameColumn = grid.addComponentColumn(this::buildNameCell);
        nameColumn.setHeader("名称");
        nameColumn.setAutoWidth(true).setFlexGrow(3);
        nameColumn.setComparator(
                Comparator.comparing(RemoteFileInfo::getFileName, String.CASE_INSENSITIVE_ORDER));

        grid.addColumn(RemoteFileInfo::getPermission)
                .setHeader("权限").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(RemoteFileInfo::getUserName)
                .setHeader("用户").setAutoWidth(true).setFlexGrow(0);

        Grid.Column<RemoteFileInfo> sizeColumn = grid.addColumn(RemoteFileInfo::getSize);
        sizeColumn.setHeader("大小").setAutoWidth(true).setFlexGrow(0);
        // 大小是「1.20 MB」这种带单位的字符串，按字典序排会把 9.00 KB 排到 10.00 MB 后面。
        // 与其给出一个看起来能点、点了结果错的排序，不如把这一列关掉。
        sizeColumn.setSortable(false);

        grid.addColumn(RemoteFileInfo::getLastModify)
                .setHeader("修改时间").setAutoWidth(true).setFlexGrow(1);

        // 上传列只在有上传权限时才出现——没权限的人连控件都看不到，不用先点一次再被拒
        if (canUpload()) {
            grid.addComponentColumn(this::buildUploadCell)
                    .setHeader("上传").setAutoWidth(true).setFlexGrow(0);
        }
        grid.addComponentColumn(this::buildDownloadCell)
                .setHeader("下载").setAutoWidth(true).setFlexGrow(0);
        grid.addComponentColumn(this::buildDeleteCell)
                .setHeader("删除").setAutoWidth(true).setFlexGrow(0);

        grid.addItemDoubleClickListener(event -> {
            if (!Boolean.TRUE.equals(event.getItem().getIsFile())) {
                navigateTo(event.getItem().getCurrentPath());
            }
        });
    }

    /** 名称列：目录渲染成按钮（点进去），文件是纯文本。 */
    private Component buildNameCell(RemoteFileInfo item) {
        if (Boolean.TRUE.equals(item.getIsFile())) {
            Span name = new Span(item.getFileName());
            name.addClassName("file-name");
            return name;
        }
        Button enter = UiFactory.small(item.getFileName(), () -> navigateTo(item.getCurrentPath()));
        enter.getElement().setAttribute("title", "进入目录 " + item.getCurrentPath());
        return enter;
    }

    /**
     * 上传列：每个目录一行一个上传控件，传到该行目录里。
     * <p>
     * 旧界面就是这个行为——不必先点进目录再上传，改路径也不用重开标签。
     */
    private Component buildUploadCell(RemoteFileInfo item) {
        if (Boolean.TRUE.equals(item.getIsFile())) {
            return new Span();
        }
        String targetDir = item.getCurrentPath();

        UploadHandler handler = UploadHandler.toFile(
                // 成功回调：文件已经落盘，由 handler 在 UI 线程里调用
                (UploadMetadata metadata, File file) -> onUploadFinished(targetDir, file),
                // 目标文件由这里决定；权限与文件名合法性都在这里面兜住
                metadata -> resolveUploadTarget(targetDir, metadata.fileName()));

        Upload upload = new Upload(handler);
        upload.addClassName("row-upload");
        upload.setI18n(UPLOAD_I18N);
        // 表格行里只要一个按钮：拖拽区占地方，而且一行一个拖拽区容易误拖到别的目录
        upload.setDropAllowed(false);
        upload.setMaxFiles(1);
        /*
         * 上传完成后必须清掉文件列表。
         * vaadin-upload 会把已上传的文件留在列表里，列表非空 → maxFilesReached 为真
         * → 按钮被置灰，同一行再也传不了第二个文件，除非用户手动点「清除」。
         */
        upload.addAllFinishedListener(event -> upload.clearFileList());
        upload.addFileRejectedListener(event -> Dialogs.warn(rejectedMessage(event)));
        upload.getElement().setAttribute("title", "上传文件到 " + targetDir);
        return upload;
    }

    /** 下载列：只有文件有下载链接，目录不给（目录打包下载不在这个模块的范围内）。 */
    private Component buildDownloadCell(RemoteFileInfo item) {
        if (!Boolean.TRUE.equals(item.getIsFile())) {
            return new Span();
        }
        return UiFactory.downloadOrHint(new File(item.getCurrentPath()), "下载");
    }

    /** 删除列：同样只对文件开放；无删除权限时整列留空。 */
    private Component buildDeleteCell(RemoteFileInfo item) {
        if (!canDelete()) {
            return new Span();
        }
        Button delete = new Button("删除", event -> confirmDelete(item));
        delete.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY,
                ButtonVariant.LUMO_ERROR);
        return delete;
    }

    // ------------------------------------------------------------------
    // 导航与加载
    // ------------------------------------------------------------------

    /**
     * 进入一个目录：写入导航历史 + 加载内容。
     *
     * @param path 目标目录；为 {@code null} / 空白时落到起始目录
     * @return 是否成功进入。失败时已经提示过原因，调用方只需要决定要不要还原输入框
     */
    private boolean navigateTo(String path) {
        String target = StrUtil.isBlank(path) ? resolveStartDir() : path;
        File dir = validateDir(target);
        if (dir == null) {
            return false;
        }
        pathHistory.addLast(dir.getAbsolutePath());
        currentDir = dir.getAbsolutePath();
        loadDir(dir);
        return true;
    }

    /**
     * 返回上一个访问过的目录。
     * <p>
     * 顶层时按钮是禁用的，正常情况下进不来；这里的判断是防御性的——
     * 万一历史栈被别处改动过，也不该抛 {@code NoSuchElementException}。
     */
    private void goBack() {
        if (pathHistory.size() <= 1) {
            Dialogs.info("已经在顶层目录了");
            return;
        }
        pathHistory.removeLast();
        String previous = pathHistory.peekLast();
        currentDir = previous;
        loadDir(new File(previous));
    }

    /** 重新加载当前目录（删除、上传、切换隐藏文件后调用），不动导航历史。 */
    private void reloadCurrentDir() {
        if (currentDir == null) {
            return;
        }
        loadDir(new File(currentDir));
    }

    private void loadDir(File dir) {
        // 路径框既是显示也是入口，切换目录后同步成新路径
        pathField.setValue(dir.getAbsolutePath());
        List<RemoteFileInfo> children = listChildren(dir);
        grid.setItems(children);

        long dirCount = children.stream().filter(item -> !Boolean.TRUE.equals(item.getIsFile())).count();
        statusLabel.setText("共 " + children.size() + " 项（目录 " + dirCount
                + "，文件 " + (children.size() - dirCount) + "）");
        // 顶层没有上一级可回，按钮禁用比"点了弹个提示"更直观
        backButton.setEnabled(pathHistory.size() > 1);
    }

    private List<RemoteFileInfo> listChildren(File dir) {
        File[] files = dir.listFiles();
        // listFiles() 在目录不可读、或路径已不是目录时返回 null。旧实现直接
        // Arrays.asList(files) → NPE，整页空白，还看不出是权限问题。
        if (files == null) {
            Dialogs.warn("目录无法读取，请确认路径与运行账号的权限：" + dir.getAbsolutePath());
            return new ArrayList<>();
        }

        boolean posix = supportsPosix();
        String osUser = System.getProperty("user.name");
        List<RemoteFileInfo> result = new ArrayList<>(files.length);
        for (File item : files) {
            if (!showHidden.getValue() && item.getName().startsWith(".")) {
                continue;
            }
            RemoteFileInfo info = toFileInfo(item, dir, posix, osUser);
            if (info != null) {
                result.add(info);
            }
        }
        result.sort(DEFAULT_ORDER);
        return result;
    }

    /**
     * 把一个目录项转成表格行数据。
     *
     * @return 读属性失败时返回 {@code null}，由调用方跳过——单个条目（断链、竞态删除、
     *         权限不足）出问题不该让整张列表打不开
     */
    private RemoteFileInfo toFileInfo(File item, File parent, boolean posix, String osUser) {
        Path path = item.toPath();
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            boolean directory = attrs.isDirectory();

            if (attrs.isSymbolicLink()) {
                try {
                    BasicFileAttributes target = Files.readAttributes(path, BasicFileAttributes.class);
                    directory = target.isDirectory();
                    // 用目标的大小与时间：软链自身的大小是路径长度，显示出来没意义
                    attrs = target;
                } catch (IOException brokenLink) {
                    // 断链：当成普通文件，进不去但看得见，用户能自己判断要不要删
                    directory = false;
                }
            }

            RemoteFileInfo info = new RemoteFileInfo();
            info.setFileName(item.getName());
            info.setIsFile(!directory);
            info.setParentPath(parent.getAbsolutePath());
            info.setCurrentDir(item.getAbsolutePath());
            info.setLastModify(Util.formatDate(new Date(attrs.lastModifiedTime().toMillis())));
            info.setSize(directory ? "-" : UiFactory.formatFileSize(attrs.size()));
            info.setUserName(readOwner(path, osUser));
            info.setPermission(posix ? readPermissions(path) : "");
            return info;
        } catch (IOException e) {
            log.warn("读取文件属性失败，已跳过：{}（{}）", item.getAbsolutePath(), e.getMessage());
            return null;
        }
    }

    /** 默认打开的目录。 */
    private static String resolveStartDir() {
        String user = System.getProperty("user.name");
        if (supportsPosix() && "root".equals(user)) {
            // root 的家目录就是 /root；容器里 user.home 有时被设成 /，直接给 /root 更符合预期
            return "/root";
        }
        String home = System.getProperty("user.home");
        return StrUtil.isBlank(home) ? System.getProperty("user.dir") : home;
    }

    private static File validateDir(String path) {
        File dir = new File(path);
        if (!dir.exists()) {
            Dialogs.warn("目录不存在：" + path);
            return null;
        }
        if (!dir.isDirectory()) {
            Dialogs.warn("这是文件，不是目录：" + path);
            return null;
        }
        if (!dir.canRead()) {
            Dialogs.warn("目录没有读取权限，请检查运行账号：" + path);
            return null;
        }
        return dir;
    }

    // ------------------------------------------------------------------
    // 上传
    // ------------------------------------------------------------------

    /**
     * 决定上传文件写到哪儿。
     * <p>
     * 这个方法在<b>上传请求线程</b>里执行（不是 UI 线程），所以这里做的检查是真正的
     * 服务端校验：行内的上传控件在没权限时压根不会渲染，但「不渲染」只是不给入口，
     * 请求本身是可以被构造出来的——上传会真的往磁盘写文件，这道检查不能省。
     *
     * @throws IOException 校验不通过。异常会让本次上传失败并在控件上显示原因
     */
    private File resolveUploadTarget(String dirPath, String rawName) throws IOException {
        if (!hasPermission(Constants.UPLOAD)) {
            throw new IOException("权限不足，禁止上传");
        }
        File dir = new File(dirPath);
        if (!dir.isDirectory()) {
            throw new IOException("目标目录不存在：" + dirPath);
        }
        File target = new File(dir, safeFileName(rawName));
        if (target.exists()) {
            // 覆盖是旧行为（FileOutputStream 直接截断写），但对生产目录来说值得留个记录
            log.warn("上传将覆盖已存在的文件：{}", target.getAbsolutePath());
        }
        return target;
    }

    /**
     * 上传完成。运行在 UI 线程上（{@code UploadHandler} 的成功回调被
     * {@code UI.access} 包着），可以直接改组件。
     */
    private void onUploadFinished(String targetDir, File file) {
        log.info("上传完成：{}", file.getAbsolutePath());
        Dialogs.success("已上传到 " + targetDir + "：" + file.getName());
        // 目标目录可能是当前列表里的子目录，刷新一下保证大小/计数是最新的；
        // 具体落到哪个目录由上面的提示文字说明，不靠"列表里看见"来判断
        reloadCurrentDir();
    }

    /**
     * 只取文件名，丢掉任何路径成分。
     * <p>
     * multipart 里的 filename 是客户端说的，老浏览器会给完整路径，
     * 恶意客户端还能塞 {@code ../../}。直接拼进目标目录就是目录穿越。
     */
    private static String safeFileName(String rawName) throws IOException {
        if (StrUtil.isBlank(rawName)) {
            throw new IOException("上传文件名为空");
        }
        String name = rawName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (StrUtil.isBlank(name) || ".".equals(name) || "..".equals(name)) {
            throw new IOException("非法的上传文件名：" + rawName);
        }
        return name;
    }

    private static String rejectedMessage(FileRejectedEvent event) {
        String reason = StrUtil.isBlank(event.getErrorMessage()) ? "不满足上传限制" : event.getErrorMessage();
        String name = StrUtil.isBlank(event.getFileName()) ? "该文件" : "「" + event.getFileName() + "」";
        return name + "被拒绝：" + reason;
    }

    // ------------------------------------------------------------------
    // 删除
    // ------------------------------------------------------------------

    private void confirmDelete(RemoteFileInfo item) {
        if (!Boolean.TRUE.equals(item.getIsFile())) {
            // 旧实现是等用户点了确认才说"暂不支持删除目录"，白让人点一次
            Dialogs.warn("为安全起见，暂不支持删除目录：" + item.getFileName());
            return;
        }
        Dialogs.confirmDanger("确认删除",
                "确定删除文件「" + item.getFileName() + "」？此操作不可撤销。",
                "删除", () -> deleteFile(item));
    }

    private void deleteFile(RemoteFileInfo item) {
        // 确认框弹出到点击之间，权限可能被管理员改掉；删除这种不可逆操作再查一次
        if (!canDelete()) {
            Dialogs.warn("权限不足，请联系管理员");
            return;
        }
        File file = new File(item.getCurrentPath());
        try {
            Files.delete(file.toPath());
            log.info("已删除文件：{}", file.getAbsolutePath());
            Dialogs.success("已删除：" + item.getFileName());
            reloadCurrentDir();
        } catch (IOException e) {
            // 文件已被别人删掉、目录只读、文件被占用都走这里
            log.error("删除文件失败：{}", file.getAbsolutePath(), e);
            Dialogs.error("删除失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 权限与文件属性
    // ------------------------------------------------------------------

    private static boolean canUpload() {
        return hasPermission(Constants.UPLOAD);
    }

    private static boolean canDelete() {
        return hasPermission(Constants.DELETE);
    }

    /**
     * 权限判定统一入口。取不到当前用户（后台线程、未登录）时<b>一律不放行</b>——
     * 旧实现用的是 {@code VaadinSession.getAttribute("userName")}，取不到直接 NPE，
     * 那种「炸掉」和这里「拒绝」的差别在于：拒绝是有日志、有提示的。
     */
    private static boolean hasPermission(String code) {
        User user = CurrentUser.get();
        return user != null && user.hasPermission(code);
    }

    private static String readOwner(Path path, String fallback) {
        try {
            String name = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).getName();
            // Windows 返回 "机器名\用户"，只留用户名
            int slash = name.lastIndexOf('\\');
            return slash >= 0 ? name.substring(slash + 1) : name;
        } catch (IOException | UnsupportedOperationException e) {
            return fallback;
        }
    }

    private static String readPermissions(Path path) {
        try {
            return PosixFilePermissions.toString(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS));
        } catch (IOException | UnsupportedOperationException e) {
            return "";
        }
    }

    /**
     * 当前文件系统是否支持 POSIX 属性视图。
     * <p>
     * 比旧代码的 {@code SystemUtil.getOsInfo().isLinux()} 准：macOS 同样支持，
     * 而 Windows 上挂载的 ext4 镜像也支持 —— 判据应该是「文件系统认不认」，
     * 而不是「操作系统叫什么」。
     */
    private static boolean supportsPosix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }
}
