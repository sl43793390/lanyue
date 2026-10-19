package com.sl.ui.admin;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sl.entity.Role;
import com.sl.entity.User;
import com.sl.mapper.UserDao;
import com.sl.service.RbacService;
import com.sl.ui.component.Dialogs;
import com.sl.ui.component.UiFactory;
import com.sl.ui.component.ViewBase;
import com.sl.util.Constants;
import com.sl.util.PermCodes;
import com.sl.util.Util;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 用户管理页。
 * <p>
 * 对应旧项目的 {@code com.so.component.UserManagementComponent}：覆盖 {@code users} 表
 * 全部字段——统计卡片、筛选（关键字 / 状态 / 角色 / 排序）、分页表格、行内编辑 /
 * 重置密码 / 禁用启用 / 删除、批量操作。旧版的「列设置」窗口没有搬：
 * Grid 列在本分辨率下一屏放得下，为它维护一套列引用映射不划算。
 * <p>
 * <b>RBAC 之后</b>：本页只负责账号本身与<b>角色分配</b>，「角色拥有哪些权限」在
 * {@link RoleManagementView} 维护。{@code users.permission} 历史权限列仍然有效，
 * 作为存量账号（含内置管理员的 {@code ALL}）的兜底，但新账号一律走角色。
 * <p>
 * 三条与数据一致性有关的约定，改动时务必留意：
 * <ol>
 * <li>登录认证读的是数据库（{@code UserDao}），没有第二份用户来源。</li>
 * <li>「禁用」和「过期」要真正生效，依赖登录链路里的 {@link User#unavailableReason()} 校验。</li>
 * <li>删除与禁用都带保护：不能操作当前登录账号；内置管理员 {@code admin} 永远不能删除、
 * 禁用或改角色；也不能让系统失去最后一个管理员账号（{@code ALL} 历史列或
 * 「系统管理员」角色，二者其一即是）。</li>
 * </ol>
 */
@Service
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class UserManagementView extends ViewBase {

    private static final Logger log = LoggerFactory.getLogger(UserManagementView.class);

    // ---------------- 筛选与排序的可选值 ----------------
    private static final String STATUS_ALL = "全部状态";
    private static final String STATUS_AVAILABLE = "正常可用";
    private static final String STATUS_ENABLED = "仅启用";
    private static final String STATUS_DISABLED = "已禁用";
    private static final String STATUS_EXPIRED = "已过期";
    private static final String STATUS_NEAR_EXPIRE = "7 天内到期";

    private static final String SORT_NAME_ASC = "用户名 A→Z";
    private static final String SORT_NAME_DESC = "用户名 Z→A";
    private static final String SORT_CREATE_DESC = "创建时间 新→旧";
    private static final String SORT_CREATE_ASC = "创建时间 旧→新";
    private static final String SORT_EXPIRE_ASC = "有效期 快到期的在前";
    private static final String SORT_EXPIRE_DESC = "有效期 长期有效在前";

    private static final String FLAG_ENABLE_TEXT = "启用";
    private static final String FLAG_DISABLE_TEXT = "禁用";

    /** 角色筛选的「全部」占位值 */
    private static final String ROLE_ALL = "全部角色";

    /** 用户名：字母开头，允许字母数字与 _ . - */
    private static final Pattern PATTERN_USER_ID = Pattern.compile("^[A-Za-z][A-Za-z0-9_.-]{1,49}$");
    private static final Pattern PATTERN_EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern PATTERN_PHONE = Pattern.compile("^1[3-9]\\d{9}$");

    private static final int MIN_PASSWORD_LENGTH = 6;

    private final transient UserDao userDao;
    private final transient RbacService rbacService;

    private final Grid<User> userGrid = UiFactory.grid(User.class);
    private final TextField keywordField = UiFactory.textField();
    private final ComboBox<String> statusBox = new ComboBox<>();
    private final ComboBox<String> roleBox = new ComboBox<>();
    private final ComboBox<String> sortBox = new ComboBox<>();

    private final Span pageInfoLb = new Span("");
    private final Span selectionLb = new Span("未选择");
    private final Button prevBtn = UiFactory.button("上一页", () -> turnPage(-1));
    private final Button nextBtn = UiFactory.button("下一页", () -> turnPage(1));
    private final ComboBox<Integer> pageSizeBox = new ComboBox<>();

    /** 全量数据；筛选、排序、分页都在内存里做，用户量级不大，没必要每次都查库 */
    private List<User> allUsers = new ArrayList<>();
    private List<User> viewUsers = new ArrayList<>();
    /** 登录名 → 角色名列表，随 allUsers 一起刷新，避免每渲染一行都查库 */
    private final java.util.Map<String, List<String>> roleNamesByUser = new java.util.HashMap<>();
    private int pageIndex = 0;
    private int pageSize = 10;

    public UserManagementView(UserDao userDao, RbacService rbacService) {
        this.userDao = userDao;
        this.rbacService = rbacService;

        add(title("用户管理"));
        add(subtitle("维护登录账号、角色与有效期"));

        add(buildFilterRow());

        buildGrid();
        HorizontalLayout foot = buildFootRow();
        VerticalLayout fill = fill(userGrid, foot);
        add(fill);
        setFlexGrow(1, fill);

        reloadData();
    }

    // ==================================================================
    // 筛选栏
    // ==================================================================

    private Component buildFilterRow() {
        keywordField.setWidth("250px");
        keywordField.setPlaceholder("用户名 / 姓名 / 组织 / 邮箱");
        // 边输边筛：LAZY 模式配合 350ms 静默期，不会每敲一个键就发一次请求
        keywordField.setValueChangeMode(com.vaadin.flow.data.value.ValueChangeMode.LAZY);
        keywordField.setValueChangeTimeout(350);
        keywordField.addValueChangeListener(e -> {
            pageIndex = 0;
            refreshView();
        });

        statusBox.setItems(STATUS_ALL, STATUS_AVAILABLE, STATUS_ENABLED, STATUS_DISABLED, STATUS_EXPIRED,
                STATUS_NEAR_EXPIRE);
        statusBox.setValue(STATUS_ALL);
        statusBox.setWidth("150px");
        statusBox.addValueChangeListener(e -> {
            pageIndex = 0;
            refreshView();
        });

        // 角色筛选项跟着数据库走：全部角色 + 每个角色名
        List<String> roleItems = new ArrayList<>();
        roleItems.add(ROLE_ALL);
        for (Role role : rbacService.allRoles()) {
            roleItems.add(role.getNameRole());
        }
        roleBox.setItems(roleItems);
        roleBox.setValue(ROLE_ALL);
        roleBox.setWidth("150px");
        roleBox.addValueChangeListener(e -> {
            pageIndex = 0;
            refreshView();
        });

        sortBox.setItems(SORT_NAME_ASC, SORT_NAME_DESC, SORT_CREATE_DESC, SORT_CREATE_ASC, SORT_EXPIRE_ASC,
                SORT_EXPIRE_DESC);
        sortBox.setValue(SORT_NAME_ASC);
        sortBox.setWidth("180px");
        sortBox.addValueChangeListener(e -> refreshView());

        Button resetBtn = UiFactory.button("重置筛选", this::resetFilter);
        Button addBtn = UiFactory.primary("新增用户", () -> {
            if (!hasPermission(PermCodes.USER_ADD)) {
                Dialogs.warn("权限不足，无法新增用户");
                return;
            }
            new UserEditDialog(null).open();
        });

        return toolbar(
                UiFactory.fieldRow("关键字", "68px", keywordField),
                UiFactory.fieldRow("状态", "52px", statusBox),
                UiFactory.fieldRow("角色", "52px", roleBox),
                UiFactory.fieldRow("排序", "52px", sortBox),
                resetBtn, spacer(),  addBtn);
    }

    private void resetFilter() {
        keywordField.setValue("");
        statusBox.setValue(STATUS_ALL);
        roleBox.setValue(ROLE_ALL);
        sortBox.setValue(SORT_NAME_ASC);
        pageIndex = 0;
        refreshView();
    }

    // ==================================================================
    // 表格
    // ==================================================================

    private void buildGrid() {
        userGrid.setSelectionMode(Grid.SelectionMode.MULTI);
        userGrid.setColumnReorderingAllowed(true);

        userGrid.addColumn(new ComponentRenderer<>(this::userCell)).setHeader("用户名").setAutoWidth(true);
        userGrid.addColumn(User::getUserName).setHeader("姓名").setAutoWidth(true);
        userGrid.addColumn(User::getOrganization).setHeader("组织").setAutoWidth(true);
        userGrid.addColumn(User::getEmail).setHeader("邮箱").setAutoWidth(true);
        userGrid.addColumn(User::getCdPhone).setHeader("手机").setAutoWidth(true);
        userGrid.addColumn(new ComponentRenderer<>(this::roleCell)).setHeader("角色").setAutoWidth(true);
        userGrid.addColumn(new ComponentRenderer<>(this::statusCell)).setHeader("状态").setAutoWidth(true);
        userGrid.addColumn(u -> u.getCreateTime() == null ? "—" : Util.formatDateTime(u.getCreateTime()))
                .setHeader("创建时间").setAutoWidth(true);
        userGrid.addColumn(new ComponentRenderer<>(this::expireCell)).setHeader("有效期").setAutoWidth(true);
        // 排序交给「排序」下拉做全量排序；表格只显示当前页，列头排序只能排到本页，反而误导
        userGrid.getColumns().forEach(column -> column.setSortable(false));
        userGrid.addComponentColumn(this::opCell).setHeader("操作").setAutoWidth(true);
        userGrid.addSelectionListener(e -> refreshSelectionInfo());
    }

    /** 用户名列：首字母头像 + 登录名（管理员加标签）+ 姓名 */
    private Div userCell(User user) {
        String name = StrUtil.isBlank(user.getUserName()) ? "未填写姓名" : user.getUserName();
        String id = StrUtil.nullToEmpty(user.getUserId());
        String initial = id.isEmpty() ? "?" : id.substring(0, 1).toUpperCase();

        Span avatar = new Span(initial);
        avatar.addClassName("um-avatar");

        Span idSpan = new Span(id);
        idSpan.addClassName("um-user-id");
        if (user.isAllPermission()) {
            Span admin = new Span("管理员");
            admin.getElement().getThemeList().add("badge");
            admin.getElement().getThemeList().add("success");
            idSpan.add(admin);
        }
        Span nameSpan = new Span(name);
        nameSpan.addClassName("um-user-name");

        Div cell = new Div(avatar, new Div(idSpan, nameSpan));
        cell.addClassName("um-user-cell");
        return cell;
    }

    /** 角色列：每个角色一枚徽标；未分配角色时给灰色提示（历史权限列仍兜底放行） */
    private Div roleCell(User user) {
        Div cell = new Div();
        cell.addClassName("um-user-cell");
        cell.getStyle().set("flex-wrap", "wrap");
        cell.getStyle().set("gap", "4px");
        List<String> names = roleNamesByUser.get(user.getUserId());
        if (names == null || names.isEmpty()) {
            Span none = new Span(user.isAllPermission() ? "全部权限（旧）" : "未分配角色");
            none.addClassName("home-badge");
            none.addClassName(user.isAllPermission() ? "home-badge-ok" : "home-badge-disabled");
            if (user.isAllPermission()) {
                none.getElement().setAttribute("title", "权限取自历史权限列 ALL，建议改授「系统管理员」角色");
            }
            cell.add(none);
            return cell;
        }
        for (String name : names) {
            Span badge = new Span(name);
            badge.addClassName("home-badge");
            badge.addClassName("home-badge-ok");
            cell.add(badge);
        }
        return cell;
    }

    private Span statusCell(User user) {
        Span badge = new Span(user.statusText());
        badge.addClassName("home-badge");
        if (!user.isEnabled()) {
            badge.addClassName("home-badge-disabled");
        } else if (user.isExpired()) {
            badge.addClassName("home-badge-expired");
        } else if (user.isNearExpire()) {
            badge.addClassName("home-badge-warning");
        } else {
            badge.addClassName("home-badge-ok");
        }
        return badge;
    }

    /** 有效期列：日期 + 剩余天数提示，到期紧迫度用颜色区分 */
    private Div expireCell(User user) {
        Div cell = new Div();
        cell.addClassName("um-expire-cell");
        if (user.getExpireTime() == null) {
            Span forever = new Span("长期有效");
            forever.addClassName("home-badge");
            forever.addClassName("home-badge-ok");
            cell.add(forever);
            return cell;
        }
        cell.add(new Span(Util.formatDate(user.getExpireTime())));
        String hint;
        String tone;
        if (user.isExpired()) {
            hint = "已过期";
            tone = "var(--lumo-error-text-color)";
        } else if (user.isNearExpire()) {
            Integer days = user.remainDays();
            hint = days == null ? "" : (days == 0 ? "今天到期" : "剩 " + days + " 天");
            tone = "var(--lumo-warning-text-color)";
        } else {
            Integer days = user.remainDays();
            hint = days == null ? "" : (days == 0 ? "今天到期" : "剩 " + days + " 天");
            tone = "var(--lumo-secondary-text-color)";
        }
        Span hintSpan = new Span(hint);
        hintSpan.addClassName("um-expire-hint");
        hintSpan.getStyle().set("color", tone);
        cell.add(hintSpan);
        return cell;
    }

    /** 行内操作：编辑 / 重置密码 / 禁用(启用) / 删除。每个按钮各自校验权限。 */
    private HorizontalLayout opCell(User user) {
        HorizontalLayout box = new HorizontalLayout();
        box.setSpacing(false);
        box.getStyle().set("gap", "2px");
        box.setAlignItems(FlexComponent.Alignment.CENTER);

        box.add(UiFactory.small("编辑", () -> {
            if (!hasPermission(PermCodes.USER_UPDATE)) {
                Dialogs.warn("权限不足，无法修改用户");
                return;
            }
            new UserEditDialog(user).open();
        }));
        box.add(UiFactory.small("重置密码", () -> {
            if (!hasPermission(PermCodes.USER_UPDATE)) {
                Dialogs.warn("权限不足，无法重置密码");
                return;
            }
            new ResetPasswordDialog(user).open();
        }));
        if (user.isBuiltinAdmin()) {
            // 内置管理员不给禁用与删除入口：删掉或禁用就等于系统再也没有引导账号了。
            // 按钮的隐藏只是别误导人，真正的硬拦截在 checkProtected 里。
            Span lock = new Span("受保护");
            lock.getElement().getThemeList().add("badge");
            lock.getElement().getThemeList().add("contrast");
            lock.getElement().setAttribute("title", "内置管理员，不允许禁用或删除");
            box.add(lock);
            return box;
        }
        box.add(UiFactory.small(user.isEnabled() ? "禁用" : "启用", () -> {
            if (!hasPermission(PermCodes.USER_UPDATE)) {
                Dialogs.warn("权限不足，无法修改用户状态");
                return;
            }
            toggleEnabled(user);
        }));
        Button delBtn = UiFactory.small("删除", () -> confirmDelete(List.of(user)));
        delBtn.getElement().getThemeList().add("error");
        box.add(delBtn);
        return box;
    }

    // ==================================================================
    // 底部：分页与批量操作
    // ==================================================================

    private HorizontalLayout buildFootRow() {
        prevBtn.setEnabled(false);
        nextBtn.setEnabled(false);

        pageSizeBox.setItems(10, 20, 50, 100);
        pageSizeBox.setValue(pageSize);
        pageSizeBox.setWidth("110px");
        pageSizeBox.addValueChangeListener(e -> {
            if (e.getValue() != null) {
                pageSize = e.getValue();
                pageIndex = 0;
                renderPage();
            }
        });

        HorizontalLayout pager = new HorizontalLayout(prevBtn, nextBtn,
                UiFactory.fieldRow("每页", "52px", pageSizeBox), pageInfoLb);
        pager.setAlignItems(FlexComponent.Alignment.CENTER);

        Button batchEnableBtn = UiFactory.button("批量启用", () -> batchToggle(true));
        Button batchDisableBtn = UiFactory.button("批量禁用", () -> batchToggle(false));
        Button batchDeleteBtn = UiFactory.danger("批量删除", () -> {
            if (!hasPermission(PermCodes.USER_DELETE)) {
                Dialogs.warn("权限不足，无法删除用户");
                return;
            }
            List<User> selected = selectedUsers();
            if (selected.isEmpty()) {
                Dialogs.warn("请先勾选要删除的用户");
                return;
            }
            confirmDelete(selected);
        });

        HorizontalLayout batchBox = new HorizontalLayout(selectionLb, batchEnableBtn, batchDisableBtn, batchDeleteBtn);
        batchBox.setAlignItems(FlexComponent.Alignment.CENTER);

        HorizontalLayout foot = new HorizontalLayout(pager, spacer(), batchBox);
        foot.setWidthFull();
        foot.setAlignItems(FlexComponent.Alignment.CENTER);
        foot.getStyle().set("flex-wrap", "wrap");
        foot.getStyle().set("padding-top", "8px");
        return foot;
    }

    private void turnPage(int delta) {
        int next = pageIndex + delta;
        if (next >= 0 && next < totalPages()) {
            pageIndex = next;
            renderPage();
        }
    }

    // ==================================================================
    // 数据加载与渲染
    // ==================================================================

    /** 重新从数据库读取全量用户，并同步刷新「登录名 → 角色名」映射 */
    private void reloadData() {
        List<User> loaded = userDao.selectList(new QueryWrapper<>());
        allUsers = loaded == null ? new ArrayList<>() : loaded;
        // 一次性查完全部用户的角色名，避免每渲染一行都查库；
        // Map 里查不到登录名时按「未分配角色」展示，见 roleCell
        roleNamesByUser.clear();
        List<Role> roles = rbacService.allRoles();
        java.util.Map<String, String> roleNameById = new java.util.HashMap<>();
        for (Role role : roles) {
            roleNameById.put(role.getRoleId(), role.getNameRole());
        }
        for (User user : allUsers) {
            List<String> names = new ArrayList<>();
            for (String roleId : rbacService.roleIdsOfUser(user.getUserId())) {
                String name = roleNameById.get(roleId);
                if (name != null) {
                    names.add(name);
                }
            }
            roleNamesByUser.put(user.getUserId(), names);
        }
        refreshView();
    }

    /** 按当前筛选条件重算视图并渲染 */
    private void refreshView() {
        viewUsers = applyFilter(allUsers);
        sortView();
        if (pageIndex > totalPages() - 1) {
            pageIndex = Math.max(0, totalPages() - 1);
        }
        renderPage();
    }

    private List<User> applyFilter(List<User> source) {
        String keyword = StrUtil.trim(keywordField.getValue()).toLowerCase();
        String status = statusBox.getValue();
        String role = roleBox.getValue();
        List<User> result = new ArrayList<>();
        for (User user : source) {
            if (!matchKeyword(user, keyword) || !matchStatus(user, status) || !matchRole(user, role)) {
                continue;
            }
            result.add(user);
        }
        return result;
    }

    private boolean matchKeyword(User user, String keyword) {
        if (StrUtil.isBlank(keyword)) {
            return true;
        }
        return contains(user.getUserId(), keyword) || contains(user.getUserName(), keyword)
                || contains(user.getOrganization(), keyword) || contains(user.getEmail(), keyword)
                || contains(user.getCdPhone(), keyword);
    }

    private static boolean contains(String text, String keyword) {
        return text != null && text.toLowerCase().contains(keyword);
    }

    private boolean matchStatus(User user, String status) {
        if (StrUtil.isBlank(status) || STATUS_ALL.equals(status)) {
            return true;
        }
        return switch (status) {
            case STATUS_AVAILABLE -> user.isAvailable();
            case STATUS_ENABLED -> user.isEnabled();
            case STATUS_DISABLED -> !user.isEnabled();
            case STATUS_EXPIRED -> user.isExpired();
            case STATUS_NEAR_EXPIRE -> user.isNearExpire();
            default -> true;
        };
    }

    private boolean matchRole(User user, String role) {
        if (StrUtil.isBlank(role) || ROLE_ALL.equals(role)) {
            return true;
        }
        List<String> names = roleNamesByUser.get(user.getUserId());
        return names != null && names.contains(role);
    }

    private void sortView() {
        viewUsers.sort(comparatorFor(sortBox.getValue()));
    }

    private static Comparator<User> comparatorFor(String sort) {
        if (SORT_NAME_DESC.equals(sort)) {
            return (a, b) -> StrUtil.nullToEmpty(b.getUserId()).compareTo(StrUtil.nullToEmpty(a.getUserId()));
        }
        if (SORT_CREATE_DESC.equals(sort)) {
            return (a, b) -> dateAscNullLast(b.getCreateTime(), a.getCreateTime());
        }
        if (SORT_CREATE_ASC.equals(sort)) {
            return (a, b) -> dateAscNullLast(a.getCreateTime(), b.getCreateTime());
        }
        if (SORT_EXPIRE_ASC.equals(sort)) {
            // 永久有效的账号（expireTime 为空）排在最后
            return (a, b) -> dateAscNullLast(a.getExpireTime(), b.getExpireTime());
        }
        if (SORT_EXPIRE_DESC.equals(sort)) {
            return (a, b) -> dateAscNullFirst(a.getExpireTime(), b.getExpireTime());
        }
        return (a, b) -> StrUtil.nullToEmpty(a.getUserId()).compareTo(StrUtil.nullToEmpty(b.getUserId()));
    }

    /** 日期升序比较，null 视为最大排在最后 */
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

    /** 日期升序比较，null 排在最前（用于「长期有效在前」） */
    private static int dateAscNullFirst(Date d1, Date d2) {
        if (d1 == null && d2 == null) {
            return 0;
        }
        if (d1 == null) {
            return -1;
        }
        if (d2 == null) {
            return 1;
        }
        return d1.compareTo(d2);
    }

    private int totalPages() {
        if (viewUsers.isEmpty()) {
            return 1;
        }
        return (viewUsers.size() + pageSize - 1) / pageSize;
    }

    private void renderPage() {
        int from = pageIndex * pageSize;
        int to = Math.min(from + pageSize, viewUsers.size());
        List<User> pageItems = from >= to ? new ArrayList<>() : new ArrayList<>(viewUsers.subList(from, to));
        userGrid.setItems(pageItems);
        userGrid.deselectAll();
        refreshSelectionInfo();
        pageInfoLb.setText(String.format("共 %d 条，第 %d/%d 页", viewUsers.size(), pageIndex + 1, totalPages()));
        prevBtn.setEnabled(pageIndex > 0);
        nextBtn.setEnabled(pageIndex < totalPages() - 1);
    }

    private void refreshSelectionInfo() {
        int size = userGrid.getSelectedItems().size();
        selectionLb.setText(size == 0 ? "未选择" : "已选 " + size + " 项");
    }

      private List<User> selectedUsers() {
        return new ArrayList<>(userGrid.getSelectedItems());
    }

    // ==================================================================
    // 启用 / 禁用 / 删除（含保护判定）
    // ==================================================================

    private void batchToggle(boolean enable) {
        if (!hasPermission(PermCodes.USER_UPDATE)) {
            Dialogs.warn("权限不足，无法修改用户状态");
            return;
        }
        List<User> selected = selectedUsers();
        if (selected.isEmpty()) {
            Dialogs.warn("请先勾选要操作的用户");
            return;
        }
        if (!enable) {
            String reason = checkProtected(selected, false);
            if (reason != null) {
                Dialogs.warn(reason);
                return;
            }
        }
        int changed = 0;
        for (User user : selected) {
            if (user.isEnabled() == enable) {
                continue;
            }
            User db = userDao.selectById(user.getUserId());
            if (db == null) {
                continue;
            }
            db.setUserFlag(enable ? User.FLAG_ENABLED : User.FLAG_DISABLED);
            userDao.updateById(db);
            changed++;
        }
        reloadData();
        Dialogs.success(changed == 0 ? "所选账号状态无需变更" : "已" + (enable ? "启用" : "禁用") + " " + changed + " 个账号");
    }

    private void toggleEnabled(User user) {
        boolean enable = !user.isEnabled();
        if (!enable) {
            String reason = checkProtected(List.of(user), false);
            if (reason != null) {
                Dialogs.warn(reason);
                return;
            }
        }
        User db = userDao.selectById(user.getUserId());
        if (db == null) {
            Dialogs.warn("用户不存在，可能已被其他人删除");
            reloadData();
            return;
        }
        db.setUserFlag(enable ? User.FLAG_ENABLED : User.FLAG_DISABLED);
        userDao.updateById(db);
        reloadData();
        Dialogs.success("已" + (enable ? "启用" : "禁用") + "账号 " + user.getUserId());
    }

    private void confirmDelete(List<User> targets) {
        String reason = checkProtected(targets, true);
        if (reason != null) {
            Dialogs.warn(reason);
            return;
        }
        List<String> names = targets.stream().map(User::getUserId).toList();
        String shown = String.join("、", names.subList(0, Math.min(names.size(), 8)))
                + (names.size() > 8 ? " 等 " + names.size() + " 个" : "");
        Dialogs.confirmDanger("删除用户",
                "确定要删除以下 " + targets.size() + " 个账号吗？" + shown
                        + "。删除后账号将无法登录，且不可恢复。",
                "确认删除", () -> {
                    // 真正的删除路径再兜一次底：不该删的删不掉不能依赖调用方记得先校验
                    String second = checkProtected(targets, true);
                    if (second != null) {
                        log.warn("已阻止删除操作：{}，目标 {}", second, names);
                        Dialogs.warn(second);
                        reloadData();
                        return;
                    }
                    List<String> ids = new ArrayList<>(names);
                    int affected = userDao.deleteByIds(ids);
                    log.info("删除用户 {} 个：{}", affected, ids);
                    reloadData();
                    Dialogs.success("已删除 " + affected + " 个账号");
                });
    }

    /**
     * 校验一批用户能否被改状态或删除，返回拒绝原因，全部允许时返回 null。
     * <p>
     * 三条硬约束：内置管理员不可删不可禁用；不能动自己；不能让系统失去最后一个
     * 管理员账号（历史权限列 {@code ALL} 或内置「系统管理员」角色，二者其一即是），
     * 否则改完就再也登不进来了。
     */
    private String checkProtected(List<User> targets, boolean removing) {
        for (User target : targets) {
            if (target.isBuiltinAdmin()) {
                return "内置管理员 " + User.ADMIN_USER_ID
                        + (removing ? " 不允许删除" : " 不允许禁用");
            }
        }
        User current = com.sl.security.CurrentUser.get();
        String currentId = current == null ? null : current.getUserId();
        if (StrUtil.isBlank(currentId)) {
            // 会话里取不到当前用户时宁可不放行：自我保护的判断依据都缺了，
            // 继续执行等于"谁都能删"。
            return "无法确认当前登录账号，出于安全考虑已阻止本次操作";
        }
        boolean touchSelf = false;
        boolean touchLastAdmin = false;
        int adminCount = countEnabledAdmins();
        for (User target : targets) {
            if (currentId.equals(target.getUserId())) {
                touchSelf = true;
            }
            if (isAdminUser(target) && target.isEnabled() && adminCount <= 1) {
                touchLastAdmin = true;
            }
        }
        if (touchSelf) {
            return removing ? "不能删除当前登录的账号" : "不能修改当前登录账号的状态";
        }
        if (touchLastAdmin) {
            return removing ? "这是系统最后一个管理员账号，删除后将无法登录，已阻止"
                    : "这是系统最后一个管理员账号，禁用后将无法登录，已阻止";
        }
        return null;
    }

    /**
     * 是否管理员身份：历史权限列 {@code ALL}，或持有内置「系统管理员」角色。
     * <p>
     * RBAC 之后管理员不只有 legacy {@code ALL} 一种形态——通过角色拿到全权的账号
     * 同样算数，否则「最后一个管理员」保护会出现漏网路径。
     */
    private boolean isAdminUser(User user) {
        return user != null && (user.isAllPermission() || rbacService.isAdminUser(user.getUserId()));
    }

    private int countEnabledAdmins() {
        int count = 0;
        for (User user : allUsers) {
            if (isAdminUser(user) && user.isAvailable()) {
                count++;
            }
        }
        return count;
    }

    // ==================================================================
    // 新增 / 编辑窗口
    // ==================================================================

    /** 新增与编辑共用的窗口，传入 null 表示新增。 */
    private class UserEditDialog extends Dialog {

        private final TextField userIdField = UiFactory.textField("用户名 *", "字母开头，2-50 位字母数字或 _ . -");
        private final TextField nameField = UiFactory.textField("姓名", "真实姓名");
        private final TextField orgField = UiFactory.textField("所属组织", "部门或公司");
        private final TextField emailField = UiFactory.textField("邮箱", "name@example.com");
        private final TextField phoneField = UiFactory.textField("手机号", "11 位手机号");
        private final PasswordField passwordField = new PasswordField("密码 *");
        private final PasswordField confirmField = new PasswordField("确认密码 *");
        private final RadioButtonGroup<String> flagGroup = new RadioButtonGroup<>("状态");
        private final DatePicker expireField = new DatePicker("有效期至");
        private final MultiSelectComboBox<Role> roleBoxField = new MultiSelectComboBox<>("角色 *");

        private final User editing;
        /** 打开窗口时库里的角色快照，用于回显 */
        private final List<Role> allRoles;

        UserEditDialog(User editing) {
            this.editing = editing;
            this.allRoles = rbacService.allRoles();
            setHeaderTitle(editing == null ? "新增用户" : "编辑用户 " + editing.getUserId());
            setWidth("750px");
            setCloseOnOutsideClick(false);

            List<String> flagItems = List.of(FLAG_ENABLE_TEXT, FLAG_DISABLE_TEXT);
            flagGroup.setItems(flagItems);
            flagGroup.setValue(FLAG_ENABLE_TEXT);

            expireField.setPlaceholder("留空表示长期有效");
            expireField.setMin(LocalDate.of(2000, 1, 1));
            expireField.setWidthFull();

            roleBoxField.setItems(allRoles);
            roleBoxField.setItemLabelGenerator(Role::getNameRole);
            roleBoxField.setPlaceholder("选择角色，权限由角色决定");
            roleBoxField.setWidthFull();

            passwordField.setPlaceholder("不少于 " + MIN_PASSWORD_LENGTH + " 位");
            confirmField.setPlaceholder("再输入一次");

            userIdField.setWidthFull();
            nameField.setWidthFull();
            orgField.setWidthFull();
            emailField.setWidthFull();
            phoneField.setWidthFull();
            passwordField.setWidthFull();
            confirmField.setWidthFull();

            // 一行两列的表单。旧版为这个排法跟 Vaadin 8 的 CssLayout caption 搏斗过，
            // Flow 的字段自带标题（不拆成独立元素），直接两列 flex 即可
            HorizontalLayout row1 = formRow(userIdField, nameField);
            HorizontalLayout row2 = formRow(orgField, emailField);
            HorizontalLayout row3 = formRow(phoneField, expireField);
            HorizontalLayout row4 = formRow(passwordField, confirmField);
            HorizontalLayout row5 = formRow(flagGroup, roleBoxField);

            String hint = editing == null
                    ? "用户名创建后不可修改；权限由所选角色决定，角色可在「角色管理」页维护。"
                    : (editing.isBuiltinAdmin()
                            ? "内置管理员 " + User.ADMIN_USER_ID + "：状态 / 角色 / 有效期已锁定，仅可修改资料与密码。"
                            : "用户名不可修改；密码留空表示不改动。");
            Span hintSpan = new Span(hint);
            hintSpan.addClassName("view-subtitle");

            Button cancel = UiFactory.button("取消", this::close);
            Button save = UiFactory.primary(editing == null ? "创建" : "保存", this::save);
            getFooter().add(cancel, save);

            VerticalLayout form = new VerticalLayout(row1, row2, row3, row4, row5, hintSpan);
            form.setPadding(false);
            form.setSpacing(false);
            form.getStyle().set("gap", "12px");
            add(form);

            if (editing != null) {
                fillForm();
            } else {
                // 新建账号默认挂「普通用户」角色（如果存在）：与角色管理页的默认勾选一致，
                // 不给的话新账号登进来是一个空菜单，反而像系统坏了
                for (Role role : allRoles) {
                    if (Role.DEFAULT_ROLE_ID.equals(role.getRoleId())) {
                        roleBoxField.select(role);
                        break;
                    }
                }
                expireField.setValue(LocalDate.now().plusMonths(1));
            }
            userIdField.setEnabled(editing == null);
        }

        private void fillForm() {
            userIdField.setValue(editing.getUserId());
            nameField.setValue(StrUtil.nullToEmpty(editing.getUserName()));
            orgField.setValue(StrUtil.nullToEmpty(editing.getOrganization()));
            emailField.setValue(StrUtil.nullToEmpty(editing.getEmail()));
            phoneField.setValue(StrUtil.nullToEmpty(editing.getCdPhone()));
            expireField.setValue(toLocalDate(editing.getExpireTime()));
            flagGroup.setValue(editing.isEnabled() ? FLAG_ENABLE_TEXT : FLAG_DISABLE_TEXT);
            // 回显角色：按 ID 从打开窗口时的角色快照里找，找不到（这期间被删了）就跳过
            Set<String> roleIds = new java.util.HashSet<>(rbacService.roleIdsOfUser(editing.getUserId()));
            for (Role role : allRoles) {
                if (roleIds.contains(role.getRoleId())) {
                    roleBoxField.select(role);
                }
            }
            passwordField.setLabel("密码");
            confirmField.setLabel("确认密码");
            if (editing.isBuiltinAdmin()) {
                // 先清空有效期再禁用：被禁用的控件不会再提交新值，服务端保留的就是此刻的状态
                expireField.setValue(null);
                expireField.setEnabled(false);
                flagGroup.setEnabled(false);
                roleBoxField.setEnabled(false);
            }
        }

        private void save() {
            String userId = StrUtil.trim(userIdField.getValue());
            String validation = validate(userId);
            if (validation != null) {
                Dialogs.warn(validation);
                return;
            }
            Set<Role> selectedRoles = roleBoxField.getValue();
            if (selectedRoles == null || selectedRoles.isEmpty()) {
                Dialogs.warn("请至少选择一个角色");
                return;
            }
            Set<String> roleIds = new LinkedHashSet<>();
            for (Role role : selectedRoles) {
                roleIds.add(role.getRoleId());
            }
            String flag = FLAG_DISABLE_TEXT.equals(flagGroup.getValue()) ? User.FLAG_DISABLED : User.FLAG_ENABLED;

            if (editing == null) {
                if (userDao.selectById(userId) != null) {
                    Dialogs.warn("用户名已存在，请换一个");
                    return;
                }
                User user = new User();
                user.setUserId(userId);
                user.setUserName(StrUtil.trim(nameField.getValue()));
                user.setOrganization(StrUtil.trim(orgField.getValue()));
                user.setEmail(StrUtil.trim(emailField.getValue()));
                user.setCdPhone(StrUtil.trim(phoneField.getValue()));
                user.setPassword(Util.getSm3DigestStr(passwordField.getValue()));
                // 权限全部走角色：users.permission 历史列留空，只对存量账号有意义
                user.setPermission("");
                user.setUserFlag(flag);
                user.setCreateTime(new Date());
                user.setExpireTime(toDate(expireField.getValue()));
                userDao.insert(user);
                rbacService.assignRoles(userId, roleIds);
                log.info("新增用户 {}（角色 {}）", userId, roleIds);
                close();
                reloadData();
                Dialogs.success("用户 " + userId + " 创建成功");
                return;
            }

            User db = userDao.selectById(editing.getUserId());
            if (db == null) {
                Dialogs.warn("用户不存在，可能已被其他人删除");
                close();
                reloadData();
                return;
            }
            if (User.FLAG_DISABLED.equals(flag)) {
                String reason = checkProtected(List.of(db), false);
                if (reason != null) {
                    Dialogs.warn(reason);
                    return;
                }
            }
            db.setUserName(StrUtil.trim(nameField.getValue()));
            db.setOrganization(StrUtil.trim(orgField.getValue()));
            db.setEmail(StrUtil.trim(emailField.getValue()));
            db.setCdPhone(StrUtil.trim(phoneField.getValue()));
            if (db.isBuiltinAdmin()) {
                // 表单控件已经禁用，这里再钉死一次：内置管理员的权限列、状态、有效期
                // 不允许被任何路径改掉，否则系统会失去唯一的引导账号。
                // 角色同样不在这里动——它由 DbInitializer 保证挂「系统管理员」。
                db.setPermission(Constants.ALL);
                db.setUserFlag(User.FLAG_ENABLED);
                db.setExpireTime(null);
            } else {
                db.setPermission("");
                db.setUserFlag(flag);
                db.setExpireTime(toDate(expireField.getValue()));
                rbacService.assignRoles(db.getUserId(), roleIds);
            }
            // 密码留空即不改动：直接复用库里读出来的密文
            if (StrUtil.isNotBlank(passwordField.getValue())) {
                db.setPassword(Util.getSm3DigestStr(passwordField.getValue()));
            }
            userDao.updateById(db);
            log.info("修改用户 {}（角色 {}）", db.getUserId(), roleIds);
            close();
            reloadData();
            Dialogs.success("用户 " + db.getUserId() + " 已保存");
        }

        /** 表单校验。返回错误提示，全部通过返回 null。 */
        private String validate(String userId) {
            if (StrUtil.isBlank(userId)) {
                return "请输入用户名";
            }
            if (editing == null && !PATTERN_USER_ID.matcher(userId).matches()) {
                return "用户名需以字母开头，长度 2-50 位，只能包含字母、数字与 _ . -";
            }
            if (StrUtil.isNotBlank(emailField.getValue())
                    && !PATTERN_EMAIL.matcher(StrUtil.trim(emailField.getValue())).matches()) {
                return "邮箱格式不正确";
            }
            if (StrUtil.isNotBlank(phoneField.getValue())
                    && !PATTERN_PHONE.matcher(StrUtil.trim(phoneField.getValue())).matches()) {
                return "手机号应为 11 位，且以 1 开头";
            }
            String pwd = passwordField.getValue();
            if (editing == null && StrUtil.isBlank(pwd)) {
                return "请输入密码";
            }
            if (StrUtil.isNotBlank(pwd)) {
                if (pwd.length() < MIN_PASSWORD_LENGTH) {
                    return "密码长度不能少于 " + MIN_PASSWORD_LENGTH + " 位";
                }
                if (!pwd.equals(confirmField.getValue())) {
                    return "两次输入的密码不一致";
                }
            }
            return null;
        }
    }

    /** Date 转 LocalDate（实体与数据库用 java.util.Date，DatePicker 用 java.time）。 */
    private static LocalDate toLocalDate(Date date) {
        return date == null ? null : date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    /** LocalDate 转 Date，取当天零点。"当天有效"的语义由 User.isExpiredAt 按当天 23:59:59 判断。 */
    private static Date toDate(LocalDate localDate) {
        return localDate == null ? null : Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    /** 一行两列。 */
    private static HorizontalLayout formRow(com.vaadin.flow.component.Component left,
                                            com.vaadin.flow.component.Component right) {
        HorizontalLayout row = new HorizontalLayout(left, right);
        row.setWidthFull();
        row.setSpacing(false);
        row.getStyle().set("gap", "16px");
        row.setFlexGrow(1, left);
        row.setFlexGrow(1, right);
        return row;
    }

    // ==================================================================
    // 重置密码窗口
    // ==================================================================

    private class ResetPasswordDialog extends Dialog {

        ResetPasswordDialog(User user) {
            setHeaderTitle("重置密码 - " + user.getUserId());
            setWidth("560px");
            setCloseOnOutsideClick(false);

            PasswordField newPwd = UiFactory.passwordField("新密码", "350px");
            newPwd.setPlaceholder("不少于 " + MIN_PASSWORD_LENGTH + " 位");
            PasswordField confirmPwd = UiFactory.passwordField("确认新密码", "350px");
            confirmPwd.setPlaceholder("再输入一次");
            TextField generatedField = UiFactory.textField("随机密码（点生成后可直接复制）","","350px");

            Button genBtn = UiFactory.button("生成随机密码", () -> {
                // 去掉容易混淆的 0/O/1/l/I，方便口头转述或手抄
                String random = RandomUtil.randomString(
                        "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789", 10);
                generatedField.setValue(random);
                newPwd.setValue(random);
                confirmPwd.setValue(random);
            });

            Span hint = new Span("重置后该账号的旧密码立即失效，请把新密码告知使用者。");
            hint.addClassName("view-subtitle");

            Button cancel = UiFactory.button("取消", this::close);
            Button save = UiFactory.primary("确认重置", () -> {
                String pwd = newPwd.getValue();
                if (StrUtil.isBlank(pwd) || pwd.length() < MIN_PASSWORD_LENGTH) {
                    Dialogs.warn("新密码长度不能少于 " + MIN_PASSWORD_LENGTH + " 位");
                    return;
                }
                if (!pwd.equals(confirmPwd.getValue())) {
                    Dialogs.warn("两次输入的密码不一致");
                    return;
                }
                User db = userDao.selectById(user.getUserId());
                if (db == null) {
                    Dialogs.warn("用户不存在，可能已被其他人删除");
                    close();
                    reloadData();
                    return;
                }
                db.setPassword(Util.getSm3DigestStr(pwd));
                userDao.updateById(db);
                log.info("重置用户 {} 的密码", db.getUserId());
                close();
                reloadData();
                Dialogs.success("已重置 " + db.getUserId() + " 的密码");
            });

            VerticalLayout form = new VerticalLayout(newPwd, confirmPwd, generatedField, genBtn, hint);
            form.setPadding(false);
            form.setSpacing(false);
            form.getStyle().set("gap", "12px");
            add(form);
            getFooter().add(cancel, save);
            newPwd.focus();
        }
    }

    // ==================================================================
    // 权限
    // ==================================================================

    /**
     * 页面内按钮的权限判断，统一走 RBAC 的有效权限快照。
     * <p>
     * 不要再写成 {@code user.hasPermission(code)}——那只看 {@code users.permission}
     * 历史列，看不见角色授予的权限（{@code CurrentUser.hasPermission} 两者都看）。
     */
    private static boolean hasPermission(String code) {
        return com.sl.security.CurrentUser.hasPermission(code);
    }
}
