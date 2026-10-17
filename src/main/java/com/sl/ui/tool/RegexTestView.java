package com.sl.ui.tool;

import com.sl.ui.component.Dialogs;
import com.sl.ui.component.UiFactory;
import com.sl.ui.component.ViewBase;
import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.dom.DomEvent;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 正则表达式测试（其他工具 → 正则表达式测试）。
 * <p>
 * 照旧在线工具页的功能搬的：常用表达式一键填入、正则 + 测试文本即时匹配、
 * 匹配结果着色、捕获分组列表、批量替换（支持 {@code $1} 组引用），
 * 页脚附元字符 / 限定符 / 反义词速查表。
 * <p>
 * 交互上的取舍：输入即匹配（value-change 同步跑，不做防抖）——这是一次
 * 请求线程内的纯内存计算，文本量在万字符级耗时毫秒级，任何去抖/后台线程
 * 都是纯开销；只有 Grid 结果做了 {@link #MAX_MATCHES} 条上限，防止
 * {@code .*} 打到超长文本把组件树撑爆。
 * <p>
 * 着色：「对匹配结果着色」是把测试文本里命中的片段套上高亮。只是
 * HTML 转义后的字符串拼接，匹配文本永远先 {@link #esc} 再进 innerHTML，
 * 用户输入里带 {@code <script>} 也不会执行。
 */
@Service
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class RegexTestView extends ViewBase {

    private static final long serialVersionUID = 1L;

    /** Grid 最多展示的匹配条数；超出只提示条数，不渲染。 */
    private static final int MAX_MATCHES = 500;

    // ------------------------------------------------------------------
    // 常用正则表达式（照旧工具页抄全，另补了颜色值/车牌/信用代码等运维常用的）
    // ------------------------------------------------------------------

    private record Preset(String label, String regex) {
    }

    private static final List<Preset> PRESETS = List.of(
            new Preset("中文文字", "[\\u4e00-\\u9fa5]+"),
            new Preset("双字节字符", "[^\\x00-\\xff]+"),
            new Preset("空白行", "(?m)^[ \\t]*$"),
            new Preset("Email地址", "[\\w!#$%&'*+/=?^_`{|}~-]+(?:\\.[\\w!#$%&'*+/=?^_`{|}~-]+)*"
                    + "@(?:[\\w](?:[\\w-]*[\\w])?\\.)+[\\w](?:[\\w-]*[\\w])?"),
            new Preset("网址URL", "[a-zA-Z]+://[^\\s]*"),
            new Preset("手机（国内）", "1[3-9]\\d{9}"),
            new Preset("电话号码（国内）", "0\\d{2,3}-\\d{7,8}"),
            new Preset("负浮点数", "-(?:[1-9]\\d*\\.\\d*|0\\.\\d*[1-9]\\d*)"),
            new Preset("匹配整数", "-?[1-9]\\d*"),
            new Preset("正浮点数", "[1-9]\\d*\\.\\d*|0\\.\\d*[1-9]\\d*"),
            new Preset("正整数", "[1-9]\\d*"),
            new Preset("负整数", "-[1-9]\\d*"),
            new Preset("腾讯QQ号", "[1-9]\\d{4,10}"),
            new Preset("微信号", "[a-zA-Z][-a-zA-Z0-9_]{5,19}"),
            new Preset("邮政编码", "[1-9]\\d{5}(?!\\d)"),
            new Preset("IP（IPv4）", "(?:25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)"
                    + "(?:\\.(?:25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)){3}"),
            new Preset("MAC地址", "(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}"),
            new Preset("身份证号", "[1-9]\\d{5}(?:18|19|20)\\d{2}"
                    + "(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]"),
            new Preset("格式日期", "\\d{4}[-/年.]\\d{1,2}[-/月.]\\d{1,2}日?"),
            new Preset("时间（24h）", "(?:[01]\\d|2[0-3]):[0-5]\\d(?::[0-5]\\d)?"),
            new Preset("域名", "(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}"),
            new Preset("十六进制颜色", "#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})\\b"),
            new Preset("HTML标签", "</?[a-zA-Z][a-zA-Z0-9]*(?:\\s[^<>]*)?/?>"),
            new Preset("车牌号", "[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼使领]"
                    + "[A-HJ-NP-Z][A-HJ-NP-Z0-9]{4,5}[A-HJ-NP-Z0-9挂学警港澳]"),
            new Preset("统一社会信用代码", "[0-9A-HJ-NPQRTUWXY]{2}\\d{6}[0-9A-HJ-NPQRTUWXY]{10}"),
            new Preset("用户名", "[a-zA-Z][a-zA-Z0-9_]{3,15}"));

    /** 匹配结果 Grid 的一行。 */
    public record MatchRow(int index, String content, String position, String groups) {
    }

    // ------------------------------------------------------------------
    // 控件
    // ------------------------------------------------------------------

    private final TextField patternField = new TextField("正则表达式");
    private final TextArea testText = new TextArea();
    private final TextField replaceField = new TextField();
    private final Span status = new Span();
    private final Div preview = new Div();
    private final Grid<MatchRow> matchGrid = UiFactory.grid(MatchRow.class);

    private final Checkbox caseInsensitive = new Checkbox("不区分大小写");
    private final Checkbox multiline = new Checkbox("对 ^$ 前后换行也支持");
    private final Checkbox dotAll = new Checkbox("符号 . 匹配所有");
    private final Checkbox colorHits = new Checkbox("对匹配结果着色");
    private final Checkbox colorNoHit = new Checkbox("无匹配结果着色");

    /** 最近一次替换的结果，「写回测试文本」用；null 表示还没替换过。 */
    private String lastReplaced;

    public RegexTestView() {
        addClassName("regex-test-view");
        buildHeader();
        buildPresetRow();
        buildPatternArea();
        buildTestArea();
        buildReplaceRow();
        buildResultArea();
        buildCheatSheet();
    }

    // ------------------------------------------------------------------
    // 页面骨架
    // ------------------------------------------------------------------

    private void buildHeader() {
        add(title("正则表达式测试"));
        add(subtitle("输入正则与测试文本即时匹配；内置常用表达式一键填入，"
                + "支持大小写 / 多行 / 点号匹配开关、匹配高亮、分组捕获与批量替换（$1 引用捕获组）。"));
        add(UiFactory.divider());
    }

    private void buildPresetRow() {
        Div chips = new Div();
        chips.addClassName("regex-chips");
        for (Preset preset : PRESETS) {
            chips.add(UiFactory.small(preset.label(), () -> applyPreset(preset.regex())));
            // 「邮政编码」是旧工具自带项的最后一批，之后全是补充项：
            // 在它后面插一个占满一行的断点，补充项整体另起一行，两批一眼可分
            if ("邮政编码".equals(preset.label())) {
                Div chipBreak = new Div();
                chipBreak.addClassName("regex-chip-break");
                chips.add(chipBreak);
            }
        }
        add(chips);
    }

    private void buildPatternArea() {
        patternField.setPlaceholder("在此输入正则表达式");
        patternField.setClearButtonVisible(true);
        patternField.setWidth("90%");
        patternField.addValueChangeListener(e -> run());
        // Vaadin 的 value-change 挂在 DOM change 事件上，失焦才发——手输/粘贴正则时
        // 页面毫无反应，点别处才突然刷新。这里直接监听 input 事件实时匹配（见 bindLiveInput）。
        bindLiveInput(patternField);

        status.setText("等待输入正则与测试文本");
        HorizontalLayout patternRow = new HorizontalLayout(patternField, status);
        patternRow.setWidthFull();
        patternRow.setAlignItems(Alignment.CENTER);
        patternRow.setFlexGrow(1, patternField);
        patternRow.getStyle().set("gap", "16px");
        add(patternRow);

        for (Checkbox box : List.of(caseInsensitive, multiline, dotAll, colorHits, colorNoHit)) {
            box.addValueChangeListener(e -> run());
        }
        colorHits.setValue(true);
        HorizontalLayout options = new HorizontalLayout(
                caseInsensitive, multiline, dotAll, colorHits, colorNoHit);
        options.getStyle().set("gap", "20px");
        options.setAlignItems(Alignment.CENTER);
        add(options);
    }

    private void buildTestArea() {
        testText.setPlaceholder("粘贴要测试的文本，命中片段会在下方高亮");
        testText.setWidth("90%");
        testText.setHeight("200px");
        testText.addValueChangeListener(e -> run());
        // 与 patternField 同理：粘贴后不等失焦，input 事件一到就匹配（见 bindLiveInput）
        bindLiveInput(testText);
        add("测试文本");
        add(testText);
    }

    /**
     * 挂 DOM input 实时监听：Vaadin 的 value-change 挂在 DOM change 事件上
     * （失焦才发），粘贴大段文本后页面毫无反应，点别处才突然刷新。
     * 这里监听 input 事件，事件数据里的 {@code element.value} 是 web component
     * 的实时值（粘贴、键入、拖放都会更新）。同步进模型——模型变了会同步触发
     * value-changed → run()；模型没差值（比如粘贴出相同内容）也手动跑一遍。
     */
    private void bindLiveInput(AbstractField<?, String> field) {
        field.getElement().addEventListener("input",
                e -> {
                    String value = e.getEventData().getString("element.value");
                    if (value == null) {
                        value = "";
                    }
                    if (!value.equals(field.getValue())) {
                        field.setValue(value);
                    } else {
                        run();
                    }
                }).addEventData("element.value");
    }

    private void buildReplaceRow() {
        replaceField.setPlaceholder("输入要替换的文本，可用 $1、$2 引用捕获组；留空即删除匹配片段");
        replaceField.setClearButtonVisible(true);
        replaceField.setWidth("520px");
        // 替换框同样要实时同步模型：否则测试里 DOM 塞值（或快速点按钮）时
        // 模型还是旧值，replaceAll 拿到空串把匹配全删掉
        bindLiveInput(replaceField);

        HorizontalLayout row = toolbar(
                replaceField,
                UiFactory.primary("替换匹配文本", this::doReplace),
                UiFactory.button("写回测试文本", this::applyReplaced),
                spacer());
        add(row);
    }

    private void buildResultArea() {
        preview.addClassName("regex-preview");
        preview.setWidth("90%");
        Span previewLabel = new Span("结果预览");
        previewLabel.addClassName("view-section-title");

        matchGrid.addColumn(MatchRow::index).setHeader("序号").setWidth("70px").setFlexGrow(0);
        matchGrid.addColumn(MatchRow::content).setHeader("匹配内容").setClassNameGenerator(row -> "mono-cell");
        matchGrid.addColumn(MatchRow::position).setHeader("位置").setWidth("130px").setFlexGrow(0);
        matchGrid.addColumn(MatchRow::groups).setHeader("捕获组").setClassNameGenerator(row -> "mono-cell");
        matchGrid.setWidthFull();
        matchGrid.setHeight("220px");

        add(previewLabel, preview, matchGrid);
    }

    // ------------------------------------------------------------------
    // 匹配主逻辑
    // ------------------------------------------------------------------

    /** 三个开关映射到 Pattern flags。 */
    private int flags() {
        int flags = 0;
        if (caseInsensitive.getValue()) {
            flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        }
        if (multiline.getValue()) {
            flags |= Pattern.MULTILINE;
        }
        if (dotAll.getValue()) {
            flags |= Pattern.DOTALL;
        }
        return flags;
    }

    /** 编译 pattern；失败时把状态行标红并返回 null。 */
    private Pattern compilePattern(String patternText) {
        try {
            return Pattern.compile(patternText, flags());
        } catch (PatternSyntaxException e) {
            markStatus(false, "正则语法错误：" + e.getDescription() + "（索引 " + e.getIndex() + "）");
            return null;
        }
    }

    /** 一键填入常用表达式；值没变时不会触发监听，补一次手动运行。 */
    private void applyPreset(String regex) {
        if (regex.equals(patternField.getValue())) {
            run();
        } else {
            patternField.setValue(regex);
        }
    }

    /**
     * 输入即匹配：正则着色、预览高亮、分组列表一次算完。
     * 全程请求线程内同步执行，纯内存操作，不做防抖。
     */
    private void run() {
        lastReplaced = null;
        String patternText = patternField.getValue() == null ? "" : patternField.getValue();
        String text = testText.getValue() == null ? "" : testText.getValue();

        if (patternText.isEmpty()) {
            markStatus(true, "等待输入正则");
            renderPreview(text, List.of());
            matchGrid.setItems(List.of());
            return;
        }
        Pattern pattern = compilePattern(patternText);
        if (pattern == null) {
            renderPreview(text, List.of());
            matchGrid.setItems(List.of());
            return;
        }
        if (text.isEmpty()) {
            markStatus(true, "正则合法，等待输入测试文本");
            renderPreview("", List.of());
            matchGrid.setItems(List.of());
            return;
        }

        Matcher matcher = pattern.matcher(text);
        List<int[]> spans = new ArrayList<>();
        List<MatchRow> rows = new ArrayList<>();
        boolean truncated = false;
        while (matcher.find()) {
            if (rows.size() >= MAX_MATCHES) {
                truncated = true;
                break;
            }
            spans.add(new int[]{matcher.start(), matcher.end()});

            StringBuilder groupText = new StringBuilder();
            for (int g = 1; g <= matcher.groupCount(); g++) {
                if (groupText.length() > 0) {
                    groupText.append(" | ");
                }
                groupText.append(matcher.group(g) == null ? "空" : matcher.group(g));
            }
            rows.add(new MatchRow(rows.size() + 1, matcher.group(),
                    matcher.start() + " ~ " + matcher.end(),
                    matcher.groupCount() == 0 ? "—" : groupText.toString()));
        }

        int total = rows.size() + (truncated ? 1 : 0);
        // 被截断时把剩余条数数完，只用于提示，不渲染
        while (truncated && matcher.find()) {
            total++;
        }
        markStatus(true, total == 0 ? "未匹配" : "匹配 " + total + " 处"
                + (truncated ? "（Grid 仅显示前 " + MAX_MATCHES + " 处）" : ""));
        renderPreview(text, spans);
        matchGrid.setItems(rows);
    }

    /** 状态行配色：成功绿 / 失败红。 */
    private void markStatus(boolean ok, String text) {
        status.setText(text);
        status.removeClassName(ok ? "regex-status-err" : "regex-status-ok");
        status.addClassName(ok ? "regex-status-ok" : "regex-status-err");
    }

    /**
     * 结果预览：测试文本按命中区间切段、HTML 转义后拼接；
     * 开「对匹配结果着色」时命中段套 {@code mark.regex-hit}，
     * 一处都没中且开了「无匹配结果着色」时整段标红。
     */
    private void renderPreview(String text, List<int[]> spans) {
        if (text.isEmpty()) {
            preview.getElement().setProperty("innerHTML",
                    "<span class=\"empty-hint\">输入测试文本后，这里显示命中高亮</span>");
            return;
        }
        if (spans.isEmpty()) {
            String html = colorNoHit.getValue()
                    ? "<span class=\"regex-miss\">" + esc(text) + "</span>"
                    : esc(text);
            preview.getElement().setProperty("innerHTML", html);
            return;
        }
        StringBuilder html = new StringBuilder();
        int cursor = 0;
        for (int[] span : spans) {
            html.append(esc(text.substring(cursor, span[0])));
            if (colorHits.getValue()) {
                html.append("<mark class=\"regex-hit\">").append(esc(text.substring(span[0], span[1])))
                        .append("</mark>");
            } else {
                html.append(esc(text.substring(span[0], span[1])));
            }
            cursor = span[1];
        }
        html.append(esc(text.substring(cursor)));
        preview.getElement().setProperty("innerHTML", html.toString());
    }

    // ------------------------------------------------------------------
    // 替换
    // ------------------------------------------------------------------

    /**
     * 批量替换：走 {@link Matcher#replaceAll}，{@code $1} 组引用是原生语义。
     * 替换串里写错的组引用（{@code $3} 但只有两组）会抛
     * IndexOutOfBoundsException，这里兜住给成人话。
     */
    private void doReplace() {
        String patternText = patternField.getValue();
        String text = testText.getValue();
        if (patternText == null || patternText.isEmpty() || text == null || text.isEmpty()) {
            Dialogs.warn("请先输入正则表达式和测试文本");
            return;
        }
        Pattern pattern = compilePattern(patternText);
        if (pattern == null) {
            return;
        }
        String replacement = replaceField.getValue() == null ? "" : replaceField.getValue();
        int count = 0;
        Matcher counter = pattern.matcher(text);
        while (counter.find()) {
            count++;
        }
        if (count == 0) {
            Dialogs.warn("没有可替换的内容：正则未命中任何片段");
            return;
        }
        try {
            lastReplaced = pattern.matcher(text).replaceAll(replacement);
        } catch (Exception e) {
            Dialogs.error("替换失败：" + e.getMessage() + "（检查 $N 组引用是否越界）");
            return;
        }
        preview.getElement().setProperty("innerHTML", esc(lastReplaced));
        markStatus(true, "替换完成，共 " + count + " 处；结果已显示在预览区，可点「写回测试文本」继续处理");
    }

    /** 把替换结果写回测试文本，形成「替换 → 再匹配」的迭代。 */
    private void applyReplaced() {
        if (lastReplaced == null) {
            Dialogs.warn("请先执行一次「替换匹配文本」");
            return;
        }
        testText.setValue(lastReplaced);
    }

    // ------------------------------------------------------------------
    // 速查表（静态内容，照旧工具页文案搬全）
    // ------------------------------------------------------------------

    private void buildCheatSheet() {
        add(UiFactory.divider());
        add(section("工具简介：什么是正则表达式？"));
        Paragraph intro = new Paragraph("在编写处理字符串的程序或网页时，经常有查找符合某些复杂规则的字符串的需要。"
                + "正则表达式就是用于描述这些规则的工具。换句话说，正则表达式就是记录文本规则的代码。");
        intro.addClassName("view-subtitle");
        add(intro);

        Html metachar = new Html("<div><table class=\"regex-cheat\">"
                + "<tr><th>代码</th><th>说明</th></tr>"
                + "<tr><td>.</td><td>匹配除换行符以外的任意字符</td></tr>"
                + "<tr><td>\\w</td><td>匹配字母或数字或下划线或汉字</td></tr>"
                + "<tr><td>\\s</td><td>匹配任意的空白符</td></tr>"
                + "<tr><td>\\d</td><td>匹配数字</td></tr>"
                + "<tr><td>\\b</td><td>匹配单词的开始或结束</td></tr>"
                + "<tr><td>^</td><td>匹配字符串的开始</td></tr>"
                + "<tr><td>$</td><td>匹配字符串的结束</td></tr>"
                + "</table></div>");

        Html quantifier = new Html("<div><table class=\"regex-cheat\">"
                + "<tr><th>代码/语法</th><th>说明</th></tr>"
                + "<tr><td>*</td><td>重复零次或更多次</td></tr>"
                + "<tr><td>+</td><td>重复一次或更多次</td></tr>"
                + "<tr><td>?</td><td>重复零次或一次</td></tr>"
                + "<tr><td>{n}</td><td>重复n次</td></tr>"
                + "<tr><td>{n,}</td><td>重复n次或更多次</td></tr>"
                + "<tr><td>{n,m}</td><td>重复n到m次</td></tr>"
                + "</table></div>");

        Html antonym = new Html("<div><table class=\"regex-cheat\">"
                + "<tr><th>代码/语法</th><th>说明</th></tr>"
                + "<tr><td>\\W</td><td>匹配任意不是字母、数字、下划线、汉字的字符</td></tr>"
                + "<tr><td>\\S</td><td>匹配任意不是空白符的字符</td></tr>"
                + "<tr><td>\\D</td><td>匹配任意非数字的字符</td></tr>"
                + "<tr><td>\\B</td><td>匹配不是单词开头或结束的位置</td></tr>"
                + "<tr><td>[^x]</td><td>匹配除了x以外的任意字符</td></tr>"
                + "<tr><td>[^aeiou]</td><td>匹配除了aeiou这几个字母以外的任意字符</td></tr>"
                + "</table></div>");

        // 三张表竖排各占整行。之前横排时 table 以 max-content 宽度参与 flex、
        // 不收缩（min-width:auto），三张挤一行必然超出容器宽，把整页撑出横向滚动条。
        // VerticalLayout 默认 align-items:stretch，Html 的根 div 自动占满整行
        HorizontalLayout layout = new HorizontalLayout();
        for (Html table : List.of(metachar, quantifier, antonym)) {
            layout.add(table);
        }
        add(layout);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** HTML 转义：用户输入进 innerHTML 前必须过这里。 */
    private static String esc(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
