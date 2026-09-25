package com.sl.ui;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

/**
 * 使用手册（原「系统概览」）。
 * <p>
 * 登录后看到的第一个标签页。旧版是骨架自检页（登录信息 / 数据库行数 / 迁移进度），
 * 框架稳定之后这些内容对使用者没有价值，按需求改写成一份面向使用者的手册：
 * 运行环境、技术框架、配置与运行、功能说明、常见问题。
 * <p>
 * 约定：所有功能页都是 {@code @Service} + {@code @Scope("prototype")}。
 * **不能改成单例**——页面持有会话内状态（当前用户展示），单例会让多个用户互相串数据。
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

        H2 title = new H2("使用手册");
        title.addClassName("home-title");
        add(title);

        Paragraph subtitle = new Paragraph(
                "揽月运维管理平台：把日志搜索、应用启停、服务器运维、Docker 管理收进一个网页。"
                        + "本页说明运行环境、配置方式与各功能模块的用法。");
        subtitle.addClassName("home-subtitle");
        add(subtitle);

        FlexLayout cards = new FlexLayout();
        cards.addClassName("home-cards");
        cards.setFlexWrap(FlexLayout.FlexWrap.WRAP);
        cards.add(buildIntroCard());
        cards.add(buildEnvCard());
        cards.add(buildFrameworkCard());
        cards.add(buildRunCard());
        cards.add(buildFeatureCard());
        cards.add(buildTipsCard());
        add(cards);
    }

    // ------------------------------------------------------------------
    // 卡片
    // ------------------------------------------------------------------

    private Div buildIntroCard() {
        Div card = card("平台简介");
        card.add(p("平台面向运维与开发人员，围绕「一台浏览器管所有服务器」设计："));
        card.add(bullet("日志：本地 / 远程服务器的日志文件搜索、预览与下载；"));
        card.add(bullet("应用：jar 包、Tomcat、通用服务的启停、状态查看与文件上传；"));
        card.add(bullet("远程：免登录服务器列表直达各远程功能页，内置 Web SSH 终端；"));
        card.add(bullet("容器：Docker 容器 / 镜像管理、Compose 项目编排与聚合日志。"));
        return card;
    }

    private Div buildEnvCard() {
        Div card = card("运行环境");
        card.add(row("Java", "JDK 21 及以上（当前运行 " + System.getProperty("java.version") + "）"));
        card.add(row("操作系统", System.getProperty("os.name") + " " + System.getProperty("os.arch")
                + "（Windows / Linux 均可部署）"));
        card.add(row("数据库", "SQLite（默认，零配置）或 MySQL 8（profile=mysql）"));
        card.add(row("访问地址", "http://<服务器IP>:9095/log"));
        card.add(row("浏览器", "Chrome / Edge 等现代浏览器（终端、上传功能依赖较新内核）"));
        return card;
    }

    private Div buildFrameworkCard() {
        Div card = card("技术框架");
        card.add(row("后端", "Spring Boot 3.5 + Spring Security（登录认证，密码 SM3/BCrypt 双格式兼容）"));
        card.add(row("前端", "Vaadin 24（服务端驱动的 Web UI，无需单独的前端工程）"));
        card.add(row("数据访问", "MyBatis-Plus 3.5，方言自动适配 SQLite / MySQL"));
        card.add(row("日志", "Log4j2，日志文件在运行目录 logs/ 下"));
        card.add(row("终端", "xterm.js + WebSocket（SSH 终端与容器日志实时输出）"));
        return card;
    }

    private Div buildRunCard() {
        Div card = card("配置与运行");
        card.add(bullet("配置文件：src/main/resources/application.properties，"
                + "端口 server.port=9095、上下文 server.servlet.context-path=/log；"));
        card.add(bullet("开发运行：项目目录执行 mvn spring-boot:run（须 JDK 21）；"));
        card.add(bullet("生产运行：mvn package -Pproduction 打包后 java -jar 启动；"));
        card.add(bullet("切换 MySQL：加启动参数 --spring.profiles.active=mysql，"
                + "并按 demo-mysql8.sql 建库；"));
        card.add(bullet("首次使用：启动时自动建表（SQLite 默认库文件为运行目录下 demo.db），"
                + "内置 admin 账号，登录后请尽快修改密码；demo.sql 仅用于灌演示数据；"));
        card.add(bullet("日志输出：运行目录 logs/ 下，排查问题先看 logViewer.log。"));
        return card;
    }

    private Div buildFeatureCard() {
        Div card = card("功能说明");
        card.add(sectionLine("本地应用管理"));
        card.add(bullet("本地日志搜索：搜索本机指定路径的日志文件，后缀白名单在 "
                + "fileSuffix.conf 里可增减；支持预览、下载；"));
        card.add(bullet("jar项目管理：登记 jar 包的所在路径与启动命令后即可一键启停。"
                + "启动优先级：JVM/Jar 参数 > 自定义启动命令 > 默认 server.sh 脚本；"
                + "JVM 参数必须写在 -jar 之前；"));
        card.add(bullet("Tomcat管理：登记 Tomcat 主目录后可启停、传 war 包（默认进 webapps）；"));
        card.add(bullet("通用项目管理：启动/停止/重启/刷新/查状态五条命令完全自定义，"
                + "适合 nginx、redis 这类没有统一脚本的服务。"));

        card.add(sectionLine("远程应用管理"));
        card.add(bullet("免登录服务器列表：维护可直连的服务器（数据库或 remoteServerList.conf），"
                + "行内按钮直达 Docker、应用管理、SSH 终端、文件管理、指标监控；"));
        card.add(bullet("远程日志搜索：SSH 到目标机按关键字搜索日志；"));
        card.add(bullet("SSH 终端：浏览器内完整终端，vim / top 可用，Ctrl+F 搜索缓冲区。"));

        card.add(sectionLine("Docker 管理"));
        card.add(bullet("容器和镜像管理：选择目标服务器连接后管理容器与镜像；"
                + "容器「详情」弹窗内含实时滚动日志终端（自动执行 docker logs -f --tail 500）"
                + "与 docker inspect 原文；"));
        card.add(bullet("Docker-Compose管理：按项目目录管理 compose 服务，"
                + "支持多容器聚合日志（按服务着色）。"));

        card.add(sectionLine("其他"));
        card.add(bullet("加密工具：常用加解密算法的在线小工具；"));
        card.add(bullet("用户管理：维护账号与权限（ADD / UPDATE / DELETE / UPLOAD / QUERY）。"));
        return card;
    }

    private Div buildTipsCard() {
        Div card = card("常见问题");
        card.add(bullet("启动 jar 后看不到日志？启动输出重定向到项目目录 app.log（或启动命令里"
                + "指定的文件），到「本地日志搜索」里查看；"));
        card.add(bullet("远程机器连不上？先在「免登录服务器列表」点保存时勾选的验证，"
                + "确认账号、端口、私钥正确，且目标机放行 22 端口；"));
        card.add(bullet("Docker 页面提示服务未运行？页面会给出诊断与启动命令，"
                + "按提示在目标机上启动 docker 后点「检测服务状态」；"));
        card.add(bullet("上传按钮变灰？上传控件一次只收一个文件，传完会自动清空列表，"
                + "如仍未恢复请刷新页面。"));
        return card;
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private Div card(String heading) {
        Div card = new Div();
        card.addClassName("home-card");

        H2 h = new H2(heading);
        h.addClassName("home-card-title");
        card.add(h);
        return card;
    }

    private Paragraph p(String text) {
        Paragraph paragraph = new Paragraph(text);
        paragraph.addClassName("home-text");
        return paragraph;
    }

    private Div bullet(String text) {
        Div item = new Div();
        item.addClassName("home-bullet");
        Span dot = new Span("·");
        dot.addClassName("home-bullet-dot");
        Span label = new Span(text);
        label.addClassName("home-bullet-text");
        item.add(dot, label);
        return item;
    }

    private Div row(String key, String value) {
        Div line = new Div();
        line.addClassName("home-row");
        Span keySpan = new Span(key);
        keySpan.addClassName("home-row-key");
        Span valueSpan = new Span(value);
        valueSpan.addClassName("home-row-value");
        line.add(keySpan, valueSpan);
        return line;
    }

    private Div sectionLine(String text) {
        Div line = new Div();
        H3 h = new H3(text);
        h.addClassName("home-section");
        line.add(h);
        return line;
    }
}
