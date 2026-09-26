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
 * {@code readOnly}。客户端每次改动都派发 {@code value-changed}（detail.value 为全文），
 * 构造器里挂了一个内部监听把最新值落回 {@link #getValue()}——
 * 所以使用方不写 addValueChangeListener 也能在保存时拿到用户编辑后的内容，
 * 不会踩「服务端缓存了旧值」的坑。
 * <p>
 * 高度控制：组件 host 是 {@code display:block; min-height:0}，内部 .cm-editor 撑满 host，
 * 所以 {@code setHeight(...)} 直接生效；放进纵向 flex 容器时也可以只给
 * {@code setFlexGrow(1, editor)} 靠剩余空间撑高（Compose 新建弹窗同 textarea 时代的做法）。
 * <p>
 * npm 依赖由 {@link NpmPackage} 声明：codemirror 元包（basicSetup 带行号/撤销/括号匹配，
 * 够轻量）+ lang-yaml（compose 完整语法）+ legacy-modes（nginx/shell/properties 移植模式）。
 * 首次构建时 vaadin-maven-plugin 会把它们写进 package.json 并 npm install。
 */
@Tag("code-editor")
@JsModule("./code-editor.ts")
@NpmPackage(value = "codemirror", version = "^6.0.1")
@NpmPackage(value = "@codemirror/lang-yaml", version = "^6.1.2")
@NpmPackage(value = "@codemirror/legacy-modes", version = "^6.5.1")
public class CodeEditor extends Component implements HasSize {

    public static final String MODE_PLAIN = "plain";
    public static final String MODE_YAML = "yaml";
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
        // 内部监听：value-changed 一律先落字段，getValue() 永远拿到最新内容
        addListener(ValueChangedEvent.class, e -> this.value = e.getValue());
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

    /** 切语法模式（yaml / shell / nginx / properties / plain），客户端只重配语言段不重建。 */
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

    /** 客户端每次输入回传的全文（detail.value）。 */
    @DomEvent("value-changed")
    public static class ValueChangedEvent extends ComponentEvent<CodeEditor> {

        private final String value;

        public ValueChangedEvent(CodeEditor source, boolean fromClient,
                                 @EventData("event.detail.value") String value) {
            super(source, fromClient);
            this.value = value == null ? "" : value;
        }

        public String getValue() {
            return value;
        }
    }

    /**
     * 按文件扩展名猜一个合适的语法模式：
     * .yml/.yaml → yaml；.sh/.bash → shell；.conf/.cfg/.ini/.properties/.env → properties；
     * 其余纯文本。前端匹配不到的扩展名一律 plain，不会报错。
     */
    public static String suggestMode(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase();
        if (name.endsWith(".yml") || name.endsWith(".yaml")) {
            return MODE_YAML;
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
