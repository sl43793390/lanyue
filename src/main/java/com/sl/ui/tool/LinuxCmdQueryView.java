package com.sl.ui.tool;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sl.entity.LinuxCmd;
import com.sl.mapper.LinuxCmdMapper;
import com.sl.ui.component.Dialogs;
import com.sl.ui.component.UiFactory;
import com.sl.ui.component.ViewBase;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Linux 命令查询（Linux工具 → Linux命令查询）。
 * <p>
 * 离线命令手册：数据在 {@code linux_cmd} 表里（命令名 / 分类 / 功能说明 / 参数说明 /
 * 使用示例 / 备注），页面上方搜索，结果以表格展示。命令名是超链接，点开详情弹窗：
 * <ul>
 * <li>弹窗上部：功能说明、参数说明、使用示例、备注；</li>
 * <li>弹窗下部约 1/3：一个输入框，默认填第一条示例命令，可对照上面的说明改，
 *     改完点「复制」拿去终端执行。</li>
 * </ul>
 * <p>
 * 本页只读：命令手册的内容变更走 SQL（152 条种子数据在 classpath 的
 * {@code seed/linux_cmd.sql}，由 DbInitializer 在 linux_cmd 表为空时自动导入；
 * 源文件是项目根目录的 linux_commands.csv），页面上不提供增删改——
 * 手册数据低频变更且全局共享，做成页面编辑反而要引入权限与误改恢复的问题。
 */
@Service
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class LinuxCmdQueryView extends ViewBase {

    private static final long serialVersionUID = 1L;

    /** 示例行里命令与说明的分隔符。竖线 | 在 shell 命令里太常见，不能拿来当分隔符。 */
    private static final String EXAMPLE_SEP = "=>";

    private final transient LinuxCmdMapper mapper;

    private final TextField searchField = new TextField();
    private final Span countLabel = new Span();
    private final Grid<LinuxCmd> grid = UiFactory.grid(LinuxCmd.class);

    public LinuxCmdQueryView(LinuxCmdMapper mapper) {
        this.mapper = mapper;

        add(title("Linux 命令查询"));
        add(subtitle("离线命令手册：输入命令名搜索（如 tar、grep），点命令名看详细说明，"
                + "弹窗下方可以直接修改示例命令后复制去终端执行。"));

        add(buildSearchBar());

        // 注意是 addComponentColumn：addColumn(ValueProvider) 走 toString 渲染，
        // 组件会被打成 Button@hash，必须用组件渲染器
        grid.addComponentColumn(this::nameLink).setHeader("命令").setAutoWidth(true)
                .setComparator(LinuxCmd::getCmdName).setSortable(true).setFlexGrow(0);
        grid.addColumn(LinuxCmd::getCmdCategory).setHeader("分类").setAutoWidth(true)
                .setComparator(LinuxCmd::getCmdCategory).setSortable(true).setFlexGrow(0);
        grid.addColumn(LinuxCmd::getCmdSummary).setHeader("功能说明").setFlexGrow(1);
        grid.addColumn(item -> firstExampleCommand(item)).setHeader("示例命令").setFlexGrow(1)
                .setPartNameGenerator(item -> "mono-cell");
        grid.setSelectionMode(Grid.SelectionMode.NONE);

        VerticalLayout gridArea = fill(countLabel, grid);
        gridArea.setFlexGrow(1, grid);
        add(gridArea);

        doSearch();
    }

    // ------------------------------------------------------------------
    // 搜索
    // ------------------------------------------------------------------

    private Component buildSearchBar() {
        searchField.setPlaceholder("输入命令名，如 tar / grep / systemctl");
        searchField.setWidth("360px");
        searchField.setClearButtonVisible(true);
        // 回车 = 点搜索，查手册的人手还在键盘上
        searchField.addKeyPressListener(Key.ENTER, e -> doSearch());
        searchField.focus();

        HorizontalLayout bar = toolbar(
                UiFactory.fieldRow("搜索命令", "80px", searchField),
                UiFactory.primary("搜索", this::doSearch),
                countLabel);
        countLabel.addClassName("search-status");
        return bar;
    }

    /** 执行搜索：空关键字列出全部，否则只对命令名列做 LIKE（说明文本里出现子串不算命中） */
    private void doSearch() {
        String keyword = StrUtil.trimToEmpty(searchField.getValue());
        QueryWrapper<LinuxCmd> query = new QueryWrapper<>();
        if (!keyword.isEmpty()) {
            query.like("cmd_name", keyword);
        }
        query.orderByAsc("cmd_category").orderByAsc("cmd_name");
        List<LinuxCmd> rows = mapper.selectList(query);
        grid.setItems(rows);
        countLabel.setText(keyword.isEmpty()
                ? "共 " + rows.size() + " 条命令"
                : "匹配到 " + rows.size() + " 条命令");
    }

    // ------------------------------------------------------------------
    // 表格里的命令超链接
    // ------------------------------------------------------------------

    /** 命令名做成链接样式按钮：点开详情弹窗。组件列是纯函数，每次渲染新建 */
    private Button nameLink(LinuxCmd item) {
        Button link = new Button(item.getCmdName(), e -> openDetail(item));
        link.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
        link.addClassName("cmd-link");
        link.setTooltipText("查看 " + item.getCmdName() + " 的说明与示例");
        return link;
    }

    // ------------------------------------------------------------------
    // 详情弹窗：上 2/3 说明，下 1/3 可改的示例命令 + 复制
    // ------------------------------------------------------------------

    private void openDetail(LinuxCmd cmd) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(cmd.getCmdName()
                + (StrUtil.isBlank(cmd.getCmdCategory()) ? "" : "（" + cmd.getCmdCategory() + "）"));
        dialog.setWidth("min(880px, 94vw)");
        dialog.setHeight("min(760px, 92vh)");

        // 上部：说明区，内容多时自己滚，别把下面的输入框挤没
        Div infoScroll = new Div(buildInfoSection(cmd));
        infoScroll.setWidthFull();
        infoScroll.getStyle().set("overflow-y", "auto");
        infoScroll.getStyle().set("flex", "2 1 0");
        infoScroll.getStyle().set("min-height", "0");

        // 下部（约 1/3）：示例命令输入框 + 复制按钮
        VerticalLayout tryArea = buildTrySection(cmd);
        tryArea.getStyle().set("flex", "1 1 0");
        tryArea.getStyle().set("min-height", "0");

        VerticalLayout body = new VerticalLayout(infoScroll, tryArea);
        body.setPadding(false);
        body.setSpacing(false);
        body.setSizeFull();
        body.getStyle().set("gap", "12px");
        body.setWidth("500px");
        dialog.add(body);
        dialog.open();
    }

    /** 功能说明 / 参数说明 / 使用示例 / 备注 四段 */
    private VerticalLayout buildInfoSection(LinuxCmd cmd) {
        VerticalLayout info = new VerticalLayout();
        info.setPadding(false);
        info.setSpacing(false);
        info.getStyle().set("gap", "12px");
        info.setWidthFull();

        Paragraph summary = new Paragraph(StrUtil.emptyToDefault(cmd.getCmdSummary(), "（无说明）"));
        summary.getStyle().set("white-space", "pre-wrap");
        summary.getStyle().set("margin", "0");
        info.add(section("功能说明"), summary);

        List<String> params = nonBlankLines(cmd.getCmdParams());
        if (!params.isEmpty()) {
            VerticalLayout paramsBox = new VerticalLayout();
            paramsBox.setPadding(false);
            paramsBox.setSpacing(false);
            paramsBox.getStyle().set("gap", "2px");
            for (String line : params) {
                paramsBox.add(monoLine(line));
            }
            info.add(section("参数说明"), paramsBox);
        }

        List<String[]> examples = parseExamples(cmd.getCmdExamples());
        if (!examples.isEmpty()) {
            VerticalLayout exampleBox = new VerticalLayout();
            exampleBox.setPadding(false);
            exampleBox.setSpacing(false);
            exampleBox.getStyle().set("gap", "6px");
            for (String[] example : examples) {
                Div row = new Div();
                Span command = new Span(example[0]);
                command.getStyle().set("font-family", "var(--lumo-font-family-monospace)");
                command.getStyle().set("font-weight", "600");
                command.getStyle().set("color", "var(--lumo-primary-text-color)");
                row.add(command);
                if (!example[1].isEmpty()) {
                    Span desc = new Span("　" + example[1]);
                    desc.getStyle().set("color", "var(--lumo-secondary-text-color)");
                    row.add(desc);
                }
                exampleBox.add(row);
            }
            info.add(section("使用示例"), exampleBox);
        }

        if (StrUtil.isNotBlank(cmd.getCmdRemark())) {
            Span remark = new Span("注意：" + cmd.getCmdRemark());
            remark.getStyle().set("color", "var(--lumo-warning-text-color)");
            remark.getStyle().set("white-space", "pre-wrap");
            info.add(section("备注"), remark);
        }
        return info;
    }

    /** 下 1/3：输入框默认填第一条示例命令，可修改，下方一个复制按钮 */
    private VerticalLayout buildTrySection(LinuxCmd cmd) {
        TextArea editor = new TextArea("示例命令（参考上方说明修改后复制）");
        editor.setValue(StrUtil.emptyToDefault(firstExampleCommand(cmd), cmd.getCmdName()));
        editor.setWidthFull();
        editor.setHeightFull();
        // 命令是等宽场景，输入框里也用等宽字体
        editor.getStyle().set("font-family", "var(--lumo-font-family-monospace)");

        Button copyBtn = UiFactory.button("复制", () -> {
            String value = editor.getValue();
            if (StrUtil.isBlank(value)) {
                Dialogs.warn("输入框是空的，没有内容可复制");
                return;
            }
            UiFactory.copyToClipboard(this, value);
            Dialogs.success("已复制到剪贴板");
        });

        VerticalLayout tryArea = new VerticalLayout(editor, copyBtn);
        tryArea.setPadding(false);
        tryArea.setSpacing(false);
        tryArea.setWidthFull();
        tryArea.getStyle().set("gap", "8px");
        // 输入框吃掉剩余高度，复制按钮一行固定在下面
        tryArea.setFlexGrow(1, editor);
        return tryArea;
    }

    // ------------------------------------------------------------------
    // 解析工具
    // ------------------------------------------------------------------

    /** 示例字段 → [{命令, 说明}, ...]，一行一条，命令与说明用 => 分隔 */
    private List<String[]> parseExamples(String examples) {
        List<String[]> result = new ArrayList<>();
        for (String line : nonBlankLines(examples)) {
            int sep = line.indexOf(EXAMPLE_SEP);
            if (sep >= 0) {
                result.add(new String[]{line.substring(0, sep).trim(), line.substring(sep + EXAMPLE_SEP.length()).trim()});
            } else {
                result.add(new String[]{line.trim(), ""});
            }
        }
        return result;
    }

    /** 第一条示例的命令部分：表格「示例命令」列和弹窗输入框的默认值 */
    private String firstExampleCommand(LinuxCmd item) {
        List<String[]> examples = parseExamples(item.getCmdExamples());
        return examples.isEmpty() ? "" : examples.get(0)[0];
    }

    private List<String> nonBlankLines(String text) {
        List<String> lines = new ArrayList<>();
        if (StrUtil.isBlank(text)) {
            return lines;
        }
        for (String line : text.split("\\r?\\n")) {
            if (StrUtil.isNotBlank(line)) {
                lines.add(line.trim());
            }
        }
        return lines;
    }

    private Div monoLine(String text) {
        Div line = new Div();
        line.setText(text);
        line.getStyle().set("font-family", "var(--lumo-font-family-monospace)");
        line.getStyle().set("white-space", "pre-wrap");
        return line;
    }
}
