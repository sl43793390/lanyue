package com.sl.ui;

import com.sl.security.LoginFailureHandler;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.login.LoginForm;
import com.vaadin.flow.component.login.LoginI18n;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.auth.AnonymousAllowed;

/**
 * 登录页：左侧品牌区 + 右侧登录卡片，窄屏自动收起品牌区
 * （媒体查询在 {@code themes/default/styles.css} 的 {@code .login-view} 段落里）。
 * <p>
 * 认证本身不在这里做——表单 POST 给 Spring Security 的 {@code /login} 端点，
 * 由 {@link com.sl.security.SecurityConfig} 配的那条链路处理。
 * 本页只负责两件事：
 * <ol>
 * <li>把界面文案换成中文（{@link LoginI18n}）；</li>
 * <li>把 {@link LoginFailureHandler} 写进 session 的**失败原因**取出来，
 * 用 {@code showErrorMessage} 显示，取完即删。</li>
 * </ol>
 */
@Route("login")
@PageTitle("登录")
@AnonymousAllowed
public class LoginView extends HorizontalLayout implements BeforeEnterObserver {

    private static final long serialVersionUID = 1L;

    /** 从 session 里读失败原因用的 key，与 LoginFailureHandler 保持一致 */
    private static final String ERROR_SESSION_KEY = LoginFailureHandler.SESSION_ATTR;

    private final LoginForm loginForm = new LoginForm();

    public LoginView() {
        addClassName("login-view");
        setSizeFull();
        setPadding(false);
        setSpacing(false);

        // 表单 POST 到 Spring Security 的 /login，相对路径由浏览器按当前 URL 解析
        loginForm.setAction("login");
        loginForm.setI18n(buildI18n());
        // 旧项目没有"忘记密码"流程，密码忘了只能找管理员重置，别放一个点了没反应的链接
        loginForm.setForgotPasswordButtonVisible(false);

        Component brand = buildBrandPanel();
        Component panel = buildLoginPanel();

        add(brand, panel);
        // 比例（品牌区 ≈1/3）完全由 styles.css 里 .login-brand/.login-panel 的
        // flex 声明控制。不要在这里再写 setFlexGrow/flex-basis：内联样式会覆盖
        // CSS 的 flex 简写，而且 basis:0 + min-content>0 会触发 Chrome 的
        // flex 因子归零规则，比例怎么调都不对（实测踩坑）。
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        boolean hasError = event.getLocation().getQueryParameters()
                .getParameters().containsKey("error");
        boolean loggedOut = event.getLocation().getQueryParameters()
                .getParameters().containsKey("logout");

        if (hasError) {
            String message = consumeFailureMessage();
            if (message != null) {
                // 具体原因（含"账号已禁用""账号已于 X 过期"）走这里，标题固定
                loginForm.showErrorMessage("登录失败", message);
            } else {
                // 直接访问 /login?error 或刷新页面时 session 里已经没有原因了，
                // 退回 i18n 里的通用文案
                loginForm.setError(true);
            }
        }
        if (loggedOut) {
            Notification notification = new Notification("已安全退出");
            notification.addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            notification.setPosition(Notification.Position.TOP_CENTER);
            notification.setDuration(2500);
            notification.open();
        }
    }

    /**
     * 取出并清除 session 里的登录失败原因。
     * <p>
     * 必须"取出即删"：Spring Security 失败后是 302 回 {@code /login?error}，
     * 用户按 F5 刷新时 URL 上的 {@code ?error} 还在，但已经不该再显示上一次的具体原因了。
     */
    private String consumeFailureMessage() {
        VaadinSession session = VaadinSession.getCurrent();
        if (session == null) {
            return null;
        }
        Object value = session.getSession().getAttribute(ERROR_SESSION_KEY);
        if (value == null) {
            return null;
        }
        session.getSession().removeAttribute(ERROR_SESSION_KEY);
        return value.toString();
    }

    /**
     * 中文界面文案。这里设的错误文案是**兜底**：
     * 有具体原因时会被 {@link #beforeEnter} 里的 {@code showErrorMessage} 覆盖。
     */
    private LoginI18n buildI18n() {
        LoginI18n i18n = LoginI18n.createDefault();

        LoginI18n.Form form = i18n.getForm();
        form.setTitle("账号登录");
        form.setUsername("用户名");
        form.setPassword("密码");
        form.setSubmit("登录");
        form.setForgotPassword("");
        i18n.setForm(form);

        LoginI18n.ErrorMessage error = i18n.getErrorMessage();
        error.setTitle("登录失败");
        error.setMessage("用户名或密码错误");
        i18n.setErrorMessage(error);

        return i18n;
    }

    private Component buildBrandPanel() {
        Div brand = new Div();
        brand.addClassName("login-brand");

        H1 name = new H1("运维管理平台");
        name.addClassName("login-brand-title");

        Paragraph slogan = new Paragraph("日志查看 · 文件管理 · 应用发布");
        slogan.addClassName("login-brand-slogan");

        Paragraph hint = new Paragraph(
                "把要运维的机器配好之后，开发同学打开浏览器就能看日志、传文件、发应用，"
                        + "不必拿到 Linux 登录凭据。");
        hint.addClassName("login-brand-hint");

        brand.add(name, slogan, hint);
        return brand;
    }

    private Component buildLoginPanel() {
        Div panel = new Div();
        panel.addClassName("login-panel");

        panel.add(loginForm);
        return panel;
    }
}
