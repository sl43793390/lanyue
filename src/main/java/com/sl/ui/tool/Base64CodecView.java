package com.sl.ui.tool;

import cn.hutool.core.util.StrUtil;
import com.sl.ui.component.Dialogs;
import com.sl.ui.component.UiFactory;
import com.sl.ui.component.ViewBase;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.component.upload.UploadI18N;
import com.vaadin.flow.component.upload.receivers.MemoryBuffer;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Base64 编解码（Linux工具 → Base64编解码）。
 * <p>
 * 两个标签页：文字编解码（UTF-8 文本 ↔ Base64，双向）、图片编码（上传图片 → Base64，
 * 可选带 Data URI 前缀直接用于 {@code <img src>} / CSS）。
 * <p>
 * 图片上传用 {@link MemoryBuffer} 纯内存接收：字节流只进 JVM 内存，不写任何临时文件，
 * 读完立即把字节引用置空交给 GC——页面上据此向用户承诺「服务器不保存上传的图片」。
 * 解码用 {@link Base64#getMimeDecoder()}：它容忍换行/空白，浏览器和各语言工具复制出来的
 * Base64 常带折行，标准 decoder 会直接判失败，MIME decoder 全都吃得下。
 */
@Service
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class Base64CodecView extends ViewBase {

    private static final long serialVersionUID = 1L;

    private final Tab textTab = new Tab("文字编解码");
    private final Tab imageTab = new Tab("图片编码");
    private final Div textPage = new Div();
    private final Div imagePage = new Div();

    // ------------------------------------------------------------------
    // 文字编解码页
    // ------------------------------------------------------------------

    private final TextArea textInput = new TextArea("原文 / 待解码的 Base64");
    private final TextArea textOutput = new TextArea("转换结果");

    // ------------------------------------------------------------------
    // 图片编码页
    // ------------------------------------------------------------------

    private final MemoryBuffer imageBuffer = new MemoryBuffer();
    private final Upload imageUpload = new Upload(imageBuffer);
    private final TextArea imageOutput = new TextArea("Base64 结果");
    private final Checkbox dataUriToggle = new Checkbox(
            "带 Data URI 前缀（data:image/…;base64,，可直接用于 <img src> 或 CSS background）");
    private final Div previewArea = new Div();
    private final Span imageMeta = new Span();

    /** 上传图片的编码结果与类型：只存 base64 字符串，原始字节转换后即释放 */
    private String imageBase64;
    private String imageMime;

    public Base64CodecView() {
        add(title("Base64 编解码"));
        add(subtitle("文字与 Base64 互转（UTF-8）；图片上传后转 Base64，可选 Data URI 前缀。"));

        Tabs tabs = new Tabs(textTab, imageTab);
        tabs.addSelectedChangeListener(e -> switchPage(e.getSelectedTab()));
        add(tabs);

        buildTextPage();
        buildImagePage();
        switchPage(textTab);
    }

    // ------------------------------------------------------------------
    // 文字编解码
    // ------------------------------------------------------------------

    private void buildTextPage() {
        textInput.setWidthFull();
        textInput.setHeight("220px");
        textOutput.setWidthFull();
        textOutput.setHeight("220px");
        textOutput.setReadOnly(true);
        textOutput.addClassName("mono-cell");

        HorizontalLayout actions = toolbar(
                UiFactory.primary("编码 → Base64", () -> transform(true)),
                UiFactory.button("解码 → 原文", () -> transform(false)),
                UiFactory.tertiary("复制结果", this::copyTextResult),
                UiFactory.tertiary("清空", this::clearText),
                spacer());

        VerticalLayout page = new VerticalLayout(textInput, actions, textOutput);
        page.setPadding(false);
        page.setSpacing(false);
        page.setWidthFull();
        page.getStyle().set("gap", "10px");
        textPage.add(page);
        add(textPage);
    }

    /** direction=true 编码，false 解码 */
    private void transform(boolean encode) {
        String source = StrUtil.trimToEmpty(textInput.getValue());
        if (source.isEmpty()) {
            Dialogs.warn("请先输入内容");
            textInput.focus();
            return;
        }
        try {
            if (encode) {
                textOutput.setValue(Base64.getEncoder().encodeToString(source.getBytes(StandardCharsets.UTF_8)));
            } else {
                byte[] decoded = Base64.getMimeDecoder().decode(source);
                textOutput.setValue(new String(decoded, StandardCharsets.UTF_8));
            }
        } catch (IllegalArgumentException e) {
            Dialogs.error("不是合法的 Base64 字符串，无法解码");
            return;
        }
        Dialogs.success(encode ? "已编码" : "已解码");
    }

    private void copyTextResult() {
        String value = textOutput.getValue();
        if (StrUtil.isBlank(value)) {
            Dialogs.warn("还没有结果可复制");
            return;
        }
        UiFactory.copyToClipboard(this, value);
        Dialogs.success("已复制到剪贴板");
    }

    private void clearText() {
        textInput.setValue("");
        textOutput.setValue("");
        textInput.focus();
    }

    // ------------------------------------------------------------------
    // 图片编码
    // ------------------------------------------------------------------

    private void buildImagePage() {
        // 不落盘承诺：MemoryBuffer 纯内存接收 + 读完后立即释放字节，这句话才成立
        Span notice = new Span("说明：服务器不保存上传的图片。图片只在内存中完成转换，"
                + "转换完成后立即释放，不会写入服务器磁盘。");
        notice.getStyle().set("color", "var(--lumo-warning-text-color)");
        notice.getStyle().set("font-size", "var(--lumo-font-size-s)");

        imageUpload.setAcceptedFileTypes("image/*");
        imageUpload.setMaxFiles(1);
        imageUpload.clearFileList();
        UploadI18N i18n = new UploadI18N();
        i18n.setDropFiles(new UploadI18N.DropFiles().setOne("拖图片到这里"));
        i18n.setAddFiles(new UploadI18N.AddFiles().setOne("选择图片…"));
        imageUpload.setI18n(i18n);
        imageUpload.setWidth("420px");
        imageUpload.addSucceededListener(e -> convertImage());

        dataUriToggle.addValueChangeListener(e -> refreshImageOutput());

        imageMeta.getStyle().set("color", "var(--lumo-secondary-text-color)");
        imageMeta.getStyle().set("font-size", "var(--lumo-font-size-s)");

        previewArea.setWidthFull();

        imageOutput.setWidth("900px");
        imageOutput.setHeight("300px");
        imageOutput.setReadOnly(true);
        imageOutput.addClassName("mono-cell");

        HorizontalLayout imageActions = toolbar(
                UiFactory.primary("复制结果", this::copyImageResult),
                UiFactory.tertiary("清空", this::clearImage),
                spacer());

        VerticalLayout page = new VerticalLayout(
                notice, imageUpload, imageMeta, previewArea, dataUriToggle, imageOutput, imageActions);
        page.setPadding(false);
        page.setSpacing(false);
        page.setWidthFull();
        page.getStyle().set("gap", "10px");
        imagePage.add(page);
        imagePage.setVisible(false);
        add(imagePage);
    }

    /** 上传成功：读内存字节 → 编码 → 立即丢弃字节。全程无磁盘 IO */
    private void convertImage() {
        try (InputStream in = imageBuffer.getInputStream()) {
            byte[] bytes = in.readAllBytes();
            if (bytes.length == 0) {
                Dialogs.error("读到的图片内容是空的，请重新选择");
                return;
            }
            String mime = StrUtil.blankToDefault(imageBuffer.getFileData().getMimeType(), "image/png");
            String name = StrUtil.blankToDefault(imageBuffer.getFileData().getFileName(), "image");
            String base64 = Base64.getEncoder().encodeToString(bytes);
            // base64 已拿到，原始字节不再需要，立即释放
            bytes = null;

            imageBase64 = base64;
            imageMime = mime;
            imageMeta.setText(name + "　" + mime + "　编码后 " + base64.length() + " 字符");

            // 预览直接用 data URI（它本身就是编码产物，不额外持有原始字节）
            Image preview = new Image("data:" + mime + ";base64," + base64, name);
            preview.getStyle().set("max-height", "180px");
            preview.getStyle().set("max-width", "360px");
            preview.getStyle().set("border", "1px solid var(--lumo-contrast-20pct)");
            previewArea.removeAll();
            previewArea.add(preview);

            refreshImageOutput();
            Dialogs.success("转换完成，图片已从内存释放");
        } catch (Exception e) {
            Dialogs.error("图片转换失败：" + StrUtil.emptyToDefault(e.getMessage(), "未知错误"));
        }
    }

    /** 按开关刷新输出框：纯 Base64 或带 Data URI 前缀 */
    private void refreshImageOutput() {
        if (imageBase64 == null) {
            return;
        }
        imageOutput.setValue(Boolean.TRUE.equals(dataUriToggle.getValue())
                ? "data:" + imageMime + ";base64," + imageBase64
                : imageBase64);
    }

    private void copyImageResult() {
        if (StrUtil.isBlank(imageOutput.getValue())) {
            Dialogs.warn("请先上传图片");
            return;
        }
        UiFactory.copyToClipboard(this, imageOutput.getValue());
        Dialogs.success("已复制到剪贴板");
    }

    private void clearImage() {
        previewArea.removeAll();
        imageMeta.setText("");
        dataUriToggle.setValue(false);
        imageOutput.setValue("");
        imageBase64 = null;
        imageMime = null;
        // 上传列表清掉，同一个文件还能重传（浏览器对同名文件不触发 change）
        imageUpload.clearFileList();
    }

    // ------------------------------------------------------------------
    // 标签页切换
    // ------------------------------------------------------------------

    private void switchPage(Tab selected) {
        boolean isText = selected.equals(textTab);
        textPage.setVisible(isText);
        imagePage.setVisible(!isText);
    }
}
