package com.sl.entity;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sl.mapper.handler.TextDateTypeHandler;
import com.sl.util.Constants;

import java.io.Serializable;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 系统用户，对应 {@code users} 表。
 * <p>
 * 表里几个字段的约定（demo.sql 里没有注释，这里说明清楚，页面与登录逻辑都依赖它们）：
 * <ul>
 * <li>{@code user_flag}：{@code "1"} 或空 = 启用，{@code "0"} = 禁用；</li>
 * <li>{@code expire_time}：账号有效期，当天 23:59:59 之前都算有效，为空表示长期有效；</li>
 * <li>{@code permission}：逗号分隔的权限串，{@code ALL} 表示全部权限；</li>
 * <li>{@code create_time} / {@code expire_time} 是文本列，用 {@link TextDateTypeHandler} 固定格式读写。</li>
 * </ul>
 */
/*
 * autoResultMap = true 不能省。
 *
 * @TableField(typeHandler = ...) 只影响 MyBatis-Plus 拼进 insert / update 的那句 SQL
 * （形如 #{et.createTime,typeHandler=com.sl.mapper.handler.TextDateTypeHandler}）。
 * select 走的是 resultMap 自动映射，处理器由 Java 类型推断——java.util.Date 会落到
 * 默认的 DateTypeHandler，它调用 rs.getTimestamp()，而 sqlite-jdbc 只认
 * yyyy-MM-dd HH:mm:ss.SSS。库里只要有 date('now') 这类纯日期（demo.sql 的示例数据就是），
 * 读出来就抛 Error parsing time stamp，整个用户管理页打不开。
 *
 * 打开 autoResultMap 后 MP 会为该实体生成 resultMap，把上面声明的 typeHandler 同时
 * 应用到读取侧，读写才真正闭环。它只作用于 MP 自动注入的方法，也只影响 User 这一个实体。
 */
@TableName(value = "users", autoResultMap = true)
public class User implements Serializable {
    private static final long serialVersionUID = -8183726775316718897L;

    /**
     * 内置管理员账号名。
     * <p>
     * 这个账号由系统在启动时自动写入（见 {@code com.sl.config.DbInitializer}），是系统唯一的引导入口：
     * 一旦它被删除、禁用或降权，就再也没有人能登进来把它改回去了。
     * 因此它在用户管理页受到硬保护，不允许删除、禁用、改权限、设有效期。
     */
    public static final String ADMIN_USER_ID = "admin";
    /** 内置管理员的初始密码，仅在库里查不到 admin 时用它写入（摘要入库，明文不落盘） */
    public static final String ADMIN_DEFAULT_PASSWORD = "admin";

    /** user_flag 取值：启用 */
    public static final String FLAG_ENABLED = "1";
    /** user_flag 取值：禁用 */
    public static final String FLAG_DISABLED = "0";
    /** 快到期提醒的阈值（天） */
    public static final int EXPIRE_WARN_DAYS = 7;

    @TableId(value = "id_user")
    private String userId;
    @TableField(value = "name_user")
    private String userName;

    private String email;

    private String password;

    @TableField(value = "create_time", typeHandler = TextDateTypeHandler.class)
    private Date createTime;

    /**
     * 过期时间。updateStrategy 用 ALWAYS：MyBatis-Plus 默认策略会把 null 字段排除在
     * update 之外，那样"清空有效期"这个操作根本写不进库。
     * 前提是更新时传入的是从库里查出来的完整对象，而不是只填了几个字段的新对象。
     * <p>
     * 注意用的是 {@code ALWAYS} 而不是旧项目 logviewer 里的 {@code IGNORED}：
     * MyBatis-Plus 3.5.9 起删掉了 {@code FieldStrategy.IGNORED}，语义等价的新名字是
     * {@code ALWAYS}（3.5.12 的取值只剩 ALWAYS / NOT_NULL / NOT_EMPTY / DEFAULT / NEVER）。
     * 照抄旧代码会直接编译失败——找不到符号: 变量 IGNORED。
     */
    @TableField(value = "expire_time", typeHandler = TextDateTypeHandler.class,
            updateStrategy = FieldStrategy.ALWAYS)
    private Date expireTime;

    private String organization;

    private String cdPhone;

    private String userFlag;

    private String permission;

    public String getOrganization() {
        return organization;
    }

    public void setOrganization(String organization) {
        this.organization = organization;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId == null ? null : userId.trim();
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName == null ? null : userName.trim();
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email == null ? null : email.trim();
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password == null ? null : password.trim();
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public Date getExpireTime() {
        return expireTime;
    }

    public void setExpireTime(Date expireTime) {
        this.expireTime = expireTime;
    }

    public String getCdPhone() {
        return cdPhone;
    }

    public void setCdPhone(String cdPhone) {
        this.cdPhone = cdPhone;
    }

    public String getUserFlag() {
        return userFlag;
    }

    public void setUserFlag(String userFlag) {
        this.userFlag = userFlag;
    }

    public User(String userId, String userName, String email) {
        super();
        this.userId = userId;
        this.userName = userName;
        this.email = email;
    }

    public User(String userId) {
        super();
        this.userId = userId;
    }

    public User() {
        super();
    }

    public String getPermission() {
        return permission;
    }

    public void setPermission(String permission) {
        this.permission = permission;
    }

    // ------------------------------------------------------------------
    // 以下是领域判断。页面的状态徽标、统计口径和登录校验共用同一套逻辑，
    // 避免出现「列表里显示正常、登录却不让进」这类前后不一致。
    // ------------------------------------------------------------------

    /**
     * 是否为内置管理员账号。删除、禁用、降权、设有效期都要先问这一句。
     */
    public boolean isBuiltinAdmin() {
        return ADMIN_USER_ID.equals(StrUtil.trim(userId));
    }

    /**
     * 账号是否启用。只有明确写着 {@link #FLAG_DISABLED} 才算禁用，
     * 历史数据里 user_flag 是空的，按启用处理。
     */
    public boolean isEnabled() {
        return !FLAG_DISABLED.equals(StrUtil.trim(userFlag));
    }

    /**
     * 账号是否已过期。
     * <p>
     * expire_time 存的是日期（时分秒为 0），语义是"当天仍可用"，
     * 所以按当天 23:59:59 作为失效时刻，而不是零点。
     */
    public boolean isExpired() {
        return isExpiredAt(new Date());
    }

    /**
     * 指定基准时刻判断是否过期，便于批量统计时统一时间基准
     */
    public boolean isExpiredAt(Date base) {
        if (null == expireTime || null == base) {
            return false;
        }
        Date endOfDay = DateUtil.endOfDay(expireTime);
        return endOfDay.getTime() < base.getTime();
    }

    /**
     * 距过期还有多少天，负数表示已过期天数；无过期时间返回 null
     */
    public Integer remainDays() {
        if (null == expireTime) {
            return null;
        }
        return (int) DateUtil.betweenDay(new Date(), expireTime, true);
    }

    /**
     * 是否处于"即将到期"的提醒区间
     */
    public boolean isNearExpire() {
        Integer days = remainDays();
        return null != days && days >= 0 && days <= EXPIRE_WARN_DAYS;
    }

    /**
     * 账号是否可用（未被禁用且未过期）
     */
    public boolean isAvailable() {
        return isEnabled() && !isExpired();
    }

    /**
     * 账号不可用的原因，可用时返回 null。登录页与列表页共用，保证提示口径一致。
     */
    public String unavailableReason() {
        if (!isEnabled()) {
            return "该账号已被禁用，请联系系统管理员";
        }
        if (isExpired()) {
            return "该账号已于 " + DateUtil.formatDate(expireTime) + " 过期，请联系系统管理员续期";
        }
        return null;
    }

    /**
     * 权限集合，去掉空白项
     */
    public Set<String> permissionSet() {
        Set<String> result = new LinkedHashSet<String>();
        if (StrUtil.isBlank(permission)) {
            return result;
        }
        for (String item : permission.split(",")) {
            String trimmed = StrUtil.trim(item);
            if (StrUtil.isNotEmpty(trimmed)) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * 是否拥有全部权限
     */
    public boolean isAllPermission() {
        return permissionSet().contains(Constants.ALL);
    }

    /**
     * 是否拥有指定权限码。{@code ALL} 视为拥有全部权限。
     */
    public boolean hasPermission(String code) {
        Set<String> items = permissionSet();
        return items.contains(Constants.ALL) || items.contains(code);
    }

    /**
     * 权限的中文描述，供列表与导出使用
     */
    public String permissionText() {
        Set<String> items = permissionSet();
        if (items.isEmpty()) {
            return "无";
        }
        StringBuilder buf = new StringBuilder();
        for (String item : items) {
            if (buf.length() > 0) {
                buf.append("、");
            }
            buf.append(permissionCaption(item));
        }
        return buf.toString();
    }

    /**
     * 单个权限码转中文
     */
    public static String permissionCaption(String code) {
        if (Constants.ALL.equals(code)) {
            return "全部权限";
        }
        if (Constants.ADD.equals(code)) {
            return "新增";
        }
        if (Constants.DELETE.equals(code)) {
            return "删除";
        }
        if (Constants.UPDATE.equals(code)) {
            return "修改";
        }
        if (Constants.QUERY.equals(code)) {
            return "查询";
        }
        if (Constants.UPLOAD.equals(code)) {
            return "上传";
        }
        return code;
    }

    /**
     * 状态的中文描述
     */
    public String statusText() {
        if (!isEnabled()) {
            return "已禁用";
        }
        if (isExpired()) {
            return "已过期";
        }
        if (isNearExpire()) {
            return "即将到期";
        }
        return "正常";
    }
}
