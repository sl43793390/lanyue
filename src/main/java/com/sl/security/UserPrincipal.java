package com.sl.security;

import com.sl.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 把 {@link User} 实体包装成 Spring Security 能识别的 {@link UserDetails}。
 * <p>
 * 权限映射：{@code users.permission} 列里是 {@code ADD,UPDATE,...} 这样的权限码，
 * 直接原样转成 {@link SimpleGrantedAuthority}，**不加 {@code ROLE_} 前缀**。
 * 因为这套码是业务权限（增删改查上传），不是角色，加了前缀反而要在
 * {@code @PreAuthorize} 里多写一层。
 * <p>
 * 账号状态与登录失败原因的对应关系（决定了用户看到哪句提示）：
 * <ul>
 * <li>{@link #isEnabled()} 返回 false → {@code DisabledException} → "该账号已被禁用"；</li>
 * <li>{@link #isAccountNonExpired()} 返回 false → {@code AccountExpiredException} → "该账号已过期"。</li>
 * </ul>
 * 这两条判断直接复用 {@link User#isEnabled()} / {@link User#isExpired()}，
 * 与用户管理页列表里的状态口径完全一致，不会出现"列表显示正常、登录却不让进"。
 */
public class UserPrincipal implements UserDetails {

    private static final long serialVersionUID = 1L;

    private final User user;
    /**
     * 登录冻结（连续密码错误）标志。
     * <p>
     * 不能在 {@link com.sl.security.DbUserDetailsService} 里直接抛
     * {@code LockedException}：Spring Security 6.5 的
     * {@code DaoAuthenticationProvider.retrieveUser} 会把 UserDetailsService 抛出的
     * 非 {@code UsernameNotFoundException} 异常包装成
     * {@code InternalAuthenticationServiceException}，登录页拿不到"冻结"语义。
     * 正确通道是 {@code isAccountNonLocked()} 返回 false——由 provider 的
     * 前置检查抛出标准的 {@code LockedException}，且发生在密码校验之前。
     */
    private final transient boolean locked;

    public UserPrincipal(User user) {
        this(user, false);
    }

    public UserPrincipal(User user, boolean locked) {
        this.user = user;
        this.locked = locked;
    }

    /**
     * 取回原始实体，登录成功后需要拿它填 session 里的用户名、权限等信息。
     */
    public User getUser() {
        return user;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        Set<GrantedAuthority> authorities = new LinkedHashSet<GrantedAuthority>();
        for (String code : user.permissionSet()) {
            authorities.add(new SimpleGrantedAuthority(code));
        }
        return authorities;
    }

    @Override
    public String getPassword() {
        return user.getPassword();
    }

    @Override
    public String getUsername() {
        return user.getUserId();
    }

    /**
     * 账号是否未过期。返回 false 会让认证流程直接抛 {@code AccountExpiredException}，
     * 不会走到密码校验那一步。
     */
    @Override
    public boolean isAccountNonExpired() {
        return !user.isExpired();
    }

    @Override
    public boolean isAccountNonLocked() {
        return !locked;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return user.isEnabled();
    }

    @Override
    public String toString() {
        return "UserPrincipal{" + user.getUserId() + ", " + user.statusText() + "}";
    }
}
