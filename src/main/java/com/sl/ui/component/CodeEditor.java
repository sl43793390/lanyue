package com.sl.ui.component;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.DomEvent;
import com.vaadin.flow.component.EventData;
import com.vaadin.flow.component.HasSize;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.dependency.NpmPackage;
import com.vaadin.flow.shared.Registration;

/**
 * CodeMirror 6 代码编辑器，替代大段配置/命令场景里的简陋 textarea。
 * <p>
 * 客户端是 {@code src/main/frontend/code-editor.ts}（Lit + CodeMirror 6），
 * 同步面只有三个属性：{@code value}（全文）、{@code mode}（语法模式）、
 * {@code readOnly}。客户端每次改动都派发 {@code value-changed}，
 * 构造器里用 {@code addPropertyChangeListener} 把 value 注册为随该事件同步的属性——
 * 这一步既让 {@link #getValue()} 永远拿到用户编辑后的最新全文，
 * 也让服务端属性缓存跟上客户端的真实状态：否则下一次 {@link #setValue} 会因为
 * 「与缓存值相同」被 Flow 跳过下发，按钮看起来就是点了没反应。
 * <p>
 * 高度控制：组件 host 是 {@code display:block; min-height:0}，内部 .cm-editor 撑满 host，
 * 所以 {@code setHeight(...)} 直接生效；放进纵向 flex 容器时也可以只给
 * {@code setFlexGrow(1, editor)} 靠剩余空间撑高（Compose 新建弹窗同 textarea 时代的做法）。
 * <p>
 * npm 依赖由 {@link NpmPackage} 声明：codemirror 元包（basicSetup 带行号/撤销/括号匹配，
 * 够轻量）+ lang-yaml（compose 完整语法）+ lang-json（JSON 完整语法，Lezer 解析器，
 * 错误恢复能力强，粘贴半截 JSON 也能着色）+ lezer-highlight（tags 常量，
 * 前端 jsonHighlightStyle 按 token 类型给 key/value 分色用）+ legacy-modes
 * （nginx/shell/properties 移植模式）。
 * 首次构建时 vaadin-maven-plugin 会把它们写进 package.json 并 npm install。
 */
@Tag("code-editor")
@JsModule("./code-editor.ts")
@NpmPackage(value = "codemirror", version = "^6.0.1")
@NpmPackage(value = "@codemirror/lang-yaml", version = "^6.1.2")
@NpmPackage(value = "@codemirror/lang-json", version = "^6.0.1")
@NpmPackage(value = "@lezer/highlight", version = "^1.2.4")
@NpmPackage(value = "@codemirror/legacy-modes", version = "^6.5.1")
public class CodeEditor extends Component implements HasSize {

    public static final String MODE_PLAIN = "plain";
    public static final String MODE_YAML = "yaml";
    public static final String MODE_JSON = "json";
    public static final String MODE_SHELL = "shell";
    public static final String MODE_NGINX = "nginx";
    public static final String MODE_PROPERTIES = "properties";

    /** 最新内容：客户端事件与本地 setValue 双向维护 */
    private String value = "";

    public CodeEditor() {
        this(MODE_PLAIN);
    }

    /** @param mode 语法模式，见 MODE_* 常量；null 按纯文本处理 */
    public CodeEditor(String mode) {
        setMode(mode);
        /*
         * 回程同步（关键修复）：客户端每次编辑都派发 value-changed，这里把 value
         * 注册为随该事件同步的属性，Flow 会把最新全文写回服务端属性缓存。
         * 没有这一步，缓存会永远停在最后一次 setValue 推下去的值上——用户在编辑器里
         * 改过/重贴过内容后再调 setValue(新值)，Flow 与缓存比对发现「值没变」就直接
         * 跳过下发，客户端收不到任何更新，表现就是「美化/清空按钮点几次之后就不起作用」
         * （服务端 getValue() 是新值、页面却纹丝不动，两边从此各说各话）。
         */
        getElement().addPropertyChangeListener("value", "value-changed", e ->
                this.value = e.getValue() == null ? "" : String.valueOf(e.getValue()));
    }

    /** 全量替换内容；客户端会把 doc 重置为该值。 */
    public void setValue(String value) {
        this.value = value == null ? "" : value;
        getElement().setProperty("value", this.value);
    }

    /** 当前内容（含用户未保存的编辑）。 */
    public String getValue() {
        return value;
    }

    /** 切语法模式（yaml / json / shell / nginx / properties / plain），客户端只重配语言段不重建。 */
    public void setMode(String mode) {
        getElement().setProperty("mode", mode == null ? MODE_PLAIN : mode);
    }

    public void setReadOnly(boolean readOnly) {
        getElement().setProperty("readOnly", readOnly);
    }

    /** 让编辑器拿焦点（CodeMirror 的可编辑面是 contenteditable，宿主元素本身不可聚焦）。 */
    public void focusEditor() {
        getElement().executeJs("this.focusEditor()");
    }

    public Registration addValueChangeListener(
            ComponentEventListener<ValueChangedEvent> listener) {
        return addListener(ValueChangedEvent.class, listener);
    }

    /**
     * 客户端每次输入回传的全文（detail.value）。
     * <p>
     * {@code pasted} 标记本次变更是否源自粘贴（客户端在 paste DOM 事件里打标，
     * 随事务回传）：调用方可用它实现「粘贴后自动处理一次、手动键入不打扰」，
     * 不必自己起去抖线程。
     */
    @DomEvent("value-changed")
    public static class ValueChangedEvent extends ComponentEvent<CodeEditor> {

        private final String value;

        private final boolean pasted;

        public ValueChangedEvent(CodeEditor source, boolean fromClient,
                                 @EventData("event.detail.value") String value,
                                 @EventData("event.detail.pasted") boolean pasted) {
            super(source, fromClient);
            this.value = value == null ? "" : value;
            this.pasted = pasted;
        }

        public String getValue() {
            return value;
        }

        /** 本次内容变更是否由粘贴产生。 */
        public boolean isPasted() {
            return pasted;
        }
    }

    /**
     * 按文件扩展名猜一个合适的语法模式：
     * .yml/.yaml → yaml；.json → json；.sh/.bash → shell；.conf/.cfg/.ini/.properties/.env → properties；
     * 其余纯文本。前端匹配不到的扩展名一律 plain，不会报错。
     */
    public static String suggestMode(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase();
        if (name.endsWith(".yml") || name.endsWith(".yaml")) {
            return MODE_YAML;
        }
        if (name.endsWith(".json")) {
            return MODE_JSON;
        }
        if (name.endsWith(".sh") || name.endsWith(".bash")) {
            return MODE_SHELL;
        }
        if (name.endsWith(".conf") || name.endsWith(".cfg") || name.endsWith(".ini")
                || name.endsWith(".properties") || name.endsWith(".env")) {
            return MODE_PROPERTIES;
        }
        return MODE_PLAIN;
    }
}
