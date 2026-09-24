package com.sl.ui.component;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

/**
 * 所有功能页的基类。
 * <p>
 * 旧项目的对应物是 {@code CommonComponent}：继承 Vaadin 8 的 {@code CustomComponent}，
 * 外加 {@code initLayout() / initContent() / registerHandler()} 三个抽象方法。
 * 那套三阶段的写法在 Vaadin 24 下没必要再保留——它当初是为了「先建组件树、
 * 再填数据、最后挂监听」分段执行，而现在页面的构造器里就可以把这三件事一次做完，
 * 需要参数的页面则改用带参构造器或 {@code open(...)} 方法，比抽象钩子更直观。
 * <p>
 * 因此这里只留真正有复用价值的部分：统一的尺寸/间距策略、标题与分节的排版。
 * <p>
 * 页面状态（输入框内容、当前选中项、已建立的连接）天然属于实例字段。
 * 功能页必须是 {@code @Service} + {@code @Scope("prototype")}，**不能是单例**。
 */
public abstract class ViewBase extends VerticalLayout {

    private static final long serialVersionUID = 1L;

    protected ViewBase() {
        addClassName("view-base");
        setSizeFull();
        // 页面自己控制内边距：标签区已经有一层 padding，这里再来一圈会显得空
        setPadding(true);
        setSpacing(true);
    }

    /**
     * 页面主标题。
     */
    protected H2 title(String text) {
        H2 title = new H2(text);
        title.addClassName("view-title");
        return title;
    }

    /**
     * 标题下方的说明文字。
     */
    protected Paragraph subtitle(String text) {
        Paragraph subtitle = new Paragraph(text);
        subtitle.addClassName("view-subtitle");
        return subtitle;
    }

    /**
     * 带下划线的小节标题。
     */
    protected Component section(String text) {
        Span label = new Span(text);
        label.addClassName("view-section-title");
        return label;
    }

    /**
     * 工具栏：一行横向排列的控件，自动居中、自动换行。
     * <p>
     * 旧代码里这类行用 {@code AbsoluteLayout} + {@code "left:187px"} 硬定位，
     * 一旦标签文字变长就会压到相邻控件上。这里交给 flex 布局，不再算像素。
     */
    protected HorizontalLayout toolbar(Component... components) {
        HorizontalLayout bar = new HorizontalLayout(components);
        bar.addClassName("view-toolbar");
        bar.setWidthFull();
        bar.setAlignItems(Alignment.CENTER);
        return bar;
    }

    /**
     * 工具行里的弹性占位，把后面的控件推到右边。
     */
    protected static Span spacer() {
        Span spacer = new Span();
        spacer.addClassName("view-spacer");
        return spacer;
    }

    /**
     * 撑满剩余高度的容器。配合 {@code setFlexGrow(1, ...)} 用在「表格要占满整屏」的场景。
     */
    protected static VerticalLayout fill(Component... components) {
        VerticalLayout layout = new VerticalLayout(components);
        layout.addClassName("view-fill");
        layout.setPadding(false);
        layout.setSpacing(false);
        return layout;
    }
}
