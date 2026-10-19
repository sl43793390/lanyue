package com.sl.ui.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sl.ui.component.CodeEditor;
import com.sl.ui.component.Dialogs;
import com.sl.ui.component.UiFactory;
import com.sl.ui.component.ViewBase;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

/**
 * JSON 美化：粘贴 JSON 后自动美化一次；之后的修改不自动处理，点「美化」手动触发。
 * <p>
 * 自动触发的判据是 {@link CodeEditor.ValueChangedEvent#isPasted()}——前端在 paste
 * DOM 事件里打标、随 value-changed 一起回传，所以只有真正的粘贴会触发，手动键入、
 * 拖拽改内容都不会。美化在事件回调（请求线程）里同步完成，不开线程、不去抖：
 * 一次粘贴就是一次往返，本来就串行，任何线程池/延时调度都是纯开销。
 * <p>
 * 粘贴的内容解析失败时静默保持原样——手上可能就是一段截断的文本，弹窗只会打扰；
 * 只有点「美化」才给显式报错。
 * <p>
 * 校验用 Jackson 的 {@link ObjectMapper#readTree}——它是严格解析，尾随垃圾字符、
 * 单引号、注释、未加引号的 key 一律判不合法，符合「不是完整的JSON格式」这个提示的预期。
 * <p>
 * 编辑器用 {@link CodeEditor}（CodeMirror 6）的 json 模式而不是 TextArea：
 * TextArea 是纯文本，key 与 value 同色，大段 JSON 的层级看不出来；
 * json 模式带 Lezer 解析器，key（蓝）/ 字符串（绿）/ 数字（橙）/ 布尔（紫）/ null（灰）
 * 分色显示，可读性差距是数量级的。粘贴半截 JSON 时解析器有错误恢复，照样着色。
 */
@Service
@Scope("prototype")
public class JsonBeautifyView extends ViewBase {

    private static final long serialVersionUID = 1L;

    /** ObjectMapper 线程安全，静态共享。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 排版规则：2 空格缩进、LF 换行、{@code "key": value} 分隔符。
     * <p>
     * 两处都不能沿用默认值：{@link DefaultPrettyPrinter} 的默认缩进器是
     * {@code SYSTEM_LINEFEED_INSTANCE}，Windows 上会输出 CRLF，编辑器里显示正常
     * 但复制出去会带上 {@code \r}；默认对象条目分隔符是 {@code " : "}，
     * 不是各端美化工具的常见长相。这里显式钉死。
     */
    private static final DefaultPrettyPrinter PRETTY_PRINTER = new DefaultPrettyPrinter()
            .withSeparators(Separators.createDefaultInstance()
                    .withObjectFieldValueSpacing(Separators.Spacing.NONE))
            .withObjectIndenter(new DefaultIndenter("  ", "\n"))
            .withArrayIndenter(new DefaultIndenter("  ", "\n"));

    /** 有些编辑器复制出来的文本带头上的 BOM，Jackson 不认，先剥掉。 */
    private static final char BOM = '\uFEFF';

    private final CodeEditor editor = new CodeEditor(CodeEditor.MODE_JSON);

    public JsonBeautifyView() {
        addClassName("json-beautify-view");
        buildLayout();
    }

    private void buildLayout() {
        add(title("JSON美化"));
        add(subtitle("粘贴 JSON 后自动美化一次；之后的手动修改请点「美化」处理，校验失败会弹窗提示。"));
        add(UiFactory.divider());

        editor.setWidthFull();
        editor.setHeight("460px");
        editor.addValueChangeListener(e -> {
            if (e.isPasted()) {
                autoFormatOnce(e.getValue());
            }
        });
        add(editor);
        setFlexGrow(1, editor);

        add(toolbar(
                UiFactory.primary("美化", this::beautify),
                UiFactory.tertiary("复制", this::copy),
                UiFactory.tertiary("清空", this::clear),
                spacer()));
    }

    /**
     * 粘贴后自动美化一次：事件回调就在请求线程上，直接同步排版回写，
     * 不调度任何后台任务。解析失败静默跳过；排版结果与原文相同也不回写，
     * 避免无意义的 setValue 往返。
     */
    private void autoFormatOnce(String pasted) {
        String pretty = tryFormat(pasted);
        if (pretty == null || pretty.equals(pasted)) {
            return;
        }
        editor.setValue(pretty);
    }

    /** 点「美化」：立即校验排版，失败弹窗（自动美化不弹窗，只有手动点才报错）。 */
    private void beautify() {
        String raw = editor.getValue();
        if (raw == null || raw.isBlank()) {
            Dialogs.warn("请先粘贴 JSON 字符串");
            editor.focusEditor();
            return;
        }
        String pretty = tryFormat(raw);
        if (pretty == null) {
            Dialogs.alert("格式校验未通过", "不是完整的JSON格式");
            return;
        }
        editor.setValue(pretty);
        Dialogs.success("格式校验通过，已美化");
    }

    /**
     * 校验并排版，不合法返回 {@code null}。
     * 同时剥掉行首 BOM——有些编辑器复制出来的文本带着它，Jackson 不认。
     */
    private String tryFormat(String raw) {
        try {
            String text = raw;
            if (!text.isEmpty() && text.charAt(0) == BOM) {
                text = text.substring(1);
            }
            JsonNode node = MAPPER.readTree(text);
            return MAPPER.writer(PRETTY_PRINTER).writeValueAsString(node);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private void copy() {
        String value = editor.getValue();
        if (value == null || value.isBlank()) {
            Dialogs.warn("还没有内容可以复制");
            return;
        }
        UiFactory.copyToClipboard(this, value);
        Dialogs.success("已复制到剪贴板");
    }

    private void clear() {
        editor.setValue("");
        editor.focusEditor();
    }
}
