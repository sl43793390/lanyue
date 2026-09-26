package com.sl.ui.remote;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sl.entity.ConnectionInfo;
import com.sl.entity.LogPath;
import com.sl.mapper.ConnectionInfoMapper;
import com.sl.mapper.LogPathMapper;
import com.sl.ui.component.Dialogs;
import com.sl.ui.component.CodeEditor;
import com.sl.ui.component.UiFactory;
import com.sl.ui.component.ViewBase;
import com.sl.util.SSHClientUtil;
import com.sl.util.Util;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.server.streams.DownloadHandler;
import com.vaadin.flow.spring.annotation.SpringComponent;
import com.vaadin.flow.spring.annotation.UIScope;
import net.schmizz.sshj.sftp.FileAttributes;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 远程日志搜索。
 * <p>
 * 对应旧项目 {@code com.so.component.remote.RemoteLogSearchComponent}：
 * 选一台机器 → SFTP 打开一个目录 → 按后缀过滤出日志文件 → 下载到本机。
 * <p>
 * 与旧实现的差异：
 * <ul>
 *   <li>连接来源从「远程登录页跳转带入」改成页面内选主机（免登录服务器列表的同一套
 *       数据：connection_info 表 + remoteServerList.conf），可以独立使用了；</li>
 *   <li>文件列表从手拆 {@code longname} 字符串（按空格 split 再数下标）改成 sshj 的
 *       结构化 {@link RemoteResourceInfo}，日期/大小直接来自 SFTP 属性，不再有解析歧义；</li>
 *   <li>下载改两步：先 SFTP 取到本机临时目录，再给一个真正的文件下载链接——
 *       旧的 {@code FileDownloader + StreamSource} 在 Vaadin 24 下已移除，
 *       而「点下载时才去远端拉流」的写法在流式下载 API 里既没法做权限校验也没法提示进度；</li>
 *   <li>路径历史仍存 {@code log_path} 表（按主机区分），输入框填过的路径自动入历史。</li>
 * </ul>
 */
@SpringComponent
@UIScope
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class RemoteLogSearchView extends ViewBase {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(RemoteLogSearchView.class);

    /** 允许在线预览的文件大小上限：10 MB。再大的日志浏览器端渲染也扛不住，引导用户下载。 */
    private static final long PREVIEW_MAX_BYTES = 10L * 1024 * 1024;

    /** 一个搜索结果的展示模型 */
    public record FileRow(String name, String fullPath, String mtime, String size, long sizeBytes) {
    }

    private final transient ConnectionInfoMapper connectionInfoMapper;
    private final transient LogPathMapper logPathMapper;

    private final ComboBox<ConnectionInfo> hostCombo = new ComboBox<>();
    private final TextField pathField = UiFactory.textField();
    private final ComboBox<String> historyCombo = new ComboBox<>();
    private final ComboBox<String> suffixCombo = UiFactory.combo(Util.getFileSuffix(), "log");
    private final ComboBox<String> containsCombo = UiFactory.combo(List.of("否", "是"), "否");
    private final Grid<FileRow> grid = UiFactory.grid(FileRow.class);
    private final Span statusLabel = new Span();

    /** 当前已建立的 SSH 连接及其对应主机；换了主机要重连 */
    private transient SSHClientUtil ssh;
    private String connectedHost;

    /** 路径历史（下拉框的数据源），随所选主机变化 */
    private final Set<String> historyItems = new LinkedHashSet<>();

    /** 本机临时目录：远程文件先取到这里，再交给浏览器下载 */
    private final File tempDir = new File(System.getProperty("java.io.tmpdir"), "lanyue-remote-dl");

    public RemoteLogSearchView(ConnectionInfoMapper connectionInfoMapper, LogPathMapper logPathMapper) {
        this.connectionInfoMapper = connectionInfoMapper;
        this.logPathMapper = logPathMapper;

        if (!tempDir.isDirectory() && !tempDir.mkdirs()) {
            log.warn("临时下载目录 {} 创建失败，下载功能可能不可用", tempDir.getAbsolutePath());
        }

        add(title("远程日志搜索"));
        add(subtitle("选择服务器后按路径搜索日志文件，可下载到本机查看。"
                + "「包含模式」用于 catalina.log.20200717 这类后缀带日期的文件名。"));

        hostCombo.setItemLabelGenerator(info -> info.getIdHost()
                + (StrUtil.isBlank(info.getIdUser()) ? "" : "（" + info.getIdUser() + "）"));
        hostCombo.setWidth("280px");
        pathField.setPlaceholder("/var/log/xxx");
        pathField.setWidth("280px");
        suffixCombo.setWidth("110px");
        containsCombo.setWidth("90px");
        historyCombo.setWidth("280px");
        containsCombo.getElement().setAttribute("title",
                "选「是」时文件名里包含后缀即命中（如 catalina.log.20200717 匹配 log）");

        Button searchBtn = UiFactory.primary("搜索日志", this::search);
        HorizontalLayout bar = toolbar(
                UiFactory.fieldRow("目标服务器", "80px", hostCombo),
                UiFactory.fieldRow("日志路径", "68px", pathField),
                searchBtn);
        add(bar);
        HorizontalLayout bar2 = toolbar(
                UiFactory.fieldRow("历史路径", "68px", historyCombo),
                UiFactory.fieldRow("选择后缀：", suffixCombo),
                UiFactory.fieldRow("包含模式：", containsCombo), spacer(), statusLabel);
        add(bar2);

        buildGrid();
        VerticalLayout fill = fill(grid);
        add(fill);
        setFlexGrow(1, fill);

        loadHosts();
        // 换主机时加载该机器自己的路径历史
        hostCombo.addValueChangeListener(e -> {
            if (e.getValue() != null) {
                loadHistory(e.getValue().getIdHost());
            }
        });
        historyCombo.addValueChangeListener(e -> {
            if (StrUtil.isNotBlank(e.getValue())) {
                pathField.setValue(e.getValue());
            }
        });
    }

    // ------------------------------------------------------------------
    // 主机与历史
    // ------------------------------------------------------------------

    private void loadHosts() {
        List<ConnectionInfo> hosts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try {
            List<ConnectionInfo> fromDb = connectionInfoMapper.selectList(new QueryWrapper<>());
            if (fromDb != null) {
                for (ConnectionInfo info : fromDb) {
                    addHost(hosts, seen, info);
                }
            }
        } catch (Exception e) {
            log.warn("读取数据库中的服务器列表失败：{}", e.getMessage());
        }
        try {
            for (String line : Util.getRemoteServerList()) {
                String[] split = line.split("=");
                if (split.length < 4) {
                    continue;
                }
                String keyPath = split.length > 4 ? split[4] : null;
                addHost(hosts, seen, new ConnectionInfo(split[0], split[3], split[1], split[2], keyPath));
            }
        } catch (Exception e) {
            log.warn("读取 remoteServerList.conf 失败：{}", e.getMessage());
        }
        hostCombo.setItems(hosts);
        statusLabel.setText("共 " + hosts.size() + " 台可选机器");
    }

    private static void addHost(List<ConnectionInfo> hosts, Set<String> seen, ConnectionInfo info) {
        if (info == null || StrUtil.isBlank(info.getIdHost())) {
            return;
        }
        String key = info.getIdHost() + ":" + StrUtil.blankToDefault(info.getCdPort(), "22") + ":"
                + StrUtil.nullToEmpty(info.getIdUser());
        if (seen.add(key)) {
            hosts.add(info);
        }
    }

    /** 该主机下搜过的路径（log_path 表按 id_loghost 存） */
    private void loadHistory(String host) {
        historyItems.clear();
        try {
            QueryWrapper<LogPath> wrapper = new QueryWrapper<>();
            wrapper.eq("id_loghost", host);
            for (LogPath p : logPathMapper.selectList(wrapper)) {
                if (StrUtil.isNotBlank(p.getIdLogPath())) {
                    historyItems.add(p.getIdLogPath());
                }
            }
        } catch (Exception e) {
            log.warn("读取路径历史失败：{}", e.getMessage());
        }
        historyCombo.setItems(historyItems);
    }

    // ------------------------------------------------------------------
    // 搜索
    // ------------------------------------------------------------------

    private void buildGrid() {
        grid.addColumn(FileRow::name).setHeader("文件名").setAutoWidth(true);
        grid.addColumn(FileRow::mtime).setHeader("修改日期").setAutoWidth(true);
        grid.addColumn(FileRow::size).setHeader("文件大小").setAutoWidth(true);
        grid.addComponentColumn(this::buildPreviewCell).setHeader("预览").setAutoWidth(true);
        grid.addComponentColumn(this::buildDownloadCell).setHeader("文件下载").setAutoWidth(true);
    }

    private void search() {
        ConnectionInfo info = hostCombo.getValue();
        if (info == null) {
            Dialogs.warn("请先选择目标服务器");
            return;
        }
        String path = StrUtil.trim(pathField.getValue());
        if (StrUtil.isBlank(path)) {
            Dialogs.warn("请输入日志路径后再搜索");
            return;
        }

        boolean needConnect = ssh == null || !info.getIdHost().equals(connectedHost);
        Dialogs.info(needConnect ? "正在连接 " + info.getIdHost() + "……" : "正在搜索……");
        getUI().ifPresent(ui -> new Thread(() -> {
            try {
                if (needConnect) {
                    SSHClientUtil client = SSHClientUtil.connect(info);
                    // 先建好新连接再关旧的：连接失败时旧的还能留着用
                    SSHClientUtil old = ssh;
                    ssh = client;
                    connectedHost = info.getIdHost();
                    if (old != null) {
                        old.closeConnection();
                    }
                }
                doSearch(path);
            } catch (Exception e) {
                log.warn("远程日志搜索失败（{}）：{}", info.getIdHost(), e.getMessage());
                ui.access(() -> {
                    statusLabel.setText("");
                    Dialogs.error("搜索失败：" + StrUtil.emptyToDefault(e.getMessage(), e.getClass().getSimpleName())
                            + "。请检查主机连通性与路径是否正确。");
                });
            }
        }, "remote-logsearch-" + info.getIdHost()).start());
    }

    /** 后台线程执行：SFTP ls + 过滤 + 写历史。所有 UI 更新都在 ui.access 里。 */
    private void doSearch(String path) throws IOException {
        SFTPClient sftp = ssh.getSftpClient();
        try {
            sftp.lstat(path);
        } catch (IOException e) {
            getUI().ifPresent(ui -> ui.access(() ->
                    Dialogs.warn("目录不存在或没有读取权限：" + path)));
            return;
        }

        List<RemoteResourceInfo> entries;
        try {
            entries = sftp.ls(path);
        } catch (IOException e) {
            log.warn("读取远程目录 {} 失败：{}", path, e.getMessage());
            getUI().ifPresent(ui -> ui.access(() ->
                    Dialogs.warn("该目录没有读取权限，请联系管理员：" + path)));
            return;
        }

        String suffix = StrUtil.blankToDefault(suffixCombo.getValue(), "log");
        boolean contains = "是".equals(containsCombo.getValue());
        List<FileRow> rows = new ArrayList<>();
        for (RemoteResourceInfo en : entries) {
            if (en.isDirectory()) {
                continue;
            }
            String name = en.getName();
            boolean hit = contains ? name.endsWith(suffix) || name.contains(suffix)
                    : "*".equals(suffix) || name.endsWith(suffix);
            if (!hit) {
                continue;
            }
            FileAttributes attrs = en.getAttributes();
            rows.add(new FileRow(name, path + "/" + name,
                    formatMtime(attrs), formatSize(attrs), attrs.getSize()));
        }
        rows.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));

        saveHistory(path);

        getUI().ifPresent(ui -> ui.access(() -> {
            grid.setItems(rows);
            statusLabel.setText("「" + path + "」命中 " + rows.size() + " 个文件");
            if (rows.isEmpty()) {
                Dialogs.warn("该目录下没有匹配 «" + suffix + "» 的文件");
            }
        }));
    }

    /** 搜过的路径记进 log_path 表 + 下拉历史（重复插入静默忽略，与旧行为一致）。 */
    private void saveHistory(String path) {
        try {
            LogPath record = new LogPath();
            record.setIdLoghost(connectedHost);
            record.setIdLogPath(path);
            logPathMapper.insert(record);
        } catch (Exception e) {
            log.debug("路径历史已存在：{}", path);
        }
        getUI().ifPresent(ui -> ui.access(() -> {
            if (historyItems.add(path)) {
                historyCombo.setItems(historyItems);
            }
        }));
    }

    // ------------------------------------------------------------------
    // 预览
    // ------------------------------------------------------------------

    /** 预览列：固定一个小按钮，点击后按大小规则决定直接预览还是引导下载。 */
    private Component buildPreviewCell(FileRow row) {
        Button btn = UiFactory.small("预览", () -> previewLog(row));
        btn.getElement().setAttribute("title",
                row.sizeBytes() > PREVIEW_MAX_BYTES
                        ? "文件超过 10M，请下载后查看"
                        : "在线预览日志内容");
        return btn;
    }

    /**
     * 在线预览：小于 10M 的文件先 SFTP 取到临时目录，再弹窗用统一的 CodeEditor
     * 只读展示；超过 10M 提示下载后查看——CodeMirror 渲染十几 MB 的文本会明显卡顿，
     * 与其让浏览器假死不如明确引导走下载。
     */
    private void previewLog(FileRow row) {
        if (ssh == null) {
            Dialogs.warn("SSH 连接已断开，请重新搜索后再预览");
            return;
        }
        if (row.sizeBytes() > PREVIEW_MAX_BYTES) {
            Dialogs.warn("「" + row.name() + "」大小 " + row.size()
                    + "，超过 10M 无法在线预览，请先下载到本机后查看");
            return;
        }
        Dialogs.info("正在取回 " + row.name() + " ……");
        getUI().ifPresent(ui -> new Thread(() -> {
            File local = new File(tempDir, System.currentTimeMillis() + "_preview_" + row.name());
            try {
                ssh.downloadFile(row.fullPath(), local.getAbsolutePath());
                String content = readPreviewText(local);
                ui.access(() -> openPreviewDialog(row, content, local));
            } catch (Exception e) {
                log.warn("预览取回远程文件 {} 失败：{}", row.fullPath(), e.getMessage());
                //noinspection ResultOfMethodCallIgnored
                local.delete();
                ui.access(() -> Dialogs.error("预览失败："
                        + StrUtil.emptyToDefault(e.getMessage(), e.getClass().getSimpleName())));
            }
        }, "remote-preview-" + row.name()).start());
    }

    /** 弹出 1000×500 的预览窗：CodeEditor 只读，模式按文件名推断。关闭即删临时文件。 */
    private void openPreviewDialog(FileRow row, String content, File tempFile) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("日志预览：" + row.name() + "（" + row.size() + "）");
        dialog.setWidth("1500px");
        dialog.setHeight("800px");
        dialog.setCloseOnEsc(true);

        CodeEditor editor = new CodeEditor(CodeEditor.suggestMode(row.name()));
        editor.setReadOnly(true);
        editor.setValue(content);
        editor.setSizeFull();

        VerticalLayout box = new VerticalLayout(editor);
        box.setPadding(false);
        box.setSpacing(false);
        box.setSizeFull();
        dialog.add(box);

        dialog.getFooter().add(new Span("共 " + content.length() + " 字符"));
        dialog.getFooter().add(Dialogs.cancelButton(dialog::close));
        // 关闭（点按钮、点叉、Esc 都算）后删临时文件，不往临时目录里攒垃圾
        dialog.addOpenedChangeListener(e -> {
            if (!e.isOpened()) {
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();
            }
        });
        dialog.open();
    }

    /**
     * 预览文本解码：先按 UTF-8 读，出现替换符（U+FFFD）再按 GBK 重试一次——
     * 老服务器上的日志不少还是 GBK 编码，直接按 UTF-8 会读出一屏乱码。
     */
    private static String readPreviewText(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (utf8.indexOf('\uFFFD') < 0) {
            return utf8;
        }
        String gbk = new String(bytes, Charset.forName("GBK"));
        return gbk.indexOf('\uFFFD') < 0 ? gbk : utf8;
    }

    // ------------------------------------------------------------------
    // 下载
    // ------------------------------------------------------------------

    /**
     * 下载两步走：点「下载」先把远端文件 SFTP 取到本机临时目录，完成后该行的按钮
     * 原地变成真正的文件下载链接，再点一次就把临时文件交给浏览器。
     * 旧的「点下载时才拉远端流」在 Vaadin 24 的下载 API 里做不了进度与错误提示。
     */
    private Component buildDownloadCell(FileRow row) {
        HorizontalLayout cell = new HorizontalLayout();
        cell.setSpacing(false);
        cell.getStyle().set("gap", "4px");
        cell.setAlignItems(com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.CENTER);

        // 不能把监听器写进构造器参数里：lambda 内要引用 fetchBtn 自己（禁用按钮），
        // 局部变量在自己的初始化器里被引用是编译错误，所以先建按钮、再挂监听
        Button fetchBtn = UiFactory.small("下载", () -> { });
        fetchBtn.addClickListener(click -> {
            if (ssh == null) {
                Dialogs.warn("SSH 连接已断开，请重新搜索后再下载");
                return;
            }
            fetchBtn.setEnabled(false);
            Dialogs.info("正在取回 " + row.name() + " ……");
            getUI().ifPresent(ui -> new Thread(() -> {
                try {
                    File local = new File(tempDir, System.currentTimeMillis() + "_" + row.name());
                    ssh.downloadFile(row.fullPath(), local.getAbsolutePath());
                    log.info("远程文件已取回：{} -> {}", row.fullPath(), local.getAbsolutePath());
                    ui.access(() -> {
                        Anchor anchor = new Anchor(DownloadHandler.forFile(local, row.name()), "保存到本机");
                        anchor.addClassName("download-link");
                        anchor.setTitle("文件已取回，点击保存（" + row.size() + "）");
                        cell.remove(fetchBtn);
                        cell.add(anchor);
                        Dialogs.success("已取回 " + row.name() + "，点击「保存到本机」完成下载");
                    });
                } catch (Exception e) {
                    log.warn("下载远程文件 {} 失败：{}", row.fullPath(), e.getMessage());
                    ui.access(() -> {
                        fetchBtn.setEnabled(true);
                        Dialogs.error("下载失败：" + StrUtil.emptyToDefault(e.getMessage(), e.getClass().getSimpleName()));
                    });
                }
            }, "remote-dl-" + row.name()).start());
        });
        cell.add(fetchBtn);
        return cell;
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static String formatMtime(FileAttributes attrs) {
        long mtime = attrs.getMtime();
        if (mtime <= 0) {
            return "未知";
        }
        return Util.formatDate(new Date(mtime * 1000L));
    }

    private static String formatSize(FileAttributes attrs) {
        long size = attrs.getSize();
        if (size <= 0) {
            return "未知";
        }
        return UiFactory.formatFileSize(size);
    }

    // ------------------------------------------------------------------
    // 收尾
    // ------------------------------------------------------------------

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        // 连接是本组件自己建的，页面关闭必须释放，否则每开一次漏一条 SSH
        if (ssh != null) {
            ssh.closeConnection();
            ssh = null;
            connectedHost = null;
        }
        super.onDetach(detachEvent);
    }
}
