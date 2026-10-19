package com.sl.ui.admin;

import cn.hutool.core.util.StrUtil;
import com.sl.entity.Permission;
import com.sl.entity.Role;
import com.sl.service.RbacService;
import com.sl.ui.component.Dialogs;
import com.sl.ui.component.UiFactory;
import com.sl.ui.component.ViewBase;
import com.sl.util.PermCodes;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 角色管理页（RBAC）。
 * <p>
 * 对应需求里缺少的那一半：用户管理页管「谁登录」，本页管「登录后能干什么」。
 * 角色是权限的集合，权限树按<b>根 = 菜单、子 = 页面内部操作按钮</b>两级组织
 * （见 {@code PermissionCatalog}）：勾「用户管理」这个根，只给开门；
 * 再勾「新增用户 / 修改用户 / 删除用户」这些子节点，才给页面里的按钮放行。
 * <p>
 * 新建角色时按 {@code PermissionCatalog.defaultRoleCodes()} 预勾：
 * 概览（使用手册）、免登录服务器列表、drawio、其他工具全部工具页、github优选。
 * <p>
 * 内置角色（{@code flag_builtin = 1}，目前是「系统管理员」）只读——删掉或改瘦它，
 * 就没有全权入口了，与内置管理员账号同一保护逻辑。
 */
@Service
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class RoleManagementView extends ViewBase {

    private static final Logger log = LoggerFactory.getLogger(RoleManagementView.class);

    /** 角色名：与用户名同一套字符集，2-50 位 */
    private static final Pattern PATTERN_ROLE_NAME = Pattern.compile("^[\\u4e00-\\u9fa5A-Za-z][\\u4e00-\\u9fa5A-Za-z0-9_.\\- ]{1,49}$");

    private final RbacService rbacService;

    private final Grid<Role> roleGrid = UiFactory.grid(Role.class);
    private final TextField keywordField = UiFactory.textField();
    private final ComboBox<String> sortBox = new ComboBox<>();

    private final Span pageInfoLb = new Span("");

    private static final String SORT_NAME_ASC = "角色名 A→Z";
    private static final String SORT_NAME_DESC = "角色名 Z→A";
    private static final String SORT_CREATE_DESC = "创建时间 新→旧";
    private static final String SORT_CREATE_ASC = "创建时间 旧→新";

    /** 全量数据；筛选、排序、分页都在内存里做，角色量级不大 */
    private List<Role> allRoles = new ArrayList<>();
    private List<Role> viewRoles = new ArrayList<>();

    public RoleManagementView(RbacService rbacService) {
        this.rbacService = rbacService;

        add(title("角色管理"));
        add(subtitle("维护角色与权限：权限树根为菜单，子节点为页面内部的操作按钮"));

        add(buildFilterRow());

        buildGrid();
        HorizontalLayout foot = buildFootRow();
        VerticalLayout fill = fill(roleGrid, foot);
        add(fill);
        setFlexGrow(1, fill);

        reloadData();
    }

    // ==================================================================
    // 筛选栏
    // ==================================================================

    private Component buildFilterRow() {
        keywordField.setWidth("250px");
        keywordField.setPlaceholder("角色名 / 描述 / 权限码");
        keywordField.setValueChangeMode(com.vaadin.flow.data.value.ValueChangeMode.LAZY);
        keywordField.setValueChangeTimeout(350);
        keywordField.addValueChangeListener(e -> {
            refreshView();
        });

        sortBox.setItems(SORT_NAME_ASC, SORT_NAME_DESC, SORT_CREATE_DESC, SORT_CREATE_ASC);
        sortBox.setValue(SORT_CREATE_DESC);
        sortBox.setWidth("180px");
        sortBox.addValueChangeListener(e -> refreshView());

        Button resetBtn = UiFactory.button("重置筛选", this::resetFilter);
        Button addBtn = UiFactory.primary("新增角色", () -> {
            if (!com.sl.security.CurrentUser.hasPermission(PermCodes.ROLE_ADD)) {
                Dialogs.warn("权限不足，无法新增角色");
                return;
            }
            new RoleEditDialog(null).open();
        });

        return toolbar(
                UiFactory.fieldRow("关键字", "68px", keywordField),
                UiFactory.fieldRow("排序", "52px", sortBox),
                resetBtn, spacer(), addBtn);
    }

    private void resetFilter() {
        keywordField.setValue("");
        sortBox.setValue(SORT_CREATE_DESC);
        refreshView();
    }

    // ==================================================================
    // 表格
    // ==================================================================

    private void buildGrid() {
        roleGrid.setSelectionMode(Grid.SelectionMode.MULTI);
        roleGrid.setColumnReorderingAllowed(true);

        roleGrid.addColumn(new ComponentRenderer<>(this::roleCell)).setHeader("角色").setAutoWidth(true);
        roleGrid.addColumn(Role::getCdDesc).setHeader("描述").setAutoWidth(true);
        roleGrid.addColumn(new ComponentRenderer<>(this::permCountCell)).setHeader("权限").setAutoWidth(true);
        roleGrid.addColumn(new ComponentRenderer<>(this::userCountCell)).setHeader("关联用户").setAutoWidth(true);
        roleGrid.addColumn(r -> r.getCreateTime() == null ? "—" : com.sl.util.Util.formatDateTime(r.getCreateTime()))
                .setHeader("创建时间").setAutoWidth(true);
        roleGrid.getColumns().forEach(column -> column.setSortable(false));
        roleGrid.addComponentColumn(this::opCell).setHeader("操作").setAutoWidth(true);
    }

    /** 角色名列：首字母头像 + 角色名（内置加标签） */
    private Div roleCell(Role role) {
        String name = StrUtil.nullToEmpty(role.getNameRole());
        String initial = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();

        Span avatar = new Span(initial);
        avatar.addClassName("um-avatar");

        Span nameSpan = new Span(name);
        nameSpan.addClassName("um-user-id");
        if (role.isBuiltin()) {
            Span builtin = new Span("内置");
            builtin.getElement().getThemeList().add("badge");
            builtin.getElement().getThemeList().add("success");
            builtin.getElement().setAttribute("title", "内置角色：权限固定为全部，不允许修改或删除");
            nameSpan.add(builtin);
        }

        Div cell = new Div(avatar, nameSpan);
        cell.addClassName("um-user-cell");
        return cell;
    }

    /** 权限数列：数量徽标 + tooltip 列出权限码 */
    private Span permCountCell(Role role) {
        int count = role.getPermissionCodes() == null ? 0 : role.getPermissionCodes().size();
        Span badge = new Span(count + " 项");
        badge.addClassName("home-badge");
        badge.addClassName(count == 0 ? "home-badge-disabled" : "home-badge-ok");
        if (count > 0) {
            badge.getElement().setAttribute("title", String.join("、", role.getPermissionCodes()));
        }
        return badge;
    }

    /** 关联用户数列：数量徽标，点击无操作（只读展示） */
    private Span userCountCell(Role role) {
        int count = role.getUserCount() == null ? 0 : role.getUserCount();
        Span badge = new Span(count + " 人");
        badge.addClassName("home-badge");
        badge.addClassName(count == 0 ? "home-badge-disabled" : "home-badge-ok");
        return badge;
    }

    /** 行内操作：编辑 / 删除。内置角色只留给「查看」。 */
    private HorizontalLayout opCell(Role role) {
        HorizontalLayout box = new HorizontalLayout();
        box.setSpacing(false);
        box.getStyle().set("gap", "2px");
        box.setAlignItems(FlexComponent.Alignment.CENTER);
        box.addClassName("row-actions");

        if (role.isBuiltin()) {
            box.add(UiFactory.rowAction("查看", () -> new RoleEditDialog(role).open()));
            Span lock = new Span("受保护");
            lock.getElement().getThemeList().add("badge");
            lock.getElement().getThemeList().add("contrast");
            lock.getElement().setAttribute("title", "内置角色，不允许修改或删除");
            box.add(lock);
            return box;
        }
        box.add(UiFactory.rowAction("编辑", () -> {
            if (!com.sl.security.CurrentUser.hasPermission(PermCodes.ROLE_UPDATE)) {
                Dialogs.warn("权限不足，无法修改角色");
                return;
            }
            new RoleEditDialog(role).open();
        }));
        Button delBtn = UiFactory.rowDanger("删除", () -> confirmDelete(role));
        box.add(delBtn);
        return box;
    }

    // ==================================================================
    // 底部
    // ==================================================================

    private HorizontalLayout buildFootRow() {
        HorizontalLayout foot = new HorizontalLayout(pageInfoLb);
        foot.setWidthFull();
        foot.setAlignItems(FlexComponent.Alignment.CENTER);
        foot.getStyle().set("padding-top", "8px");
        return foot;
    }

    // ==================================================================
    // 数据加载与渲染
    // ==================================================================

    private void reloadData() {
        allRoles = rbacService.allRoles();
        refreshView();
    }

    private void refreshView() {
        viewRoles = applyFilter(allRoles);
        sortView();
        renderPage();
    }

    private List<Role> applyFilter(List<Role> source) {
        String keyword = StrUtil.trim(keywordField.getValue()).toLowerCase();
        List<Role> result = new ArrayList<>();
        for (Role role : source) {
            if (matchKeyword(role, keyword)) {
                result.add(role);
            }
        }
        return result;
    }

    private boolean matchKeyword(Role role, String keyword) {
        if (StrUtil.isBlank(keyword)) {
            return true;
        }
        if (contains(role.getNameRole(), keyword) || contains(role.getCdDesc(), keyword)) {
            return true;
        }
        if (role.getPermissionCodes() != null) {
            for (String code : role.getPermissionCodes()) {
                if (contains(code, keyword)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean contains(String text, String keyword) {
        return text != null && text.toLowerCase().contains(keyword);
    }

    private void sortView() {
        String sort = sortBox.getValue();
        viewRoles.sort((a, b) -> {
            if (SORT_NAME_DESC.equals(sort)) {
                return StrUtil.nullToEmpty(b.getNameRole()).compareTo(StrUtil.nullToEmpty(a.getNameRole()));
            }
            if (SORT_CREATE_ASC.equals(sort)) {
                return dateAscNullLast(a.getCreateTime(), b.getCreateTime());
            }
            if (SORT_CREATE_DESC.equals(sort)) {
                return dateAscNullLast(b.getCreateTime(), a.getCreateTime());
            }
            return StrUtil.nullToEmpty(a.getNameRole()).compareTo(StrUtil.nullToEmpty(b.getNameRole()));
        });
    }

    private static int dateAscNullLast(Date d1, Date d2) {
        if (d1 == null && d2 == null) {
            return 0;
        }
        if (d1 == null) {
            return 1;
        }
        if (d2 == null) {
            return -1;
        }
        return d1.compareTo(d2);
    }

    private void renderPage() {
        roleGrid.setItems(viewRoles);
        pageInfoLb.setText(String.format("共 %d 个角色", viewRoles.size()));
    }

    // ==================================================================
    // 删除
    // ==================================================================

    private void confirmDelete(Role role) {
        if (!com.sl.security.CurrentUser.hasPermission(PermCodes.ROLE_DELETE)) {
            Dialogs.warn("权限不足，无法删除角色");
            return;
        }
        int users = rbacService.countRoleUsers(role.getRoleId());
        if (users > 0) {
            Dialogs.warn("角色「" + role.getNameRole() + "」已分配给 " + users
                    + " 个用户，请先在用户管理中调整这些账号的角色");
            return;
        }
        Dialogs.confirmDanger("删除角色",
                "确定要删除角色「" + role.getNameRole() + "」吗？删除后该角色的权限配置将不可恢复。",
                "确认删除", () -> {
                    rbacService.deleteRole(role.getRoleId());
                    log.info("删除角色 {}（{}）", role.getRoleId(), role.getNameRole());
                    reloadData();
                    Dialogs.success("已删除角色 " + role.getNameRole());
                });
    }

    // ==================================================================
    // 新增 / 编辑 / 查看窗口
    // ==================================================================

    /**
     * 权限树节点包装：Permission + 子节点 + 勾选状态。
     * <p>
     * Checkbox 是每个节点一份的组件实例（TreeGrid 组件列复用同一实例，不会重复创建）。
     * 父子联动规则：
     * <ul>
     * <li>勾/取消父节点 → 所有子节点同步为同一状态；</li>
     * <li>勾子节点 → 父节点自动变为已勾（页面访问权是按钮权的前提）；</li>
     * <li>取消子节点 → 父节点按「部分选中」显示半选（indeterminate）。</li>
     * </ul>
     */
    private static final class PermNode {
        final Permission perm;
        final List<PermNode> children = new ArrayList<>();
        final Checkbox checkbox = new Checkbox();

        PermNode(Permission perm) {
            this.perm = perm;
        }

        String code() {
            return perm.getCdPerm();
        }
    }

    /** 新增与编辑共用的窗口；{@code readOnly} 时是内置角色的查看态。 */
    private class RoleEditDialog extends com.vaadin.flow.component.dialog.Dialog {

        private final TextField nameField = UiFactory.textField("角色名 *", "2-50 个字符，创建后不可修改");
        private final TextField descField = UiFactory.textField("角色描述", "这个角色是给谁用的");
        private final TreeGrid<PermNode> permTree = new TreeGrid<>();

        private final List<PermNode> roots = new ArrayList<>();
        /** 父子联动时抑制递归的哨兵 */
        private boolean syncing = false;

        private final Role editing;
        private final boolean readOnly;

        RoleEditDialog(Role editing) {
            this.editing = editing;
            this.readOnly = editing != null && editing.isBuiltin();
            boolean creating = editing == null;

            setHeaderTitle(creating ? "新增角色" : (readOnly ? "查看角色 " + editing.getNameRole() : "编辑角色 " + editing.getNameRole()));
            setWidth("860px");
            setMaxHeight("760px");
            setCloseOnOutsideClick(false);

            nameField.setWidthFull();
            descField.setWidthFull();

            buildTree();
            HorizontalLayout treeTools = buildTreeTools();
            VerticalLayout treeBox = new VerticalLayout(treeTools, permTree);
            treeBox.setPadding(false);
            treeBox.setSpacing(false);
            treeBox.getStyle().set("gap", "8px");
            treeBox.setWidthFull();
            treeBox.setFlexGrow(1, permTree);

            Span hint = new Span(creating
                    ? "勾选规则：根节点是菜单（页面访问权），子节点是页面内部的操作按钮；新建角色默认勾选概览、免登录服务器列表、drawio、其他工具与 github优选。"
                    : "根节点是菜单（页面访问权），子节点是页面内部的操作按钮；取消根节点会同时取消它的全部按钮权限。");
            hint.addClassName("view-subtitle");

            Button cancel = UiFactory.button("关闭", this::close);
            Button save = UiFactory.primary(creating ? "创建" : "保存", this::save);
            getFooter().add(cancel, save);

            VerticalLayout form = new VerticalLayout(nameField, descField, treeBox, hint);
            form.setPadding(false);
            form.setSpacing(false);
            form.getStyle().set("gap", "12px");
            form.setWidthFull();
            form.setFlexGrow(0, nameField);
            form.setFlexGrow(0, descField);
            form.setFlexGrow(1, treeBox);
            form.setFlexGrow(0, hint);
            add(form);

            if (creating) {
                selectCodes(defaultCodes());
            } else {
                nameField.setValue(StrUtil.nullToEmpty(editing.getNameRole()));
                descField.setValue(StrUtil.nullToEmpty(editing.getCdDesc()));
                selectCodes(editing.getPermissionCodes());
            }
            if (readOnly) {
                // 内置角色：纯查看态，不给保存入口
                nameField.setReadOnly(true);
                descField.setReadOnly(true);
                hint.setText("内置角色的权限固定为全部，由系统在启动时维护，不允许修改。");
                getFooter().removeAll();
                getFooter().add(UiFactory.button("关闭", this::close));
            }
        }

        /** 新建角色的默认勾选清单 */
        private Set<String> defaultCodes() {
            return com.sl.ui.PermissionCatalog.defaultRoleCodes();
        }

        // ------------------------------------------------------------------
        // 权限树
        // ------------------------------------------------------------------

        private void buildTree() {
            permTree.addClassName("role-perm-tree");
            permTree.setSelectionMode(Grid.SelectionMode.NONE);
            permTree.setMinHeight("260px");
            permTree.setMaxHeight("420px");
            permTree.setWidthFull();

            permTree.addComponentColumn(node -> node.checkbox).setHeader("选择").setAutoWidth(true);
            permTree.addHierarchyColumn(node -> node.perm.getNamePerm()).setHeader("权限名称").setAutoWidth(true);
            permTree.addColumn(node -> StrUtil.nullToEmpty(node.perm.getCdPerm())).setHeader("权限码").setAutoWidth(true);
            permTree.addColumn(node -> StrUtil.nullToEmpty(node.perm.getCdDesc())).setHeader("说明").setAutoWidth(true);

            roots.clear();
            for (Permission root : rbacService.permissionTree()) {
                PermNode node = toNode(root, null);
                roots.add(node);
            }
            permTree.setItems(roots, node -> node.children);
            permTree.expand(roots);
        }

        /** 递归构节点，并挂上父子联动的勾选逻辑 */
        private PermNode toNode(Permission perm, PermNode parent) {
            PermNode node = new PermNode(perm);
            if (parent != null) {
                parent.children.add(node);
            }
            for (Permission child : perm.getChildren() == null ? List.<Permission>of() : perm.getChildren()) {
                toNode(child, node);
            }
            node.checkbox.addValueChangeListener(e -> {
                if (syncing || e.getValue() == null) {
                    return;
                }
                boolean value = e.getValue();
                if (parent == null) {
                    // 父节点：整组同步
                    syncing = true;
                    for (PermNode child : node.children) {
                        child.checkbox.setValue(value);
                        child.checkbox.setIndeterminate(false);
                    }
                    node.checkbox.setIndeterminate(false);
                    syncing = false;
                } else if (value) {
                    // 子节点被勾 → 父节点至少是半选/全选
                    syncing = true;
                    parent.checkbox.setValue(true);
                    refreshParent(parent);
                    syncing = false;
                } else {
                    refreshParent(parent);
                }
            });
            return node;
        }

        /**
         * 按子节点勾选情况刷新父节点的显示形态。
         * <p>
         * 三态约定（菜单授权与按钮授权相互独立，「只给菜单不给按钮」是合法状态，
         * 新建角色的默认勾选就是纯菜单级）：
         * <ul>
         * <li>子节点全部勾上 → 父节点实心勾（菜单 + 全部按钮）；</li>
         * <li>子节点部分勾上或全部没勾 → 父节点显示半选，但 <b>value 保持不动</b>——
         * value=true 表示菜单权仍在。绝不在这里清父节点的 value，否则编辑弹窗
         * 回显「纯菜单授权」的根节点时会把勾选状态吞掉（2026-10-19 修的丢勾选 bug）。</li>
         * </ul>
         * 要取消整个菜单，用户直接点根节点本身：半选框 value=true 时点击，客户端
         * 会发 checked=false 进来，走父节点联动分支整组取消。
         */
        private void refreshParent(PermNode parent) {
            if (parent.children.isEmpty()) {
                return;
            }
            long checked = parent.children.stream().filter(c -> Boolean.TRUE.equals(c.checkbox.getValue())).count();
            syncing = true;
            if (checked == parent.children.size()) {
                parent.checkbox.setValue(true);
                parent.checkbox.setIndeterminate(false);
            } else {
                parent.checkbox.setIndeterminate(true);
            }
            syncing = false;
        }

        private HorizontalLayout buildTreeTools() {
            Button allBtn = UiFactory.button("全选", () -> setAll(true));
            Button noneBtn = UiFactory.button("全不选", () -> setAll(false));
            Span tip = new Span("共 " + roots.size() + " 个菜单权限");
            tip.addClassName("view-subtitle");
            HorizontalLayout bar = new HorizontalLayout(allBtn, noneBtn, tip);
            bar.setAlignItems(FlexComponent.Alignment.CENTER);
            bar.setWidthFull();
            return bar;
        }

        private void setAll(boolean value) {
            syncing = true;
            for (PermNode root : roots) {
                root.checkbox.setValue(value);
                root.checkbox.setIndeterminate(false);
                for (PermNode child : root.children) {
                    child.checkbox.setValue(value);
                    child.checkbox.setIndeterminate(false);
                }
            }
            syncing = false;
        }

        /**
         * 按权限码集合预勾选。
         * <p>
         * 顺序很关键：先勾子节点再定根节点形态。根节点码在集合内就勾上并保持
         * value=true（菜单权），按钮子节点全勾才显示实心、否则半选——<b>不能</b>
         * 勾完根再走 {@link #refreshParent}，它按「子节点勾选数」回写根节点，
         * 会把「只授菜单、没授按钮」的根节点（默认角色就是这种）清回未勾，
         * 造成弹窗回显丢勾选、保存进一步丢权限码。
         */
        private void selectCodes(Set<String> codes) {
            if (codes == null || codes.isEmpty()) {
                return;
            }
            syncing = true;
            for (PermNode root : roots) {
                for (PermNode child : root.children) {
                    if (codes.contains(child.code())) {
                        child.checkbox.setValue(true);
                        child.checkbox.setIndeterminate(false);
                    }
                }
                if (codes.contains(root.code())) {
                    root.checkbox.setValue(true);
                    boolean anyChildUnchecked = root.children.stream()
                            .anyMatch(c -> !Boolean.TRUE.equals(c.checkbox.getValue()));
                    root.checkbox.setIndeterminate(anyChildUnchecked);
                } else {
                    refreshParent(root);
                }
            }
            syncing = false;
        }

        /** 收集勾选的权限码（父与子都收；父被勾时子必然全勾，由联动保证） */
        private Set<String> selectedCodes() {
            Set<String> codes = new LinkedHashSet<>();
            for (PermNode root : roots) {
                if (Boolean.TRUE.equals(root.checkbox.getValue())) {
                    codes.add(root.code());
                }
                for (PermNode child : root.children) {
                    if (Boolean.TRUE.equals(child.checkbox.getValue())) {
                        codes.add(child.code());
                    }
                }
            }
            return codes;
        }

        // ------------------------------------------------------------------
        // 保存
        // ------------------------------------------------------------------

        private void save() {
            String name = StrUtil.trim(nameField.getValue());
            if (StrUtil.isBlank(name)) {
                Dialogs.warn("请输入角色名");
                return;
            }
            if (!PATTERN_ROLE_NAME.matcher(name).matches()) {
                Dialogs.warn("角色名需以中文或字母开头，长度 2-50 位，可包含字母、数字与 _ . - 和空格");
                return;
            }
            Role same = rbacService.findRoleByName(name);
            if (same != null && (editing == null || !same.getRoleId().equals(editing.getRoleId()))) {
                Dialogs.warn("角色名已存在，请换一个");
                return;
            }
            Set<String> codes = selectedCodes();
            if (codes.isEmpty()) {
                Dialogs.warn("请至少勾选一项权限");
                return;
            }

            Role role = editing == null ? new Role() : reloadFromDb(editing);
            if (role == null) {
                Dialogs.warn("角色不存在，可能已被其他人删除");
                close();
                reloadData();
                return;
            }
            role.setNameRole(name);
            role.setCdDesc(StrUtil.trim(descField.getValue()));
            rbacService.saveRole(role, codes);
            log.info("保存角色 {}（{} 项权限）", name, codes.size());
            close();
            reloadData();
            Dialogs.success(editing == null ? "角色 " + name + " 创建成功" : "角色 " + name + " 已保存");
        }

        /**
         * 编辑前从库里回读一次再改。
         * <p>
         * 列表页的数据可能是几分钟前加载的，直接拿旧对象 updateById 会把
         * 这期间别人改的属性覆盖回旧值（含 flag_builtin 这类保护字段）。
         * save() 随后会用表单里的值覆盖可写字段，所以这里原样返回即可。
         */
        private Role reloadFromDb(Role stale) {
            return rbacService.findRoleById(stale.getRoleId());
        }
    }
}
