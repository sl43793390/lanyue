package com.sl.ui.component;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Hr;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.upload.UploadI18N;
import com.vaadin.flow.server.streams.DownloadHandler;

import cn.hutool.core.util.StrUtil;

import java.io.File;
import java.io.IOException;
import java.util.Collection;

/**
 * 控件工厂：把「一套统一的控件长相」收在一个地方。
 * <p>
 * 旧项目的对应物是 {@code ComponentFactory}（15KB，约 30 个 {@code getXxx()} 方法），
 * 它的主体工作是给每个控件贴 Valo 主题的 CSS 类名（{@code button_standard} /
 * {@code field_box_standard_height} / {@code textfield_standard} …），
 * 以此在没有设计系统的年代维持统一外观。
 * <p>
 * Vaadin 24 自带 Lumo 设计系统，控件默认就是统一的，**绝大部分方法不需要了**。
 * 这里只保留三类真正还有价值的：
 * <ol>
 *   <li>把「主色 / 危险 / 次要」这种语义映射到 {@code ButtonVariant}，
 *       免得每个页面各写各的、实际用出五六种不同的红；</li>
 *   <li>表单一行的排版（{@code fieldRow}）——旧代码用 {@code AbsoluteLayout}
 *       + {@code "left:187px"} 死算像素，标签一长就压控件，这里换成定宽标签 + flex；</li>
 *   <li>下载链接。Vaadin 24 移除了 {@code FileDownloader}，改由
 *       {@link Anchor} 直接持有 {@link DownloadHandler}，这个转换有点绕，值得封装一次。</li>
 * </ol>
 */
public final class UiFactory {

    /** 表单行里标签的默认宽度。中文标签一般 4~6 个字，120px 够用且对齐整齐。 */
    private static final String DEFAULT_LABEL_WIDTH = "120px";

    private UiFactory() {
    }

    // ------------------------------------------------------------------
    // 按钮
    // ------------------------------------------------------------------

    /** 主操作按钮（主色）。查询、保存、提交。 */
    public static Button primary(String text, Runnable action) {
        Button button = new Button(text, event -> action.run());
        button.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        return button;
    }

    /** 普通按钮。 */
    public static Button button(String text, Runnable action) {
        return new Button(text, event -> action.run());
    }

    /** 次要按钮：视觉上更轻，避免一行里全是重按钮抢焦点。 */
    public static Button tertiary(String text, Runnable action) {
        Button button = new Button(text, event -> action.run());
        button.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        return button;
    }

    /** 危险按钮（红色）。删除、停止、强制重启。 */
    public static Button danger(String text, Runnable action) {
        Button button = new Button(text, event -> action.run());
        button.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);
        return button;
    }

    /** 小尺寸按钮，用在表格的组件列里。 */
    public static Button small(String text, Runnable action) {
        Button button = new Button(text, event -> action.run());
        button.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        return button;
    }

    /**
     * 表格「操作」列里的按钮：小尺寸；浅色底由 {@code .row-actions} 容器的 CSS 统一上
     * （ButtonVariant 里没有 tint 变量，且浅色底跟容器走更好统一调整）。
     * 配合 {@code .row-actions} 的 8px 间隔，避免一排蓝字挤在一起点错。
     */
    public static Button rowAction(String text, Runnable action) {
        Button button = new Button(text, event -> action.run());
        button.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        return button;
    }

    /** 同 {@link #rowAction}，红色（error 主题），用于删除等危险操作。 */
    public static Button rowDanger(String text, Runnable action) {
        Button button = rowAction(text, action);
        button.addThemeVariants(ButtonVariant.LUMO_ERROR);
        return button;
    }

    // ------------------------------------------------------------------
    // 「查看状态」后的行内状态联动
    // ------------------------------------------------------------------

    /**
     * 状态查询返回「运行中」：启动钮变绿改字为「运行中」并禁点（服务已在跑，再点启动无意义）；
     * 停止钮还原为可点的「停止」。样式（绿底绿字）由 {@code .row-actions} 的 success 主题规则统一上。
     */
    public static void markRowRunning(Button startBtn, Button stopBtn) {
        resetRowStatus(startBtn, stopBtn);
        if (startBtn != null) {
            startBtn.setText("运行中");
            startBtn.addThemeNames("success");
            startBtn.setEnabled(false);
        }
    }

    /**
     * 状态查询返回「已停止」：停止钮变红改字为「已停止」并禁点；
     * 启动钮还原为可点的「启动」。
     */
    public static void markRowStopped(Button startBtn, Button stopBtn) {
        resetRowStatus(startBtn, stopBtn);
        if (stopBtn != null) {
            stopBtn.setText("已停止");
            stopBtn.addThemeNames("error");
            stopBtn.setEnabled(false);
        }
    }

    /** 把一对启动/停止钮还原成默认形态（表格行重渲染前、或状态翻转前先调它）。 */
    public static void resetRowStatus(Button startBtn, Button stopBtn) {
        if (startBtn != null) {
            startBtn.setText("启动");
            startBtn.removeThemeNames("success", "error");
            startBtn.setEnabled(true);
        }
        if (stopBtn != null) {
            stopBtn.setText("停止");
            stopBtn.removeThemeNames("success", "error");
            stopBtn.setEnabled(true);
        }
    }

    // ------------------------------------------------------------------
    // 表单
    // ------------------------------------------------------------------

    /**
     * 表单行：左侧定宽标签 + 右侧控件。
     * <p>
     * 之所以要固定标签宽度而不是用 {@code FormLayout}：旧界面上大量出现
     * 「标签-控件-标签-控件」四个一组挤在同一行的排法（比如「选择后缀 / 选择编码」），
     * {@code FormLayout} 是两列的纵向表单，塞不下这种横向分组。
     */
    public static HorizontalLayout fieldRow(String label, Component field) {
        return fieldRow(label, DEFAULT_LABEL_WIDTH, field);
    }

    public static HorizontalLayout fieldRow(String label, String labelWidth, Component field) {
        Span labelSpan = new Span(label);
        labelSpan.addClassName("field-label");
        labelSpan.getStyle().set("flex", "0 0 " + labelWidth);
        labelSpan.getStyle().set("white-space", "nowrap");

        HorizontalLayout row = new HorizontalLayout(labelSpan, field);
        row.addClassName("field-row");
        row.setAlignItems(com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.CENTER);
        row.setSpacing(false);
        row.getStyle().set("gap", "8px");
        return row;
    }

    /** 不带标签的窄字段行，用于塞进上面的横向分组。 */
    public static HorizontalLayout group(Component... components) {
        HorizontalLayout layout = new HorizontalLayout(components);
        layout.addClassName("field-group");
        layout.setAlignItems(com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.CENTER);
        layout.setSpacing(false);
        layout.getStyle().set("gap", "8px");
        return layout;
    }

    public static TextField textField() {
        return new TextField();
    }

    public static TextField textField(String label) {
        return new TextField(label);
    }

    public static TextField textField(String label, String placeholder) {
        TextField field = new TextField(label);
        field.setPlaceholder(placeholder);
        return field;
    }
    public static TextField textField(String label, String placeholder,String width) {
        TextField field = new TextField(label);
        field.setPlaceholder(placeholder);
        field.setWidth(width);
        return field;
    }

    public static PasswordField passwordField(String label) {
        return new PasswordField(label);
    }

    public static PasswordField passwordField(String label,String width) {
        PasswordField passwordField = new PasswordField(label);
        passwordField.setWidth(width);
        return passwordField;
    }

    public static TextArea textArea() {
        return new TextArea();
    }

    public static TextArea textArea(String label) {
        return new TextArea(label);
    }

    public static TextArea textArea(String label,String width) {
        TextArea textArea = new TextArea(label);
        textArea.setWidth(width);
        return textArea;
    }

    /**
     * 下拉框。{@code allowCustomValue} 打开后可以手输，历史路径那种场景需要。
     */
    public static ComboBox<String> combo(String label, Collection<String> items, String value) {
        ComboBox<String> combo = new ComboBox<>(label);
        combo.setItems(items);
        if (value != null) {
            combo.setValue(value);
        }
        return combo;
    }

    public static ComboBox<String> combo(Collection<String> items, String value) {
        return combo(null, items, value);
    }

    // ------------------------------------------------------------------
    // 表格
    // ------------------------------------------------------------------

    /**
     * 标准表格：行高紧凑、可排序、无边框底纹。
     * <p>
     * {@code setAllRowsVisible} 在数据量大时不能开，所以不在这里设；
     * 需要「表格撑满剩余高度」的页面自己调 {@code setHeightFull()}。
     */
    public static <T> Grid<T> grid(Class<T> beanType) {
        Grid<T> grid = new Grid<>(beanType, false);
        grid.addClassName("standard-grid");
        grid.setWidthFull();
        // 空表时高度塌成一条线，看着像坏了
        grid.setMinHeight("200px");
        return grid;
    }

    /** 表格里的空状态提示。 */
    public static Span emptyHint(String text) {
        Span hint = new Span(text);
        hint.addClassName("empty-hint");
        return hint;
    }

    /**
     * 字节数格式化：按 B/KB/MB/GB 分档，保留两位小数。
     * <p>
     * 旧代码用 {@code length / 1024} 整除，小于 1KB 的文件一律显示 {@code 0kb}，
     * 而且单位全小写（{@code 1024mb} 这种）。这里分档并保留小数，
     * 一眼能看出文件量级——运维看日志目录时「这文件是不是刚滚过」全靠这一列。
     */
    public static String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.2f MB", bytes / 1024.0 / 1024);
        }
        return String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024);
    }

    // ------------------------------------------------------------------
    // 其他
    // ------------------------------------------------------------------

    /** 水平分割线。 */
    public static Hr divider() {
        Hr hr = new Hr();
        hr.addClassName("view-divider");
        return hr;
    }

    // ------------------------------------------------------------------
    // 上传（本地项目三个管理页共用的行内上传）
    // ------------------------------------------------------------------

    /** 行内上传控件的中文文案（上传按钮只有 i18n 一条路，没有 setButtonCaption）。 */
    public static final UploadI18N UPLOAD_I18N = new UploadI18N()
            .setAddFiles(new UploadI18N.AddFiles().setOne("上传").setMany("上传"))
            .setDropFiles(new UploadI18N.DropFiles().setOne("拖到此处").setMany("拖到此处"));

    /**
     * 只取文件名，丢掉任何路径成分。
     * <p>
     * multipart 里的 filename 是客户端说的，老浏览器会给完整路径，
     * 恶意客户端还能塞 {@code ../../}。直接拼进目标目录就是目录穿越。
     */
    public static String safeFileName(String rawName) throws IOException {
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

    /**
     * 文件下载链接（做成按钮样子）。
     * <p>
     * Vaadin 24 里 {@code FileDownloader} 已被移除，新的做法是让 {@link Anchor}
     * 直接持有 {@link DownloadHandler}——{@code DownloadHandler} 负责在请求到达时
     * 把字节写出去，不需要提前注册资源。详见
     * {@code com.vaadin.flow.server.streams.DownloadHandler}。
     *
     * @param file 要下载的文件，必须存在
     * @param text 链接文案
     */
    public static Anchor download(File file, String text) {
        Anchor anchor = new Anchor(DownloadHandler.forFile(file, file.getName()), text);
        anchor.addClassName("download-link");
        anchor.setTitle("下载 " + file.getName());
        return anchor;
    }

    /**
     * 与 {@link #download} 同款，但只在文件确实存在时返回可点的链接；
     * 文件不在则返回一段灰字说明，避免点下去 500。
     */
    public static Component downloadOrHint(File file, String text) {
        if (file == null || !file.isFile()) {
            Span hint = new Span("文件不存在");
            hint.addClassName("empty-hint");
            return hint;
        }
        return download(file, text);
    }
}
