package com.sl.security;

import com.sl.ui.LoginView;
import com.vaadin.flow.spring.security.VaadinSecurityConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证与授权配置。
 * <p>
 * 用 Spring Security 重做旧的 {@code LoginView} + {@code MyUI.Navigator} 那套自研登录，
 * 但**账号数据模型完全沿用**：还是 {@code users} 表、还是 {@code {ADD,UPDATE,...}} 权限码、
 * 还是 {@code admin} 幂等引导。换掉的只是"谁来校验密码"这一段。
 *
 * <h2>为什么是 VaadinSecurityConfigurer 而不是 VaadinWebSecurity</h2>
 * {@code VaadinWebSecurity} 自 Vaadin 24.8 起被标记为
 * {@code @Deprecated(forRemoval = true)}，用它编译会有一条 "已过时，且标记为待删除" 的告警，
 * 而且这个类迟早会消失。官方替代方案是把 Vaadin 的安全配置当成一个普通
 * {@link SecurityFilterChain} bean 里的 configurer 挂上去（见下），
 * 顺带把继承关系换成了组合。
 *
 * <h2>密码格式的渐进式迁移</h2>
 * 旧库里的密码是**无盐 SM3 大写十六进制**（见 {@link Sm3PasswordEncoder}），新密码统一用 BCrypt。
 * 两者靠 {@link DelegatingPasswordEncoder} 共存：
 * <ul>
 * <li>密文带 {@code {bcrypt}} 前缀 → 走 BCrypt；</li>
 * <li>密文无前缀（旧格式）→ 落到 {@code defaultPasswordEncoderForMatches}，走 SM3；</li>
 * <li>认证成功且被判为"格式落后"时，框架自动调 {@link DbUserDetailsPasswordService}
 * 把同一个明文重编码成 BCrypt 写回库。</li>
 * </ul>
 * 结果是老用户无感知：用原密码登录一次，库里的摘要就自动升级了，不需要任何人重置密码。
 * 这条链路已实测：拿旧 demo.db 的 admin 登录后，库里的 {@code DC1FD00E...}（SM3）
 * 变成了 {@code $2a$10$...}（BCrypt）。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final DbUserDetailsService userDetailsService;
    private final DbUserDetailsPasswordService passwordService;

    public SecurityConfig(DbUserDetailsService userDetailsService,
                         DbUserDetailsPasswordService passwordService) {
        this.userDetailsService = userDetailsService;
        this.passwordService = passwordService;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        /*
         * Vaadin 那一套安全细节（放行 /VAADIN 与 /VAADIN/push 等内部路径、
         * CSRF token 的注入方式、@AnonymousAllowed 导航访问控制、请求缓存、
         * 开发模式下的若干例外）都封装在这个 configurer 里。
         * 用 http.with(...) 挂上去，不要自己拿 authorizeHttpRequests 重写一遍——
         * 漏掉任何一条都会表现为"页面白屏"或"登录后立刻被踢回登录页"这类难查的现象。
         */
        http.with(VaadinSecurityConfigurer.vaadin(),
                vaadin -> vaadin.loginView(LoginView.class));

        // SSH 终端用 iframe 嵌 VAADIN/static/terminal/terminal.html。
        // Spring Security 的默认响应头是 X-Frame-Options: DENY，会把同源 iframe 一并拦掉，
        // 症状是终端页面空白、iframe 的 contentDocument 为 null。放宽为同源可嵌。
        http.headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));

        // 覆盖默认的失败处理器，把失败原因（含"账号已禁用""账号已于 X 过期"）写进 session
        http.formLogin(form -> form.failureHandler(loginFailureHandler()));

        http.logout(logout -> logout
                // 退出后回登录页并带上 ?logout，方便页面提示"已安全退出"
                .logoutSuccessUrl("/login?logout")
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                .deleteCookies("JSESSIONID"));

        return http.build();
    }

    /**
     * 密码编码器。
     * <p>
     * {@code idForEncode} 是 {@code bcrypt}——所有**新写入**的密码都用它。
     * {@code defaultPasswordEncoderForMatches} 是 SM3——只用于校验那些没有 {@code {id}}
     * 前缀的历史密文。这个组合让新旧两种格式可以同时存在，是迁移期的关键。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        Map<String, PasswordEncoder> encoders = new HashMap<String, PasswordEncoder>();
        encoders.put("bcrypt", new BCryptPasswordEncoder());
        encoders.put("sm3", new Sm3PasswordEncoder());

        DelegatingPasswordEncoder encoder = new DelegatingPasswordEncoder("bcrypt", encoders);
        encoder.setDefaultPasswordEncoderForMatches(new Sm3PasswordEncoder());
        return encoder;
    }

    /**
     * 显式声明 DaoAuthenticationProvider，而不是依赖自动装配。
     * <p>
     * 为什么坚持显式：
     * <ol>
     * <li>{@code UserDetailsPasswordService} 是否被接上，取决于
     * {@code InitializeUserDetailsBeanManagerConfigurer} 的内部查找逻辑。一旦认证链上
     * 出现了第二个 {@code UserDetailsService}（比如以后给外部接口加一套），
     * 自动装配会直接放弃装配、退回默认行为，**而密码升级会静默失效**——不报错、不告警，
     * 只是库里的摘要永远停在旧格式。显式声明把这个行为钉死。</li>
     * <li>代价是启动时多一条 WARN，提示"UserDetailsService bean 不会被用于自动配置"。
     * 这是预期内的，已在 log4j2.xml 里把这个 logger 降到 ERROR，不再刷屏。</li>
     * </ol>
     */
    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        provider.setUserDetailsPasswordService(passwordService);
        // 保持默认：把"用户不存在"也报成 BadCredentialsException，避免账号枚举
        provider.setHideUserNotFoundExceptions(true);
        return provider;
    }

    @Bean
    public AuthenticationFailureHandler loginFailureHandler() {
        return new LoginFailureHandler(userDetailsService);
    }
}
