package com.sl.ui.local;

import com.sl.entity.LogPath;
import com.sl.entity.PathEntityInfo;
import com.sl.mapper.LogPathMapper;
import com.sl.ui.component.Dialogs;
import com.sl.ui.component.TabHost;
import com.sl.ui.component.UiFactory;
import com.sl.ui.component.ViewBase;
import com.sl.util.Constants;
import com.sl.util.Util;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 本地日志搜索：按目录 + 后缀列出文件，可预览、可下载。
 * <p>
 * 从旧项目的 {@code LogSearchComponent} 重写而来。
 * <p>
 * <b>重写时改掉的几处</b>：
 * <ul>
 *   <li>布局不再用 {@code AbsoluteLayout} + {@code left:187px} 死算像素。
 *       那一排「路径 / 后缀 / 编码 / 搜索」在旧代码里是按字符数估算位置摆的，
 *       窗口一窄就互相压叠。这里换成 flex 排布，窄屏自动换行。</li>
 *   <li>{@code listFiles()} 在目录无读权限时会返回 {@code null}，旧实现直接遍历导致 NPE。
 *       这里在进入循环前判空并给出可读提示。</li>
 *   <li>文件大小旧实现用 {@code length / 1024} 整除，小于 1KB 的文件一律显示 {@code 0kb}。
 *       这里按 B/KB/MB/GB 分档保留两位小数。</li>
 *   <li>编码下拉框旧实现漏了设默认值，配合 {@code setEmptySelectionAllowed(false)}
 *       会让它显示为空；预览时拿到 {@code null} 编码后退回平台默认编码，
 *       在 Linux 上打开 GBK 日志就乱码。这里显式默认 UTF-8。</li>
 *   <li>预览打开的详情页不再是「当前页里嵌一层 TabSheet」，而是交给主框架开一个同级标签，
 *       见 {@link TabHost}。旧实现往全局静态 TabSheet 上挂，多用户会互相串。</li>
 * </ul>
 */
@Service
@Scope("prototype")
public class LogSearchView extends ViewBase {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(LogSearchView.class);

    /** 历史路径下拉的宽度，与上面的路径输入框对齐 */
    private static final String PATH_FIELD_WIDTH = "380px";

    /** 后缀下拉里的通配项，表示不过滤 */
    private static final String ANY_SUFFIX = "*";

    private final transient LogPathMapper logPathMapper;

    private final TextField pathField = new TextField();
    private final ComboBox<String> historyCombo = new ComboBox<>();
    private final ComboBox<String> suffixCombo = new ComboBox<>();
    private final ComboBox<String> encodingCombo = new ComboBox<>();
    private final ComboBox<String> containCombo = new ComboBox<>();
    private final Grid<PathEntityInfo> resultGrid = UiFactory.grid(PathEntityInfo.class);
    private final Span statusLabel = new Span();

    /** 历史路径。用 LinkedHashSet 保序去重，避免下拉项每次刷新都换顺序。 */
    private final Set<String> historyPaths = new LinkedHashSet<>();

    private List<PathEntityInfo> searchResult = new ArrayList<>();

    public LogSearchView(LogPathMapper logPathMapper) {
        this.logPathMapper = logPathMapper;
        addClassName("log-search-view");
        buildLayout();
        loadHistory();
    }

    // ------------------------------------------------------------------
    // 界面
    // ------------------------------------------------------------------

    private void buildLayout() {
        add(title("本地日志搜索"));
        add(subtitle("输入日志所在目录，按后缀筛选后预览或下载。预览会在新标签页中打开。"));
        add(UiFactory.divider());
        add(buildSearchBar());
        add(buildHistoryBar());
        add(buildResultHeader());
        add(resultGrid);

        // 表格吃掉剩余高度，日志列表很长时只在表格内部滚动
        setFlexGrow(1, resultGrid);
    }

    /** 第一行：路径 + 后缀 + 编码 + 搜索。 */
    private HorizontalLayout buildSearchBar() {
        pathField.setPlaceholder("例如 /opt/app/logs");
        pathField.setWidth(PATH_FIELD_WIDTH);
        pathField.setClearButtonVisible(true);
        pathField.setTitle("请输入文件所在目录，点搜索后列出该目录下的日志文件");
        // 回车即搜索。运维习惯敲完路径直接回车，不该逼他再去点按钮。
        pathField.addKeyPressListener(
                com.vaadin.flow.component.Key.ENTER, event -> doSearch());

        suffixCombo.setItems(Util.getFileSuffix());
        suffixCombo.setValue("log");
        suffixCombo.setWidth("120px");
        // 允许手输后缀，比如搜 .out、.txt，不必回头改 fileSuffix.conf
        suffixCombo.setAllowCustomValue(true);

        encodingCombo.setItems(Constants.UTF_8, Constants.GBK, Constants.ISO_8859_1);
        encodingCombo.setValue(Constants.UTF_8);
        encodingCombo.setWidth("140px");

        HorizontalLayout bar = toolbar(
                UiFactory.fieldRow("日志目录", "80px", pathField),
                UiFactory.fieldRow("后缀", "52px", suffixCombo),
                UiFactory.fieldRow("编码", "52px", encodingCombo),
                UiFactory.primary("搜索日志", this::doSearch),
                spacer());
        bar.getStyle().set("flex-wrap", "wrap");
        bar.getStyle().set("gap", "16px");
        return bar;
    }

    /** 第二行：历史路径 + 包含模式 + 状态。 */
    private HorizontalLayout buildHistoryBar() {
        historyCombo.setWidth(PATH_FIELD_WIDTH);
        historyCombo.setPlaceholder("从历史记录中选择");
        historyCombo.setClearButtonVisible(true);
        historyCombo.addValueChangeListener(event -> {
            if (event.getValue() != null && !event.getValue().isBlank()) {
                // 选中历史项即视为要搜它，省掉一次点击
                pathField.setValue(event.getValue());
                doSearch();
            }
        });

        containCombo.setItems("否", "是");
        containCombo.setValue("否");
        containCombo.setWidth("100px");
        // ComboBox 没有 setTitle(String)（TextField 有），统一走元素属性
        containCombo.getElement().setAttribute("title",
                "选「是」时按文件名包含后缀匹配（如 log 会匹配 app.log.1）；选「否」时按结尾精确匹配");

        statusLabel.addClassName("search-status");

        HorizontalLayout bar = toolbar(
                UiFactory.fieldRow("历史记录", "80px", historyCombo),
                UiFactory.fieldRow("包含模式", "80px", containCombo),
                spacer(),
                statusLabel);
        bar.getStyle().set("flex-wrap", "wrap");
        bar.getStyle().set("gap", "16px");
        return bar;
    }

    private HorizontalLayout buildResultHeader() {
        configureGrid();
        HorizontalLayout bar = toolbar(section("搜索结果（点“预览”在新标签打开）"), spacer());
        return bar;
    }

    private void configureGrid() {
        resultGrid.addColumn(PathEntityInfo::getFileName)
                .setHeader("文件名")
                .setAutoWidth(true)
                .setFlexGrow(3);
        resultGrid.addColumn(PathEntityInfo::getFileSize)
                .setHeader("大小")
                .setAutoWidth(true)
                .setFlexGrow(0);
        resultGrid.addColumn(PathEntityInfo::getCreateDate)
                .setHeader("修改时间")
                .setAutoWidth(true)
                .setFlexGrow(1);
        resultGrid.addColumn(PathEntityInfo::getParentPath)
                .setHeader("所在目录")
                .setAutoWidth(true)
                .setFlexGrow(2);
        resultGrid.addComponentColumn(info -> {
            HorizontalLayout actions = new HorizontalLayout();
            actions.setSpacing(false);
            actions.getStyle().set("gap", "12px");
            actions.setAlignItems(Alignment.CENTER);

            actions.add(UiFactory.small("预览", () -> openDetail(info)));

            File file = new File(info.getAbsolutePath());
            actions.add(UiFactory.downloadOrHint(file, "下载"));
            return actions;
        }).setHeader("操作").setAutoWidth(true).setFlexGrow(0);

        // 双击整行也能预览，比精准点那个小按钮省事
        resultGrid.addItemDoubleClickListener(event -> openDetail(event.getItem()));
    }

    // ------------------------------------------------------------------
    // 行为
    // ------------------------------------------------------------------

    private void doSearch() {
        String manualPath = pathField.getValue();
        String path = (manualPath != null && !manualPath.isBlank()) ? manualPath.trim() : historyCombo.getValue();

        if (path == null || path.isBlank()) {
            Dialogs.warn("请先输入日志目录，或从历史记录中选择一个");
            return;
        }

        File dir = new File(path);
        if (!dir.exists()) {
            Dialogs.warn("该目录不存在：" + path);
            return;
        }
        if (!dir.isDirectory()) {
            Dialogs.warn("请输入目录路径，不能是单个文件：" + path);
            return;
        }
        if (!dir.canRead()) {
            Dialogs.warn("该目录没有读取权限，请检查运行账号的权限：" + path);
            return;
        }

        rememberPath(path);
        try {
            listFiles(dir);
        } catch (IOException e) {
            log.error("读取目录失败：{}", path, e);
            Dialogs.error("读取目录失败：" + e.getMessage());
        }
    }

    private void listFiles(File dir) throws IOException {
        String suffix = suffixCombo.getValue() == null ? "log" : suffixCombo.getValue().trim();
        boolean containsMode = "是".equals(containCombo.getValue());
        boolean anySuffix = ANY_SUFFIX.equals(suffix);

        File[] files = dir.listFiles();
        // listFiles() 在目录不可读或不是目录时返回 null，旧实现直接遍历 -> NPE
        if (files == null) {
            Dialogs.warn("该目录无法读取，请确认路径与权限：" + dir.getAbsolutePath());
            return;
        }

        List<PathEntityInfo> found = new ArrayList<>();
        for (File item : files) {
            if (!item.isFile()) {
                // 只列文件。子目录由「文件管理」模块负责，这里混进来只会干扰搜索。
                continue;
            }
            String name = item.getName();
            boolean matched = containsMode ? name.contains(suffix) : (anySuffix || name.endsWith(suffix));
            if (!matched) {
                continue;
            }
            PathEntityInfo info = new PathEntityInfo();
            info.setFileName(name);
            info.setFileSize(formatFileSize(item.length()));
            info.setAbsolutePath(item.getAbsolutePath());
            info.setParentPath(item.getParent());
            info.setSuffix(suffix);
            info.setCreateDate(Util.formatDate(new Date(item.lastModified())));
            found.add(info);
        }

        Collections.sort(found);
        searchResult = found;
        resultGrid.setItems(found);

        statusLabel.setText("共 " + found.size() + " 个文件");
        if (found.isEmpty()) {
            Dialogs.info("目录下没有匹配「" + suffix + "」的文件");
        }
    }

    /**
     * 打开文件详情。
     * <p>
     * Office / PDF 这类二进制文档不在页内预览（浏览器渲染不了），提示下载后查看——
     * 这是旧项目就有的行为，保留。
     */
    private void openDetail(PathEntityInfo info) {
        String lower = info.getFileName() == null ? "" : info.getFileName().toLowerCase();
        if (lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx")
                || lower.endsWith(".xls") || lower.endsWith(".xlsx")) {
            Dialogs.info("该格式不支持在线预览，请下载后查看");
            return;
        }

        TabHost host = TabHost.current();
        if (host == null) {
            Dialogs.warn("未能连接到主框架，无法打开标签页");
            return;
        }
        String encoding = encodingCombo.getValue();
        host.open(info.getFileName(), () -> new LogDetailView(info, encoding));
    }

    // ------------------------------------------------------------------
    // 历史记录
    // ------------------------------------------------------------------

    /** 历史路径持久化在 {@code log_path} 表里，键是 localhost。 */
    private void loadHistory() {
        try {
            QueryWrapper<LogPath> query = new QueryWrapper<>();
            query.eq("id_loghost", "localhost");
            List<LogPath> rows = logPathMapper.selectList(query);
            for (LogPath row : rows) {
                if (row.getIdLogPath() != null && !row.getIdLogPath().isBlank()) {
                    historyPaths.add(row.getIdLogPath());
                }
            }
        } catch (Exception e) {
            // 历史记录读不到不影响搜索主流程，降级为空列表即可
            log.warn("读取日志搜索历史失败：{}", e.getMessage());
        }
        refreshHistoryCombo();
    }

    private void rememberPath(String path) {
        if (historyPaths.contains(path)) {
            return;
        }
        historyPaths.add(path);
        refreshHistoryCombo();
        try {
            LogPath row = new LogPath();
            row.setIdLoghost("localhost");
            row.setIdLogPath(path);
            logPathMapper.insert(row);
        } catch (Exception e) {
            // 主键重复、表不存在都走这里：历史记录写不进去不值得打断搜索
            log.debug("日志路径入库失败（通常已存在）：{}", path);
        }
    }

    private void refreshHistoryCombo() {
        historyCombo.setItems(new ArrayList<>(historyPaths));
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /**
     * 字节数格式化。
     * <p>
     * 旧实现用 {@code length / 1024} 整除，小于 1KB 的文件一律显示 {@code 0kb}。
     */
    static String formatFileSize(long bytes) {
        // 实现统一移到 UiFactory：本地文件管理也要用同一套分档，别再各写一份
        return UiFactory.formatFileSize(bytes);
    }
}
