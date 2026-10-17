package com.sl.ui.tool;

import com.sl.ui.component.Dialogs;
import com.sl.ui.component.UiFactory;
import com.sl.ui.component.ViewBase;
import com.sl.util.EncryptionUtils;
import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 加密工具页，上下两块：
 * <ol>
 *   <li>随机密码生成器：勾选所用字符、设定长度与数量，用 {@link SecureRandom} 生成密码；</li>
 *   <li>摘要加密（原有功能）：把输入串按选定算法算成摘要。</li>
 * </ol>
 * 从旧项目的 {@code EncryptionComponent} 重写而来，算法清单与默认项（SM3）与旧实现保持一致——
 * 用户口令就是用 SM3 的大写十六进制结果存的（见 {@code Sm3PasswordEncoder}），默认值不能改。
 * <p>
 * 界面在构造器里一次搭完，不再有 {@code initLayout / initContent / registerHandler} 三段式。
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

    // ------------------------------------------------------------------
    // 随机密码生成器
    // ------------------------------------------------------------------

    private static final String DIGITS = "0123456789";
    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz";
    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    /** 不含引号与反斜杠：避免生成结果在 shell / 配置文件里粘贴时要二次转义。 */
    private static final String PUNCTUATION = "!@#$%^&*()-_=+[]{};:,.<>?/~";

    private static final int MAX_LENGTH = 64;
    private static final int MAX_COUNT = 100;

    /** 密码生成必须用安全随机数，{@code java.util.Random} 的种子可预测，生成的是"看起来随机"。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Checkbox digitsBox = new Checkbox("数字", true);
    private final Checkbox lowerBox = new Checkbox("小写字母", true);
    private final Checkbox upperBox = new Checkbox("大写字母", true);
    private final Checkbox punctuationBox = new Checkbox("标点符号");
    private final Checkbox noRepeatBox = new Checkbox("字符不重复");

    private final TextField lengthField = new TextField();
    private final TextField countField = new TextField();
    private final TextArea passwordArea = new TextArea();

    // ------------------------------------------------------------------
    // 摘要加密
    // ------------------------------------------------------------------

    private final ComboBox<String> algorithmCombo = new ComboBox<>();
    private final TextField inputField = new TextField();
    private final TextArea resultArea = new TextArea();

    public EncryptionView() {
        addClassName("encryption-view");
        buildLayout();
    }

    private void buildLayout() {
        add(title("加密工具"));

        buildPasswordGenerator();
        add(UiFactory.divider());
        buildDigest();
    }

    /** 上半块：随机密码生成器。 */
    private void buildPasswordGenerator() {
        add(section("随机密码生成器"));
        add(new Text("随机密码生成器为您提供随机密码生成,使用内置随机数发生器产生随机序列,"
                + "避免人的主观意识产生的有序排列字符串,在用作密码或信息识别时强度更高,"
                + "在允许时数字、大小写字母、标点符号都应该用上生成组合密码以增加安全性等。"));

        HorizontalLayout charsetRow = toolbar(UiFactory.fieldRow("所用字符", "90px",
                UiFactory.group(digitsBox, lowerBox, upperBox, punctuationBox, noRepeatBox)));
        charsetRow.getStyle().set("flex-wrap", "wrap");
        add(charsetRow);

        lengthField.setValue("8");
        lengthField.setWidth("120px");
        countField.setValue("1");
        countField.setWidth("120px");
        add(toolbar(
                UiFactory.fieldRow("密码长度", "90px", lengthField),
                UiFactory.fieldRow("密码数量", "90px", countField),
                spacer()));

        add(toolbar(
                UiFactory.success("生成密码", this::generatePasswords),
                UiFactory.tertiary("复制密码", this::copyPasswords),
                UiFactory.tertiary("清空", this::clearPasswords),
                spacer()));

        add(section("生成结果"));
        passwordArea.setReadOnly(true);
        passwordArea.setWidthFull();
        passwordArea.setHeight("200px");
        // 密码是逐字符核对的，等宽字体下 0/O、1/l 的位置关系更清楚
        passwordArea.getStyle().set("--lumo-font-family", "Consolas, 'Courier New', monospace");
        add(passwordArea);
    }

    /** 下半块：原有的摘要加密，内容原样下移，结果框从 260px 缩到 200px。 */
    private void buildDigest() {
        add(section("字符串加密"));
        add(subtitle("选择摘要算法，输入原文即可生成结果。默认 SM3，与用户口令使用的算法一致。"));

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
        resultArea.setHeight("200px");
        // 摘要是等长十六进制串，等宽字体更容易核对字符
        resultArea.getStyle().set("--lumo-font-family", "Consolas, 'Courier New', monospace");
        add(resultArea);
        setFlexGrow(1, resultArea);
    }

    // ------------------------------------------------------------------
    // 随机密码生成器
    // ------------------------------------------------------------------

    /** 勾选的字符池：返回 (合并池, 各类型池) ，各类型池用于「每种至少出一个」的保底。 */
    private List<String> selectedPools() {
        List<String> pools = new ArrayList<>();
        if (digitsBox.getValue()) {
            pools.add(DIGITS);
        }
        if (lowerBox.getValue()) {
            pools.add(LOWER);
        }
        if (upperBox.getValue()) {
            pools.add(UPPER);
        }
        if (punctuationBox.getValue()) {
            pools.add(PUNCTUATION);
        }
        return pools;
    }

    private void generatePasswords() {
        List<String> pools = selectedPools();
        if (pools.isEmpty()) {
            Dialogs.warn("请至少选择一种所用字符");
            digitsBox.focus();
            return;
        }

        int length;
        int count;
        try {
            length = Integer.parseInt(lengthField.getValue().trim());
            count = Integer.parseInt(countField.getValue().trim());
        } catch (NumberFormatException e) {
            Dialogs.warn("密码长度与数量必须是整数");
            return;
        }
        if (length < 1 || length > MAX_LENGTH) {
            Dialogs.warn("密码长度须为 1~" + MAX_LENGTH + " 的整数");
            lengthField.focus();
            return;
        }
        if (count < 1 || count > MAX_COUNT) {
            Dialogs.warn("密码数量须为 1~" + MAX_COUNT + " 的整数");
            countField.focus();
            return;
        }

        String pool = String.join("", pools);
        if (noRepeatBox.getValue() && length > pool.length()) {
            Dialogs.warn("字符不重复时，密码长度不能超过可用字符数（当前 " + pool.length() + "）");
            return;
        }

        List<String> passwords = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            passwords.add(generateOne(pool, pools, length));
        }
        passwordArea.setValue(String.join("\n", passwords));
        Dialogs.success("已生成 " + count + " 个密码");
    }

    /**
     * 生成一个密码。
     * <p>
     * 两种保底，缺一个都会被用户当成 bug：
     * <ul>
     *   <li>勾了 k 种字符、长度 ≥ k 时，每种至少出现一次——否则「勾了标点却一个都没出」；</li>
     *   <li>「字符不重复」时从袋子里取后即移除，杜绝重复。</li>
     * </ul>
     * 保底字符集中在开头会暴露规律，最后统一洗牌。
     */
    private String generateOne(String pool, List<String> pools, int length) {
        boolean noRepeat = noRepeatBox.getValue();
        // 长度不够时保底不了这么多种类，能保几种保几种
        int coverage = Math.min(pools.size(), length);

        List<Character> chars = new ArrayList<>(length);
        for (int i = 0; i < coverage; i++) {
            String type = pools.get(i);
            chars.add(type.charAt(RANDOM.nextInt(type.length())));
        }

        if (noRepeat) {
            List<Character> bag = new ArrayList<>(pool.length());
            pool.chars().forEach(c -> bag.add((char) c));
            // 各类型池两两不相交，保底字符在袋中只会各出现一次，移除是精确的
            bag.removeAll(chars);
            while (chars.size() < length) {
                chars.add(bag.remove(RANDOM.nextInt(bag.size())));
            }
        } else {
            while (chars.size() < length) {
                chars.add(pool.charAt(RANDOM.nextInt(pool.length())));
            }
        }
        Collections.shuffle(chars, RANDOM);

        StringBuilder sb = new StringBuilder(length);
        for (char c : chars) {
            sb.append(c);
        }
        return sb.toString();
    }

    private void copyPasswords() {
        String value = passwordArea.getValue();
        if (value == null || value.isBlank()) {
            Dialogs.warn("还没有密码可以复制");
            return;
        }
        UiFactory.copyToClipboard(this, value);
        Dialogs.success("已复制到剪贴板");
    }

    private void clearPasswords() {
        passwordArea.clear();
        lengthField.setValue("8");
        countField.setValue("10");
        lengthField.focus();
    }

    // ------------------------------------------------------------------
    // 摘要加密
    // ------------------------------------------------------------------

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
        UiFactory.copyToClipboard(this, value);
        Dialogs.success("已复制到剪贴板");
    }

    private void clear() {
        inputField.clear();
        resultArea.clear();
        inputField.focus();
    }
}
