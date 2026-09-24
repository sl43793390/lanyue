package com.sl.ui.component;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.server.VaadinSession;

import java.util.function.Supplier;

/**
 * 「请求主框架开一个新标签页」的入口。
 * <p>
 * 旧项目 logviewer 里对应的是 {@code TabSheetUtil.getMainTabsheet()}——一个静态方法，
 * 返回当时那个主 {@code TabSheet}，任何组件拿到它就能往里加标签。
 * 日志搜索点了「预览」要打开文件详情、容器列表点了要打开详情，走的都是这条路。
 * <p>
 * 直接照搬那个写法在 Vaadin 24 下行不通：静态字段在多用户下会互相串（谁后登录谁覆盖），
 * 而且那个 TabSheet 属于某一个 UI 实例，不是全局资源。<b>正确的归属是 VaadinSession</b>——
 * 一个浏览器会话一个主框架，一个主框架一个标签区，多用户天然隔离。
 * <p>
 * 用法（在任意功能页里）：
 * <pre>{@code
 * TabHost host = TabHost.current();
 * if (host != null) {
 *     host.open("app.log", () -> new LogDetailView(info, "UTF-8"));
 * }
 * }</pre>
 * {@code current()} 返回 null 只有一种情况：组件没挂在主框架上（比如被单独放进测试页面）。
 * 调用处必须容忍 null，不要抛异常——开不了标签不值得让整个页面崩掉。
 * <p>
 * <b>为什么收的是 {@code Supplier} 而不是现成的组件</b>：主框架按标题去重，
 * 同名标签已存在时会直接切回旧的、不新建。如果让调用方先把组件 new 出来再传进来，
 * 那个被丢弃的实例已经执行完构造器——像 {@link com.sl.ui.local.LogDetailView}
 * 这类在构造器里就打开文件句柄的页面，句柄就泄漏了。传工厂则由主框架决定要不要创建。
 */
public interface TabHost {

    /** VaadinSession 里存放主框架引用的键 */
    String SESSION_ATTRIBUTE = TabHost.class.getName();

    /**
     * 打开（或切回）一个标签页。
     *
     * @param title          标签标题，同时作为去重键
     * @param contentFactory 标签内容的工厂；**只有确实需要新建时才会被调用**
     */
    void open(String title, Supplier<Component> contentFactory);

    /**
     * 该标题的标签是否已经打开。
     * <p>
     * 用于「打开前想先做点别的事」的场景，比如判断详情页是否已开：
     * <pre>{@code
     * if (!host.isOpen(name)) { ... }
     * }</pre>
     */
    boolean isOpen(String title);

    /**
     * 取当前会话的主框架。取不到返回 {@code null}，不抛异常。
     */
    static TabHost current() {
        VaadinSession session = VaadinSession.getCurrent();
        if (session == null) {
            return null;
        }
        Object host = session.getAttribute(SESSION_ATTRIBUTE);
        return host instanceof TabHost tabHost ? tabHost : null;
    }
}
