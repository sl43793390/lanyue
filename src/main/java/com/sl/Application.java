package com.sl;

import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Push;
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
/*
 * 全 UI 开启 WebSocket Push（app shell 注解，Vaadin 24.10 只允许放在 AppShellConfigurator 上）。
 * 功能页大量使用「后台线程 + ui.access 回 UI」的骨架（Docker 详情、检测状态、文件上传、
 * 指标刷新……），没有 Push 时 ui.access 攒下的变更要等下一次任意客户端请求才 flush——
 * 症状就是「点了没反应，切个标签提示才冒出来」。
 */
@Push
//@JsModule("@vaadin/vaadin-lumo-styles/presets/compact.js")//压缩模式
public class Application implements AppShellConfigurator {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone(); // You can also use Clock.systemUTC()
    }

    public static void main(String[] args) {
        ConfigurableApplicationContext applicationContext = SpringApplication.run(Application.class, args);
    }

}
