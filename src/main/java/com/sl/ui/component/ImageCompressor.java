package com.sl.ui.component;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasSize;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.dependency.JsModule;

/**
 * 浏览器本地图片压缩组件（其他工具 → 图片压缩）。
 * <p>
 * 客户端是 {@code src/main/frontend/image-compressor.ts}（Lit Web Component），
 * 与服务端零状态同步：选图、解码、重编码、下载全部发生在浏览器里，
 * 图片字节不上传到服务器——这正是 imagecompressor.com 的核心卖点，照搬。
 * <p>
 * 服务端唯一要做的事是把组件挂进页面（{@code ImageCompressView}），
 * 并给它一个确定的高度：组件内部用 flex 布局，设置栏固定、结果列表滚动，
 * 依赖 host 有真实高度才能撑开。
 */
@Tag("image-compressor")
@JsModule("./image-compressor.ts")
public class ImageCompressor extends Component implements HasSize {

    private static final long serialVersionUID = 1L;
}
