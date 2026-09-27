package com.sl.ui.component;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasSize;
import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinServletRequest;
import com.vaadin.flow.shared.Registration;
import elemental.json.Json;
import elemental.json.JsonObject;
import elemental.json.JsonValue;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * draw.io 内嵌编辑器（其他工具 → drawio绘图）。
 * <p>
 * 包装一个 iframe，加载自托管的 drawio 静态版（见 StaticResourceConfig 的
 * {@code /drawio/**} 映射），用官方 embed 模式 + JSON 消息协议（{@code proto=json}）
 * 与服务端双向通信。之所以选 iframe 而不是像 CodeEditor 那样包 Lit 组件：
 * drawio 是一个完整的单页应用（几十 MB 静态资源、独立的事件体系），拆开重组成
 * Web Component 的成本远高于 iframe 隔离——这和 SSH 终端选 terminal.html + iframe
 * 是同一个判断（见 SshTerminalView 类注释）。
 *
 * <h2>协议握手（drawio embed 模式）</h2>
 * <ol>
 * <li>iframe 加载完成，drawio 主动 postMessage 一条 {@code {event: 'init'}}；</li>
 * <li>父页面此后可随时发 {@code {action: 'load', xml}} 载入图表，
 * 或 {@code {action: 'template'}} 打开模板选择；</li>
 * <li>用户在编辑器里点保存（或 Ctrl+S）时，drawio 回发
 * {@code {event: 'save', xml}}——这是唯一的取图途径，协议没有
 * "把当前 XML 给我"的反向动作。</li>
 * </ol>
 *
 * <h2>消息桥的实现</h2>
 * 客户端 JS 装一个 {@code window.message} 监听器，收到事件后通过
 * {@code $server} 回调（{@link ClientCallable}）叫到服务端——不走
 * {@code CustomEvent + element.addEventListener}，因为事件数据表达式
 * {@code event.detail.xml} 的取值路径在 Flow 里没有跨版本保证，而
 * {@code $server} 是文档明确支持的通道。服务端往 drawio 发消息则通过
 * {@code Page.executeJs} 调 {@code window.__drawioSend}（见
 * {@link #sendAction} 的注释，不要改回 Element.executeJs）。
 *
 * <h2>时序容错</h2>
 * {@code load} 可能发生在 init 之前（用户手快双击列表，而 iframe 还在加载）。
 * 未就绪期间的 load 请求在服务端排队，init 到达后按最后一条补发。
 */
@Tag("div")
public class DrawioEmbed extends Component implements HasSize {

    private static final Logger log = LoggerFactory.getLogger(DrawioEmbed.class);

    /**
     * embed 编辑器页面（相对 context 根）。lang=zh 用中文界面；
     * spin=1 加载期显示转圈；noExitBtn=1 去掉"退出"按钮——本页是标签页内嵌，
     * 没有"退出后去哪"的合理去处，用户直接关标签即可。
     */
    public static final String EMBED_PATH = "drawio/index.html?embed=1&proto=json&lang=zh&spin=1&noExitBtn=1";

    private final Element iframe;

    /** 桥只装一次（attach 可能因标签页切换触发多次） */
    private boolean bridgeInstalled = false;

    /** drawio 是否已回发 init */
    private boolean clientReady = false;

    /** init 前收到的排队请求：待载入的 XML；pendingTemplate=true 表示要打开模板选择 */
    private String pendingXml;
    private boolean pendingTemplate = false;
    /** 排队请求是否带 autosave 标记（与 loadXml 的入参一致，见 loadXml 注释） */
    private boolean pendingAutosave = false;

    private final List<Consumer<String>> saveListeners = new ArrayList<>();
    /** autosave 事件监听：宿主用它做「有未保存修改」标记（见 loadXml 注释） */
    private final List<Consumer<String>> autosaveListeners = new ArrayList<>();
    private final List<Runnable> initListeners = new ArrayList<>();
    private final List<Runnable> exitListeners = new ArrayList<>();

    public DrawioEmbed() {
        setSizeFull();
        getStyle().set("display", "block");
        iframe = new Element("iframe");
        iframe.setAttribute("title", "drawio editor");
        iframe.getStyle().set("width", "100%")
                .set("height", "100%")
                .set("border", "none")
                .set("display", "block");
        getElement().appendChild(iframe);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        if (bridgeInstalled) {
            return;
        }
        bridgeInstalled = true;
        // src 必须带 context-path（/log），从当前请求的 contextPath 推，不写死。
        // onAttach 一定发生在某个请求线程里，VaadinService.getCurrentRequest() 不会为 null
        String contextPath = "";
        VaadinRequest request = VaadinService.getCurrentRequest();
        if (request instanceof VaadinServletRequest servletRequest) {
            contextPath = ((HttpServletRequest) servletRequest.getRequest()).getContextPath();
        }
        iframe.setAttribute("src", contextPath + "/" + EMBED_PATH);
        installBridge();
    }

    /**
     * 客户端消息桥。this 即本组件根 div；drawio 的消息跨 iframe 边界后
     * 是全局 window 事件，所以监听器挂在 window 上，靠来源字段判断内容。
     */
    private void installBridge() {
        getElement().executeJs("""
                const host = this;
                if (host.__drawioBridge) return;
                host.__drawioBridge = true;
                const frame = host.querySelector('iframe');
                window.addEventListener('message', (e) => {
                    // proto=json 模式下 drawio 发出的消息是 JSON.stringify 后的字符串
                    // （见 drawio/js/diagramly/EditorUi.js：JSON.stringify({event:'init'})），
                    // 必须先解析再判断；对象形态一并接受（测试脚本模拟消息用对象）。
                    let msg = e.data;
                    if (typeof msg === 'string') {
                        try { msg = JSON.parse(msg); } catch (err) { return; }
                    }
                    if (!msg || typeof msg !== 'object') return;
                    if (msg.event === 'init') {
                        host.$server.onDrawioInit();
                        // init 后由客户端拉取待载入动作（$server 返回值通道双向可靠；
                        // 服务端在 ClientCallable 处理期间 executeJs 派发的脚本实测不会执行）
                        try {
                            const p = host.$server.consumePendingAction();
                            if (p && p.then) {
                                p.then((res) => {
                                    if (res) {
                                        window.__drawioSend(res);
                                    }
                                }).catch((err) => console.warn('consumePendingAction failed', err));
                            }
                        } catch (err) { console.warn('consumePendingAction failed', err); }
                    } else if (msg.event === 'save'
                            && typeof msg.xml === 'string' && msg.xml.length > 0) {
                        host.$server.onDrawioSave(msg.xml);
                    } else if (msg.event === 'autosave'
                            && typeof msg.xml === 'string' && msg.xml.length > 0) {
                        host.$server.onDrawioAutosave(msg.xml);
                    } else if (msg.event === 'exit') {
                        host.$server.onDrawioExit();
                    }
                });
                host.__drawioSend = window.__drawioSend = (msg) => {
                    // proto=json 下 drawio 入站只认 JSON 字符串（EditorUi.installMessageHandler
                    // 里 JSON.parse(evt.data)，对象会抛错被静默丢弃），对象形态在此统一序列化
                    try {
                        const payload = (typeof msg === 'string') ? msg : JSON.stringify(msg);
                        frame.contentWindow.postMessage(payload, '*');
                    }
                    catch (err) { console.warn('drawio postMessage failed', err); }
                };
                // flush 桥就绪前服务端已排队的消息
                if (window.__drawioPending && window.__drawioPending.length) {
                    window.__drawioPending.splice(0).forEach((m) => window.__drawioSend(m));
                }
                """);
    }

    /* ------------------------------------------------------------------ *
     * 服务端 API
     * ------------------------------------------------------------------ */

    /**
     * 请求 drawio 载入一张图。{@code xml} 传 {@code null} 表示打开模板选择器。
     * <p>
     * 双通道投递：写入 {@code pendingXml} 信箱（客户端 init 后经
     * {@link #consumePendingAction} 拉取，兜底覆盖 iframe 重载等丢失场景），
     * 已就绪时再尽力推送一条（常态双击打开走这条，延迟最低）。
     * <p>
     * load 消息带 {@code autosave=1}：drawio 收到后会给图形模型挂 change 监听，
     * 每次改动（拖拽/连线/改文字）回发一条 {@code autosave} 事件——宿主页面拿它
     * 做「未保存」标记，不用额外协议就能感知用户画了东西（见 FlowchartView
     * 的切换确认框）。save/autosave 事件在桥里分流，互不影响。
     */
    public void loadXml(String xml) {
        pendingXml = xml;
        pendingTemplate = null == xml;
        pendingAutosave = null != xml;
        if (clientReady) {
            sendAction(null == xml ? "template" : "load", xml, null != xml);
        }
    }

    /** 图表保存事件（用户点保存按钮 / Ctrl+S / 自动保存），参数是 mxfile XML 全文。 */
    public Registration addSaveListener(Consumer<String> listener) {
        saveListeners.add(listener);
        return Registration.once(() -> saveListeners.remove(listener));
    }

    /**
     * 图表改动事件（load 时带 {@code autosave=1}，drawio 在每次图形模型变更后
     * 回发），参数是当前 mxfile XML 全文。与保存无关，只表示「编辑器里有
     * 尚未落库的内容」。
     */
    public Registration addAutosaveListener(Consumer<String> listener) {
        autosaveListeners.add(listener);
        return Registration.once(() -> autosaveListeners.remove(listener));
    }

    /** drawio 就绪（init 已回发，此后 load 即时生效）。 */
    public Registration addInitListener(Runnable listener) {
        initListeners.add(listener);
        return Registration.once(() -> initListeners.remove(listener));
    }

    /** drawio 编辑器退出（目前配置下不会触发，noExitBtn=1；兜底留给以后放开配置用）。 */
    public Registration addExitListener(Runnable listener) {
        exitListeners.add(listener);
        return Registration.once(() -> exitListeners.remove(listener));
    }

    /** drawio 就绪信号（init 可多次到达，如 iframe 重载后；每次都让客户端重新拉取信箱）。 */
    @ClientCallable
    private void onDrawioInit() {
        clientReady = true;
        for (Runnable listener : initListeners) {
            listener.run();
        }
    }

    /**
     * 客户端 init 后主动拉取待载入动作。之所以由客户端拉而不是服务端推：
     * ClientCallable 处理期间 executeJs 派发的脚本实测不会执行（原因见
     * {@link #sendAction} 注释），而 $server 调用的返回值通道是可靠双向的。
     * 返回 {@code null} 表示信箱为空。
     */
    @ClientCallable
    private JsonValue consumePendingAction() {
        JsonObject payload = Json.createObject();
        if (pendingTemplate) {
            pendingTemplate = false;
            pendingXml = null;
            payload.put("action", "template");
            return payload;
        }
        String xml = pendingXml;
        pendingXml = null;
        if (xml == null) {
            return Json.createNull();
        }
        payload.put("action", "load");
        payload.put("xml", xml);
        if (pendingAutosave) {
            payload.put("autosave", 1);
            pendingAutosave = false;
        }
        return payload;
    }

    @ClientCallable
    private void onDrawioSave(String xml) {
        for (Consumer<String> listener : saveListeners) {
            try {
                listener.accept(xml);
            } catch (Exception e) {
                log.error("处理 drawio 保存事件失败", e);
            }
        }
    }

    @ClientCallable
    private void onDrawioAutosave(String xml) {
        for (Consumer<String> listener : autosaveListeners) {
            try {
                listener.accept(xml);
            } catch (Exception e) {
                log.error("处理 drawio 改动事件失败", e);
            }
        }
    }

    @ClientCallable
    private void onDrawioExit() {
        for (Runnable listener : exitListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                log.error("处理 drawio 退出事件失败", e);
            }
        }
    }

    private void sendAction(String action, String xml, boolean autosave) {
        // 用 Page.executeJs + window 级函数，不用 Element.executeJs（this/$元素参数）。
        // 原因：客户端处理带 StateNode 参数的调用时会做 isBound 检查，若节点首次绑定
        // 时被判为不可见（VISIBILITY_BOUND_PROPERTY=false），调用会被挂到
        // domNodeSetListener 上永久搁置且无任何报错——表现为 load 永远到不了 drawio。
        // Page.executeJs 的参数全是标量，客户端直接执行，无此坑。
        getUI().ifPresent(ui -> ui.getPage().executeJs("""
                const msg = {action: $0};
                if ($1 !== null) msg.xml = $1;
                if ($2) msg.autosave = 1;
                if (window.__drawioSend) {
                    window.__drawioSend(msg);
                } else {
                    (window.__drawioPending = window.__drawioPending || []).push(msg);
                }
                """, action, xml, autosave));
    }
}
