package com.sl.ui.tool;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sl.entity.ScriptInfoEntity;
import com.sl.mapper.ScriptInfoMapper;
import com.sl.ui.component.Dialogs;
import com.sl.ui.component.CodeEditor;
import com.sl.ui.component.UiFactory;
import com.sl.ui.component.ViewBase;
import com.sl.util.Constants;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.contextmenu.ContextMenu;
import com.vaadin.flow.component.contextmenu.MenuItem;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.spring.annotation.SpringComponent;
import com.vaadin.flow.spring.annotation.UIScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 脚本管理（其他工具 → 脚本管理）：卡片式脚本库。
 * <p>
 * 一张卡片一个脚本：浅绿色标题条展示脚本名称，标题下一行是创建时间，
 * 中间展示脚本内容的前三行（超出折叠），右下角「⋯」按钮弹出复制 / 删除 / 打开菜单；
 * 点卡片中间部位弹出只读的脚本详情。顶部按脚本名称搜索。
 * <p>
 * 主键 id_script 用创建时刻的纳秒值（见 {@link ScriptInfoEntity}），时间列是文本
 * yyyy-MM-dd HH:mm:ss。数据量小（运维脚本库），每次全量查库重建卡片即可，
 * 不做分页——分页的复杂度在这个量级纯属自找。
 */
@Service
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class ScriptMgmtView extends ViewBase {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(ScriptMgmtView.class);

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final transient ScriptInfoMapper scriptMapper;

    private final TextField searchField = UiFactory.textField();
    private final Span statusLabel = new Span();
    private final Div cardArea = new Div();

    public ScriptMgmtView(ScriptInfoMapper scriptMapper) {
        this.scriptMapper = scriptMapper;

        add(title("脚本管理"));
        add(subtitle("集中存放常用运维脚本：创建、复制、查看、删除。脚本 ID 取创建时刻的纳秒值，自动生成。"));

        searchField.setPlaceholder("输入脚本名称搜索");
        searchField.setWidth("260px");
        searchField.setClearButtonVisible(true);
        searchField.addKeyPressListener(com.vaadin.flow.component.Key.ENTER, e -> reload());

        Button searchBtn = UiFactory.primary("搜索", this::reload);
        Button resetBtn = UiFactory.button("清空", () -> {
            searchField.clear();
            reload();
        });
        Button addBtn = UiFactory.button("新建脚本", () -> {
            if (!hasPermission(Constants.ADD)) {
                Dialogs.warn("权限不足，无法新建脚本");
                return;
            }
            new ScriptEditDialog(null).open();
        });
        HorizontalLayout bar = UiFactory.group(
                new Span("名称"), searchField, searchBtn, resetBtn, spacer(), statusLabel, addBtn);
        add(bar);

        cardArea.addClassName("script-card-area");
        add(cardArea);
        setFlexGrow(1, cardArea);

        reload();
    }

    // ------------------------------------------------------------------
    // 数据与卡片
    // ------------------------------------------------------------------

    private void reload() {
        QueryWrapper<ScriptInfoEntity> wrapper = new QueryWrapper<>();
        if (StrUtil.isNotBlank(searchField.getValue())) {
            wrapper.like("script_name", searchField.getValue().trim());
        }
        wrapper.orderByDesc("create_time");
        List<ScriptInfoEntity> rows = scriptMapper.selectList(wrapper);
        statusLabel.setText("共 " + rows.size() + " 个脚本");

        cardArea.removeAll();
        if (rows.isEmpty()) {
            cardArea.add(UiFactory.emptyHint(StrUtil.isNotBlank(searchField.getValue())
                    ? "没有匹配「" + searchField.getValue().trim() + "」的脚本"
                    : "还没有脚本，点右上角「新建脚本」创建一个"));
            return;
        }
        for (ScriptInfoEntity script : rows) {
            cardArea.add(buildCard(script));
        }
    }

    /** 一张脚本卡片。中间部位（标题/时间/内容）可点开详情，右下角 ⋯ 弹操作菜单。 */
    private Component buildCard(ScriptInfoEntity script) {
        Div card = new Div();
        card.addClassName("script-card");

        // 标题条：浅绿色，展示脚本名称
        Div titleBar = new Div(new Span(StrUtil.nullToEmpty(script.getScriptName())));
        titleBar.addClassName("script-card-title");
        // 标题下一行：创建时间
        Div timeBar = new Div(new Span("创建于 " + StrUtil.nullToDefault(script.getCreateTime(), "-")));
        timeBar.addClassName("script-card-time");
        // 内容区：只展示三行，超出折叠（CSS line-clamp）
        Div content = new Div(new Span(StrUtil.nullToEmpty(script.getScriptContent())));
        content.addClassName("script-card-content");

        Div body = new Div(titleBar, timeBar, content);
        body.addClassName("script-card-body");
        body.addClickListener(e -> openViewDialog(script));
        body.setTitle("点击查看脚本详情");

        // 右下角三个点 + 弹出菜单（基于此脚本创建 / 复制内容 / 打开 / 删除）
        Button dots = UiFactory.small("⋯", () -> {
            // 菜单由 ContextMenu 的 setOpenOnClick 触发，按钮本身不用做事
        });
        dots.addClassName("script-card-dots");
        ContextMenu menu = new ContextMenu();
        menu.setTarget(dots);
        menu.setOpenOnClick(true);
        menu.addItem("基于此脚本创建", e -> {
            if (!hasPermission(Constants.ADD)) {
                Dialogs.warn("权限不足，无法创建脚本");
                return;
            }
            new ScriptEditDialog(script).open();
        });
        menu.addItem("复制内容", e -> copyContentToClipboard(script));
        menu.addItem("打开", e -> openViewDialog(script));
        MenuItem deleteItem = menu.addItem("删除", e -> confirmDelete(script));
        // 删除是危险操作，菜单项标红（只染文字，悬浮态由主题的背景反白兜底）
        deleteItem.getStyle().set("color", "var(--lumo-error-text-color)");

        HorizontalLayout footer = new HorizontalLayout(spacer(), dots);
        footer.setSpacing(false);
        footer.setAlignItems(FlexComponent.Alignment.CENTER);
        footer.addClassName("script-card-footer");

        card.add(body, footer);
        return card;
    }

    // ------------------------------------------------------------------
    // 删除
    // ------------------------------------------------------------------

    private void confirmDelete(ScriptInfoEntity script) {
        if (!hasPermission(Constants.DELETE)) {
            Dialogs.warn("权限不足，无法删除脚本");
            return;
        }
        Dialogs.confirmDanger("删除脚本",
                "确认删除脚本「" + StrUtil.nullToDefault(script.getScriptName(), script.getIdScript()) + "」吗？"
                        + "删除后不可恢复。",
                "确认删除", () -> {
                    try {
                        scriptMapper.deleteById(script.getIdScript());
                    } catch (Exception e) {
                        log.error("删除脚本失败：{}", script.getIdScript(), e);
                        Dialogs.error("删除失败：" + e.getMessage());
                        return;
                    }
                    reload();
                    Dialogs.success("已删除");
                });
    }

    // ------------------------------------------------------------------
    // 查看弹窗（点卡片中间 / 菜单「打开」）
    // ------------------------------------------------------------------

    private void openViewDialog(ScriptInfoEntity script) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("脚本详情：" + StrUtil.nullToDefault(script.getScriptName(), "-"));
        dialog.setWidth("800px");

        TextField idView = UiFactory.textField("脚本ID", "", "520px");
        idView.setValue(StrUtil.nullToEmpty(script.getIdScript()));
        idView.setReadOnly(true);
        TextField nameView = UiFactory.textField("脚本名称", "", "520px");
        nameView.setValue(StrUtil.nullToEmpty(script.getScriptName()));
        nameView.setReadOnly(true);
        TextField timeView = UiFactory.textField("创建时间", "", "520px");
        timeView.setValue(StrUtil.nullToEmpty(script.getCreateTime()));
        timeView.setReadOnly(true);
        TextField descView = UiFactory.textField("脚本说明", "", "520px");
        descView.setValue(StrUtil.nullToEmpty(script.getScriptDesc()));
        descView.setReadOnly(true);
        TextArea contentView = UiFactory.textArea("脚本内容", "640px");
        contentView.setValue(StrUtil.nullToEmpty(script.getScriptContent()));
        contentView.setReadOnly(true);
        contentView.setHeight("300px");
        contentView.getStyle().set("--lumo-font-family", "Consolas, 'Courier New', monospace");

        VerticalLayout form = new VerticalLayout(idView, nameView, timeView, descView, contentView);
        form.setPadding(false);
        form.setSpacing(false);
        form.getStyle().set("gap", "10px");
        dialog.add(form);

        Button copyBtn = UiFactory.button("复制内容", () -> {
            copyContentToClipboard(script);
            Dialogs.success("已复制脚本内容。若浏览器拦截了剪贴板访问，请手动选中复制");
        });
        dialog.getFooter().add(copyBtn, Dialogs.cancelButton(dialog::close));
        dialog.open();
    }

    /**
     * 把脚本内容写进系统剪贴板。
     * 非安全上下文（http 内网地址）下 navigator.clipboard 不存在，退回 execCommand 兜底。
     */
    private void copyContentToClipboard(ScriptInfoEntity script) {
        String value = StrUtil.nullToEmpty(script.getScriptContent());
        getElement().executeJs("""
                const value = $0;
                const fallbackCopy = (v) => {
                    const ta = document.createElement('textarea');
                    ta.value = v;
                    ta.style.position = 'fixed';
                    ta.style.top = '-1000px';
                    document.body.appendChild(ta);
                    ta.select();
                    try { document.execCommand('copy'); } catch (e) {}
                    document.body.removeChild(ta);
                };
                if (window.isSecureContext && navigator.clipboard && navigator.clipboard.writeText) {
                    navigator.clipboard.writeText(value).catch(() => fallbackCopy(value));
                } else {
                    fallbackCopy(value);
                }
                """, value);
    }

    // ------------------------------------------------------------------
    // 新建 / 复制弹窗（同一个表单；复制时预填原脚本内容，保存为一条新记录）
    // ------------------------------------------------------------------

    private class ScriptEditDialog extends Dialog {

        private final TextField nameField = UiFactory.textField("脚本名称", "卡片标题与搜索关键字", "520px");
        private final TextField descField = UiFactory.textField("脚本说明", "这个脚本是干什么的", "520px");
        private final CodeEditor contentField = new CodeEditor(CodeEditor.MODE_SHELL);

        /**
         * @param source 非 null 表示从这条脚本复制：预填名称（加 - 副本）、说明、内容，
         *               保存时生成新 ID 与新创建时间，不影响原脚本。
         */
        ScriptEditDialog(ScriptInfoEntity source) {
            setHeaderTitle(source == null ? "新建脚本" : "复制脚本");
            setWidth("680px");

            contentField.setHeight("320px");
            contentField.setWidth("640px");

            if (source != null) {
                nameField.setValue(StrUtil.nullToDefault(source.getScriptName(), "") + "-副本");
                descField.setValue(StrUtil.nullToEmpty(source.getScriptDesc()));
                contentField.setValue(StrUtil.nullToEmpty(source.getScriptContent()));
            }

            Span contentLabel = new Span("脚本内容");
            contentLabel.addClassName("view-section-title");
            VerticalLayout form = new VerticalLayout(nameField, descField, contentLabel, contentField);
            form.setPadding(false);
            form.setSpacing(false);
            form.getStyle().set("gap", "10px");
            add(form);

            getFooter().add(Dialogs.cancelButton(this::close),
                    Dialogs.primaryButton("保存", this::save));
        }

        private void save() {
            String name = StrUtil.trim(nameField.getValue());
            if (StrUtil.isBlank(name)) {
                Dialogs.warn("脚本名称不能为空！");
                return;
            }
            ScriptInfoEntity script = new ScriptInfoEntity();
            // 主键 = 当前系统纳秒值（需求指定）；同纳秒冲突的概率可忽略，但 insert 的
            // 主键冲突异常依然会被下面的 try 兜住并提示
            script.setIdScript(String.valueOf(System.nanoTime()));
            script.setScriptName(name);
            script.setScriptDesc(descField.getValue());
            script.setScriptContent(contentField.getValue());
            script.setCreateTime(LocalDateTime.now().format(TIME_FMT));

            try {
                scriptMapper.insert(script);
            } catch (Exception e) {
                log.error("保存脚本失败", e);
                Dialogs.error("保存失败：" + e.getMessage());
                return;
            }
            close();
            reload();
            Dialogs.success("脚本已保存");
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
