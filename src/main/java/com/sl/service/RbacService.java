package com.sl.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sl.entity.Permission;
import com.sl.entity.Role;
import com.sl.entity.RolePermission;
import com.sl.entity.User;
import com.sl.entity.UserRole;
import com.sl.mapper.PermissionDao;
import com.sl.mapper.RoleDao;
import com.sl.mapper.RolePermissionDao;
import com.sl.mapper.UserDao;
import com.sl.mapper.UserRoleDao;
import com.sl.ui.PermissionCatalog;
import com.sl.util.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RBAC 核心服务：权限目录播种、有效权限解析、角色 CRUD、用户角色分配。
 * <p>
 * <b>模型</b>（对应需求「根是菜单，子权限是页面内部的操作按钮」）：
 * <pre>
 * permissions（树，两级）
 *   ├─ user:view        用户管理（菜单，MENU）
 *   │    ├─ user:add    新增用户（按钮，BUTTON）
 *   │    ├─ user:update 修改用户
 *   │    └─ user:delete 删除用户
 *   └─ …
 * roles ──&lt; role_permissions ──&gt; permissions
 * users ──&lt; user_roles        ──&gt; roles
 * </pre>
 * <b>有效权限 = 角色权限并集 ∪ {@code users.permission} 历史权限列</b>。
 * 保留历史列是为了让存量账号（含内置管理员 {@code admin} 的 {@code ALL}）零迁移可用；
 * 新授权一律走角色，{@code users.permission} 只读不再维护。
 * <p>
 * 登录时由 {@code DbUserDetailsService} 调 {@link #effectivePermissions(User)} 取一次快照
 * 装进 {@code UserPrincipal}，页面内的权限判断都走这份快照（见
 * {@code CurrentUser.hasPermission}）。代价是改角色后要重新登录才生效——
 * 对这套内网运维工具是可接受的权衡，免得每次判断都查库。
 */
@Service
public class RbacService {

    private static final Logger log = LoggerFactory.getLogger(RbacService.class);

    private final PermissionDao permissionDao;
    private final RoleDao roleDao;
    private final RolePermissionDao rolePermissionDao;
    private final UserRoleDao userRoleDao;
    private final UserDao userDao;

    public RbacService(PermissionDao permissionDao, RoleDao roleDao,
                       RolePermissionDao rolePermissionDao, UserRoleDao userRoleDao,
                       UserDao userDao) {
        this.permissionDao = permissionDao;
        this.roleDao = roleDao;
        this.rolePermissionDao = rolePermissionDao;
        this.userRoleDao = userRoleDao;
        this.userDao = userDao;
    }

    // ==================================================================
    // 一、启动播种（幂等）
    // ==================================================================

    /**
     * 按 {@link PermissionCatalog} 把权限树与内置角色补进库表。
     * <p>
     * 幂等三条规则：
     * <ol>
     * <li>权限按 {@code cd_perm} 判存在：缺的插入，有的校准名称/层级/排序（不删行）；</li>
     * <li>「系统管理员」角色按固定 ID 保证存在，并始终持有全部权限码；</li>
     * <li>「普通用户」演示角色只在 roles 表为空时创建，用户删掉后不复活；</li>
     * <li>内置管理员 {@code admin} 没有任何角色时，补挂「系统管理员」。</li>
     * </ol>
     */
    public void ensureSeedData() {
        ensurePermissions();
        ensureBuiltinRoles();
        ensureAdminUserRole();
    }

    /** 权限树播种：缺则插，在位则校准。返回 cd_perm → id_perm 的映射。 */
    private Map<String, String> ensurePermissions() {
        Map<String, String> codeToId = new LinkedHashMap<>();
        for (PermissionCatalog.Def def : PermissionCatalog.all()) {
            Permission existing = findByCode(def.code());
            String parentId = null;
            if (StrUtil.isNotBlank(def.parentCode())) {
                parentId = codeToId.get(def.parentCode());
                if (parentId == null) {
                    // 父节点刚被插进来时映射已就绪；走到这里说明目录本身配错了父码
                    log.warn("权限 {} 的父权限 {} 不在库中，按根节点处理", def.code(), def.parentCode());
                }
            }
            if (existing == null) {
                Permission perm = new Permission();
                perm.setPermId(newPermId());
                perm.setCdPerm(def.code());
                perm.setNamePerm(def.name());
                perm.setIdParent(parentId);
                perm.setNbrLevel(PermissionCatalog.TYPE_BUTTON.equals(def.type()) ? 2 : 1);
                perm.setNbrOrder(def.order());
                perm.setCdType(def.type());
                perm.setCdDesc(def.desc());
                perm.setCreateTime(new Date());
                permissionDao.insert(perm);
                codeToId.put(def.code(), perm.getPermId());
            } else {
                boolean changed = !def.name().equals(existing.getNamePerm())
                        || !def.type().equalsIgnoreCase(StrUtil.nullToEmpty(existing.getCdType()))
                        || parentId != null && !parentId.equals(existing.getIdParent())
                        || !Integer.valueOf(def.order()).equals(existing.getNbrOrder() == null ? -1 : existing.getNbrOrder())
                        || !def.desc().equals(existing.getCdDesc());
                if (changed) {
                    existing.setNamePerm(def.name());
                    existing.setIdParent(parentId);
                    existing.setNbrLevel(PermissionCatalog.TYPE_BUTTON.equals(def.type()) ? 2 : 1);
                    existing.setNbrOrder(def.order());
                    existing.setCdType(def.type());
                    existing.setCdDesc(def.desc());
                    permissionDao.updateById(existing);
                }
                codeToId.put(def.code(), existing.getPermId());
            }
        }
        return codeToId;
    }

    /** 内置角色：「系统管理员」始终存在且持全权；「普通用户」仅在首次启动（roles 为空）时创建。 */
    private void ensureBuiltinRoles() {
        // firstBoot 必须在插入系统管理员之前取：之后 roles 表至少有 1 行，
        // 就分不清"首次启动"和"用户删光了其他角色"。删掉「普通用户」后重启不该复活它。
        boolean firstBoot = roleDao.selectCount(new QueryWrapper<>()) == 0;
        Role adminRole = roleDao.selectById(Role.BUILTIN_ADMIN_ROLE_ID);
        if (adminRole == null) {
            adminRole = new Role();
            adminRole.setRoleId(Role.BUILTIN_ADMIN_ROLE_ID);
            adminRole.setNameRole(Role.BUILTIN_ADMIN_ROLE_NAME);
            adminRole.setCdDesc("内置角色，持有系统全部权限，不可删除");
            adminRole.setFlagBuiltin("1");
            adminRole.setCreateTime(new Date());
            roleDao.insert(adminRole);
            log.info("已创建内置角色 {}", Role.BUILTIN_ADMIN_ROLE_NAME);
        }
        // 全权对齐：目录里新增的权限码要能补进去（删掉的行不动，免得误伤用户改过的配置）
        Set<String> granted = permissionCodesOfRole(adminRole.getRoleId());
        for (PermissionCatalog.Def def : PermissionCatalog.all()) {
            if (!granted.contains(def.code())) {
                grantPermission(adminRole.getRoleId(), def.code());
            }
        }
        if (firstBoot) {
            Role defaultRole = new Role();
            defaultRole.setRoleId(Role.DEFAULT_ROLE_ID);
            defaultRole.setNameRole(Role.DEFAULT_ROLE_NAME);
            defaultRole.setCdDesc("演示角色：概览、免登录服务器列表、drawio、其他工具、github优选");
            defaultRole.setFlagBuiltin("0");
            defaultRole.setCreateTime(new Date());
            roleDao.insert(defaultRole);
            for (String code : PermissionCatalog.defaultRoleCodes()) {
                grantPermission(defaultRole.getRoleId(), code);
            }
            log.info("已创建演示角色 {}（{} 项权限）", Role.DEFAULT_ROLE_NAME,
                    PermissionCatalog.defaultRoleCodes().size());
        }
    }

    /** 内置管理员账号补挂「系统管理员」角色（只在没有任何角色时）。 */
    private void ensureAdminUserRole() {
        List<UserRole> assigned = userRoleDao.selectList(new QueryWrapper<UserRole>()
                .eq("id_user", User.ADMIN_USER_ID));
        if (!assigned.isEmpty()) {
            return;
        }
        if (userDao.selectById(User.ADMIN_USER_ID) != null) {
            UserRole link = new UserRole();
            link.setUserId(User.ADMIN_USER_ID);
            link.setRoleId(Role.BUILTIN_ADMIN_ROLE_ID);
            userRoleDao.insert(link);
            log.info("已为内置管理员 {} 分配角色 {}", User.ADMIN_USER_ID, Role.BUILTIN_ADMIN_ROLE_NAME);
        }
    }

    // ==================================================================
    // 二、有效权限解析
    // ==================================================================

    /**
     * 用户的有效权限码集合：角色权限 ∪ 历史权限列。
     * <p>
     * {@code ALL} 原样保留在集合里，由 {@link #hasPermission(Set, String)} 解释为
     * 「命中全部」，不展开成目录全量——展开会让每次登录都物化一大份集合。
     */
    public Set<String> effectivePermissions(User user) {
        Set<String> codes = new LinkedHashSet<>();
        if (user == null) {
            return codes;
        }
        codes.addAll(user.permissionSet());
        for (String roleId : roleIdsOfUser(user.getUserId())) {
            codes.addAll(permissionCodesOfRole(roleId));
        }
        return codes;
    }

    /** 是否持有指定权限码；{@code ALL} 命中全部。 */
    public static boolean hasPermission(Set<String> effective, String code) {
        return effective != null && (effective.contains(Constants.ALL) || effective.contains(code));
    }

    /** 用户持有的角色 ID 列表 */
    public List<String> roleIdsOfUser(String userId) {
        if (StrUtil.isBlank(userId)) {
            return List.of();
        }
        List<UserRole> links = userRoleDao.selectList(new QueryWrapper<UserRole>()
                .eq("id_user", userId));
        List<String> ids = new ArrayList<>();
        for (UserRole link : links) {
            ids.add(link.getRoleId());
        }
        return ids;
    }

    /** 用户持有的角色实体列表（按角色名排序） */
    public List<Role> rolesOfUser(String userId) {
        List<String> ids = roleIdsOfUser(userId);
        if (ids.isEmpty()) {
            return List.of();
        }
        return roleDao.selectList(new QueryWrapper<Role>().in("id_role", ids)
                .orderByAsc("name_role"));
    }

    /** 角色持有的权限码集合 */
    public Set<String> permissionCodesOfRole(String roleId) {
        if (StrUtil.isBlank(roleId)) {
            return Set.of();
        }
        List<RolePermission> links = rolePermissionDao.selectList(new QueryWrapper<RolePermission>()
                .eq("id_role", roleId));
        Set<String> codes = new LinkedHashSet<>();
        for (RolePermission link : links) {
            Permission perm = permissionDao.selectById(link.getPermId());
            if (perm != null && StrUtil.isNotBlank(perm.getCdPerm())) {
                codes.add(perm.getCdPerm());
            }
        }
        return codes;
    }

    /**
     * 是否管理员身份：历史权限列含 {@code ALL}，或持有内置「系统管理员」角色。
     * <p>
     * 用户管理页的「最后一个管理员」保护用这道判断，避免把角色体系建起来之后
     * 反而能通过改角色把系统锁死。
     */
    public boolean isAdminUser(String userId) {
        if (StrUtil.isBlank(userId)) {
            return false;
        }
        return roleIdsOfUser(userId).contains(Role.BUILTIN_ADMIN_ROLE_ID);
    }

    // ==================================================================
    // 三、权限树
    // ==================================================================

    /**
     * 全部权限节点（库序 = 目录序），平铺列表。
     */
    public List<Permission> allPermissions() {
        return permissionDao.selectList(new QueryWrapper<Permission>()
                .orderByAsc("nbr_level", "nbr_order", "cd_perm"));
    }

    /** 按 cd_perm 查权限节点，找不到返回 null */
    public Permission findByCode(String code) {
        if (StrUtil.isBlank(code)) {
            return null;
        }
        return permissionDao.selectOne(new QueryWrapper<Permission>().eq("cd_perm", code));
    }

    /**
     * 构权限树：根 = 菜单，子 = 按钮。
     *
     * @return 根节点列表，每个根节点的 {@code children} 已按 nbr_order 填好
     */
    public List<Permission> permissionTree() {
        List<Permission> all = allPermissions();
        Map<String, Permission> byId = new LinkedHashMap<>();
        for (Permission perm : all) {
            perm.setChildren(new ArrayList<>());
            byId.put(perm.getPermId(), perm);
        }
        List<Permission> roots = new ArrayList<>();
        for (Permission perm : all) {
            Permission parent = perm.getIdParent() == null ? null : byId.get(perm.getIdParent());
            if (parent == null) {
                roots.add(perm);
            } else {
                parent.getChildren().add(perm);
            }
        }
        return roots;
    }

    // ==================================================================
    // 四、角色 CRUD
    // ==================================================================

    /** 全部角色（含权限码与关联用户数，供角色管理页列表用） */
    public List<Role> allRoles() {
        List<Role> roles = roleDao.selectList(new QueryWrapper<Role>().orderByAsc("create_time"));
        for (Role role : roles) {
            role.setPermissionCodes(permissionCodesOfRole(role.getRoleId()));
            role.setUserCount(countRoleUsers(role.getRoleId()));
        }
        return roles;
    }

    /** 按名字精确查重 */
    public Role findRoleByName(String name) {
        if (StrUtil.isBlank(name)) {
            return null;
        }
        return roleDao.selectOne(new QueryWrapper<Role>().eq("name_role", name.trim()));
    }

    /**
     * 按 ID 查角色（不含权限码/用户数，编辑窗口回读用）。
     * <p>
     * 编辑前必须回读：列表数据可能是几分钟前加载的，拿旧对象 updateById 会把这期间
     * 别人改的属性覆盖回旧值。
     */
    public Role findRoleById(String roleId) {
        if (StrUtil.isBlank(roleId)) {
            return null;
        }
        return roleDao.selectById(roleId);
    }

    /**
     * 新增或保存角色，并整体替换其权限集合。
     *
     * @param role  {@code roleId} 为空表示新增
     * @param codes 该角色应持有的权限码全集（不在集合内的旧授权会被移除）
     */
    public void saveRole(Role role, Set<String> codes) {
        boolean inserting = StrUtil.isBlank(role.getRoleId());
        if (inserting) {
            role.setRoleId(newRoleId());
            role.setFlagBuiltin(StrUtil.isBlank(role.getFlagBuiltin()) ? "0" : role.getFlagBuiltin());
            role.setCreateTime(new Date());
            roleDao.insert(role);
            log.info("新增角色 {}（{} 项权限）", role.getNameRole(), codes.size());
        } else {
            roleDao.updateById(role);
            log.info("修改角色 {}（{} 项权限）", role.getNameRole(), codes.size());
        }
        replaceRolePermissions(role.getRoleId(), codes);
    }

    /** 删除角色及其授权关联（调用方负责校验内置角色与已分配用户） */
    public void deleteRole(String roleId) {
        rolePermissionDao.delete(new QueryWrapper<RolePermission>().eq("id_role", roleId));
        userRoleDao.delete(new QueryWrapper<UserRole>().eq("id_role", roleId));
        roleDao.deleteById(roleId);
        log.info("已删除角色 {}", roleId);
    }

    /** 角色下关联的用户数 */
    public int countRoleUsers(String roleId) {
        Long count = userRoleDao.selectCount(new QueryWrapper<UserRole>().eq("id_role", roleId));
        return count == null ? 0 : count.intValue();
    }

    private void replaceRolePermissions(String roleId, Set<String> codes) {
        rolePermissionDao.delete(new QueryWrapper<RolePermission>().eq("id_role", roleId));
        for (String code : codes) {
            grantPermission(roleId, code);
        }
    }

    /** 给角色授予一条权限码（权限码不存在时静默跳过——目录没登记的码不该能授出去） */
    private void grantPermission(String roleId, String code) {
        Permission perm = findByCode(code);
        if (perm == null) {
            log.warn("权限码 {} 不在权限目录中，跳过授予（角色 {}）", code, roleId);
            return;
        }
        RolePermission link = new RolePermission();
        link.setRoleId(roleId);
        link.setPermId(perm.getPermId());
        rolePermissionDao.insert(link);
    }

    // ==================================================================
    // 五、用户角色分配
    // ==================================================================

    /**
     * 整体替换一个用户的角色集合。
     * <p>
     * 用户管理页保存时调用；内置管理员 {@code admin} 的分配由 DbInitializer 兜底，
     * 这里不设特殊逻辑——能改 admin 角色的入口只有同时持有 {@code user:update} 的账号，
     * 而那本身就是管理员行为。
     */
    public void assignRoles(String userId, Set<String> roleIds) {
        userRoleDao.delete(new QueryWrapper<UserRole>().eq("id_user", userId));
        for (String roleId : roleIds) {
            if (roleDao.selectById(roleId) == null) {
                continue;
            }
            UserRole link = new UserRole();
            link.setUserId(userId);
            link.setRoleId(roleId);
            userRoleDao.insert(link);
        }
        log.info("用户 {} 的角色已更新为 {}", userId, roleIds);
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /** 权限主键：纳秒值，与其他业务表（script_mgmt / drawio_file）同规格 */
    private static String newPermId() {
        return String.valueOf(System.nanoTime());
    }

    private static String newRoleId() {
        return String.valueOf(System.nanoTime());
    }
}
