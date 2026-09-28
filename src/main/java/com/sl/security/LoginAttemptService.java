package com.sl.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败计数与账号冻结。
 * <p>
 * 规则：同一用户名<b>连续</b>输错密码达到 {@link #MAX_ATTEMPTS} 次后冻结 {@link #LOCK_MINUTES} 分钟，
 * 冻结期间任何登录尝试都会被直接拒绝（不再校验密码，提示剩余时间），冻结到期自动解冻；
 * 计数期间只要登录成功一次就清零。防的是脚本暴力试密码，不是管理员处罚——
 * 所以选自动解冻而不是永久锁死，否则一次误操作就得去用户管理页找管理员救人。
 * <p>
 * <b>为什么挂在内存里而不是库表：</b>冻结是短时效状态（分钟级），写库要引入新列和
 * 定时解冻任务，收益不成比例。代价是重启后计数清零——对防爆破来说可接受，
 * 攻击者重启不了我们的进程。
 * <p>
 * <b>计数只算"密码错误"。</b>账号被禁用、过期这类失败与密码对错无关，
 * 不能往里混（否则一个被禁用的账号会一直被计数）。这个区分在
 * {@link LoginFailureHandler} 里做：只有 {@code BadCredentialsException} 才计数。
 * <p>
 * <b>解冻/清零时机：</b>计数在登录<b>失败处理器</b>里累加，清零靠 Spring Security 的
 * {@link AuthenticationSuccessEvent}（{@code ProviderManager} 认证成功后发布）。
 * 用户名不存在的尝试也照常计数——反正对外都报"用户名或密码错误"，不泄露账号是否存在。
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    /** 连续失败多少次触发冻结 */
    public static final int MAX_ATTEMPTS = 5;

    /** 冻结时长（分钟） */
    public static final long LOCK_MINUTES = 10;

    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();

    /**
     * 该用户名当前是否处于冻结期。冻结已过期的条目顺带清掉（等价于自动解冻）。
     */
    public boolean isLocked(String username) {
        if (username == null || username.isEmpty()) {
            return false;
        }
        Attempt attempt = attempts.get(username);
        if (attempt == null) {
            return false;
        }
        if (attempt.lockedUntil <= 0) {
            return false;
        }
        if (System.currentTimeMillis() >= attempt.lockedUntil) {
            // 冻结到期：解冻并清空计数，重新从零算起
            attempts.remove(username, attempt);
            log.info("账号 {} 冻结到期，自动解冻，失败计数已清零", username);
            return false;
        }
        return true;
    }

    /** 距解冻还剩多少秒；未冻结返回 0。 */
    public long remainingLockSeconds(String username) {
        if (username == null || username.isEmpty()) {
            return 0;
        }
        Attempt attempt = attempts.get(username);
        if (attempt == null || attempt.lockedUntil <= 0) {
            return 0;
        }
        return Math.max(0, (attempt.lockedUntil - System.currentTimeMillis()) / 1000);
    }

    /** 当前已连续失败次数（给提示文案与日志用） */
    public int failedCount(String username) {
        if (username == null || username.isEmpty()) {
            return 0;
        }
        Attempt attempt = attempts.get(username);
        return attempt == null ? 0 : attempt.count;
    }

    /**
     * 记一次密码错误。达到阈值即进入冻结期。
     *
     * @return 本次是否触发了冻结
     */
    public boolean onFailure(String username) {
        if (username == null || username.isEmpty()) {
            return false;
        }
        String trimmed = username.trim();
        Attempt attempt = attempts.computeIfAbsent(trimmed, k -> new Attempt());
        synchronized (attempt) {
            attempt.count++;
            if (attempt.count >= MAX_ATTEMPTS) {
                attempt.lockedUntil = System.currentTimeMillis() + LOCK_MINUTES * 60 * 1000L;
                log.warn("账号 {} 连续 {} 次密码错误，冻结 {} 分钟", trimmed, attempt.count, LOCK_MINUTES);
                return true;
            }
            log.info("账号 {} 密码错误第 {} 次（{} 次后冻结）", trimmed, attempt.count, MAX_ATTEMPTS - attempt.count);
            return false;
        }
    }

    /** 登录成功：清空该用户名的失败计数。 */
    public void onSuccess(String username) {
        if (username != null && attempts.remove(username.trim()) != null) {
            log.info("账号 {} 登录成功，失败计数已清零", username.trim());
        }
    }

    /** 认证成功事件：无论从哪条认证路径成功，都把失败计数清零。 */
    @EventListener
    public void onAuthenticationSuccess(AuthenticationSuccessEvent event) {
        Object principal = event.getAuthentication().getPrincipal();
        if (principal instanceof UserPrincipal userPrincipal) {
            onSuccess(userPrincipal.getUsername());
        }
    }

    /** 单个用户名的失败状态。count/lockedUntil 由 synchronized(attempt) 保护。 */
    private static final class Attempt {
        private int count;
        private long lockedUntil;
    }
}
