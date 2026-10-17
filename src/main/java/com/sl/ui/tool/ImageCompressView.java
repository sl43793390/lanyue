package com.sl.ui.tool;

import com.sl.ui.component.ImageCompressor;
import com.sl.ui.component.ViewBase;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

/**
 * 图片压缩（其他工具 → 图片压缩）。
 * <p>
 * 功能与交互对标 imagecompressor.com：多选/拖拽图片 → 选输出格式
 * （保持原格式 / JPEG / PNG / WebP）→ 调质量与颜色数 → 逐张展示
 * 原始体积、压缩后体积、节省比例 → 单张下载。
 * <p>
 * 实现上没有服务端参与：压缩流水线（解码、重编码、调色板量化）
 * 全部在浏览器里跑（见 {@code image-compressor.ts} 的类注释），页面
 * 本身只是一个壳——标题、说明和组件挂载。这样做有两个直接收益：
 * <ul>
 *   <li>隐私：图片字节不出本机，服务器磁盘上不落任何临时文件；</li>
 *   <li>吞吐：不占请求线程，不占带宽，压 20MB 的图和压 2KB 的图对服务器无差别。</li>
 * </ul>
 * 已知边界（有意为之）：GIF 输出为静态图（浏览器无 GIF 编码器，转 PNG）；
 * 重编码会剥掉 EXIF/GPS 等元数据（与 imagecompressor 行为一致，属预期效果）。
 */
@Service
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class ImageCompressView extends ViewBase {

    private static final long serialVersionUID = 1L;

    public ImageCompressView() {
        add(title("图片压缩"));
        add(subtitle("在浏览器本地压缩 JPG / PNG / WebP / GIF / SVG / BMP，图片不上传服务器；"
                + "可切换输出格式、调整质量与颜色数，重编码同时去除 EXIF 等元数据。"));
        add(subtitle("免费且无限制，无需注册，无每日限制，无水印。随意压缩任意数量的图像，完全免费。此工具专为需要可靠、高质量压缩的普通用户和专业人士设计。"));
        ImageCompressor compressor = new ImageCompressor();
        compressor.setWidthFull();
        compressor.setHeightFull();
        add(compressor);
        // 页面是纵向 flex，压缩组件靠 flex-grow 占满标题以下的全部剩余空间
        setFlexGrow(1, compressor);
    }
}
