package com.sl.ui.tool;

import com.sl.ui.component.ImageConverter;
import com.sl.ui.component.ViewBase;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

/**
 * 图片转换（其他工具 → 图片转换）。
 * <p>
 * 功能与交互对标 imagecompressor.com 的 Image Converter：HEIC/JPG/PNG/
 * WebP/GIF/BMP/TIFF/AVIF/ICO → JPG/PNG/WebP/AVIF，一次最多 20 个文件，
 * 选输出格式点「开始转换」，逐文件保存。实现上没有服务端参与：
 * 解码（HEIC 走 libheif WASM、TIFF 走 UTIF、其余走浏览器原生）与编码
 * （JPG 走 MozJPEG WASM、AVIF 走 libavif WASM、PNG/WebP 走 Canvas）
 * 全部在浏览器里跑，页面只是标题 + 说明 + 组件挂载的壳。
 */
@Service
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class ImageConverterView extends ViewBase {

    private static final long serialVersionUID = 1L;

    public ImageConverterView() {
        add(title("图片转换"));
        add(subtitle("将 HEIC、JPG、PNG、WebP、GIF、BMP、TIFF、AVIF 和 ICO 图像转换为 JPG、PNG、WebP 或 AVIF。"
                + "无需上传到服务器——转换在浏览器中进行。快速、安全、私密。"));

        add(buildWhyCard());

        ImageConverter converter = new ImageConverter();
        converter.setWidthFull();
        converter.setHeightFull();
        add(converter);
        // 页面是纵向 flex，转换组件靠 flex-grow 占满说明区以下的全部剩余空间
        setFlexGrow(1, converter);
    }

    /** 标题下方的「为什么使用 Image Converter？」说明卡片 */
    private VerticalLayout buildWhyCard() {
        VerticalLayout card = new VerticalLayout();
        card.setSpacing(false);
        card.setPadding(false);
        card.setWidthFull();
        card.getStyle().set("background", "var(--lumo-base-color, #fff)");
        card.getStyle().set("border", "1px solid var(--lumo-contrast-20pct, #d8d8d8)");
        card.getStyle().set("border-radius", "var(--lumo-border-radius-m, 6px)");
        card.getStyle().set("padding", "12px 16px");

        H3 heading = new H3("为什么使用 Image Converter？");
        heading.getStyle().set("margin", "0 0 8px");
        heading.getStyle().set("font-size", "var(--lumo-font-size-l, 1.125rem)");
        card.add(heading);

        card.add(para("完全隐私。", "与其他转换器不同，您的文件永远不会离开您的设备。"
                + "所有转换都直接在您的浏览器中使用 WebAssembly 技术进行。无需上传，无服务器存储，无隐私顾虑。"));
        card.add(para("快速且免费。", "即使是大文件，转换也只需几秒钟。无需注册，每日转换次数无限制，"
                + "图像上无水印。服务完全免费。"));
        card.add(para("随处可用。", "在任何带有现代浏览器的设备上使用——台式机、平板电脑或智能手机。"
                + "无需安装软件。只需打开页面即可开始转换。"));
        return card;
    }

    /** 一段说明：加粗导语 + 正文 */
    private Span para(String lead, String body) {
        Span leadSpan = new Span(lead);
        leadSpan.getStyle().set("font-weight", "700");
        Span bodySpan = new Span(body);
        Span p = new Span(leadSpan, bodySpan);
        p.getStyle().set("display", "block");
        p.getStyle().set("margin", "0 0 6px");
        p.getStyle().set("color", "var(--lumo-secondary-text-color, #6f7787)");
        p.getStyle().set("line-height", "1.5");
        return p;
    }
}
