package com.sl.ui.component;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasSize;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.dependency.JsModule;

/**
 * 浏览器本地文本对比组件（其他工具 → 文本对比）。
 * <p>
 * 客户端是 {@code src/main/frontend/text-diff.ts}（Lit Web Component），
 * 与服务端零状态同步：两段文本的读取、diff 计算（Myers 线性空间差分，
 * 见 {@code text-diff-core.ts}）、把差异行以颜色标记回两个输入框，全部发生在
 * 浏览器里，文本内容不上传服务器——与 {@link ImageCompressor} 同一思路。
 * <p>
 * 服务端唯一要做的事是把组件挂进页面（{@code TextDiffView}），并给它一个
 * 确定的高度：组件内部是纵向 flex（工具行与状态行固定、两个输入卡片按剩余
 * 空间伸缩），依赖 host 有真实高度才能撑开。
 */
@Tag("text-diff")
@JsModule("./text-diff.ts")
public class TextDiff extends Component implements HasSize {

    private static final long serialVersionUID = 1L;
}
