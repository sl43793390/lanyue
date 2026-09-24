package com.sl.ui.tool;

import com.sl.ui.component.Dialogs;
import com.sl.ui.component.UiFactory;
import com.sl.ui.component.ViewBase;
import com.sl.util.EncryptionUtils;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 加密工具：把输入串按选定算法算成摘要。
 * <p>
 * 从旧项目的 {@code EncryptionComponent} 重写而来。这个页面是迁移里最简单的一个——
 * 除了算法下拉、输入框、结果框、一个按钮之外没有别的东西，
 * 也因此很适合当作「Vaadin 24 页面长什么样」的样板：
 * 界面在构造器里一次搭完，不再有 {@code initLayout / initContent / registerHandler} 三段式。
 * <p>
 * 算法列表照抄旧实现。注意其中 SM3 是国密算法，旧项目的用户口令就是用它的
 * 大写十六进制结果存的（见 {@code Sm3PasswordEncoder}），所以这个默认值不能改。
 */
@Service
@Scope("prototype")
public class EncryptionView extends ViewBase {

    private static final long serialVersionUID = 1L;

    /** 与旧项目保持完全一致的算法清单与默认项，少一个都可能导致运维找不到习惯用的那个。 */
    private static final List<String> ALGORITHMS = List.of(
            "SM3", "SHA3-512", "SHA-1", "SHA-384", "MD5", "SHA-256", "SHA3-384", "SHA-512",
            "RIPEMD256", "Skein-1024-384", "Tiger", "SHA3-256", "SHA3-224", "Skein-1024-1024", "WHIRLPOOL");

    private static final String DEFAULT_ALGORITHM = "SM3";

    private final ComboBox<String> algorithmCombo = new ComboBox<>();
    private final TextField inputField = new TextField();
    private final TextArea resultArea = new TextArea();

    public EncryptionView() {
        addClassName("encryption-view");
        buildLayout();
    }

    private void buildLayout() {
        add(title("加密工具"));
        add(subtitle("选择摘要算法，输入原文即可生成结果。默认 SM3，与用户口令使用的算法一致。"));
        add(UiFactory.divider());

        algorithmCombo.setItems(ALGORITHMS);
        algorithmCombo.setValue(DEFAULT_ALGORITHM);
        algorithmCombo.setWidth("200px");
        algorithmCombo.setAllowCustomValue(false);

        inputField.setPlaceholder("输入要加密的字符串");
        inputField.setWidth("420px");
        inputField.setClearButtonVisible(true);
        // 回车即加密，与「搜索日志」页的手感一致
        inputField.addKeyPressListener(com.vaadin.flow.component.Key.ENTER, event -> encrypt());

        HorizontalLayout inputBar = toolbar(
                UiFactory.fieldRow("算法", "60px", algorithmCombo),
                UiFactory.fieldRow("原文", "60px", inputField),
                spacer());
        inputBar.getStyle().set("flex-wrap", "wrap");
        inputBar.getStyle().set("gap", "16px");
        add(inputBar);

        HorizontalLayout actionBar = toolbar(
                UiFactory.primary("生成摘要", this::encrypt),
                UiFactory.tertiary("复制结果", this::copyResult),
                UiFactory.tertiary("清空", this::clear),
                spacer());
        add(actionBar);

        add(section("结果"));
        resultArea.setReadOnly(true);
        resultArea.setWidthFull();
        resultArea.setHeight("260px");
        // 摘要是等长十六进制串，等宽字体更容易核对字符
        resultArea.getStyle().set("--lumo-font-family", "Consolas, 'Courier New', monospace");
        add(resultArea);
        setFlexGrow(1, resultArea);
    }

    private void encrypt() {
        String algorithm = algorithmCombo.getValue();
        String message = inputField.getValue();

        if (message == null || message.isBlank()) {
            Dialogs.warn("请先输入要加密的字符串");
            inputField.focus();
            return;
        }
        if (algorithm == null || algorithm.isBlank()) {
            Dialogs.warn("请先选择加密算法");
            return;
        }

        String digest = EncryptionUtils.getkeyByAlgorithm(algorithm, message);
        if (digest == null) {
            // 走到这里说明算法在清单里但底层 Provider 不支持，属于环境问题而非输入问题
            resultArea.setValue("该算法在当前环境不可用：" + algorithm);
            Dialogs.error("算法 " + algorithm + " 计算失败，请确认 BouncyCastle 依赖是否正常加载");
            return;
        }
        resultArea.setValue(digest);
    }

    /**
     * 复制结果到剪贴板。
     * <p>
     * {@code navigator.clipboard} 只在安全上下文（HTTPS 或 localhost）可用。
     * 本系统常以 IP + HTTP 的方式内网访问，那种情况下这个调用会静默失败——
     * 所以 catch 掉并明确告知，而不是让用户以为复制成功了。
     */
    private void copyResult() {
        String value = resultArea.getValue();
        if (value == null || value.isBlank()) {
            Dialogs.warn("还没有结果可以复制");
            return;
        }
        getElement().executeJs(
                "if (navigator.clipboard) { navigator.clipboard.writeText($0); }", value);
        Dialogs.success("已尝试复制。若浏览器拦截了剪贴板访问，请手动选中复制");
    }

    private void clear() {
        inputField.clear();
        resultArea.clear();
        inputField.focus();
    }
}
