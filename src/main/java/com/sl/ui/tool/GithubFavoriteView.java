package com.sl.ui.tool;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.sl.entity.GithubFavoriteEntity;
import com.sl.mapper.GithubFavoriteMapper;
import com.sl.ui.component.Dialogs;
import com.sl.ui.component.UiFactory;
import com.sl.ui.component.ViewBase;
import com.sl.util.Constants;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.contextmenu.ContextMenu;
import com.vaadin.flow.component.contextmenu.MenuItem;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
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

import java.text.Collator;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * github优选（其他工具 → github优选）：卡片式开源项目收藏夹。
 * <p>
 * 一张卡片一个项目：最上方是项目名称，中间是项目介绍（超出三行折叠），
 * 右下角是向右的小箭头，点击直接跳转到 GitHub 项目页（新标签页）。
 * 卡片右下角还有一个「⋯」按钮，弹出 编辑 / 删除 菜单。
 * <p>
 * <b>分类管理。</b>项目按 {@code cd_category} 分组展示，每个分类之间用一条横向
 * 分割线分隔；分类信息直接存在每一行上（不为收藏夹量级的数据单独建分类表），
 * 「分类管理」弹窗里可以重命名 / 删除分类：重命名会同步改组内所有项目，
 * 删除则把这些项目移入「未分类」。新分类在编辑弹窗的分类框里直接手输即可。
 * <p>
 * 主键 id_github 用创建时刻的纳秒值（见 {@link GithubFavoriteEntity}），时间列是
 * 文本 yyyy-MM-dd HH:mm:ss。数据量小（收藏夹），每次全量查库重建卡片即可，
 * 不做分页——分页的复杂度在这个量级纯属自找。
 */
@Service
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class GithubFavoriteView extends ViewBase {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(GithubFavoriteView.class);

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 全部分类筛选项的值（与真实分类名不可能撞车：分类名不允许出现这个前缀） */
    private static final String ALL_CATEGORIES = "__ALL__";

    /** GitHub 地址白名单：http(s)://github.com/组织/仓库，后面允许带子路径 */
    private static final Pattern GITHUB_URL = Pattern.compile("^https?://github\\.com/[A-Za-z0-9_.\\-]+/[A-Za-z0-9_.\\-]+.*$");

    /** 中文按拼音排序：分类名多是中文，字典序会排成乱码 */
    private static final Collator CN_COLLATOR = Collator.getInstance(Locale.CHINA);

    private final transient GithubFavoriteMapper mapper;

    private final TextField searchField = UiFactory.textField();
    private final ComboBox<String> categoryFilter = new ComboBox<>();
    private final Span statusLabel = new Span();
    private final Div cardArea = new Div();

    public GithubFavoriteView(GithubFavoriteMapper mapper) {
        this.mapper = mapper;

        add(title("github优选"));
        add(subtitle("收藏值得反复看的开源项目：卡片右下角箭头直达 GitHub 项目页；支持新增、编辑、删除项目，"
                + "并按分类分组管理（分类之间以分割线分隔）。"));

        searchField.setPlaceholder("输入项目名称搜索");
        searchField.setWidth("240px");
        searchField.setClearButtonVisible(true);
        searchField.addKeyPressListener(com.vaadin.flow.component.Key.ENTER, e -> reload());

        Button searchBtn = UiFactory.primary("搜索", this::reload);
        Button resetBtn = UiFactory.button("清空", () -> {
            searchField.clear();
            categoryFilter.setValue(ALL_CATEGORIES);
            reload();
        });
        categoryFilter.setWidth("180px");
        categoryFilter.setPlaceholder("全部分类");
        categoryFilter.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                reload();
            }
        });

        Button addBtn = UiFactory.button("新增项目", () -> {
            if (!hasPermission(Constants.ADD)) {
                Dialogs.warn("权限不足，无法新增项目");
                return;
            }
            new ProjectEditDialog(null, loadCategories()).open();
        });
        Button catBtn = UiFactory.button("分类管理", () -> new CategoryManageDialog().open());

        HorizontalLayout bar = UiFactory.group(
                new Span("名称"), searchField, searchBtn, resetBtn,
                new Span("分类"), categoryFilter,
                spacer(), statusLabel, catBtn, addBtn);
        bar.getStyle().set("flex-wrap", "wrap");
        add(bar);

        cardArea.addClassName("github-card-area");
        add(cardArea);
        setFlexGrow(1, cardArea);

        reload();
    }

    // ------------------------------------------------------------------
    // 数据与卡片
    // ------------------------------------------------------------------

    private void reload() {
        List<GithubFavoriteEntity> rows = loadRows();
        List<String> categories = loadCategories();
        refreshCategoryFilter(categories);
        statusLabel.setText("共 " + rows.size() + " 个项目 · " + categories.size() + " 个分类");

        cardArea.removeAll();
        if (rows.isEmpty()) {
            cardArea.add(UiFactory.emptyHint(StrUtil.isNotBlank(searchField.getValue())
                    ? "没有匹配「" + searchField.getValue().trim() + "」的项目"
                    : "还没有项目，点右上角「新增项目」收藏第一个"));
            return;
        }

        // 按分类分组：空分类归「未分类」且排最后；其余按中文拼音序
        Map<String, List<GithubFavoriteEntity>> groups = groupByCategory(rows);
        int index = 0;
        for (Map.Entry<String, List<GithubFavoriteEntity>> group : groups.entrySet()) {
            if (index > 0) {
                cardArea.add(UiFactory.divider());
            }
            cardArea.add(buildCategoryHeader(group.getKey(), group.getValue().size()));
            for (GithubFavoriteEntity row : group.getValue()) {
                cardArea.add(buildCard(row));
            }
            index++;
        }
    }

    /** 查库：可按名称模糊、按分类精确过滤；组内按创建时间倒序（新收藏的在前）。 */
    private List<GithubFavoriteEntity> loadRows() {
        QueryWrapper<GithubFavoriteEntity> wrapper = new QueryWrapper<>();
        if (StrUtil.isNotBlank(searchField.getValue())) {
            wrapper.like("project_name", searchField.getValue().trim());
        }
        String filter = categoryFilter.getValue();
        if (StrUtil.isNotBlank(filter) && !ALL_CATEGORIES.equals(filter)) {
            wrapper.eq("cd_category", filter);
        }
        wrapper.orderByDesc("create_time");
        return mapper.selectList(wrapper);
    }

    /** 现有分类（去空、按中文拼音序），筛选下拉与编辑弹窗共用。 */
    private List<String> loadCategories() {
        List<GithubFavoriteEntity> rows = mapper.selectList(new QueryWrapper<GithubFavoriteEntity>()
                .select("cd_category"));
        List<String> categories = new ArrayList<>();
        for (GithubFavoriteEntity row : rows) {
            String cat = StrUtil.trimToEmpty(row.getCdCategory());
            if (!cat.isEmpty() && !categories.contains(cat)) {
                categories.add(cat);
            }
        }
        categories.sort(CN_COLLATOR);
        return categories;
    }

    private void refreshCategoryFilter(List<String> categories) {
        String previous = categoryFilter.getValue();
        List<String> items = new ArrayList<>();
        items.add(ALL_CATEGORIES);
        items.addAll(categories);
        categoryFilter.setItems(items);
        categoryFilter.setItemLabelGenerator(v -> ALL_CATEGORIES.equals(v) ? "全部分类" : v);
        // 保持用户已选的筛选：分类还在就继续筛，被删了/改名了就回落全部分类
        if (previous != null && items.contains(previous)) {
            categoryFilter.setValue(previous);
        } else {
            categoryFilter.setValue(ALL_CATEGORIES);
        }
    }

    /** 分组：空分类 → 「未分类」置底；其余按中文拼音序；组内保持 create_time 倒序。 */
    private Map<String, List<GithubFavoriteEntity>> groupByCategory(List<GithubFavoriteEntity> rows) {
        Map<String, List<GithubFavoriteEntity>> groups = new LinkedHashMap<>();
        List<String> named = new ArrayList<>();
        List<GithubFavoriteEntity> uncategorized = new ArrayList<>();
        for (GithubFavoriteEntity row : rows) {
            String cat = StrUtil.trimToEmpty(row.getCdCategory());
            if (cat.isEmpty()) {
                uncategorized.add(row);
            } else {
                groups.computeIfAbsent(cat, k -> new ArrayList<>()).add(row);
                if (!named.contains(cat)) {
                    named.add(cat);
                }
            }
        }
        named.sort(CN_COLLATOR);
        Map<String, List<GithubFavoriteEntity>> sorted = new LinkedHashMap<>();
        for (String cat : named) {
            sorted.put(cat, groups.get(cat));
        }
        if (!uncategorized.isEmpty()) {
            sorted.put("未分类", uncategorized);
        }
        return sorted;
    }

    /** 分类标题行：分类名 + 项目数。 */
    private Component buildCategoryHeader(String category, int count) {
        Span name = new Span(category);
        name.addClassName("view-section-title");
        Span countLabel = new Span(count + " 个项目");
        countLabel.addClassName("github-cat-count");
        HorizontalLayout header = new HorizontalLayout(name, countLabel);
        header.setSpacing(false);
        header.getStyle().set("gap", "8px");
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.addClassName("github-cat-head");
        return header;
    }

    /**
     * 一张项目卡片：标题（项目名）→ 介绍（三行折叠）→ 底部分类角标 + ⋯ 菜单 + 右向箭头。
     * 点卡片主体等同于点箭头，都是打开 GitHub 项目页。
     */
    private Component buildCard(GithubFavoriteEntity row) {
        Div card = new Div();
        card.addClassName("github-card");

        Div titleBar = new Div(new Span(StrUtil.nullToEmpty(row.getProjectName())));
        titleBar.addClassName("github-card-title");
        titleBar.setTitle(StrUtil.nullToEmpty(row.getProjectName()));

        Div content = new Div(new Span(StrUtil.nullToEmpty(row.getProjectDesc())));
        content.addClassName("github-card-content");

        Div body = new Div(titleBar, content);
        body.addClassName("github-card-body");
        body.addClickListener(e -> openProjectPage(row));
        body.setTitle("点击打开 GitHub 项目页");

        Div footer = new Div(buildCardActions(row));
        footer.addClassName("github-card-footer");

        card.add(body, footer);
        return card;
    }

    /** 卡片底部：左侧分类角标，右侧 ⋯ 菜单（编辑/删除）与右向箭头（跳转）。 */
    private Component buildCardActions(GithubFavoriteEntity row) {
        Span cat = new Span(StrUtil.isBlank(row.getCdCategory()) ? "未分类" : row.getCdCategory());
        cat.addClassName("github-card-cat");

        Button dots = UiFactory.small("⋯", () -> {
            // 菜单由 ContextMenu 的 setOpenOnClick 触发，按钮本身不用做事
        });
        dots.addClassName("github-card-dots");
        ContextMenu menu = new ContextMenu();
        menu.setTarget(dots);
        menu.setOpenOnClick(true);
        menu.addItem("编辑", e -> {
            if (!hasPermission(Constants.UPDATE)) {
                Dialogs.warn("权限不足，无法编辑项目");
                return;
            }
            new ProjectEditDialog(row, loadCategories()).open();
        });
        MenuItem deleteItem = menu.addItem("删除", e -> confirmDelete(row));
        deleteItem.getStyle().set("color", "var(--lumo-error-text-color)");

        // 右向箭头：真正的超链接（可中键新开、可复制链接），指向 GitHub 项目页
        Anchor arrow = new Anchor(row.getProjectUrl(), new Icon(VaadinIcon.ANGLE_RIGHT));
        arrow.setTarget("_blank");
        arrow.setRouterIgnore(true);
        arrow.getElement().setAttribute("rel", "noopener noreferrer");
        arrow.addClassName("github-card-arrow");
        arrow.setTitle("访问 GitHub 项目页：" + row.getProjectUrl());

        HorizontalLayout actions = new HorizontalLayout(cat, spacer(), dots, arrow);
        actions.setSpacing(false);
        actions.addClassName("row-actions");
        actions.setAlignItems(FlexComponent.Alignment.CENTER);
        return actions;
    }

    /** 点卡片主体打开项目页（Page.open 在点击手势里调用，不会被浏览器当弹窗拦）。 */
    private void openProjectPage(GithubFavoriteEntity row) {
        String url = StrUtil.trimToEmpty(row.getProjectUrl());
        if (url.isEmpty()) {
            Dialogs.warn("该项目没有填写 GitHub 地址");
            return;
        }
        getUI().ifPresent(ui -> ui.getPage().open(url, "_blank"));
    }

    // ------------------------------------------------------------------
    // 删除
    // ------------------------------------------------------------------

    private void confirmDelete(GithubFavoriteEntity row) {
        if (!hasPermission(Constants.DELETE)) {
            Dialogs.warn("权限不足，无法删除项目");
            return;
        }
        Dialogs.confirmDanger("删除项目",
                "确认删除项目「" + StrUtil.nullToDefault(row.getProjectName(), row.getIdGithub()) + "」吗？"
                        + "删除后不可恢复。",
                "确认删除", () -> {
                    try {
                        mapper.deleteById(row.getIdGithub());
                    } catch (Exception e) {
                        log.error("删除项目失败：{}", row.getIdGithub(), e);
                        Dialogs.error("删除失败：" + e.getMessage());
                        return;
                    }
                    reload();
                    Dialogs.success("已删除");
                });
    }

    // ------------------------------------------------------------------
    // 新增 / 编辑弹窗
    // ------------------------------------------------------------------

    private class ProjectEditDialog extends Dialog {

        /** 非 null 表示在编辑这条记录；null 表示新增。 */
        private final GithubFavoriteEntity source;

        private final TextField nameField = UiFactory.textField("", "卡片标题，如 spring-boot", "520px");
        private final TextField urlField = UiFactory.textField("","https://github.com/组织/仓库", "520px");
        private final TextArea descField = UiFactory.textArea("", "520px");
        private final ComboBox<String> categoryCombo = new ComboBox<>();

        /**
         * @param source     非 null 表示编辑这条记录；null 表示新增。
         * @param categories 现有的分类，供下拉选择；也允许直接手输新分类。
         */
        ProjectEditDialog(GithubFavoriteEntity source, List<String> categories) {
            this.source = source;
            setHeaderTitle(source == null ? "新增项目" : "编辑项目");
            setWidth("720px");

            descField.setHeight("120px");
            descField.setPlaceholder("这个项目是干什么的、解决什么问题（卡片中部展示，超出三行折叠）");

            categoryCombo.setWidth("520px");
            categoryCombo.setItems(categories);
            categoryCombo.setAllowCustomValue(true);
            categoryCombo.setPlaceholder("选择已有分类，或直接输入新分类名（留空 = 未分类）");
            categoryCombo.setClearButtonVisible(true);
            /*
             * 只 setAllowCustomValue(true) 是不够的：ComboBox 不会自动把手输的值
             * 写进 value（官方 javadoc 原话：doesn't do anything with the custom
             * value string automatically），必须自己挂 addCustomValueSetListener
             * 把 detail 设回去——否则失焦/点保存时手输的分类被静默丢弃，
             * getValue() 拿到 null，项目落回「未分类」。与 ComposeCreateDialog
             * 的 fileCombo 同一写法。
             */
            categoryCombo.addCustomValueSetListener(e -> {
                String custom = StrUtil.trimToEmpty(e.getDetail());
                if (custom.isEmpty()) {
                    categoryCombo.setValue(null);
                    return;
                }
                // 顺手并进候选列表，下拉里能看见刚输的分类（官方「stored and added」写法）
                List<String> merged = new ArrayList<>(categories);
                if (!merged.contains(custom)) {
                    merged.add(custom);
                }
                categoryCombo.setItems(merged);
                categoryCombo.setValue(custom);
            });

            if (source != null) {
                nameField.setValue(StrUtil.nullToEmpty(source.getProjectName()));
                urlField.setValue(StrUtil.nullToEmpty(source.getProjectUrl()));
                descField.setValue(StrUtil.nullToEmpty(source.getProjectDesc()));
                categoryCombo.setValue(StrUtil.isBlank(source.getCdCategory())
                        ? null : source.getCdCategory().trim());
            }

            VerticalLayout form = new VerticalLayout(
                    UiFactory.fieldRow("项目名称", nameField),
                    UiFactory.fieldRow("GitHub 地址", urlField),
                    UiFactory.fieldRow("项目介绍", descField),
                    UiFactory.fieldRow("分类", categoryCombo));
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
                Dialogs.warn("项目名称不能为空！");
                return;
            }
            String url = normalizeUrl(urlField.getValue());
            if (url == null) {
                return; // 提示已在 normalizeUrl 里给过
            }

            GithubFavoriteEntity entity = new GithubFavoriteEntity();
            entity.setIdGithub(String.valueOf(System.nanoTime()));
            entity.setProjectName(name);
            entity.setProjectUrl(url);
            entity.setProjectDesc(StrUtil.trimToEmpty(descField.getValue()));
            entity.setCdCategory(StrUtil.trimToEmpty(categoryCombo.getValue()));

            try {
                if (source != null) {
                    entity.setIdGithub(source.getIdGithub());
                    entity.setCreateTime(source.getCreateTime());
                    mapper.updateById(entity);
                } else {
                    entity.setCreateTime(LocalDateTime.now().format(TIME_FMT));
                    mapper.insert(entity);
                }
            } catch (Exception e) {
                log.error("保存项目失败", e);
                Dialogs.error("保存失败：" + e.getMessage());
                return;
            }
            close();
            reload();
            Dialogs.success(source == null ? "项目已保存" : "修改已保存");
        }

        /** 规范化并校验 GitHub 地址：补 https:// 前缀，只认 github.com。 */
        private String normalizeUrl(String raw) {
            String url = StrUtil.trimToEmpty(raw);
            if (url.isEmpty()) {
                Dialogs.warn("GitHub 地址不能为空！");
                return null;
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }
            if (!GITHUB_URL.matcher(url).matches()) {
                Dialogs.warn("只支持 GitHub 项目地址，形如 https://github.com/组织/仓库，收到：" + url);
                return null;
            }
            return url;
        }
    }

    // ------------------------------------------------------------------
    // 分类管理
    // ------------------------------------------------------------------

    private class CategoryManageDialog extends Dialog {

        CategoryManageDialog() {
            setHeaderTitle("分类管理");
            setWidth("560px");

            VerticalLayout list = new VerticalLayout();
            list.setPadding(false);
            list.setSpacing(false);
            list.getStyle().set("gap", "8px");

            List<String> categories = loadCategories();
            if (categories.isEmpty()) {
                list.add(UiFactory.emptyHint("还没有分类。在「新增项目 / 编辑项目」弹窗的分类框里手输一个名称即可新建分类。"));
            } else {
                Map<String, Integer> counts = categoryCounts();
                for (String category : categories) {
                    list.add(buildCategoryRow(category, counts.getOrDefault(category, 0)));
                }
            }
            add(list);
            getFooter().add(Dialogs.primaryButton("关闭", this::close));
        }

        /** 一行分类：名称 + 项目数 + 重命名 / 删除。 */
        private Component buildCategoryRow(String category, int count) {
            Span name = new Span(category);
            name.addClassName("github-cat-row-name");
            Span countLabel = new Span(count + " 个项目");
            countLabel.addClassName("github-cat-count");

            Button renameBtn = UiFactory.small("重命名", () -> renameCategory(category));
            Button deleteBtn = UiFactory.small("删除", () -> deleteCategory(category, count));

            HorizontalLayout row = new HorizontalLayout(name, countLabel, spacer(), renameBtn, deleteBtn);
            row.setWidthFull();
            row.setSpacing(false);
            row.getStyle().set("gap", "8px");
            row.setAlignItems(FlexComponent.Alignment.CENTER);
            row.addClassName("github-cat-row");
            return row;
        }

        /** 重命名：同步改组内所有项目的 cd_category（一条 UPDATE ... WHERE cd_category=old）。 */
        private void renameCategory(String oldName) {
            if (!hasPermission(Constants.UPDATE)) {
                Dialogs.warn("权限不足，无法重命名分类");
                return;
            }
            Dialog dialog = new Dialog();
            dialog.setHeaderTitle("重命名分类");
            dialog.setWidth("460px");
            TextField field = UiFactory.textField("新名称", oldName, "360px");
            field.setValue(oldName);
            VerticalLayout form = new VerticalLayout(UiFactory.fieldRow("分类名称", field));
            form.setPadding(false);
            dialog.add(form);
            dialog.getFooter().add(Dialogs.cancelButton(dialog::close),
                    Dialogs.primaryButton("保存", () -> {
                        String newName = StrUtil.trim(field.getValue());
                        if (StrUtil.isBlank(newName)) {
                            Dialogs.warn("分类名称不能为空！");
                            return;
                        }
                        if (newName.equals(oldName)) {
                            dialog.close();
                            return;
                        }
                        if (loadCategories().contains(newName)) {
                            Dialogs.warn("已存在同名分类「" + newName + "」，请换个名称");
                            return;
                        }
                        try {
                            mapper.update(null, new LambdaUpdateWrapper<GithubFavoriteEntity>()
                                    .eq(GithubFavoriteEntity::getCdCategory, oldName)
                                    .set(GithubFavoriteEntity::getCdCategory, newName));
                        } catch (Exception e) {
                            log.error("重命名分类失败：{} -> {}", oldName, newName, e);
                            Dialogs.error("重命名失败：" + e.getMessage());
                            return;
                        }
                        dialog.close();
                        close();
                        reload();
                        Dialogs.success("分类已重命名，组内 " + categoryCounts().getOrDefault(newName, 0) + " 个项目同步更新");
                    }));
            dialog.open();
        }

        /**
         * 删除分类：不删项目，只把组内项目的 cd_category 清空（落入「未分类」组）。
         * 收藏夹里的项目是资产，删分类不该连带删项目。
         */
        private void deleteCategory(String category, int count) {
            if (!hasPermission(Constants.DELETE)) {
                Dialogs.warn("权限不足，无法删除分类");
                return;
            }
            Dialogs.confirmDanger("删除分类",
                    "确认删除分类「" + category + "」吗？\n该分类下的 " + count + " 个项目不会被删除，"
                            + "将移入「未分类」组。",
                    "确认删除", () -> {
                        try {
                            mapper.update(null, new LambdaUpdateWrapper<GithubFavoriteEntity>()
                                    .eq(GithubFavoriteEntity::getCdCategory, category)
                                    .set(GithubFavoriteEntity::getCdCategory, null));
                        } catch (Exception e) {
                            log.error("删除分类失败：{}", category, e);
                            Dialogs.error("删除分类失败：" + e.getMessage());
                            return;
                        }
                        close();
                        reload();
                        Dialogs.success("分类已删除，相关项目已移入「未分类」");
                    });
        }
    }

    /** 每个分类下的项目数（分类管理弹窗用）。 */
    private Map<String, Integer> categoryCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (GithubFavoriteEntity row : mapper.selectList(new QueryWrapper<GithubFavoriteEntity>()
                .select("cd_category"))) {
            String cat = StrUtil.trimToEmpty(row.getCdCategory());
            if (!cat.isEmpty()) {
                counts.merge(cat, 1, Integer::sum);
            }
        }
        return counts;
    }

    // ------------------------------------------------------------------
    // 权限
    // ------------------------------------------------------------------

    private static boolean hasPermission(String code) {
        com.sl.entity.User user = com.sl.security.CurrentUser.get();
        return user != null && user.hasPermission(code);
    }
}
