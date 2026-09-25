package com.sl.ui.remote;

import com.sl.entity.ConnectionInfo;
import com.sl.ui.component.ViewBase;
import com.sl.ui.local.CommonProjectView;
import com.sl.ui.local.JarProjectView;
import com.sl.ui.local.TomcatMgmtView;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.spring.annotation.SpringComponent;
import com.vaadin.flow.spring.annotation.UIScope;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 远程应用管理：一台机器的 jar / Tomcat / 通用项目三合一。
 * <p>
 * 对应旧项目 {@code com.so.component.remote.RemoteAppManagement}（TabSheet 里塞
 * 日志搜索 + 三种应用管理，共享一条 SSH 连接）。这里只保留三种应用管理：
 * 日志搜索已有独立页面（菜单「远程日志搜索」），不再重复塞进来。
 * <p>
 * 与旧实现的差异：旧代码三个子组件<b>共享</b>一条 SSH 连接（父页面建、detach 时断）；
 * 这里每个子页面各自懒建连接、各自在 onDetach 收尾。代价是同开三个标签会建三条
 * SSH（实际只有切到的那个子页会连），换来的是子页面可以独立打开/关闭，生命周期
 * 不再依赖父壳的 disconnect 纪律（旧代码 closeChannel 曾漏调，每开一次漏一条连接）。
 * <p>
 * 子页面仍是本机/远程双模式的那三个 {@code *View}：注入 presetHost 即远程模式，
 * 不为本页复制第二套实现。
 */
@SpringComponent
@UIScope
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class RemoteAppMgmtView extends ViewBase {

    private static final long serialVersionUID = 1L;

    private final transient ApplicationContext applicationContext;

    private ConnectionInfo presetHost;

    /** 子页标题 → 标签页，切换用 setVisible 隐藏而非移除（与 MainView 同一纪律） */
    private final Map<String, TabEntry> tabs = new LinkedHashMap<>();
    private final Tabs tabBar = new Tabs();
    private final Div contentHost = new Div();

    private record TabEntry(Tab tab, Component content) {
    }

    public RemoteAppMgmtView(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public void setPresetHost(ConnectionInfo info) {
        this.presetHost = info;
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        if (!tabs.isEmpty()) {
            return;
        }
        if (presetHost == null) {
            add(new Paragraph("缺少连接信息：请从「免登录服务器列表」的行内按钮打开本页。"));
            return;
        }
        String host = presetHost.getIdHost();

        /*
         * FlexLayout（VerticalLayout 父类）默认 align-items 是 flex-start，不是 stretch：
         * 标题、标签栏、内容容器都按内容宽收缩，页面整体缩在左边、不随窗口变宽——
         * 「应用管理页面宽度不是响应式」就是这个原因。显式声明 stretch（与 MainView 同解），
         * 内容容器铺满后，里面的子页面（Div 的块级子元素）自然占满整行。
         */
        setDefaultHorizontalComponentAlignment(FlexComponent.Alignment.STRETCH);

        add(title("应用管理（" + host + "）"));
        add(subtitle("同一台机器上的 jar 项目 / Tomcat 实例 / 通用项目 / nginx 集中管理，"
                + "命令通过 SSH 远程执行，数据按 id_host=" + host + " 查询。"));

        tabBar.addClassName("main-tabs-bar");
        tabBar.addSelectedChangeListener(e -> applySelection(e.getSelectedTab()));
        contentHost.addClassName("main-tab-content");

        add(tabBar, contentHost);
        setFlexGrow(1, contentHost);

        addTab("jar项目管理-" + host, () -> {
            JarProjectView view = applicationContext.getBean(JarProjectView.class);
            view.setPresetHost(presetHost);
            return view;
        });
        addTab("Tomcat管理-" + host, () -> {
            TomcatMgmtView view = applicationContext.getBean(TomcatMgmtView.class);
            view.setPresetHost(presetHost);
            return view;
        });
        addTab("通用项目管理-" + host, () -> {
            CommonProjectView view = applicationContext.getBean(CommonProjectView.class);
            view.setPresetHost(presetHost);
            return view;
        });
        addTab("nginx管理-" + host, () -> {
            NginxMgmtView view = applicationContext.getBean(NginxMgmtView.class);
            view.setPresetHost(presetHost);
            return view;
        });

        // 默认选中第一个：Tabs 没有子项时 setSelectedTab 会 NPE，先 add 再选
        tabBar.setSelectedTab(tabs.values().iterator().next().tab());
    }

    private void addTab(String title, java.util.function.Supplier<Component> factory) {
        Tab tab = new Tab(title);
        Component content = factory.get();
        content.setVisible(false);
        tabs.put(title, new TabEntry(tab, content));
        tabBar.add(tab);
        contentHost.add(content);
    }

    private void applySelection(Tab selected) {
        for (TabEntry entry : tabs.values()) {
            boolean show = entry.tab() == selected;
            entry.content().setVisible(show);
            // nginx 页的 SSH 建连推迟到第一次切进来：四个子页构建即 attach，
            // 自动连会让只想看 jar 项目的用户也背一条 SSH（见 NginxMgmtView#lazyConnect）
            if (show && entry.content() instanceof NginxMgmtView nginx) {
                nginx.lazyConnect();
            }
        }
    }
}
