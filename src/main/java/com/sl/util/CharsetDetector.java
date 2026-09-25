package com.sl.util;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * 小文本文件的编码探测与读写包装（文件管理「编辑」功能专用）。
 * <p>
 * 探测顺序（与运维实际遇到的文件分布匹配）：
 * <ol>
 *   <li>BOM：UTF-8 / UTF-16LE / UTF-16BE 的 BOM 都直接认，写回时原样保留 BOM；</li>
 *   <li>无 BOM 时用 {@link CharsetDecoder} 以 {@code REPORT} 模式严格试 UTF-8——
 *       GBK 中文文件几乎必然出现非法 UTF-8 字节序列，这一步能分掉绝大多数；</li>
 *   <li>UTF-8 严格解码失败，按 GBK 处理（GBK 对任意字节序列都能解码，永远兜得住，
 *       不会在保存环节抛异常）。</li>
 * </ol>
 * 关键约束：<b>写回时必须用探测到的同一组 charset + BOM</b>，
 * 否则就会出现「编辑前正常、保存后乱码」的经典问题。
 */
public final class CharsetDetector {

    /**
     * 一次探测的结果。
     *
     * @param charset 解码/编码用的字符集
     * @param bom     文件头原有的 BOM 字节（没有则为空数组），写回时要拼回去
     * @param label   展示给用户看的编码名称
     * @param content 解码后的文本内容
     */
    public record TextSnapshot(Charset charset, byte[] bom, String label, String content) {
    }

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final byte[] UTF16LE_BOM = {(byte) 0xFF, (byte) 0xFE};
    private static final byte[] UTF16BE_BOM = {(byte) 0xFE, (byte) 0xFF};

    private CharsetDetector() {
    }

    /** 读入整个文件并探测编码（调用方保证文件 ≤ 10M 量级）。 */
    public static TextSnapshot load(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        Charset charset;
        byte[] bom = new byte[0];
        String label;

        if (startsWith(bytes, UTF8_BOM)) {
            charset = StandardCharsets.UTF_8;
            bom = UTF8_BOM;
            label = "UTF-8（带 BOM）";
        } else if (startsWith(bytes, UTF16LE_BOM)) {
            charset = StandardCharsets.UTF_16LE;
            bom = UTF16LE_BOM;
            label = "UTF-16LE（带 BOM）";
        } else if (startsWith(bytes, UTF16BE_BOM)) {
            charset = StandardCharsets.UTF_16BE;
            bom = UTF16BE_BOM;
            label = "UTF-16BE（带 BOM）";
        } else if (isStrictUtf8(bytes)) {
            charset = StandardCharsets.UTF_8;
            label = "UTF-8";
        } else {
            charset = Charset.forName("GBK");
            label = "GBK";
        }

        String content = new String(bytes, bom.length, bytes.length - bom.length, charset);
        return new TextSnapshot(charset, bom, label, content);
    }

    /** 按探测结果编码写回：BOM + 内容，保证保存前后编码完全一致。 */
    public static byte[] encode(String content, Charset charset, byte[] bom) {
        byte[] body = content.getBytes(charset);
        byte[] out = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, out, 0, bom.length);
        System.arraycopy(body, 0, out, bom.length, body.length);
        return out;
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        return bytes.length >= prefix.length
                && Arrays.equals(Arrays.copyOf(bytes, prefix.length), prefix);
    }

    /** 严格 UTF-8 校验：任何非法序列都不放行（纯 ASCII 也算 UTF-8，与 GBK 字节相同，无损）。 */
    private static boolean isStrictUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }
}
