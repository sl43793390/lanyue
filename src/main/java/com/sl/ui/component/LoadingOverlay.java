package com.sl.ui.component;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;

/**
 * 连接/加载等待遮罩：半透明底 + 旋转圆圈 + 一行提示文案。
 * <p>
 * 为什么要有它：SSH 建连在慢网/跨机房环境下要 10~30 秒（demo 主机实测如此），
 * 页面在这段时间里没有任何可见反馈，用户会以为功能坏了反复点按钮。
 * 遮罩 {@code position: absolute; inset: 0} 盖在<b>最近的 position:relative 祖先</b>上，
 * 所以父容器必须自己 {@code getStyle().set("position", "relative")}（见 RemoteFileView）。
 * <p>
 * 转圈动画的 keyframes（{@code lanyue-spin}）与配色在主题 styles.css 的
 * {@code .loading-overlay} 段，这里只出结构。所有方法都必须在 UI 线程调用——
 * 后台线程里要通过 {@code ui.access(...)} 回来再调。
 */
public class LoadingOverlay extends Div {

    private final Span message = new Span();

    public LoadingOverlay(String initialText) {
        addClassName("loading-overlay");
        Div spinner = new Div();
        spinner.addClassName("loading-spinner");
        message.addClassName("loading-overlay-text");
        message.setText(initialText);
        add(spinner, message);
    }

    /** 显示遮罩并更新提示文案。 */
    public void show(String text) {
        message.setText(text);
        setVisible(true);
    }

    /** 只更新文案不改变可见性。 */
    public void setText(String text) {
        message.setText(text);
    }

    /** 隐藏遮罩。 */
    public void hide() {
        setVisible(false);
    }
}
