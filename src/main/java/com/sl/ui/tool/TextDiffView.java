package com.sl.ui.tool;

import com.sl.ui.component.TextDiff;
import com.sl.ui.component.UiFactory;
import com.sl.ui.component.ViewBase;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

/**
 * 文本对比（其他工具 → 文本对比）。
 * <p>
 * 对标各类在线 diff 工具的核心体验：粘贴两段文本 → 点「开始对比」→ 差异不再单独
 * 列一份结果，而是直接以颜色标记回左右两个输入框对应的行上——左侧「原始文本」
 * 被删除/被修改的行标红底，右侧「修改后的文本」被新增/被修改的行标绿底，
 * 同时给出增减行数统计。「修改」一行左右成对出现（左红右绿），纯新增只在右边绿，
 * 纯删除只在左边红。
 * <p>
 * 实现上没有服务端参与：diff 计算与行内标记全部在浏览器里跑
 * （见 {@code text-diff.ts} 与 {@code text-diff-core.ts} 的类注释），页面
 * 本身只是一个壳——标题、说明和组件挂载。两个直接收益：
 * <ul>
 *   <li>隐私：文本不出本机，服务器上不落任何临时内容；</li>
 *   <li>吞吐：不占请求线程，对比两段 5000 行的文本对服务器零开销。</li>
 * </ul>
 * 已知边界（有意为之）：diff 按<b>整行</b>比较，不做行内字符级高亮；
 * 用户再编辑任一输入框后旧标记立即失效（行号会错位），需重新点「开始对比」。
 */
@Service
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class TextDiffView extends ViewBase {

    private static final long serialVersionUID = 1L;

    public TextDiffView() {
        addClassName("text-diff-view");
        add(title("文本对比"));
        add(subtitle("在浏览器本地对比两段文本，差异直接以颜色标记在两侧编辑器对应的行上"
                + "（红底=删除/修改，绿底=新增/修改），文本不上传服务器。"));
        add(UiFactory.divider());

        TextDiff diff = new TextDiff();
        diff.setWidthFull();
        diff.setHeightFull();
        add(diff);
        // 页面是纵向 flex，对比组件靠 flex-grow 占满标题以下的全部剩余空间
        setFlexGrow(1, diff);
    }
}
