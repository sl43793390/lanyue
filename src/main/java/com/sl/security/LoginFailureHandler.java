package com.sl.security;

import cn.hutool.core.date.DateUtil;
import com.sl.entity.User;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AccountExpiredException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;

import java.io.IOException;

/**
 * 登录失败时，把**中文原因**写进 session，供登录页读出来展示。
 * <p>
 * 为什么不用默认行为：Spring Security 抛出的异常消息是英文的
 * （{@code User is disabled} / {@code Bad credentials}），而且
 * {@code DaoAuthenticationProvider} 默认会把"用户不存在"和"密码错误"统一成
 * {@code BadCredentialsException}——这是防止用户名枚举的正确设计，要保留。
 * 但"账号被禁用"和"账号已过期"这两类必须区分出来，否则用户只会看到一句
 * "用户名或密码错误"，然后反复试同一个正确密码。
 * <p>
 * 处理完之后仍然走父类的重定向逻辑（回 {@code /login?error}），
 * 由 {@code LoginView} 从 session 里取消息显示，取完即删。
 */
public class LoginFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(LoginFailureHandler.class);

    /** session 里存登录失败原因的 key，登录页读一次就清掉 */
    public static final String SESSION_ATTR = "LANYUE_LOGIN_ERROR";

    /** Vaadin 的 LoginForm 提交这两个字段名 */
    private static final String FIELD_USERNAME = "username";

    private final DbUserDetailsService userDetailsService;

    public LoginFailureHandler(DbUserDetailsService userDetailsService) {
        // 失败后回登录页，带上 ?error 让 Vaadin 的 LoginForm 自己进入错误态
        super("/login?error");
        this.userDetailsService = userDetailsService;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {
        String message = resolveMessage(request, exception);
        request.getSession(true).setAttribute(SESSION_ATTR, message);
        // 只记一行 warn，不打堆栈：登录失败是预期内的业务事件，不是故障
        log.warn("登录失败：{}（{}）", request.getParameter(FIELD_USERNAME), message);
        super.onAuthenticationFailure(request, response, exception);
    }

    /**
     * 把异常翻译成给用户看的中文。
     * <p>
     * 账号过期这两种情况要带上具体日期，与用户管理页里的文案口径保持一致——
     * 只说"已过期"用户还得去问管理员是哪天到期的。
     */
    private String resolveMessage(HttpServletRequest request, AuthenticationException exception) {
        if (exception instanceof DisabledException) {
            return "该账号已被禁用，请联系系统管理员";
        }
        if (exception instanceof AccountExpiredException) {
            User user = userDetailsService.findByUserId(request.getParameter(FIELD_USERNAME));
            if (user != null && user.getExpireTime() != null) {
                return "该账号已于 " + DateUtil.formatDate(user.getExpireTime())
                        + " 过期，请联系系统管理员续期";
            }
            return "该账号已过期，请联系系统管理员续期";
        }
        if (exception instanceof LockedException) {
            return "该账号已被锁定，请联系系统管理员";
        }
        if (exception instanceof CredentialsExpiredException) {
            return "该账号的密码已过期，请联系系统管理员重置";
        }
        if (exception instanceof BadCredentialsException) {
            // 用户名不存在与密码错误在这里是同一条，不能分开提示，否则可以被用来枚举账号
            return "用户名或密码错误";
        }
        log.warn("未识别的登录失败类型 {}", exception.getClass().getName(), exception);
        return "登录失败，请稍后重试";
    }
}
