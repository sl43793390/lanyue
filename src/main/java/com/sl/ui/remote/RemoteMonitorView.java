package com.sl.ui.remote;

import cn.hutool.core.util.StrUtil;
import com.sl.entity.ConnectionInfo;
import com.sl.ui.component.Dialogs;
import com.sl.ui.component.ViewBase;
import com.sl.util.Constants;
import com.sl.util.SSHClientUtil;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.spring.annotation.SpringComponent;
import com.vaadin.flow.spring.annotation.UIScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 远程指标监控：CPU / 内存 / 磁盘 三张卡片，5 秒一轮自动刷新。
 * <p>
 * 对应旧项目 {@code com.so.component.remote.RemoteMonitorComponent}——旧页用
 * Vaadin Charts 画仪表盘，Charts 是收费组件，这里用 {@code vaadin-progress-bar}
 * 加颜色变化表达同样的信息（&lt;50% 绿、&lt;80% 黄、更高红），数据源与解析完全一致：
 * <ul>
 *   <li>CPU：{@code Constants.CUP_CMD}（top -bn1 取空闲百分比再算使用率）；</li>
 *   <li>内存：{@code free -m}，used = total - free（不含 buffers/cache，偏保守，
 *       与旧实现口径一致）；</li>
 *   <li>磁盘：{@code df -h}，逐个挂载点列出，百分比列用 {@code \s+} 切分取值。</li>
 * </ul>
 * 刷新线程是页面私有的守护线程，detach 时置停止位退出——不占公共线程池，
 * 也不留「页面关了还在跑」的僵尸循环。
 */
@SpringComponent
@UIScope
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class RemoteMonitorView extends ViewBase {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(RemoteMonitorView.class);

    /** 刷新周期，毫秒 */
    private static final long REFRESH_INTERVAL_MS = 5000;

    private transient ConnectionInfo presetHost;
    private transient SSHClientUtil ssh;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private Meter cpuMeter;
    private Meter memMeter;
    private VerticalLayout diskPanel;
    private Span refreshLabel;

    public void setPresetHost(ConnectionInfo info) {
        this.presetHost = info;
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        if (cpuMeter != null) {
            return;
        }
        if (presetHost == null) {
            add(new Paragraph("缺少连接信息：请从「免登录服务器列表」的行内按钮打开本页。"));
            return;
        }
        String host = presetHost.getIdHost();
        add(title("指标监控（" + host + "）"));
        add(subtitle("CPU / 内存 / 磁盘使用率，每 " + (REFRESH_INTERVAL_MS / 1000) + " 秒自动刷新"
                + "（数据经 SSH 执行 top / free / df 获取）。"));

        cpuMeter = new Meter("CPU");
        memMeter = new Meter("内存");
        HorizontalLayout meters = new HorizontalLayout(cpuMeter.card, memMeter.card);
        meters.setWidthFull();
        meters.setSpacing(true);
        add(meters);

        add(section("磁盘（df -h）"));
        diskPanel = new VerticalLayout();
        diskPanel.setPadding(false);
        diskPanel.setSpacing(false);
        diskPanel.getStyle().set("gap", "8px");
        add(diskPanel);

        refreshLabel = new Span("尚未获取数据");
        refreshLabel.addClassName("search-status");
        add(refreshLabel);

        startRefreshLoop();
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        running.set(false);
        if (ssh != null) {
            try {
                ssh.closeConnection();
            } catch (Exception e) {
                log.debug("关闭监控 SSH 连接失败：{}", e.getMessage());
            }
            ssh = null;
        }
        super.onDetach(detachEvent);
    }

    // ------------------------------------------------------------------
    // 刷新循环
    // ------------------------------------------------------------------

    private void startRefreshLoop() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        getUI().ifPresent(ui -> new Thread(() -> {
            while (running.get()) {
                Sample sample = collect();
                if (!running.get()) {
                    return;
                }
                ui.access(() -> apply(sample));
                try {
                    Thread.sleep(REFRESH_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "remote-monitor-" + presetHost.getIdHost()).start());
    }

    /** 一次采样：三条命令顺序执行。失败不致命，下一轮重试。 */
    private Sample collect() {
        Sample s = new Sample();
        try {
            SSHClientUtil client = ensureSsh();
            String cpuOut = client.executeCommand(Constants.CUP_CMD);
            s.cpu = parsePercent(cpuOut);

            String freeOut = client.executeCommand("free -m");
            s.mem = parseFree(freeOut);
            s.memDetail = parseFreeDetail(freeOut);

            String dfOut = client.executeCommand("df -h");
            s.disks = parseDf(dfOut);
        } catch (Exception e) {
            log.warn("采集监控数据失败：{}", e.getMessage());
            s.error = e.getMessage();
        }
        return s;
    }

    private void apply(Sample s) {
        if (s.error != null) {
            refreshLabel.setText("采集失败：" + s.error);
            return;
        }
        cpuMeter.update(s.cpu, s.cpu == null ? "-" : String.format("%.1f %%", s.cpu));
        if (s.mem == null) {
            memMeter.update(null, "解析失败");
        } else {
            memMeter.update(s.mem, String.format("%.1f %%（%s）", s.mem, s.memDetail));
        }
        diskPanel.removeAll();
        if (s.disks.isEmpty()) {
            Span empty = new Span("df 输出解析失败");
            empty.addClassName("empty-hint");
            diskPanel.add(empty);
        } else {
            for (DiskMount d : s.disks) {
                DiskMeter meter = new DiskMeter(d.mount);
                meter.update(d.usedPercent, d.detail);
                diskPanel.add(meter.row);
            }
        }
        refreshLabel.setText("最近刷新：" + new java.util.Date());
    }

    private synchronized SSHClientUtil ensureSsh() throws IOException {
        if (ssh == null) {
            ssh = SSHClientUtil.connect(presetHost);
        }
        return ssh;
    }

    // ------------------------------------------------------------------
    // 输出解析（口径与旧实现一致）
    // ------------------------------------------------------------------

    /** 一次采样的结果。字段手工赋值所以用普通类：record 不允许实例字段初始化器。 */
    private static final class Sample {
        Double cpu;
        Double mem;
        String memDetail = "-";
        java.util.List<DiskMount> disks = new java.util.ArrayList<>();
        String error;
    }

    /** top -bn1 的输出是「空闲百分比」，使用率 = 100 - 空闲。 */
    private static Double parsePercent(String out) {
        if (StrUtil.isBlank(out)) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+(?:\\.\\d+)?)\\s*%").matcher(out.trim());
        if (m.find()) {
            double idle = Double.parseDouble(m.group(1));
            return Math.max(0, Math.min(100, 100 - idle));
        }
        return null;
    }

    /** free -m：取 Mem 行的 total 与 free，used = total - free（旧实现口径）。 */
    private static Double parseFree(String out) {
        double[] pair = freePair(out);
        if (pair == null || pair[0] <= 0) {
            return null;
        }
        return Math.max(0, Math.min(100, (pair[0] - pair[1]) / pair[0] * 100));
    }

    private static String parseFreeDetail(String out) {
        double[] pair = freePair(out);
        if (pair == null) {
            return "-";
        }
        return String.format("已用 %.0f MB / 共 %.0f MB", pair[0] - pair[1], pair[0]);
    }

    /** 返回 [total, free]，单位 MB；解析不了返回 null。 */
    private static double[] freePair(String out) {
        if (StrUtil.isBlank(out)) {
            return null;
        }
        for (String line : out.split("\r?\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Mem:") || trimmed.startsWith("内存：")) {
                String[] cells = trimmed.split("\\s+");
                if (cells.length >= 4) {
                    try {
                        return new double[]{Double.parseDouble(cells[1]), Double.parseDouble(cells[3])};
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private record DiskMount(String mount, double usedPercent, String detail) {
    }

    /**
     * df -h 逐行解析。列间距不保证单空格，用 \s+ 切；
     * 跳过 tmpfs/devtmpfs/overlay 这类内存与容器伪文件系统，只保留真实挂载
     * （第 6 列以 / 开头即可，比如 / 和 /data）。
     */
    private static java.util.List<DiskMount> parseDf(String out) {
        java.util.List<DiskMount> list = new java.util.ArrayList<>();
        if (StrUtil.isBlank(out)) {
            return list;
        }
        for (String line : out.split("\r?\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("/dev/")) {
                continue;
            }
            String[] cells = trimmed.split("\\s+");
            if (cells.length < 6) {
                continue;
            }
            String useCell = cells[4];
            if (!useCell.endsWith("%")) {
                continue;
            }
            try {
                double used = Double.parseDouble(useCell.substring(0, useCell.length() - 1));
                String mount = cells[5];
                list.add(new DiskMount(mount, used,
                        String.format("%s / %s（%s 已用）", cells[2], cells[1], useCell)));
            } catch (NumberFormatException e) {
                log.debug("df 行解析失败：{}", trimmed);
            }
        }
        return list;
    }

    // ------------------------------------------------------------------
    // 展示件
    // ------------------------------------------------------------------

    /** 单条使用率：标题 + 百分比 + 进度条。颜色随阈值变化。 */
    private static final class Meter {

        private final Span label = new Span();
        private final ProgressBar bar = new ProgressBar(0, 100, 0);
        final VerticalLayout card;

        Meter(String name) {
            Span title = new Span(name);
            title.addClassName("view-section-title");
            label.addClassName("search-status");
            HorizontalLayout head = new HorizontalLayout(title, label);
            head.setWidthFull();
            head.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);
            VerticalLayout box = new VerticalLayout(head, bar);
            box.setPadding(false);
            box.setSpacing(false);
            box.getStyle().set("gap", "4px");
            box.getStyle().set("min-width", "260px");
            box.getStyle().set("flex", "1 1 260px");
            box.getStyle().set("padding", "12px 16px");
            box.getStyle().set("border", "1px solid var(--lumo-contrast-10pct)");
            box.getStyle().set("border-radius", "var(--lumo-border-radius-l)");
            card = box;
        }

        void update(Double percent, String text) {
            label.setText(text);
            if (percent == null) {
                bar.setValue(0);
                return;
            }
            bar.setValue(percent);
            String color = percent >= 80 ? "var(--lumo-error-text-color)"
                    : percent >= 50 ? "var(--lumo-warning-text-color)"
                    : "var(--lumo-success-text-color)";
            bar.getStyle().set("--lumo-primary-color", color);
            bar.getStyle().set("color", color);
        }
    }

    /** 磁盘行：挂载点 + 百分比 + 进度条，横向一排占满宽度。 */
    private static final class DiskMeter {

        private final Span label = new Span();
        private final ProgressBar bar = new ProgressBar(0, 100, 0);
        final HorizontalLayout row;

        DiskMeter(String mount) {
            Span title = new Span(mount);
            title.getStyle().set("min-width", "120px");
            label.addClassName("search-status");
            row = new HorizontalLayout(title, bar, label);
            row.setWidthFull();
            row.setAlignItems(FlexComponent.Alignment.CENTER);
            row.getStyle().set("gap", "12px");
            row.expand(bar);
        }

        void update(double percent, String text) {
            label.setText(String.format("%.0f%%  %s", percent, text));
            bar.setValue(percent);
            String color = percent >= 80 ? "var(--lumo-error-text-color)"
                    : percent >= 50 ? "var(--lumo-warning-text-color)"
                    : "var(--lumo-success-text-color)";
            bar.getStyle().set("--lumo-primary-color", color);
        }
    }
}
