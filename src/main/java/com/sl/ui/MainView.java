package com.sl.ui;

import com.sl.entity.User;
import com.sl.security.UserPrincipal;
import com.sl.ui.component.TabHost;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.contextmenu.MenuItem;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.menubar.MenuBar;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.PreserveOnRefresh;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.PermitAll;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 登录后的主框架：顶部标题栏 + 菜单 + 多标签内容区。
 * <p>
 * 结构对应旧项目的 {@code LogCheckView}（{@code MenuBar} + {@code TabSheet}），
 * 交互也保持一致——菜单点击是在**当前页面里新开一个标签**，而不是整页跳转。
 * 这样运维同学可以同时开着「日志搜索」和「容器列表」来回切，是这套工具的主要用法。
 * <p>
 * 之所以不用 Vaadin 的路由来做多标签（{@code @Route} + {@code RouterLayout}），是因为
 * 那样每个功能都要有一个 URL、每次切换都会重建页面实例，而旧项目的组件是
 * 「开一次、留一个标签、保持状态」的。
 * <p>
 * <b>为什么不用 {@code TabSheet}</b>：Vaadin 24 的 {@code TabSheet} 没有
 * 「标签可关闭」的能力（Vaadin 8 的 {@code TabSheet#setClosable} 在迁移中被拿掉了）。
 * 旧布局的标签是可关的，而且运维习惯是「查完一个就随手关掉、一次开七八个」，
 * 不能关等于逼人刷新整页。所以这里用 {@code Tabs + 内容容器} 自己拼，
 * 关闭逻辑完全可控。
 * <p>
 * <b>内容组件的保留方式</b>：切换标签时用 {@code setVisible(false)} 隐藏而**不是**移除。
 * 移除会触发 {@code onDetach}，页面状态（日志读到第几页、表单填了什么）全部丢失，
 * 文件句柄之类的资源也会被提前释放。关闭标签时才真正移除，让 {@code onDetach} 正常收尾。
 * <p>
 * 组件从 Spring 容器按类型取，因此每个功能页都必须是
 * {@code @Service @Scope("prototype")}——**不能是单例**，否则多个用户会互相看到对方的数据。
 */
@Route("")
@PageTitle("揽月运维管理平台")
@PermitAll
@PreserveOnRefresh
public class MainView extends VerticalLayout implements TabHost {

    private static final long serialVersionUID = 1L;

    private final transient ApplicationContext applicationContext;
    private final transient AuthenticationContext authenticationContext;

    private final Tabs tabBar = new Tabs();
    private final Div contentHost = new Div();

    /** 标题 → 标签页，同时承担「同一个功能重复点击时切回已有标签」的去重 */
    private final Map<String, TabEntry> openedTabs = new LinkedHashMap<>();

    /**
     * 一个已打开的标签。
     *
     * @param tab     标签栏上的那一项
     * @param content 对应的内容组件，关闭时要从内容区移除
     */
    private record TabEntry(Tab tab, Component content) {
    }

    public MainView(ApplicationContext applicationContext, AuthenticationContext authenticationContext) {
        this.applicationContext = applicationContext;
        this.authenticationContext = authenticationContext;

        addClassName("main-view");
        setSizeFull();
        setPadding(false);
        setSpacing(false);
        /*
         * 必须显式声明 stretch：Vaadin 24 的 FlexLayout（VerticalLayout 的父类）
         * 默认 align-items 是 flex-start，不是 stretch——子组件会按内容宽度收缩。
         * 症状就是用户反馈的「页面打开后特别窄」：概览、服务器列表这类内容天然的
         * 页面整块缩在左边，视口再宽也不铺满。
         * 另外不要用 setWidthFull() 给子组件写 width:100% 顶替——content-box 下
         * 100% 会把父级 padding 加在宽度之外，又回到「右侧被裁」的老坑。
         */
        setDefaultHorizontalComponentAlignment(Alignment.STRETCH);

        /*
         * 顶部分两行：第一行是标题 + 用户名/退出，第二行是深蓝底的菜单行。
         * 两行都是纵向 flex（MainView）的直接子项，宽度交给 align-items: stretch，
         * 不写 width: 100%（content-box 把 padding 加在 100% 之外的老坑，见构造器注释）。
         */
        add(buildTopBar(), buildMenuBarRow());

        Component contentArea = buildContentArea();
        add(contentArea);
        // 内容区吃掉「除顶栏之外」的全部高度，配合 .main-tab-content 的 min-height:0，
        // 标签页内部的内容才会自己滚动，而不是把整个页面顶出滚动条。
        setFlexGrow(1, contentArea);

        // 进来先打开概览，避免主区域一片空白
        openTab(MenuRegistry.defaultItem());
        openTab(MenuRegistry.remoteServer());
    }

    // ------------------------------------------------------------------
    // 顶部两行：标题 / 用户信息 + 深蓝菜单行
    // ------------------------------------------------------------------

    /** 第一行：左侧平台标题，右侧用户名与退出按钮。 */
    private Component buildTopBar() {
        HorizontalLayout topBar = new HorizontalLayout();
        topBar.addClassName("main-topbar");
        topBar.setAlignItems(Alignment.CENTER);
        topBar.setPadding(false);
        topBar.setSpacing(false);

        H1 logo = new H1("揽月运维管理平台");
        logo.addClassName("main-header-logo");

        Div spacer = new Div();
        spacer.addClassName("main-header-spacer");

        topBar.add(logo, spacer, buildUserArea());
        // 只有中间的 spacer 参与伸缩，logo 与用户区保持原宽
        topBar.setFlexGrow(1, spacer);
        return topBar;
    }

    /** 第二行：一整行深蓝底的菜单。 */
    private Component buildMenuBarRow() {
        Div row = new Div(buildMenuBar());
        row.addClassName("main-menu-row");
        return row;
    }

    private MenuBar buildMenuBar() {
        MenuBar menuBar = new MenuBar();
        menuBar.addClassName("main-menu");
        // 悬停即展开：运维页面点击密度高，少一次点击是一次
        menuBar.setOpenOnHover(true);

        for (MenuRegistry.Group group : MenuRegistry.GROUPS) {
            MenuItem groupItem = menuBar.addItem(group.title());
            for (MenuRegistry.Item item : group.items()) {
                MenuItem sub = groupItem.getSubMenu().addItem(item.title(), e -> openTab(item));
                if (!item.implemented()) {
                    sub.addClassName("main-menu-item-pending");
                }
            }
        }
        return menuBar;
    }

    /**
     * 右上角：当前用户名 + 退出按钮。
     * <p>
     * 用户名优先取实体里的「姓名」，取不到再退回登录名——旧项目顶栏显示的就是姓名。
     */
    private Component buildUserArea() {
        HorizontalLayout area = new HorizontalLayout();
        area.addClassName("main-header-user");
        area.setAlignItems(Alignment.CENTER);

        User user = currentUser();
        String displayName = user != null && user.getUserName() != null && !user.getUserName().isBlank()
                ? user.getUserName()
                : currentLoginName();

        Span name = new Span(displayName);
        name.addClassName("main-header-username");
        if (user != null) {
            name.getElement().setAttribute("title", user.getUserId() + " · " + user.permissionText());
        }

        Button logout = new Button("退出", e -> authenticationContext.logout());
        logout.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        logout.addClassName("main-header-logout");

        area.add(name, logout);
        return area;
    }

    // ------------------------------------------------------------------
    // 内容区：标签栏 + 内容容器
    // ------------------------------------------------------------------

    private Component buildContentArea() {
        VerticalLayout area = new VerticalLayout();
        area.addClassName("main-content-area");
        area.setSizeFull();
        area.setPadding(false);
        area.setSpacing(false);
        // 标签栏和内容容器都要横向铺满（FlexLayout 默认 flex-start，见构造器里的说明）
        area.setDefaultHorizontalComponentAlignment(Alignment.STRETCH);

        tabBar.addClassName("main-tabs-bar");
        // 标签太多时不换行、改为横向滚动，避免顶掉内容区高度。
        // 宽度不用显式设：纵向 flex 容器的子项默认 stretch，写 width: 100% 反而会
        // 按 content-box 把左右 padding 加在 100% 之外（见下面 contentHost 的说明）。
        tabBar.addSelectedChangeListener(event -> applySelection(event.getSelectedTab()));

        contentHost.addClassName("main-tab-content");
        /*
         * 刻意**不**调 setSizeFull()（那会写成 width/height: 100%）。
         *
         * 在纵向 flex 容器里，宽度由 align-items: stretch 决定、高度由下行的
         * setFlexGrow(1, contentHost) 撑开，本来就不需要显式尺寸。
         * 而一旦写上 width: 100%，元素按浏览器默认的 box-sizing: content-box 计算，
         * 实际宽度会变成「100% + 左右 padding」——实测 1424px 的容器里量到 1456px，
         * 正好多出左右各 16px 的 padding，导致右侧内容被裁：
         * 状态文字显示不全、表格最右列的按钮只剩一半。
         */

        area.add(tabBar, contentHost);
        area.setFlexGrow(1, contentHost);
        return area;
    }

    /**
     * 打开（或切回）一个功能的标签页。
     * <p>
     * 去重按**标题**而不是组件实例：同一个菜单项点第二次时，旧项目会把已有标签切到前台，
     * 不新开一个——否则反复点几次就会攒出一排内容一模一样的标签。
     */
    private void openTab(MenuRegistry.Item item) {
        if (!item.implemented()) {
            Notification notification = new Notification(
                    "「" + item.title() + "」还在迁移中，功能尚未接入");
            notification.addThemeVariants(NotificationVariant.LUMO_CONTRAST);
            notification.setPosition(Notification.Position.MIDDLE);
            notification.setDuration(2500);
            notification.open();
            return;
        }
        open(item.title(), () -> applicationContext.getBean(item.view()));
    }

    // ------------------------------------------------------------------
    // TabHost
    // ------------------------------------------------------------------

    /**
     * {@inheritDoc}
     * <p>
     * 用 {@code Supplier} 而不是直接收组件，是为了让「同名标签已存在时切回旧的」
     * 这条路径**根本不会创建新实例**——否则像 {@code LogDetailView} 那样在构造器里
     * 就打开文件句柄的页面，会在被丢弃的实例上留下未关闭的句柄。
     */
    @Override
    public void open(String title, Supplier<Component> contentFactory) {
        String key = normalizeTitle(title);

        TabEntry existing = openedTabs.get(key);
        if (existing != null) {
            tabBar.setSelectedTab(existing.tab());
            return;
        }

        Component content = contentFactory.get();
        Tab tab = buildTab(key, content);
        contentHost.add(content);

        openedTabs.put(key, new TabEntry(tab, content));
        tabBar.add(tab);
        tabBar.setSelectedTab(tab);
        applySelection(tab);
    }

    @Override
    public boolean isOpen(String title) {
        return openedTabs.containsKey(normalizeTitle(title));
    }

    /** 与 {@link #open} 共用同一套归一化规则，否则会出现「明明开了却查不到」。 */
    private static String normalizeTitle(String title) {
        return (title == null || title.isBlank()) ? "未命名" : title;
    }

    /** 构造一个带关闭按钮的标签。 */
    private Tab buildTab(String title, Component content) {
        Tab tab = new Tab();
        tab.addClassName("main-tab");

        Span label = new Span(title);
        label.addClassName("main-tab-label");
        label.getElement().setAttribute("title", title);

        Span close = new Span("×");
        close.addClassName("main-tab-close");
        close.getElement().setAttribute("title", "关闭");
        // stopPropagation 不能省：点击事件若冒泡到 <vaadin-tab>，
        // vaadin-tabs 会顺手把这一项选中，于是「关掉别的标签」会变成「先跳过去再关」。
        close.getElement().addEventListener("click", event -> closeTab(title)).stopPropagation();

        tab.add(label, close);
        return tab;
    }

    /**
     * 关闭标签：从标签栏和内容区都摘掉。
     * <p>
     * 从内容区移除会让组件 detach，{@code onDetach} 里注册的清理逻辑（关闭文件句柄、
     * 断开 SSH 通道等）会正常执行——这是「关闭」与「切走」的关键区别。
     */
    private void closeTab(String title) {
        TabEntry entry = openedTabs.remove(title);
        if (entry == null) {
            return;
        }
        boolean wasSelected = tabBar.getSelectedTab() == entry.tab();

        tabBar.remove(entry.tab());
        contentHost.remove(entry.content());

        if (wasSelected && !openedTabs.isEmpty()) {
            // 关掉当前标签后落到剩下的任意一个。不显式 setSelectedTab(null)：
            // vaadin-tabs 在 remove 之后自己会把选中态收干净，多此一举反而可能触发空选中事件。
            tabBar.setSelectedTab(openedTabs.values().iterator().next().tab());
        }
    }

    /** 按选中的标签显示对应内容，其余隐藏。 */
    private void applySelection(Tab selected) {
        TabEntry target = selected == null ? null : entryOf(selected);
        for (TabEntry entry : openedTabs.values()) {
            entry.content().setVisible(entry == target);
        }
    }

    private TabEntry entryOf(Tab tab) {
        for (TabEntry entry : openedTabs.values()) {
            if (entry.tab() == tab) {
                return entry;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 会话注册：让功能页能通过 TabHost.current() 找到主框架
    // ------------------------------------------------------------------

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        VaadinSession session = attachEvent.getSession();
        session.setAttribute(TabHost.SESSION_ATTRIBUTE, this);
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        super.onDetach(detachEvent);
        VaadinSession session = detachEvent.getSession();
        // 只在还是自己的时候清掉：避免把别的会话/后建实例的注册抹掉
        if (session != null && session.getAttribute(TabHost.SESSION_ATTRIBUTE) == this) {
            session.setAttribute(TabHost.SESSION_ATTRIBUTE, null);
        }
    }

    // ------------------------------------------------------------------
    // 当前登录用户
    // ------------------------------------------------------------------

    /**
     * 取当前登录用户的实体。取不到返回 null，不抛异常——
     * 这个方法只用于顶栏显示，不该因为它让整个主框架打不开。
     */
    private User currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof UserPrincipal userPrincipal) {
            return userPrincipal.getUser();
        }
        return null;
    }

    private String currentLoginName() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "未知用户" : authentication.getName();
    }
}
