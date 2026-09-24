package com.sl.ui.local;

import com.sl.entity.PathEntityInfo;
import com.sl.ui.component.UiFactory;
import com.sl.ui.component.ViewBase;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import cn.hutool.core.io.FileUtil;
import cn.hutool.json.JSONUtil;

/**
 * 日志文件详情：分页浏览单个文本文件的内容。
 * <p>
 * 由 {@link LogSearchView} 点「预览」打开，走 {@code TabHost} 加到主框架的标签区。
 * <p>
 * <b>关键设计与旧项目的差别</b>：旧实现用 {@code FileUtil.readLines} 把整个文件读进内存，
 * 一个几百 MB 的日志就能把堆打爆。这里改成两段式——
 * 先用 64KB 块扫一遍建立「行号 -&gt; 字节偏移」索引，再按页 seek 读取需要的区间。
 * 索引只存 long 数组，一个 100 万行的日志约 8MB，可接受；文件内容则永远只驻留当前页。
 * <p>
 * 另外两个防炸阈值：单页最多读 {@value #MAX_PAGE_BYTES} 字节（挡住「一行几个 G」的畸形文件），
 * 单行最多显示 {@value #MAX_LINE_LENGTH} 字符（挡住超长单行把浏览器拖死）。
 * <p>
 * 这个类不是 Spring bean，也不该是——它由调用方带参构造，无依赖注入需求。
 */
public class LogDetailView extends ViewBase {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(LogDetailView.class);

    /** 每页行数 */
    private static final int PAGE_SIZE = 500;

    /** 单页最多读取的字节数 */
    private static final long MAX_PAGE_BYTES = 8L * 1024 * 1024;

    /** 建索引时的读取块大小 */
    private static final int INDEX_BUFFER_SIZE = 64 * 1024;

    /** JSON 美化模式允许的最大文件体积 */
    private static final long MAX_JSON_BYTES = 5L * 1024 * 1024;

    /** 单行最多显示的字符数 */
    private static final int MAX_LINE_LENGTH = 20000;

    private final transient PathEntityInfo fileInfo;
    private final String fileEncoding;

    private final TextArea contentArea = new TextArea();
    private final Span pageLabel = new Span();

    /** 文件读取句柄，随标签关闭而释放 */
    private transient RandomAccessFile accessFile;

    /** 行偏移索引：offsets[i] 是第 i 行的起始字节位置，末位为文件长度 */
    private transient long[] lineOffsets;

    private long fileLength;
    private int currentPage = 1;
    private int totalPages = 1;

    public LogDetailView(PathEntityInfo fileInfo, String fileEncoding) {
        this.fileInfo = fileInfo;
        this.fileEncoding = normalizeEncoding(fileEncoding);
        addClassName("log-detail-view");
        buildLayout();
        load();
    }

    // ------------------------------------------------------------------
    // 界面
    // ------------------------------------------------------------------

    private void buildLayout() {
        HorizontalLayout toolbar = toolbar();
        toolbar.getStyle().set("flex-wrap", "wrap");
        add(toolbar);

        contentArea.addClassName("log-content");
        contentArea.setReadOnly(true);
        contentArea.setWidthFull();
        // 不设 setHeightFull()：那会写成 height:100%，而父容器是 flex 纵向布局、
        // 上方还有一条工具栏，100% 会把工具栏的高度一并算进去，底部溢出。
        // 交给下面的 setFlexGrow(1, ...) 撑开剩余空间，最小高度由 .log-content 兜底。
        // TextArea 内部真正的 <textarea> 在 shadow DOM 里，普通 CSS 选择器够不到，
        // 只能覆盖 Lumo 变量来换字体。日志必须等宽，否则对齐全乱。
        contentArea.getStyle().set("--lumo-font-family", "Consolas, 'Courier New', monospace");
        contentArea.getStyle().set("--lumo-font-size-s", "13px");

        add(contentArea);
        // 内容区吃掉标题之外的全部高度，滚动条出现在 textarea 内部
        setFlexGrow(1, contentArea);
    }

    /** 工具栏。做成方法是因为「重载」会重建它——换编码后页码文案要跟着变。 */
    private HorizontalLayout toolbar() {
        Span name = new Span(fileInfo == null || fileInfo.getFileName() == null
                ? "日志详情" : fileInfo.getFileName());
        name.addClassName("log-detail-name");

        pageLabel.addClassName("log-detail-page");

        HorizontalLayout bar = new HorizontalLayout();
        bar.addClassName("view-toolbar");
        bar.setWidthFull();
        bar.setAlignItems(Alignment.CENTER);
        bar.setSpacing(false);
        bar.getStyle().set("gap", "8px");
        /*
         * 必须就地设 flex-wrap。虽然 styles.css 里 .view-toolbar 已经写了 flex-wrap: wrap，
         * 但实测在窄窗口下不起作用——载荷有「文件名 + 页码 + 三个按钮」时，
         * 最右边的「下载」会被容器裁掉一截。vaadin-horizontal-layout 的 :host 样式的
         * 层叠优先级与外部的类选择器同级，谁生效不好赌，写 inline 最确定。
         */
        bar.getStyle().set("flex-wrap", "wrap");
        bar.add(name, spacer(), pageLabel);

        bar.add(UiFactory.button("上一页", this::loadPreviousPage));
        bar.add(UiFactory.button("下一页", this::loadNextPage));
        if (fileInfo != null && fileInfo.getAbsolutePath() != null) {
            bar.add(UiFactory.downloadOrHint(new File(fileInfo.getAbsolutePath()), "下载"));
        }
        return bar;
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    private void load() {
        if (fileInfo == null || fileInfo.getAbsolutePath() == null) {
            contentArea.setValue("没有选择文件。");
            return;
        }
        File file = new File(fileInfo.getAbsolutePath());
        if (!file.isFile()) {
            contentArea.setValue("文件不存在或不可读：" + fileInfo.getAbsolutePath());
            // 文件没了，上一页/下一页继续点只会报错，直接禁掉更清楚
            setNavigationEnabled(false);
            pageLabel.setText("—");
            return;
        }
        this.fileLength = file.length();

        // 小文件且确实是 JSON 才格式化，否则退回分页——格式化要全量读进内存
        String suffix = fileInfo.getSuffix() == null ? "" : fileInfo.getSuffix().toLowerCase();
        if ("json".equals(suffix) && fileLength <= MAX_JSON_BYTES && loadPrettyJson(file)) {
            setNavigationEnabled(false);
            return;
        }

        if (!buildIndex(file)) {
            return;
        }
        // 与旧行为一致：打开时停在最后一页。日志的用法就是看最新那几条。
        currentPage = totalPages;
        renderCurrentPage();
    }

    /** 建索引。失败时界面给提示并禁用翻页。 */
    private boolean buildIndex(File file) {
        closeAccessFile();
        try {
            accessFile = new RandomAccessFile(file, "r");
            long[] offsets = new long[1024];
            int count = 0;
            offsets[count++] = 0;

            byte[] buffer = new byte[INDEX_BUFFER_SIZE];
            long position = 0;
            int read;
            while ((read = accessFile.read(buffer)) != -1) {
                for (int i = 0; i < read; i++) {
                    if (buffer[i] == '\n') {
                        long nextLine = position + i + 1;
                        // 文件以换行结尾时不再多算一个空行
                        if (nextLine < fileLength) {
                            if (count == offsets.length) {
                                offsets = Arrays.copyOf(offsets, count * 2);
                            }
                            offsets[count++] = nextLine;
                        }
                    }
                }
                position += read;
            }
            if (count == offsets.length) {
                offsets = Arrays.copyOf(offsets, count + 1);
            }
            // 末位放文件长度，作为最后一行的结束位置
            offsets[count++] = fileLength;
            lineOffsets = Arrays.copyOf(offsets, count);

            long totalLines = lineOffsets.length - 1L;
            totalPages = (int) Math.max(1, Math.ceil((double) totalLines / PAGE_SIZE));
            accessFile.seek(0);
            return true;
        } catch (IOException e) {
            log.error("建立日志行索引失败：{}", fileInfo.getAbsolutePath(), e);
            contentArea.setValue("读取日志失败：" + e.getMessage());
            pageLabel.setText("—");
            setNavigationEnabled(false);
            closeAccessFile();
            return false;
        }
    }

    private void renderCurrentPage() {
        if (accessFile == null || lineOffsets == null) {
            return;
        }
        try {
            int startLine = (currentPage - 1) * PAGE_SIZE;
            int endLine = Math.min(startLine + PAGE_SIZE, lineOffsets.length - 1);
            long startOffset = lineOffsets[startLine];
            long endOffset = lineOffsets[endLine];
            if (endOffset - startOffset > MAX_PAGE_BYTES) {
                endOffset = startOffset + MAX_PAGE_BYTES;
            }

            int length = (int) Math.max(0, endOffset - startOffset);
            byte[] bytes = new byte[length];
            accessFile.seek(startOffset);
            accessFile.readFully(bytes);

            String content = new String(bytes, resolveCharset());
            StringBuilder builder = new StringBuilder(content.length());
            for (String line : content.split("\r\n|\r|\n")) {
                if (line.length() > MAX_LINE_LENGTH) {
                    line = line.substring(0, MAX_LINE_LENGTH) + " ...(本行过长，已截断)";
                }
                builder.append(line).append(System.lineSeparator());
            }
            contentArea.setValue(builder.toString());
        } catch (IOException e) {
            log.error("读取日志分页失败：{}", fileInfo.getAbsolutePath(), e);
            contentArea.setValue("读取日志失败：" + e.getMessage());
            return;
        }
        pageLabel.setText("第 " + currentPage + " / " + totalPages + " 页（每页 " + PAGE_SIZE + " 行）");
    }

    private void loadNextPage() {
        if (currentPage >= totalPages) {
            // 旧实现这里弹 Notification，点一次弹一次，很吵。改成就地提示，不打断。
            pageLabel.setText("已经是最后一页");
            return;
        }
        currentPage++;
        renderCurrentPage();
    }

    private void loadPreviousPage() {
        if (currentPage <= 1) {
            pageLabel.setText("已经是第一页");
            return;
        }
        currentPage--;
        renderCurrentPage();
    }

    /**
     * 小文件且是合法 JSON 时格式化展示。
     *
     * @return 是否走了 JSON 分支
     */
    private boolean loadPrettyJson(File file) {
        try {
            List<String> lines = FileUtil.readLines(file, resolveCharset());
            StringBuilder raw = new StringBuilder();
            for (String line : lines) {
                raw.append(line);
            }
            if (!JSONUtil.isTypeJSON(raw.toString())) {
                return false;
            }
            contentArea.setValue(JSONUtil.toJsonPrettyStr(JSONUtil.parseObj(raw.toString())));
            pageLabel.setText("JSON 格式化展示（共 " + lines.size() + " 行）");
            return true;
        } catch (Exception e) {
            log.warn("JSON 格式化失败，退回到分页展示", e);
            return false;
        }
    }

    private Charset resolveCharset() {
        try {
            return Charset.forName(fileEncoding);
        } catch (Exception e) {
            log.warn("不支持的编码 {}，回退到 UTF-8", fileEncoding);
            return StandardCharsets.UTF_8;
        }
    }

    private static String normalizeEncoding(String encoding) {
        return encoding == null || encoding.isBlank() ? StandardCharsets.UTF_8.name() : encoding;
    }

    private void setNavigationEnabled(boolean enabled) {
        getChildren()
                .filter(HorizontalLayout.class::isInstance)
                .map(HorizontalLayout.class::cast)
                .forEach(bar -> bar.getChildren()
                        .filter(com.vaadin.flow.component.button.Button.class::isInstance)
                        .map(com.vaadin.flow.component.button.Button.class::cast)
                        .forEach(button -> button.setEnabled(enabled)));
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /**
     * 标签关闭时释放文件句柄。
     * <p>
     * Vaadin 8 是重写 {@code detach()}，Flow 改成了 {@code onDetach(DetachEvent)}，
     * 而且必须调 {@code super}——父类要往下传播事件。
     */
    @Override
    protected void onDetach(com.vaadin.flow.component.DetachEvent detachEvent) {
        super.onDetach(detachEvent);
        closeAccessFile();
    }

    private void closeAccessFile() {
        if (accessFile != null) {
            try {
                accessFile.close();
            } catch (IOException e) {
                log.warn("关闭日志文件失败：{}", e.getMessage());
            } finally {
                accessFile = null;
            }
        }
    }
}
