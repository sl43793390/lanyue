package com.sl.security;

import com.sl.entity.User;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 取"当前登录用户"的静态入口。
 * <p>
 * 为什么是静态工具类而不是 Spring bean：旧项目里到处是 {@code ComponentUtil.getCurrentUserName()}
 * 这种静态调用（往往出现在底层工具类里，比如 {@code Util} 拼 per-user 配置文件名的场景），
 * 从工具类里注入一个 bean 会逼着所有调用方都变成 Spring 托管对象，改造面太大。
 * Spring Security 本身就是用 {@link SecurityContextHolder} 暴露当前上下文的，这里只是加一层
 * 类型安全 + 空值兜底。
 * <p>
 * 与旧实现的差异（有意为之）：旧代码是
 * {@code VaadinSession.getCurrent().getAttribute("userName").toString()}，
 * 在没有 UI 上下文的线程（启动任务、定时任务、异步线程）里会直接 NPE。
 * 这里改成**返回 null / 走兜底**，让调用方能自己决定怎么办，而不是把异常抛到无法定位的地方。
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /**
     * 当前登录用户实体。
     *
     * @return 已登录时返回 {@link User}；未登录或不在请求线程里返回 {@code null}
     */
    public static User get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (null == auth || !auth.isAuthenticated()) {
            return null;
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof UserPrincipal) {
            return ((UserPrincipal) principal).getUser();
        }
        return null;
    }

    /**
     * 当前登录名（即 {@code users.id_user}）。
     *
     * @return 未登录返回 {@code null}
     */
    public static String id() {
        User user = get();
        return null == user ? null : user.getUserId();
    }

    /**
     * 当前用户的姓名（{@code users.name_user}），仅用于展示。
     *
     * @return 未登录返回 {@code null}
     */
    public static String displayName() {
        User user = get();
        return null == user ? null : user.getUserName();
    }

    /**
     * 当前登录名，取不到时回落到操作系统用户名。
     * <p>
     * 给"需要给某个文件/目录起个跟人相关的名字"这种场景用（旧代码里是
     * {@code <user.dir>/<登录名>.properties}）。回落而不是抛异常，是因为这类调用
     * 经常发生在启动期或后台线程，那里本来就"没有当前用户"，报错没有意义。
     */
    public static String idOrSystemUser() {
        String id = id();
        return null != id ? id : System.getProperty("user.name");
    }
}
