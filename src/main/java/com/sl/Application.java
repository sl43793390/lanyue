package com.sl;

import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.server.AppShellSettings;
import com.vaadin.flow.router.PreserveOnRefresh;
import com.vaadin.flow.theme.Theme;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;

@SpringBootApplication
@Theme("default")
@Push
@JsModule("@vaadin/vaadin-lumo-styles/presets/compact.js")//压缩模式
public class Application implements AppShellConfigurator {

    /*
     * 浏览器标签页图标。href 必须写相对路径（"favicon.ico"），配合 Vaadin 为
     * 深层路由注入的 <base>，任意页面都解析到 /log/favicon.ico——绝对路径
     * "/favicon.ico" 会绕过 context-path 直接打到 9095 根上，永远 404。
     * 在 AppShell 里声明还有一层用意：SecurityConfig 里 VaadinSecurityConfigurer
     * 的 WebIconsRequestMatcher 会收集 configurePage 里 addFavIcon 的路径并放行，
     * 未登录时请求 /log/favicon.ico 也不会被重定向到登录页（登录页标签同样有图标）。
     * 文件本体放在 src/main/resources/META-INF/resources/favicon.ico，
     * dev（spring-boot:run）和生产（-Pproduction）都由静态资源处理直接服务。
     */
    @Override
    public void configurePage(AppShellSettings settings) {
        settings.addFavIcon("icon", "favicon.ico", "32x32");
    }

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone(); // You can also use Clock.systemUTC()
    }

    public static void main(String[] args) {
        ConfigurableApplicationContext applicationContext = SpringApplication.run(Application.class, args);
    }

}
