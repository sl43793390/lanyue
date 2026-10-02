package com.sl.ui.remote;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sl.entity.ConnectionInfo;
import com.sl.entity.ServerGroup;
import com.sl.mapper.ConnectionInfoMapper;
import com.sl.mapper.ServerGroupMapper;
import com.sl.ui.component.Dialogs;
import com.sl.ui.component.UiFactory;
import com.sl.util.SSHClientUtil;
import com.sl.util.SshConnectionPool;
import com.sl.util.Util;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.textfield.TextField;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.sftp.SFTPFileTransfer;
import net.schmizz.sshj.common.StreamCopier;
import net.schmizz.sshj.xfer.FileSystemFile;
import net.schmizz.sshj.xfer.TransferListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 文件分发弹窗：把一台机器上的文件批量分发到某个分组的其它机器。
 * <p>
 * 两段式弹窗：
 * <ol>
 *   <li><b>配置</b>——选分组（默认选中当前机器所在分组）、目标目录，
 *       表格列出该分组下的全部机器（数据来源与 {@link RemoteServerListView} 一致：
 *       connection_info 表 + remoteServerList.conf，去重合并），
 *       <b>当前机器按 主机:端口 自动剔除</b>；</li>
 *   <li><b>进度</b>——每台机器一行 ProgressBar + 状态文字
 *       （等待中 → 连接中 → 传输中 xx% → 已完成 / 失败：原因）。
 *       确认分发后弹窗不可关闭（外部点击 / ESC / 关闭按钮都禁用），
 *       全部机器出结果后才放行「关闭」。</li>
 * </ol>
 * 执行链路：先把源文件从当前机器 SFTP 下载到平台本地临时文件（一次），
 * 然后每台目标机器一个线程并行上传（连接走 {@link SshConnectionPool} 共享池）。
 * 字节级进度来自 sshj 的 {@code TransferListener}
 * （{@code SFTPClient.put} 委托给 {@code SFTPFileTransfer.upload}，监听器生效，
 * 已对照 sshj 0.40.0 源码确认）；进度回调按 300ms 节流后再 {@code ui.access} 回 UI，
 * 避免每个缓冲区都推一次页面变更。
 * <p>
 * 注意两点：
 * <ul>
 *   <li>目标目录不存在时会自动逐级创建（{@code SFTPClient.mkdirs}），
 *       同名文件直接覆盖——弹窗上有文案提示；</li>
 *   <li>传输完成后按「本地大小 = 远端大小」校验，不一致按失败处理。</li>
 * </ul>
 */
class FileDistributeDialog {

    private static final Logger log = LoggerFactory.getLogger(FileDistributeDialog.class);

    /** 进度回调回 UI 的节流间隔（毫秒）：再快肉眼看不出区别，只会白耗推送 */
    private static final long PROGRESS_THROTTLE_MS = 300;

    /** 与 {@link RemoteServerListView#DEFAULT_GROUP} 同源的虚拟默认分组名 */
    private static final String DEFAULT_GROUP = RemoteServerListView.DEFAULT_GROUP;

    /** 待分发的源文件（当前机器上的一个文件） */
    record SourceFile(String name, String remotePath, long size) {
    }

    /** 拿「当前机器」的共享连接（视图里的 {@code ensureSsh}），可能抛受检异常所以单独定义 */
    @FunctionalInterface
    interface SourceSshSupplier {
        SSHClientUtil get() throws Exception;
    }

    /** 一台目标机器的进度行：组件在构建时创建，状态由后台线程经 ui.access 更新 */
    private static final class MachineRow {
        final ConnectionInfo info;
        final ProgressBar bar = new ProgressBar(0, 1, 0);
        final Span status = new Span("等待中");

        MachineRow(ConnectionInfo info) {
            this.info = info;
            bar.setWidthFull();
        }
    }

    private final transient ConnectionInfoMapper connectionInfoMapper;
    private final transient ServerGroupMapper serverGroupMapper;

    FileDistributeDialog(ConnectionInfoMapper connectionInfoMapper, ServerGroupMapper serverGroupMapper) {
        this.connectionInfoMapper = connectionInfoMapper;
        this.serverGroupMapper = serverGroupMapper;
    }

    // ------------------------------------------------------------------
    // 第一段：配置弹窗
    // ------------------------------------------------------------------

    /**
     * 打开配置弹窗。必须在 UI 请求线程调用（读库、读 UI 状态都发生在这里）。
     *
     * @param source           待分发的源文件
     * @param currentHost      当前机器（用于剔除与预选分组）
     * @param defaultTargetDir 目标目录预填值（一般是文件页当前浏览的目录）
     * @param ui               发起请求的 UI，后台线程回调用它做 ui.access
     * @param sourceSsh        取当前机器共享连接的供应商
     * @param onFinished       全部结束且至少一台成功后的回调（如刷新文件列表），在 UI 线程执行
     */
    void open(SourceFile source, ConnectionInfo currentHost, String defaultTargetDir,
              UI ui, SourceSshSupplier sourceSsh, Runnable onFinished) {
        Dialog dialog = new Dialog();
        dialog.setWidth("880px");
        dialog.setHeaderTitle("分发文件：" + source.name());

        ComboBox<String> groupCombo = new ComboBox<>("分发分组");
        groupCombo.setWidth("240px");
        List<String> groups = loadGroups();
        groupCombo.setItems(groups);
        // 默认选中当前机器所在分组：往本组发是最常见的场景
        String currentGroup = groupOf(currentHost, true);
        groupCombo.setValue(groups.contains(currentGroup) ? currentGroup : groups.get(0));

        Grid<ConnectionInfo> machineGrid = UiFactory.grid(ConnectionInfo.class);
        machineGrid.setSelectionMode(Grid.SelectionMode.MULTI);
        machineGrid.addColumn(ConnectionInfo::getIdHost).setHeader("主机").setAutoWidth(true);
        machineGrid.addColumn(info -> StrUtil.blankToDefault(info.getCdPort(), "22"))
                .setHeader("端口").setAutoWidth(true);
        machineGrid.addColumn(ConnectionInfo::getIdUser).setHeader("用户").setAutoWidth(true);
        machineGrid.addColumn(info -> StrUtil.nullToEmpty(info.getDesc()))
                .setHeader("备注").setAutoWidth(true).setFlexGrow(1);
        machineGrid.setHeight("280px");
        machineGrid.setItems(loadMachines(groupCombo.getValue(), currentHost));
        groupCombo.addValueChangeListener(e ->
                machineGrid.setItems(loadMachines(e.getValue(), currentHost)));

        TextField dirField = UiFactory.textField("目标目录", "以 / 开头的绝对路径", "420px");
        dirField.setValue(StrUtil.blankToDefault(defaultTargetDir, "/tmp"));

        Span hint = new Span("已自动跳过当前机器 " + currentHost.getIdHost()
                + "；目标目录不存在会自动创建，同名文件将被覆盖。");
        hint.addClassName("view-subtitle");

        VerticalLayout content = new VerticalLayout(
                UiFactory.fieldRow("分发分组", groupCombo),
                new Span("目标机器（勾选后分发）"),
                machineGrid,
                UiFactory.fieldRow("目标目录", dirField),
                hint);
        content.setPadding(true);
        content.setSpacing(false);
        content.getStyle().set("gap", "10px");
        dialog.add(content);

        Button startBtn = Dialogs.primaryButton("开始分发", () -> {
            Set<ConnectionInfo> selected = machineGrid.getSelectedItems();
            if (selected.isEmpty()) {
                Dialogs.warn("请先勾选至少一台目标机器");
                return;
            }
            String dir = normalizeDir(dirField.getValue());
            if (dir == null) {
                Dialogs.warn("目标目录必须是绝对路径（以 / 开头）");
                return;
            }
            dialog.close();
            openProgress(source, new ArrayList<>(selected), dir, ui, sourceSsh, onFinished);
        });
        dialog.getFooter().add(Dialogs.cancelButton(dialog::close), startBtn);
        dialog.open();
    }

    // ------------------------------------------------------------------
    // 第二段：进度弹窗
    // ------------------------------------------------------------------

    private void openProgress(SourceFile source, List<ConnectionInfo> targets, String targetDir,
                              UI ui, SourceSshSupplier sourceSsh, Runnable onFinished) {
        Dialog dialog = new Dialog();
        dialog.setWidth("760px");
        dialog.setHeaderTitle("分发进度：" + source.name());
        // 分发进行中不允许关：关了进度行就没了，而任务还在后台跑
        dialog.setCloseOnOutsideClick(false);
        dialog.setCloseOnEsc(false);

        List<MachineRow> rows = new ArrayList<>();
        VerticalLayout list = new VerticalLayout();
        list.setPadding(false);
        list.setSpacing(false);
        list.getStyle().set("gap", "12px");
        for (ConnectionInfo info : targets) {
            MachineRow row = new MachineRow(info);
            rows.add(row);

            Span hostSpan = new Span(rowLabel(info));
            hostSpan.setWidth("240px");

            HorizontalLayout line = new HorizontalLayout(hostSpan, row.bar, row.status);
            line.setWidthFull();
            line.setAlignItems(FlexComponent.Alignment.CENTER);
            line.setSpacing(false);
            line.getStyle().set("gap", "12px");
            line.setFlexGrow(1, row.bar);
            list.add(line);
        }

        Span summary = new Span("正在准备源文件……");
        summary.setWidthFull();

        Button closeBtn = Dialogs.cancelButton(dialog::close);
        closeBtn.setEnabled(false);

        VerticalLayout content = new VerticalLayout(summary, list);
        content.setPadding(true);
        content.setSpacing(false);
        content.getStyle().set("gap", "14px");
        content.getStyle().set("max-height", "60vh");
        content.getStyle().set("overflow", "auto");
        dialog.add(content);
        dialog.getFooter().add(closeBtn);
        dialog.open();

        // 后台编排：先取源文件到本地临时，再并行分发；全部结束后统一收尾
        Thread orchestrator = new Thread(() -> {
            File tmp = null;
            AtomicInteger okCount = new AtomicInteger();
            try {
                tmp = File.createTempFile("lanyue-dist-", "-" + UiFactory.safeFileName(source.name()));
                String localPath = tmp.getAbsolutePath();
                ui.access(() -> summary.setText("正在从源机器读取文件……"));
                sourceSsh.get().downloadFile(source.remotePath(), localPath);
                long total = tmp.length();
                ui.access(() -> summary.setText("源文件就绪（" + humanSize(total) + "），"
                        + "正在向 " + rows.size() + " 台机器分发……"));

                Thread[] workers = new Thread[rows.size()];
                for (int i = 0; i < rows.size(); i++) {
                    MachineRow row = rows.get(i);
                    workers[i] = new Thread(() -> {
                        boolean ok = distributeToOne(row, new File(localPath), targetDir,
                                source.name(), total, ui);
                        if (ok) {
                            okCount.incrementAndGet();
                        }
                    }, "file-dist-" + row.info.getIdHost());
                    workers[i].start();
                }
                for (Thread worker : workers) {
                    worker.join();
                }
            } catch (Exception e) {
                log.warn("分发 {} 的源文件准备失败：{}", source.name(), e.getMessage());
                // 源文件都没拿到：所有机器直接标失败
                ui.access(() -> {
                    for (MachineRow row : rows) {
                        fail(row, "未开始（源文件读取失败）");
                    }
                });
            } finally {
                if (tmp != null) {
                    tmp.delete();
                }
                int ok = okCount.get();
                int fail = rows.size() - ok;
                boolean anyOk = ok > 0;
                ui.access(() -> {
                    summary.setText("分发结束：成功 " + ok + " 台，失败 " + fail + " 台。");
                    closeBtn.setEnabled(true);
                    if (anyOk) {
                        onFinished.run();
                    }
                });
            }
        }, "file-dist-main");
        orchestrator.start();
    }

    /** 分发到一台机器：连接 → 建目录 → 带进度上传 → 大小校验。返回是否成功。 */
    private boolean distributeToOne(MachineRow row, File localFile, String targetDir,
                                    String fileName, long totalSize, UI ui) {
        try {
            ui.access(() -> status(row, "连接中…", null));
            SSHClientUtil util = SshConnectionPool.acquire(row.info);
            SFTPClient sftp = util.getSftpClient();
            if (sftp.statExistence(targetDir) == null) {
                sftp.mkdirs(targetDir);
            }
            String remotePath = joinPath(targetDir, fileName);
            SFTPFileTransfer transfer = sftp.getFileTransfer();
            AtomicLong lastPush = new AtomicLong(0);
            transfer.setTransferListener(new TransferListener() {
                @Override
                public TransferListener directory(String name) {
                    return this;
                }

                @Override
                public StreamCopier.Listener file(String name, long size) {
                    return transferred -> {
                        long last = lastPush.get();
                        long now = System.currentTimeMillis();
                        if (now - last < PROGRESS_THROTTLE_MS && transferred < totalSize) {
                            return;
                        }
                        if (lastPush.compareAndSet(last, now)) {
                            double pct = totalSize <= 0 ? 1.0 : Math.min(1.0, transferred / (double) totalSize);
                            ui.access(() -> {
                                row.bar.setValue(pct);
                                status(row, "传输中 " + Math.round(pct * 100) + "%", null);
                            });
                        }
                    };
                }
            });
            sftp.put(new FileSystemFile(localFile), remotePath);
            long remoteSize = sftp.stat(remotePath).getSize();
            if (remoteSize != totalSize) {
                throw new IOException("传输后大小不一致（本地 " + totalSize + "，远端 " + remoteSize + "）");
            }
            ui.access(() -> {
                row.bar.setValue(1.0);
                done(row);
            });
            log.info("文件分发成功：{} -> {}:{}", fileName, row.info.getIdHost(), remotePath);
            return true;
        } catch (Exception e) {
            String reason = StrUtil.blankToDefault(e.getMessage(), "未知错误");
            log.warn("分发到 {} 失败：{}", row.info.getIdHost(), reason);
            ui.access(() -> fail(row, reason));
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 进度行的状态渲染（只允许在 ui.access 里调）
    // ------------------------------------------------------------------

    private static void status(MachineRow row, String text, String color) {
        row.status.setText(text);
        row.status.getStyle().remove("color");
        if (color != null) {
            row.status.getStyle().set("color", color);
        }
    }

    private static void done(MachineRow row) {
        status(row, "已完成", "var(--lumo-success-text-color)");
    }

    private static void fail(MachineRow row, String reason) {
        status(row, "失败：" + reason, "var(--lumo-error-text-color)");
    }

    private static String rowLabel(ConnectionInfo info) {
        String label = info.getIdHost() + ":" + StrUtil.blankToDefault(info.getCdPort(), "22");
        return StrUtil.blankToDefault(info.getDesc(), null) == null ? label
                : label + "（" + info.getDesc() + "）";
    }

    // ------------------------------------------------------------------
    // 分组与机器数据（与 RemoteServerListView 同源：库表 + remoteServerList.conf）
    // ------------------------------------------------------------------

    /** 分组定义：默认分组虚拟、永远排第一；库里与默认分组重名的忽略。 */
    private List<String> loadGroups() {
        List<String> groups = new ArrayList<>();
        groups.add(DEFAULT_GROUP);
        try {
            List<ServerGroup> fromDb = serverGroupMapper.selectList(
                    new QueryWrapper<ServerGroup>().orderByAsc("group_name"));
            if (fromDb != null) {
                for (ServerGroup group : fromDb) {
                    String name = group.getGroupName();
                    if (StrUtil.isNotBlank(name) && !groups.contains(name)) {
                        groups.add(name);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("读取服务器分组失败：{}", e.getMessage());
        }
        return groups;
    }

    /**
     * 某个分组下的机器（不含当前机器）。数据来源与服务器列表页一致：
     * connection_info 表 + remoteServerList.conf，按 主机:端口:用户 去重合并。
     */
    private List<ConnectionInfo> loadMachines(String group, ConnectionInfo currentHost) {
        List<ConnectionInfo> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String currentKey = hostPortKey(currentHost);
        try {
            List<ConnectionInfo> fromDb = connectionInfoMapper.selectList(new QueryWrapper<>());
            if (fromDb != null) {
                for (ConnectionInfo info : fromDb) {
                    addIfMatch(result, seen, info, group, currentKey, true);
                }
            }
        } catch (Exception e) {
            log.warn("读取数据库中的服务器列表失败：{}", e.getMessage());
        }
        try {
            for (String line : Util.getRemoteServerList()) {
                String[] split = line.split("=");
                if (split.length < 4) {
                    continue;
                }
                String keyPath = split.length > 4 ? split[4] : null;
                addIfMatch(result, seen,
                        new ConnectionInfo(split[0], split[3], split[1], split[2], keyPath),
                        group, currentKey, false);
            }
        } catch (Exception e) {
            log.warn("读取 remoteServerList.conf 失败：{}", e.getMessage());
        }
        return result;
    }

    /** 归组、去重、剔除当前机器之后再收进结果。 */
    private static void addIfMatch(List<ConnectionInfo> result, Set<String> seen, ConnectionInfo info,
                                   String group, String currentKey, boolean fromDb) {
        if (info == null || StrUtil.isBlank(info.getIdHost())) {
            return;
        }
        if (!group.equals(groupOf(info, fromDb))) {
            return;
        }
        // 当前机器按 主机:端口 识别（同一台机器配了两个账号也照跳，往本机分发没有意义）
        if (hostPortKey(info).equals(currentKey)) {
            return;
        }
        String key = info.getIdHost() + ":" + StrUtil.blankToDefault(info.getCdPort(), "22").trim()
                + ":" + StrUtil.nullToEmpty(info.getIdUser());
        if (!seen.add(key)) {
            return;
        }
        result.add(info);
    }

    /** 一台机器的归属分组：库来源按 cd_group，空白 / 配置文件来源都算默认分组。 */
    private static String groupOf(ConnectionInfo info, boolean fromDb) {
        String group = info.getCdGroup();
        return fromDb && StrUtil.isNotBlank(group) ? group.trim() : DEFAULT_GROUP;
    }

    /** 机器身份键：主机:端口。端口统一按 22 兜底，避免空端口和 "22" 被当成两台。 */
    private static String hostPortKey(ConnectionInfo info) {
        return info.getIdHost() + ":" + StrUtil.blankToDefault(info.getCdPort(), "22").trim();
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /** 目标目录归一化：必须以 / 开头，去掉尾斜杠（根目录除外）。 */
    private static String normalizeDir(String input) {
        String path = StrUtil.trimToEmpty(input);
        if (!path.startsWith("/")) {
            return null;
        }
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private static String joinPath(String dir, String name) {
        return dir.equals("/") ? "/" + name : dir + "/" + name;
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double v = bytes;
        for (String unit : new String[]{"KB", "MB", "GB", "TB"}) {
            v /= 1024;
            if (v < 1024) {
                return String.format("%.1f %s", v, unit);
            }
        }
        return String.format("%.1f PB", v / 1024);
    }
}
