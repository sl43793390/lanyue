package com.sl.ui.remote;

import com.sl.entity.ConnectionInfo;
import com.sl.service.TerminalTokens;
import com.sl.ui.component.ViewBase;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.IFrame;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.spring.annotation.SpringComponent;
import com.vaadin.flow.spring.annotation.UIScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;

/**
 * SSH 终端页：xterm.js（成熟终端前端）+ WebSocket 桥接。
 * <p>
 * <b>页面本身没有任何终端逻辑</b>：真正的终端是 {@code VAADIN/static/terminal/terminal.html}
 * 里的 xterm.js（fit/search/serialize/webgl 等 addon 齐全），SSH 通道由
 * {@code com.sl.controller.SshHandler}（{@code /ws/ssh} 端点）以二进制帧原样转发
 * pty 字节流。本组件只做两件事：
 * <ol>
 *   <li>打开时把目标机器的连接信息登记进 {@link TerminalTokens} 换一个随机 token，
 *       塞进 iframe 的 URL —— websocket 建立时拿 token 换回连接信息，避免静态字段
 *       共享连接信息导致「A 用户开的终端连到 B 用户选的机器」；</li>
 *   <li>detach 时回收 token。</li>
 * </ol>
 * <p>
 * 为什么要走静态页 + iframe 而不是把 xterm 包成 Vaadin 组件：
 * xterm.js 的 DOM/事件模型和 Flow 的服务端状态树是两套世界，硬包一层要维护
 * client-server 桥接器（Element API + JSON 协议），而终端数据本来就是
 * 「websocket 二进制帧 → 浏览器直渲」的点对点流，绕经服务端状态树纯属浪费。
 * iframe 方案是旧项目（RemoteSSHXterm + terminal.html）验证过的成熟做法，原样保留。
 * <p>
 * 终端操作提示（原文来自旧页面）：完整 VT 实现，vim / top / tmux 可直接使用；
 * Ctrl+F 搜索缓冲区，Ctrl+Shift+C 复制选中，Ctrl+Shift+V 粘贴；
 * 卡在全屏程序里按 Esc 后输入 :q! 或 q。
 */
@SpringComponent
@UIScope
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class SshTerminalView extends ViewBase {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(SshTerminalView.class);

    /** 相对 UI 基址的终端静态页。IFrame 的相对路径按 /log/ 解析，不用拼 context-path。 */
    private static final String TERMINAL_PAGE = "VAADIN/static/terminal/terminal.html";

    /** 打开本页的入口（服务器列表行内按钮）负责注入；进来时必非空 */
    private transient ConnectionInfo presetHost;

    /** 当前页面持有的终端凭证，detach 时回收 */
    private String token;

    private IFrame frame;

    public void setPresetHost(ConnectionInfo info) {
        this.presetHost = info;
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        if (frame != null) {
            // 标签切换只是 setVisible，不会重复 onAttach；二次 attach 只发生在极端场景，防御一下
            return;
        }
        if (presetHost == null) {
            add(new Paragraph("缺少连接信息：请从「免登录服务器列表」的行内按钮打开本页。"));
            return;
        }
        token = TerminalTokens.register(presetHost);
        String url = TERMINAL_PAGE + "?token=" + token;

        frame = new IFrame(url);
        frame.setSizeFull();

        VerticalLayout fill = fill(frame);
        add(fill);
        setFlexGrow(1, fill);

        add(new Paragraph("终端为完整的 VT 实现，vim / top / tmux 等全屏程序可直接使用。"
                + "快捷键：Ctrl+F 搜索缓冲区，Ctrl+Shift+C 复制选中内容，Ctrl+Shift+V 粘贴；"
                + "若卡在全屏程序里无法退出，按 Esc 后输入 :q! 或 q 即可。"));
        log.info("打开 SSH 终端：{}，token={}", presetHost.getIdHost(), token);
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        // 关闭标签页立刻回收；「重新连接」复用同一 token 的能力在 iframe 内部自持，
        // 只要页面活着 token 一直有效（TerminalTokens.resolvePending 会续期）
        TerminalTokens.release(token);
        token = null;
        frame = null;
        super.onDetach(detachEvent);
    }
}
