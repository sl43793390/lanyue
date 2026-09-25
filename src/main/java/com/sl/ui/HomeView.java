package com.sl.ui;

import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

/**
 * 使用手册（原「系统概览」）。
 * <p>
 * 登录后看到的第一个标签页。整页是一份自包含的 HTML（{@link Html} 组件注入，
 * 内联 {@code <style>}，类名统一 lm- 前缀避免与主题冲突）：
 * 蓝色为主、浅绿点缀的双色卡片版式，比逐个拼 Vaadin 组件自由得多，
 * 内容更新只动一个字符串，排版不会散。
 * <p>
 * 约定：所有功能页都是 {@code @Service} + {@code @Scope("prototype")}。
 * **不能改成单例**——页面持有会话内状态，单例会让多个用户互相串数据。
 */
@Service
@Scope("prototype")
public class HomeView extends VerticalLayout {

    private static final long serialVersionUID = 1L;

    public HomeView() {
        addClassName("home-view");
        setSizeFull();
        setPadding(true);
        setSpacing(true);

        Html manual = new Html(manualHtml());
        add(manual);
    }

    // ------------------------------------------------------------------
    // 手册 HTML
    // ------------------------------------------------------------------

    private String manualHtml() {
        return """
                <div class='lm-root'>
                <style>
                .lm-root { max-width: 1080px; margin: 0 auto; color: #1f2937;
                           font-size: 14px; line-height: 1.65; }
                .lm-root * { box-sizing: border-box; }
                .lm-hero { background: linear-gradient(120deg, #1d4ed8 0%, #2563eb 55%, #10b981 130%);
                           border-radius: 14px; padding: 30px 34px; color: #fff; margin-bottom: 22px; }
                .lm-hero h1 { margin: 0 0 8px; font-size: 26px; font-weight: 700; letter-spacing: 1px; }
                .lm-hero p { margin: 0 0 14px; font-size: 14px; opacity: .92; max-width: 640px; }
                .lm-chips { display: flex; flex-wrap: wrap; gap: 8px; }
                .lm-chip { background: rgba(255,255,255,.16); border: 1px solid rgba(255,255,255,.35);
                           border-radius: 999px; padding: 3px 12px; font-size: 12px; }
                .lm-sec-title { display: flex; align-items: center; gap: 10px;
                                margin: 26px 0 12px; font-size: 17px; font-weight: 700; color: #111827; }
                .lm-sec-title::before { content: ''; width: 4px; height: 18px; border-radius: 2px;
                                        background: linear-gradient(180deg, #2563eb, #10b981); }
                .lm-steps { display: grid; grid-template-columns: repeat(auto-fit, minmax(230px, 1fr)); gap: 12px; }
                .lm-step { background: #fff; border: 1px solid #e5e7eb; border-radius: 10px; padding: 14px 16px;
                           border-top: 3px solid #2563eb; }
                .lm-step:nth-child(2) { border-top-color: #34d399; }
                .lm-step:nth-child(3) { border-top-color: #60a5fa; }
                .lm-step:nth-child(4) { border-top-color: #10b981; }
                .lm-step b { display: block; margin-bottom: 4px; color: #111827; }
                .lm-step span { color: #4b5563; font-size: 13px; }
                .lm-no { display: inline-block; width: 20px; height: 20px; line-height: 20px; text-align: center;
                         border-radius: 50%; background: #2563eb; color: #fff; font-size: 12px; margin-right: 6px; }
                .lm-cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 14px; }
                .lm-card { background: #fff; border: 1px solid #e5e7eb; border-radius: 12px; padding: 16px 18px;
                           border-top: 3px solid #2563eb; box-shadow: 0 1px 3px rgba(31,41,55,.06); }
                .lm-card.green { border-top-color: #10b981; }
                .lm-card.sky { border-top-color: #60a5fa; }
                .lm-card .lm-icon { font-size: 20px; }
                .lm-card h3 { margin: 6px 0 6px; font-size: 15px; color: #111827; }
                .lm-card p { margin: 0; color: #4b5563; font-size: 13px; }
                .lm-cols { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 14px; }
                .lm-table { background: #fff; border: 1px solid #e5e7eb; border-radius: 12px; overflow: hidden; }
                .lm-table .lm-tt { padding: 10px 16px; font-weight: 600; color: #111827; font-size: 14px;
                                   background: #f0fdf4; border-bottom: 1px solid #e5e7eb; }
                .lm-table.blue .lm-tt { background: #eff6ff; }
                .lm-table table { width: 100%; border-collapse: collapse; font-size: 13px; }
                .lm-table td { padding: 8px 16px; border-bottom: 1px dashed #e5e7eb; color: #374151; vertical-align: top; }
                .lm-table tr:last-child td { border-bottom: none; }
                .lm-table td.lm-k { width: 96px; color: #6b7280; white-space: nowrap; }
                .lm-list { background: #fff; border: 1px solid #e5e7eb; border-radius: 12px; padding: 6px 18px; }
                .lm-list ul { margin: 8px 0; padding-left: 18px; }
                .lm-list li { margin: 6px 0; color: #374151; font-size: 13px; }
                .lm-badge { display: inline-block; padding: 1px 9px; border-radius: 999px; font-size: 12px;
                            margin-right: 6px; white-space: nowrap; }
                .lm-badge.b { background: #dbeafe; color: #1d4ed8; }
                .lm-badge.g { background: #d1fae5; color: #047857; }
                .lm-badge.s { background: #e0f2fe; color: #0369a1; }
                .lm-list code, .lm-faq code { background: #f3f4f6; border: 1px solid #e5e7eb; border-radius: 4px;
                                              padding: 1px 5px; font-size: 12px; color: #1d4ed8; }
                .lm-faq details { background: #fff; border: 1px solid #e5e7eb; border-radius: 10px;
                                  padding: 10px 16px; margin: 8px 0; }
                .lm-faq summary { cursor: pointer; font-weight: 600; color: #111827; font-size: 14px; }
                .lm-faq details p { margin: 8px 0 2px; color: #4b5563; font-size: 13px; }
                .lm-foot { margin: 22px 0 8px; text-align: center; color: #9ca3af; font-size: 12px; }
                </style>

                <div class='lm-hero'>
                  <h1>揽月运维管理平台 · 使用手册</h1>
                  <p>把日志搜索、应用启停、服务器运维、Docker 管理收进一个网页。本页说明运行环境、配置方式与各功能模块的用法，新用户按「快速上手」四步即可开始使用。</p>
                  <div class='lm-chips'>
                    <span class='lm-chip'>访问地址：http://&lt;服务器IP&gt;:9095/log</span>
                    <span class='lm-chip'>运行环境：Java __JAVA__</span>
                    <span class='lm-chip'>部署系统：__OS__</span>
                  </div>
                </div>

                <div class='lm-sec-title'>快速上手</div>
                <div class='lm-steps'>
                  <div class='lm-step'><b><span class='lm-no'>1</span>登录平台</b><span>浏览器打开访问地址，使用管理员分配的账号登录，首次登录后请尽快修改密码。</span></div>
                  <div class='lm-step'><b><span class='lm-no'>2</span>登记服务器</b><span>进入「免登录服务器列表」，可先「新建分组」再「添加机器」，添加时会做一次真实连通性验证。</span></div>
                  <div class='lm-step'><b><span class='lm-no'>3</span>直达功能页</b><span>在机器所在行点行内按钮，直达 Docker、应用管理、SSH 终端、文件管理或指标监控。</span></div>
                  <div class='lm-step'><b><span class='lm-no'>4</span>查日志、管应用</b><span>本地或远程日志搜索按关键字定位问题；本地应用管理负责 jar、Tomcat 与通用服务的启停。</span></div>
                </div>

                <div class='lm-sec-title'>平台能力</div>
                <div class='lm-cards'>
                  <div class='lm-card'><div class='lm-icon'>📂</div><h3>日志</h3><p>本地 / 远程服务器的日志文件搜索、预览与下载。</p></div>
                  <div class='lm-card green'><div class='lm-icon'>🚀</div><h3>应用</h3><p>jar 包、Tomcat、通用服务的启停、状态查看与文件上传。</p></div>
                  <div class='lm-card sky'><div class='lm-icon'>🖥️</div><h3>远程</h3><p>免登录服务器列表按分组管理机器，行内直达各功能页，内置 Web SSH 终端。</p></div>
                  <div class='lm-card green'><div class='lm-icon'>🐳</div><h3>容器</h3><p>Docker 容器 / 镜像管理、Compose 项目编排与聚合日志。</p></div>
                </div>

                <div class='lm-sec-title'>运行环境与技术框架</div>
                <div class='lm-cols'>
                  __ENV_TABLE__
                  __FRAMEWORK_TABLE__
                </div>

                <div class='lm-sec-title'>配置与运行</div>
                <div class='lm-list'>
                  <ul>
                    <li>配置文件：<code>application.properties</code>，端口 <code>server.port=9095</code>、上下文 <code>server.servlet.context-path=/log</code>；</li>
                    <li>开发运行：项目目录执行 <code>mvn spring-boot:run</code>（须 JDK 21）；</li>
                    <li>生产运行：<code>mvn package -Pproduction</code> 打包后 <code>java -jar</code> 启动；</li>
                    <li>切换 MySQL：加启动参数 <code>--spring.profiles.active=mysql</code>，并按 <code>demo-mysql8.sql</code> 建库；</li>
                    <li>首次使用：启动时自动建表（SQLite 默认库文件为运行目录下 <code>demo.db</code>），内置 <code>admin</code> 账号，登录后请尽快修改密码；<code>demo.sql</code> 仅用于灌演示数据；</li>
                    <li>日志输出：运行目录 <code>logs/</code> 下，排查问题先看 <code>logViewer.log</code>。</li>
                  </ul>
                </div>

                <div class='lm-sec-title'>功能说明</div>
                <div class='lm-list'>
                  <ul>
                    <li><span class='lm-badge b'>本地应用管理</span><b>本地日志搜索</b>：搜索本机指定路径的日志文件，后缀白名单在 <code>fileSuffix.conf</code> 里可增减；支持预览、下载；</li>
                    <li><span class='lm-badge b'>本地应用管理</span><b>jar项目管理</b>：登记 jar 包的所在路径与启动命令后即可一键启停。启动优先级：JVM/Jar 参数 &gt; 自定义启动命令 &gt; 默认 server.sh 脚本；JVM 参数必须写在 -jar 之前；</li>
                    <li><span class='lm-badge b'>本地应用管理</span><b>Tomcat管理</b>：登记 Tomcat 主目录后可启停、传 war 包（默认进 webapps）；</li>
                    <li><span class='lm-badge b'>本地应用管理</span><b>通用项目管理</b>：启动/停止/重启/刷新/查状态五条命令完全自定义，适合 nginx、redis 这类没有统一脚本的服务；</li>
                    <li><span class='lm-badge g'>远程应用管理</span><b>免登录服务器列表</b>：维护可直连的服务器（数据库或 remoteServerList.conf），支持<b>分组管理</b>——工具栏可「新建分组」「按分组筛选」，行内按钮直达 Docker、应用管理、SSH 终端、文件管理、指标监控；</li>
                    <li><span class='lm-badge g'>远程应用管理</span><b>远程日志搜索</b>：SSH 到目标机按关键字搜索日志；</li>
                    <li><span class='lm-badge g'>远程应用管理</span><b>SSH 终端</b>：浏览器内完整终端，vim / top 可用，Ctrl+F 搜索缓冲区；</li>
                    <li><span class='lm-badge s'>Docker 管理</span><b>容器和镜像管理</b>：选择目标服务器连接后管理容器与镜像；容器「详情」弹窗内含实时滚动日志终端（自动执行 docker logs -f --tail 500）与 docker inspect 原文；</li>
                    <li><span class='lm-badge s'>Docker 管理</span><b>Docker-Compose管理</b>：按项目目录管理 compose 服务，支持多容器聚合日志（按服务着色）；</li>
                    <li><span class='lm-badge s'>其他</span><b>加密工具</b>：常用加解密算法的在线小工具；<b>用户管理</b>：维护账号与权限（ADD / UPDATE / DELETE / UPLOAD / QUERY）。</li>
                  </ul>
                </div>

                <div class='lm-sec-title'>分组管理（免登录服务器列表）</div>
                <div class='lm-list'>
                  <ul>
                    <li><b>新建分组</b>：工具栏点「新建分组」，输入名称即可创建（如：生产环境 / 测试环境）；</li>
                    <li><b>机器归属</b>：「添加机器」弹窗顶部可选择分组，不选则归入「默认分组」；配置文件 remoteServerList.conf 里的机器固定归默认分组；</li>
                    <li><b>按分组筛选</b>：工具栏右侧下拉框选择分组后，页面只显示该分组的机器，清空选择即恢复全部；</li>
                    <li><b>卡片视图</b>：每个分组一张带浅色边框的卡片，组名在卡片顶部并显示机器数量，组内一台机器一行，备注超长时悬浮显示全文。</li>
                  </ul>
                </div>

                <div class='lm-sec-title'>常见问题</div>
                <div class='lm-faq'>
                  <details><summary>启动 jar 后看不到日志？</summary>
                    <p>启动输出重定向到项目目录 <code>app.log</code>（或启动命令里指定的文件），到「本地日志搜索」里查看。</p></details>
                  <details><summary>远程机器连不上？</summary>
                    <p>先确认添加时验证是通过的：账号、端口、私钥正确，且目标机放行 22 端口；机器加进「免登录服务器列表」后点行内「SSH 终端」最快定位是网络问题还是认证问题。</p></details>
                  <details><summary>Docker 页面提示服务未运行？</summary>
                    <p>页面会给出诊断与启动命令，按提示在目标机上启动 docker 后点「检测服务状态」。</p></details>
                  <details><summary>上传按钮变灰？</summary>
                    <p>上传控件一次只收一个文件，传完会自动清空列表，如仍未恢复请刷新页面。</p></details>
                  <details><summary>想从 SQLite 切到 MySQL 8？</summary>
                    <p>先按 <code>demo-mysql8.sql</code> 建库建表，再加启动参数 <code>--spring.profiles.active=mysql</code> 并在 <code>application-mysql.properties</code> 里配好连接。已有旧库的 desc 列改名等兼容迁移会在启动时自动执行。</p></details>
                  <details><summary>机器太多看着乱？</summary>
                    <p>用「新建分组」把机器按环境或用途分好，再用工具栏右侧的分组下拉框切换查看；默认分组收留没分组的机器。</p></details>
                </div>

                <div class='lm-foot'>揽月运维管理平台 · 使用手册 · 有问题先看本页，再看 logs/logViewer.log</div>
                </div>
                """
                .replace("__JAVA__", System.getProperty("java.version"))
                .replace("__OS__", System.getProperty("os.name") + " " + System.getProperty("os.arch"))
                .replace("__ENV_TABLE__", envTable())
                .replace("__FRAMEWORK_TABLE__", frameworkTable());
    }

    private String envTable() {
        return """
                <div class='lm-table blue'>
                  <div class='lm-tt'>运行环境</div>
                  <table>
                    __ROWS__
                  </table>
                </div>
                """
                .replace("__ROWS__", String.join("\n",
                        row("Java", "JDK 21 及以上（当前运行 " + System.getProperty("java.version") + "）"),
                        row("操作系统", System.getProperty("os.name") + " " + System.getProperty("os.arch")
                                + "（Windows / Linux 均可部署）"),
                        row("数据库", "SQLite（默认，零配置）或 MySQL 8（profile=mysql）"),
                        row("访问地址", "http://&lt;服务器IP&gt;:9095/log"),
                        row("浏览器", "Chrome / Edge 等现代浏览器（终端、上传功能依赖较新内核）")));
    }

    private String frameworkTable() {
        return """
                <div class='lm-table'>
                  <div class='lm-tt'>技术框架</div>
                  <table>
                    __ROWS__
                  </table>
                </div>
                """
                .replace("__ROWS__", String.join("\n",
                        row("后端", "Spring Boot 3.5 + Spring Security（登录认证，密码 SM3/BCrypt 双格式兼容）"),
                        row("前端", "Vaadin 24（服务端驱动的 Web UI，无需单独的前端工程）"),
                        row("数据访问", "MyBatis-Plus 3.5，方言自动适配 SQLite / MySQL"),
                        row("日志", "Log4j2，日志文件在运行目录 logs/ 下"),
                        row("终端", "xterm.js + WebSocket（SSH 终端与容器日志实时输出）")));
    }

    private static String row(String key, String value) {
        return "<tr><td class='lm-k'>" + key + "</td><td>" + value + "</td></tr>";
    }
}
