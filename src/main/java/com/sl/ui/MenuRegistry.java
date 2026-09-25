package com.sl.ui;

import com.sl.ui.admin.UserManagementView;
import com.sl.ui.docker.DockerComposeView;
import com.sl.ui.docker.DockerMgmtView;
import com.sl.ui.local.CommonProjectView;
import com.sl.ui.local.JarProjectView;
import com.sl.ui.local.LocalFileMgmtView;
import com.sl.ui.local.LogSearchView;
import com.sl.ui.local.TomcatMgmtView;
import com.sl.ui.remote.RemoteLogSearchView;
import com.sl.ui.remote.RemoteServerListView;
import com.sl.ui.tool.EncryptionView;
import com.sl.ui.tool.ScriptMgmtView;
import com.sl.util.Constants;
import com.vaadin.flow.component.Component;

import java.util.List;

/**
 * 主菜单的静态定义：分组、标题、要打开的组件、所需权限。
 * <p>
 * 这份结构是照旧项目 logviewer 的菜单定义抄下来的，
 * 保持分组和排序完全一致——运维同学的操作路径不该因为换框架而改变。
 * <p>
 * 与旧项目的差别在于**组件如何被找到**：旧项目用
 * {@code ComponentUtil.createComponentUseClassName("management.JarMgmtComponent")}
 * 拼类名后反射 {@code Class.forName("com.so.component." + 相对名)}；
 * 这里直接写 {@code Class} 字面量，编译期就能发现拼错的类名。
 * <p>
 * {@code view == null} 表示该功能还在迁移队列里，菜单上照常显示（让人看得见规划），
 * 点击时明确提示"还在迁移中"，而不是抛 404。
 */
public final class MenuRegistry {

    private MenuRegistry() {
    }

    /**
     * 一个菜单项。
     *
     * @param title      显示的标题，同时也是标签页去重的键
     * @param view       点击后要打开的组件类；{@code null} 表示尚未迁移
     * @param permission 需要持有的权限码（见 {@link Constants}）；{@code null} 表示登录即可访问
     */
    public record Item(String title, Class<? extends Component> view, String permission) {

        public boolean implemented() {
            return view != null;
        }
    }

    /** 一级菜单，点击展开下拉。 */
    public record Group(String title, List<Item> items) {
    }

    /**
     * 完整菜单。顺序与旧项目一致：本地 → 远程 → Docker → 安全 → 工具 → 用户。
     */
    public static final List<Group> GROUPS = List.of(
            new Group("概览", List.of(
                    new Item("使用手册", HomeView.class, null))),

            new Group("本地应用管理", List.of(
                    new Item("本地日志搜索", LogSearchView.class, Constants.QUERY),
                    new Item("本地文件管理", LocalFileMgmtView.class, Constants.QUERY),
                    new Item("jar项目管理", JarProjectView.class, Constants.UPDATE),
                    new Item("Tomcat管理", TomcatMgmtView.class, Constants.UPDATE),
                    new Item("通用项目管理", CommonProjectView.class, Constants.UPDATE))),

            new Group("远程应用管理", List.of(
                    new Item("远程日志搜索", RemoteLogSearchView.class, Constants.QUERY),
                    new Item("免登录服务器列表", RemoteServerListView.class, Constants.QUERY))),

            new Group("Docker管理", List.of(
                    new Item("容器和镜像管理", DockerMgmtView.class, Constants.UPDATE),
                    new Item("Docker-Compose管理", DockerComposeView.class, Constants.UPDATE))),

            new Group("其他工具", List.of(
                    new Item("加密工具", EncryptionView.class, null),
                    new Item("脚本管理", ScriptMgmtView.class, Constants.UPDATE))),

            new Group("用户管理", List.of(
                    new Item("用户管理", UserManagementView.class, Constants.ADD))));

    /** 默认打开的第一个标签页 */
    public static Item defaultItem() {
        return GROUPS.get(0).items().get(0);
    }
    public static Item remoteServer(){
        return GROUPS.get(2).items.get(1);
    }
}
