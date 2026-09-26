package com.sl.ui.remote;

import cn.hutool.core.util.StrUtil;
import com.sl.entity.ConnectionInfo;
import com.sl.ui.component.Dialogs;
import com.sl.ui.component.UiFactory;
import com.sl.ui.component.ViewBase;
import com.sl.util.Constants;
import com.sl.util.SSHClientUtil;
import com.sl.util.SshConnectionPool;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.charts.Chart;
import com.vaadin.flow.component.charts.model.Background;
import com.vaadin.flow.component.charts.model.BackgroundShape;
import com.vaadin.flow.component.charts.model.ChartType;
import com.vaadin.flow.component.charts.model.Configuration;
import com.vaadin.flow.component.charts.model.Credits;
import com.vaadin.flow.component.charts.model.DataLabels;
import com.vaadin.flow.component.charts.model.DataSeries;
import com.vaadin.flow.component.charts.model.DataSeriesItem;
import com.vaadin.flow.component.charts.model.Pane;
import com.vaadin.flow.component.charts.model.PlotOptionsSolidgauge;
import com.vaadin.flow.component.charts.model.Stop;
import com.vaadin.flow.component.charts.model.VerticalAlign;
import com.vaadin.flow.component.charts.model.YAxis;
import com.vaadin.flow.component.charts.model.style.SolidColor;
import com.vaadin.flow.component.grid.Grid;
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
 * Vaadin Charts 画仪表盘，这里 CPU / 内存同样用 Vaadin Charts 的
 * <b>Solid Gauge 仪表盘</b>（{@code ChartType.SOLIDGAUGE}）展示，弧线颜色随阈值变化
 * （&lt;50% 绿、50~80% 黄、更高红）；磁盘保持进度条逐挂载点列出。数据源与解析完全一致：
 * <ul>
 *   <li>CPU：直接解析 {@code top -bn1} 输出里 Cpu(s) 行的空闲值（100 - 空闲 =
 *       整机平均使用率，全核合计口径，与核数无关、不需要除核数）；旧的
 *       CUP_CMD 管道只作解析失败时的兜底；</li>
 *   <li>内存：{@code free -m}，used = total - free（不含 buffers/cache，偏保守，
 *       与旧实现口径一致）；</li>
 *   <li>磁盘：{@code df -h}，逐个挂载点列出，百分比列用 {@code \s+} 切分取值。</li>
 * </ul>
 * 进程 TOP 10 支持「按 CPU / 按内存」一键切换排序：默认 top 批模式就是 CPU 序，
 * 内存序在服务端对最近一次采样的解析结果重排，不增加 SSH 采集次数。
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

    private final AtomicBoolean running = new AtomicBoolean(false);

    private GaugeCard cpuCard;
    private GaugeCard memCard;
    private VerticalLayout diskPanel;
    private Span refreshLabel;
    /** 进程 TOP 10 表（top -bn1 解析后按当前排序取前 10 行） */
    private Grid<ProcRow> procGrid;
    /** 进程排序切换按钮：点击在「按 CPU / 按内存」之间切换 */
    private Button sortBtn;
    /** true = 进程表按 %MEM 降序；false = 按 %CPU 降序（top 批模式的默认序） */
    private volatile boolean sortByMem = false;
    /** 最近一次采样结果：切换排序时不重新采集，直接重排已有数据 */
    private volatile Sample lastSample;

    public void setPresetHost(ConnectionInfo info) {
        this.presetHost = info;
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        if (cpuCard != null) {
            return;
        }
        if (presetHost == null) {
            add(new Paragraph("缺少连接信息：请从「免登录服务器列表」的行内按钮打开本页。"));
            return;
        }
        String host = presetHost.getIdHost();
        add(title("指标监控（" + host + "）"));
        add(subtitle("CPU / 内存 / 磁盘使用率与进程 TOP 10，每 " + (REFRESH_INTERVAL_MS / 1000) + " 秒自动刷新"
                + "（数据经 SSH 执行 top / free / df 获取）。"));

        cpuCard = new GaugeCard("CPU");
        memCard = new GaugeCard("内存");
        HorizontalLayout meters = new HorizontalLayout(cpuCard.card, memCard.card);
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

        sortBtn = UiFactory.button("排序：CPU ↓", this::toggleProcSort);
        sortBtn.getElement().setAttribute("title", "点击在「按 CPU」与「按内存」之间切换");
        HorizontalLayout procHeader = new HorizontalLayout(section("进程 TOP 10（top）"), spacer(), sortBtn);
        procHeader.setWidthFull();
        procHeader.setAlignItems(FlexComponent.Alignment.CENTER);
        add(procHeader);
        procGrid = new Grid<>();
        procGrid.addClassName("standard-grid");
        procGrid.setWidthFull();
        procGrid.setHeight("330px");
        procGrid.addColumn(ProcRow::pid).setHeader("PID").setAutoWidth(true);
        procGrid.addColumn(ProcRow::user).setHeader("用户").setAutoWidth(true);
        procGrid.addColumn(r -> String.format("%.1f%%", r.cpu())).setHeader("CPU").setAutoWidth(true);
        procGrid.addColumn(r -> String.format("%.1f%%", r.mem())).setHeader("内存").setAutoWidth(true);
        procGrid.addColumn(ProcRow::command).setHeader("命令").setAutoWidth(true).setFlexGrow(1);
        add(procGrid);

        startRefreshLoop();
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        running.set(false);
        // SSH 连接已交给共享连接池（SshConnectionPool）管理，页面关闭不断开，
        // 空闲 10 分钟后由池自动回收
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
            String freeOut = client.executeCommand("free -m");
            s.mem = parseFree(freeOut);
            s.memDetail = parseFreeDetail(freeOut);

            String dfOut = client.executeCommand("df -h");
            s.disks = parseDf(dfOut);

            // top -bn1 一份输出同时喂两个指标：整机 CPU（解析 Cpu(s) 行的空闲值）
            // 和进程列表。旧的 CUP_CMD 管道（sed/awk）在输出格式稍有出入时会把
            // 整行透传，awk 拿 "Cpu(s):" 当数字算出 100 - 0 = 100%，页面上就
            // 永远显示 100%——所以改成在服务端按字段解析，只认「id」前的那个数。
            String topOut = client.executeCommand("top -bn1");
            s.cpu = parseCpuUsage(topOut);
            if (s.cpu == null) {
                // 个别极简系统 top 输出对不上格式时，退回旧管道兜底
                String cpuOut = client.executeCommand(Constants.CUP_CMD);
                s.cpu = parsePercent(cpuOut);
            }
            try {
                s.cores = Integer.parseInt(client.executeCommand("nproc").trim());
            } catch (Exception ignore) {
                s.cores = null; // 核数仅作展示，拿不到不影响主指标
            }

            s.procs = parseTopProcs(topOut);
        } catch (Exception e) {
            log.warn("采集监控数据失败：{}", e.getMessage());
            s.error = e.getMessage();
        }
        return s;
    }

    private void apply(Sample s) {
        lastSample = s;
        if (s.error != null) {
            refreshLabel.setText("采集失败：" + s.error);
            return;
        }
        cpuCard.update(s.cpu, s.cpu == null ? "-" : cpuDetail(s));
        if (s.mem == null) {
            memCard.update(null, "解析失败");
        } else {
            memCard.update(s.mem, s.memDetail);
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
        procGrid.setItems(sortedProcs(s));
        refreshLabel.setText("最近刷新：" + new java.util.Date());
    }

    /** CPU 卡片的明细文字：补上逻辑核数，说明这个百分比是「全机平均」口径。 */
    private static String cpuDetail(Sample s) {
        return s.cores != null && s.cores > 0
                ? String.format("全机平均 %.1f%%（%d 逻辑核合计 100%%）", s.cpu, s.cores)
                : String.format("%.1f %%", s.cpu);
    }

    /** 按当前排序取前 10：top 批模式默认按 %CPU 降序，内存序要在服务端重排。 */
    private java.util.List<ProcRow> sortedProcs(Sample s) {
        java.util.List<ProcRow> rows = new java.util.ArrayList<>(s.procs);
        if (sortByMem) {
            rows.sort((a, b) -> Double.compare(b.mem(), a.mem()));
        } else {
            rows.sort((a, b) -> Double.compare(b.cpu(), a.cpu()));
        }
        return rows.subList(0, Math.min(10, rows.size()));
    }

    /** 排序切换按钮：翻转排序方向，重排最近一次采样（不重新采集）。 */
    private void toggleProcSort() {
        sortByMem = !sortByMem;
        sortBtn.setText(sortByMem ? "排序：内存 ↓" : "排序：CPU ↓");
        Sample s = lastSample;
        if (s != null && s.error == null) {
            procGrid.setItems(sortedProcs(s));
        }
    }

    /** 取共享池里的连接（懒建、复用，最后使用 10 分钟后池自动断开）。 */
    private synchronized SSHClientUtil ensureSsh() throws IOException {
        return SshConnectionPool.acquire(presetHost);
    }

    // ------------------------------------------------------------------
    // 输出解析（口径与旧实现一致）
    // ------------------------------------------------------------------

    /** 一次采样的结果。字段手工赋值所以用普通类：record 不允许实例字段初始化器。 */
    private static final class Sample {
        Double cpu;
        /** 逻辑核数（nproc），仅用于展示口径，拿得到才算 */
        Integer cores;
        Double mem;
        String memDetail = "-";
        java.util.List<DiskMount> disks = new java.util.ArrayList<>();
        java.util.List<ProcRow> procs = new java.util.ArrayList<>();
        String error;
    }

    /**
     * 从 top -bn1 的输出里解析整机 CPU 使用率：找到 Cpu(s) 行，
     * 只取「id（le）」前的那个数作为空闲百分比，使用率 = 100 - 空闲。
     * <p>
     * 为什么不用旧的 CUP_CMD 管道做主路径：那条 sed/awk 在 top 输出格式或
     * locale 有出入时会把整行原样透传，awk 把 "Cpu(s):" 当数字（0），
     * 算出 100 - 0 = 100%——这就是页面上 CPU 一直 100% 的根因。
     * 这里按字段锚定解析，两种常见格式都认：
     * {@code Cpu(s):  0.7 us, ..., 98.9 id, ...} 与
     * {@code Cpu(s): 0.7% us, ..., 98.9% id, ...}。
     * top 的 Cpu(s) 本身就是全核平均（各核加总后归一到 100%），
     * 所以 100 - id 就是整机使用率，不需要再除以核数。
     */
    private static Double parseCpuUsage(String topOut) {
        if (StrUtil.isBlank(topOut)) {
            return null;
        }
        for (String line : topOut.split("\r?\n")) {
            String trimmed = line.trim();
            // 只认 Cpu(s) / %Cpu(s) 那一行，避免进程命令行里恰好出现 "id" 误命中
            if (!trimmed.contains("Cpu") || !trimmed.contains("(")) {
                continue;
            }
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(\\d+(?:\\.\\d+)?)\\s*%?\\s*id(?:,|\\s|$)").matcher(trimmed);
            if (m.find()) {
                double idle = Double.parseDouble(m.group(1));
                return Math.max(0, Math.min(100, 100 - idle));
            }
        }
        return null;
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

    /** top -bn1 的一行进程数据（top 非交互批模式默认按 %CPU 降序，前 10 行就是 CPU 大户）。 */
    private record ProcRow(String pid, String user, double cpu, double mem, String command) {
    }

    /**
     * 解析 top -bn1 输出，取进程行（最多 50 行，够两套排序各取前 10）。
     * <p>
     * 进程行紧跟在表头（含 {@code %CPU} 的那行）之后，遇到空行即结束。
     * 列间距不保证单空格，按 {@code \s+} 切分后从行尾倒数取 %CPU / %MEM——
     * 命令名本身可能含空格（如 {@code postgres: writer}），从行首取固定列会漂移，
     * 而 PID/USER 永远在行首前两列。命令名由第 12 列起原样拼接。
     * <p>
     * 行数上限从 10 放宽到 50：排序切换到「按内存」时 top 批模式的默认
     * CPU 序不适用，需要服务端拿更多行重排后再取前 10。
     */
    private static java.util.List<ProcRow> parseTopProcs(String out) {
        java.util.List<ProcRow> list = new java.util.ArrayList<>();
        if (StrUtil.isBlank(out)) {
            return list;
        }
        boolean headerSeen = false;
        for (String line : out.split("\r?\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                if (headerSeen) {
                    break; // 表头之后的空行 = 进程列表结束
                }
                continue;
            }
            if (!headerSeen) {
                if (trimmed.startsWith("PID") && trimmed.contains("%CPU")) {
                    headerSeen = true;
                }
                continue;
            }
            String[] cells = trimmed.split("\\s+");
            if (cells.length < 12) {
                continue;
            }
            try {
                double cpu = Double.parseDouble(cells[cells.length - 4]);
                double mem = Double.parseDouble(cells[cells.length - 3]);
                String command = String.join(" ",
                        java.util.Arrays.copyOfRange(cells, 11, cells.length));
                list.add(new ProcRow(cells[0], cells[1], cpu, mem, command));
            } catch (NumberFormatException e) {
                log.debug("top 进程行解析失败：{}", trimmed);
            }
            if (list.size() >= 50) {
                break;
            }
        }
        return list;
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

    /**
     * 单张 Solid Gauge 仪表盘卡片：标题 + 半圆仪表 + 明细文字。
     * <p>
     * 弧线颜色由 YAxis 的 stops 渐变控制（&lt;50% 绿、50~80% 黄、&gt;80% 红），
     * 数值随采样用 {@code DataSeries.update} 推给客户端，只传一个 Y 值，不整图重绘。
     * 图 deliberately 做得紧凑（200×160），三两张卡并排不挤占磁盘列表的空间。
     */
    private static final class GaugeCard {

        private final Chart chart = new Chart(ChartType.SOLIDGAUGE);
        private final DataSeriesItem point = new DataSeriesItem();
        private final DataSeries series = new DataSeries(point);
        private final Span detail = new Span();
        final VerticalLayout card;

        GaugeCard(String name) {
            chart.setWidth("270px");
            chart.setHeight("270px");

            Configuration conf = chart.getConfiguration();
            conf.setTitle("");
            conf.setCredits(new Credits(false));
            conf.getTooltip().setEnabled(false);

            // 半圆仪表：起点 -90°，终点 90°，中心压到底部
            Pane pane = conf.getPane();
            pane.setCenter(new String[]{"50%", "85%"});
            pane.setStartAngle(-90);
            pane.setEndAngle(90);
            Background bg = new Background();
            bg.setShape(BackgroundShape.ARC);
            bg.setInnerRadius("60%");
            bg.setOuterRadius("100%");
            pane.setBackground(bg);

            YAxis yAxis = conf.getyAxis();
            yAxis.setMin(0);
            yAxis.setMax(100);
            yAxis.setLineWidth(0);
            yAxis.setTickWidth(0);
            yAxis.getLabels().setEnabled(false);
            // 颜色阈值：0~50% 绿，50~80% 黄，80% 以上红（与旧进度条的口径一致）
            yAxis.setStops(
                    new Stop(0.5f, new SolidColor("#55BF3B")),
                    new Stop(0.8f, new SolidColor("#DDDF0D")),
                    new Stop(1.0f, new SolidColor("#DF5353")));

            PlotOptionsSolidgauge options = new PlotOptionsSolidgauge();
            DataLabels labels = new DataLabels();
            labels.setEnabled(true);
            labels.setFormat("{y:.1f}%");
            labels.setVerticalAlign(VerticalAlign.MIDDLE);
            labels.getStyle().setFontSize("18px");
            options.setDataLabels(labels);
            series.setPlotOptions(options);
            conf.addSeries(series);

            Span title = new Span(name);
            title.addClassName("view-section-title");
            detail.addClassName("search-status");
            VerticalLayout box = new VerticalLayout(title, chart, detail);
            box.setPadding(false);
            box.setSpacing(false);
            box.getStyle().set("gap", "4px");
            box.getStyle().set("min-width", "220px");
            box.getStyle().set("flex", "1 1 220px");
            box.getStyle().set("padding", "12px 16px");
            box.getStyle().set("border", "1px solid var(--lumo-contrast-10pct)");
            box.getStyle().set("border-radius", "var(--lumo-border-radius-l)");
            box.setDefaultHorizontalComponentAlignment(FlexComponent.Alignment.CENTER);
            card = box;
        }

        void update(Double percent, String text) {
            detail.setText(text);
            if (percent == null) {
                point.setY(0);
            } else {
                point.setY(Math.round(percent * 10) / 10.0);
            }
            series.update(point);
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
