package com.sl.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * drawio 静态资源映射（其他工具 → drawio绘图）。
 * <p>
 * draw.io 官方发布的静态版（draw.war 解开剔除 WEB-INF 后的 webapp）整体放在
 * 运行目录的 {@code drawio/} 下（与 {@code fileStorage/} 同级的相对路径约定），
 * 这里映射到 {@code /drawio/**}。两个设计取舍：
 * <ul>
 * <li>**为什么不放进 {@code src/main/resources/META-INF/resources/}**：静态版有
 * 五十多 MB（含图表模板、多语言资源），进 resources 会全部打进 jar；外置目录
 * 让升级 drawio 版本变成"换目录"，与构建产物彻底解耦。</li>
 * <li>**为什么能被请求到**：Vaadin Spring 集成把根路径请求经
 * {@code RootExcludeHandler} 转发给 VaadinServlet，但它在转发前会先查
 * {@code resourceHandlerMapping}——凡是映射到非根位置的静态资源（如本 handler）
 * 直接由 Spring 服务，不进 VaadinServlet。这是 Vaadin 24.8+ 官方支持的共存方式。</li>
 * </ul>
 * 访问控制：该路径不在 VaadinSecurityConfigurer 的放行名单（/VAADIN/** 等）里，
 * 默认要求登录后才能访问，与功能页一致。
 * <p>
 * **打包发布**：双 location，按顺序探测——
 * <ol>
 * <li>{@code file:drawio/}（jar 同级外置目录）：开发期与常规部署用，jar 保持瘦身，
 * 升级 drawio 只换目录。注意相对路径按 JVM 启动时的工作目录解析，
 * 用 systemd/服务方式启动时必须保证 WorkingDirectory 指向 drawio/ 所在目录。</li>
 * <li>{@code classpath:/drawio/}（打进 jar 内）：若构建时用 maven-resources-plugin
 * 把 drawio/ 拷进 target/classes，则单 jar 也能跑，外置目录不存在时自动兜底。</li>
 * </ol>
 */
@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/drawio/**")
                .addResourceLocations("file:drawio/");
    }
}
