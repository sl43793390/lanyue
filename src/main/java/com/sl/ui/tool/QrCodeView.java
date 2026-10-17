package com.sl.ui.tool;

import cn.hutool.core.img.ImgUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.qrcode.QrCodeUtil;
import cn.hutool.extra.qrcode.QrConfig;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.sl.ui.component.Dialogs;
import com.sl.ui.component.UiFactory;
import com.sl.ui.component.ViewBase;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.server.streams.DownloadHandler;
import com.vaadin.flow.server.streams.DownloadResponse;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * 二维码生成（其他工具 → 二维码生成）。
 * <p>
 * 参照 ostools 在线二维码工具的三栏布局：左边输入内容（URL 或任意文本），
 * 中间一列参数（输出格式、纠错级别、尺寸、边缘留白）+ 生成按钮，
 * 右边灰底预览区展示二维码并提供下载。
 * <p>
 * 生成走 hutool 的 {@link QrCodeUtil}（编码本体是 ZXing core，见 pom 里的依赖说明），
 * 全部在请求线程内完成，不写任何临时文件：字节先生成、预览用 data URI、
 * 下载用 {@link DownloadHandler#fromInputStream} 直接把内存字节发出去——
 * 服务器磁盘上不落任何二维码图片。
 * <p>
 * JPEG 的一个已知坑在这里天然规避：hutool 内部 {@code toImage} 产出的
 * {@code BufferedImage} 在设置了背景色时是 {@code TYPE_INT_RGB}，
 * Java 的 JPEG 编码器吃得下；若是 {@code TYPE_BYTE_BINARY} 才会报
 * 「Bogus input colorspace」。默认白底，两种格式都安全。
 */
@Service
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class QrCodeView extends ViewBase {

    private static final long serialVersionUID = 1L;

    private final TextArea contentArea = new TextArea();
    private final ComboBox<String> formatCombo = new ComboBox<>("输出格式:");
    private final ComboBox<String> ecCombo = new ComboBox<>("纠错级别:");
    private final NumberField sizeField = new NumberField("尺寸(像素):");
    private final NumberField marginField = new NumberField("边缘留白:");

    /** 灰底预览区：未生成时放提示字，生成后放二维码图片 */
    private final Div previewBox = new Div();
    private final VerticalLayout downloadSlot = new VerticalLayout();

    /** 最近一次生成的图片字节与元信息，下载时从这里取 */
    private byte[] qrBytes;
    private String qrMime;
    private String qrFileName;

    public QrCodeView() {
        add(title("二维码生成"));
        add(subtitle("输入 URL 或任意文本生成二维码，支持 PNG / JPEG 输出、纠错级别与留白调整；"
                + "图片只在内存中生成，服务器不保存。"));

        HorizontalLayout main = new HorizontalLayout(buildInputPanel(), buildOptionPanel(), buildPreviewPanel());
        main.setWidthFull();
        main.setAlignItems(FlexComponent.Alignment.STRETCH);
        // FlexLayout 不显式声明 stretch 会塌成内容高（记过的坑）
        main.getStyle().set("gap", "24px");
        add(main);

        downloadSlot.setPadding(false);
        downloadSlot.setSpacing(false);
    }

    // ------------------------------------------------------------------
    // 三栏布局
    // ------------------------------------------------------------------

    private VerticalLayout buildInputPanel() {
        contentArea.setWidthFull();
        contentArea.setHeight("420px");
        contentArea.setPlaceholder("http://ostools.net/qr");
        contentArea.setValueChangeMode(com.vaadin.flow.data.value.ValueChangeMode.EAGER);

        VerticalLayout panel = new VerticalLayout(new Span("URL或其他文本"), contentArea);
        panel.setPadding(false);
        panel.setSpacing(false);
        panel.getStyle().set("gap", "8px");
        panel.getStyle().set("flex", "1 1 0");
        panel.getStyle().set("min-width", "0");
        return panel;
    }

    private VerticalLayout buildOptionPanel() {
        // Lumo 给 vaadin-combo-box / vaadin-number-field 的主机元素带默认 min-width，
        // setWidth(150px) 会被它压过（实测渲染出 268px），右端直接叠到 QR 预览区上。
        // 必须 min-width:0 把设计宽度真正压下去。
        formatCombo.setItems("PNG", "JPEG");
        formatCombo.setValue("PNG");
        formatCombo.setWidth("150px");
        formatCombo.getStyle().set("min-width", "0");

        ecCombo.setItems("L 7%", "M 15%", "Q 25%", "H 30%");
        ecCombo.setValue("M 15%");
        ecCombo.setWidth("150px");
        ecCombo.getStyle().set("min-width", "0");

        sizeField.setValue(300d);
        sizeField.setMin(100d);
        sizeField.setMax(1000d);
        sizeField.setStep(50d);
        sizeField.setHelperText("100 - 1000");
        sizeField.setWidth("150px");
        sizeField.getStyle().set("min-width", "0");

        marginField.setValue(2d);
        marginField.setMin(0d);
        marginField.setMax(16d);
        marginField.setStep(1d);
        marginField.setHelperText("0 - 16");
        marginField.setWidth("150px");
        marginField.getStyle().set("min-width", "0");

        VerticalLayout panel = new VerticalLayout(
                new Span("参数"),
                formatCombo, ecCombo, sizeField, marginField,
                UiFactory.primary("生成二维码", this::generate));
        panel.setPadding(false);
        panel.getStyle().set("gap", "10px");
        panel.getStyle().set("flex", "0 0 170px");
        panel.setWidth("270px");
        return panel;
    }

    private VerticalLayout buildPreviewPanel() {
        previewBox.setWidthFull();
        previewBox.setHeight("420px");
        previewBox.getStyle().set("background", "var(--lumo-contrast-5pct)");
        previewBox.getStyle().set("border", "1px solid var(--lumo-contrast-20pct)");
        previewBox.getStyle().set("display", "flex");
        previewBox.getStyle().set("align-items", "center");
        previewBox.getStyle().set("justify-content", "center");
        showEmptyHint();

        VerticalLayout panel = new VerticalLayout(new Span("QR码:"), previewBox, downloadSlot);
        panel.setPadding(false);
        panel.getStyle().set("gap", "8px");
        panel.getStyle().set("flex", "1 1 0");
        panel.getStyle().set("min-width", "0");
        panel.setWidthFull();
        // 竖向容器显式 stretch，右栏预览框才有宽度
        panel.setAlignItems(FlexComponent.Alignment.STRETCH);
        return panel;
    }

    // ------------------------------------------------------------------
    // 生成 / 下载
    // ------------------------------------------------------------------

    private void generate() {
        String content = StrUtil.trimToEmpty(contentArea.getValue());
        if (content.isEmpty()) {
            Dialogs.warn("请先输入要编码的内容");
            contentArea.focus();
            return;
        }
        int size = clamp(intValue(sizeField, 300), 100, 1000);
        int margin = clamp(intValue(marginField, 2), 0, 16);

        boolean jpeg = "JPEG".equals(formatCombo.getValue());
        String hutoolType = jpeg ? ImgUtil.IMAGE_TYPE_JPEG : ImgUtil.IMAGE_TYPE_PNG;
        String ext = jpeg ? "jpg" : "png";

        QrConfig config = new QrConfig(size, size);
        config.setMargin(margin);
        config.setErrorCorrection(levelOf(ecCombo.getValue()));

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            QrCodeUtil.generate(content, config, hutoolType, out);
            qrBytes = out.toByteArray();
        } catch (Exception e) {
            Dialogs.error("生成失败：" + StrUtil.emptyToDefault(e.getMessage(), "未知错误"));
            return;
        }
        qrMime = jpeg ? "image/jpeg" : "image/png";
        qrFileName = "qrcode." + ext;

        showPreview();
        rebuildDownloadLink();
        Dialogs.success("二维码已生成");
    }

    private void showEmptyHint() {
        Span hint = new Span("生成后在此显示二维码");
        hint.getStyle().set("color", "var(--lumo-secondary-text-color)");
        previewBox.removeAll();
        previewBox.add(hint);
    }

    private void showPreview() {
        Image image = new Image("data:" + qrMime + ";base64,"
                + java.util.Base64.getEncoder().encodeToString(qrBytes), "二维码");
        image.getStyle().set("max-width", "80%");
        image.getStyle().set("max-height", "80%");
        image.getStyle().set("background", "#ffffff");
        image.getStyle().set("padding", "8px");
        previewBox.removeAll();
        previewBox.add(image);
    }

    /**
     * 重建下载链接。不复用旧 Anchor 再 setHref：Anchor 对 DownloadHandler
     * 的替换支持不稳，重新 new 一个语义清楚，也不怕旧 handler 持有过期字节。
     */
    private void rebuildDownloadLink() {
        byte[] bytes = qrBytes;
        String mime = qrMime;
        String name = qrFileName;
        Anchor link = new Anchor(
                DownloadHandler.fromInputStream(event ->
                        new DownloadResponse(new ByteArrayInputStream(bytes), name, mime, bytes.length)),
                "下载二维码");
        link.getElement().setAttribute("download", name);
        downloadSlot.removeAll();
        downloadSlot.add(link);
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static int intValue(NumberField field, int fallback) {
        Double v = field.getValue();
        return v == null ? fallback : (int) Math.round(v);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** 下拉文案 "L 7%" → ZXing 纠错级别；未选时兜底 M（hutool 默认也是 M） */
    private static ErrorCorrectionLevel levelOf(String text) {
        if (StrUtil.isBlank(text)) {
            return ErrorCorrectionLevel.M;
        }
        return switch (text.charAt(0)) {
            case 'L' -> ErrorCorrectionLevel.L;
            case 'Q' -> ErrorCorrectionLevel.Q;
            case 'H' -> ErrorCorrectionLevel.H;
            default -> ErrorCorrectionLevel.M;
        };
    }
}
