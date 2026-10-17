package com.sl.ui.component;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasSize;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.dependency.JsModule;

/**
 * 浏览器本地图片格式转换组件（其他工具 → 图片转换）。
 * <p>
 * 客户端是 {@code src/main/frontend/image-converter.ts}（Lit Web Component），
 * 与服务端零状态同步：选图、解码（HEIC/TIFF 走 WASM）、重编码（AVIF 走
 * libavif WASM）、下载全部发生在浏览器里，图片字节不上传服务器。
 * <p>
 * 服务端唯一要做的事是把组件挂进页面（{@code ImageConverterView}），
 * 并给它一个确定的高度：组件内部用 flex 布局，设置栏固定、结果列表滚动，
 * 依赖 host 有真实高度才能撑开。
 */
@Tag("image-converter")
@JsModule("./image-converter.ts")
public class ImageConverter extends Component implements HasSize {

    private static final long serialVersionUID = 1L;
}
