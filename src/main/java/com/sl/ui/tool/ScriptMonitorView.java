package com.sl.ui.tool;

import cn.hutool.core.util.StrUtil;
import com.sl.entity.ConnectionInfo;
import com.sl.ui.component.CodeEditor;
import com.sl.ui.component.Dialogs;
import com.sl.ui.component.UiFactory;
import com.sl.ui.component.ViewBase;
import com.sl.util.SSHClientUtil;
import com.sl.util.Util;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.spring.annotation.SpringComponent;
import com.vaadin.flow.spring.annotation.UIScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.LinkedHashSet;

/**
 * 自定义脚本监控（其他工具 → 自定义脚本监控）。
 * <p>
 * 选一台服务器 + 选一个内置监控脚本（脚本内容可在编辑器里直接改），配置执行间隔后
 * 开始循环执行。内置脚本统一输出 {@code 指标名=值} 格式，每轮执行结果被解析成一行行
 * 指标，展示在下方 Grid 里（轮次 / 时间 / 指标 / 值 / 状态），保留最近 10 轮，
 * 最新的在最上面。异常指标（CPU / 内存 / 磁盘超阈值、进程数为 0、端口未监听、
 * 探测失败等）的值与状态标红。
 * <p>
 * 为什么自己维护一条长连接而不是走 {@code SshConnectionPool}：监控是分钟到小时级
 * 的持续循环，池的空闲回收（10 分钟）反而会让循环中途断连重连；这里连接跟随
 * 「一次监控会话」的生命周期，停止 / 关页 / 达到时长上限时统一关闭。
 * <p>
 * 自定义脚本也建议按 {@code 指标名=值} 每行一条输出；解析不到 {@code =} 的行会
 * 归入「其他输出」指标原样展示，但不参与异常判定。
 */
@SpringComponent
@UIScope
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class ScriptMonitorView extends ViewBase {

    private static final Logger log = LoggerFactory.getLogger(ScriptMonitorView.class);

    /** 最小执行间隔（秒）：再密的循环对远端机器就是不礼貌的了 */
    private static final long MIN_INTERVAL_SECONDS = 5;

    /** 默认监控时长（小时）：需求约定的默认循环 10 小时 */
    private static final double DEFAULT_DURATION_HOURS = 10;

    /** 结果保留轮数：Grid 只展示最近 10 次监控结果 */
    private static final int MAX_ROUNDS = 10;

    /** 单轮输出的解析上限（字符）：自定义脚本可能吐出巨量文本，这里兜底截断 */
    private static final int ROUND_OUTPUT_LIMIT = 8000;

    /** 单个指标值的展示上限（字符） */
    private static final int VALUE_LIMIT = 200;

    /** 异常阈值：CPU / 内存 / 磁盘 / 负载 */
    private static final double CPU_THRESHOLD = 80;
    private static final double MEM_THRESHOLD = 85;
    private static final double DISK_THRESHOLD = 90;
    private static final double LOAD_THRESHOLD = 8;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ------------------------------------------------------------------
    // 内置监控脚本（统一输出「指标名=值」，一行一个指标）
    // ------------------------------------------------------------------

    /** 一个内置监控脚本：名称 + 说明 + 脚本内容（用户可在编辑器里改） */
    public record BuiltinScript(String name, String desc, String script) {

        @Override
        public String toString() {
            return name;
        }
    }

    private static final String LINUX_SCRIPT = """
            echo "主机名=$(hostname)"
            echo "内核版本=$(uname -r)"
            echo "运行时长=$(uptime -p 2>/dev/null || uptime)"
            echo "CPU逻辑核数=$(nproc)"
            echo "负载(1分钟)=$(awk '{print $1}' /proc/loadavg)"
            echo "CPU使用率=$(idle=$(top -bn1 | grep -i 'cpu(s)' | grep -oE '[0-9.]+ *id' | head -1 | grep -oE '[0-9.]+'); awk -v i="${idle:-0}" 'BEGIN{printf "%.1f", 100-i}')%"
            echo "内存使用率=$(free | awk '/^Mem:/{printf "%.1f", $3/$2*100}')%"
            echo "内存已用=$(free -m | awk '/^Mem:/{printf "%dMB / %dMB", $3, $2}')"
            df -hP | awk '/^\\/dev/{printf "磁盘使用率(%s)=%s\\n", $6, $5}'
            ps aux --sort=-%cpu | awk 'NR>1 && NR<=6 {printf "CPU进程TOP%d=PID %s · %s · CPU %s%% · %s\\n", NR-1, $2, $1, $3, $11}'
            ps aux --sort=-%mem | awk 'NR>1 && NR<=6 {printf "内存进程TOP%d=PID %s · %s · MEM %s%% · %s\\n", NR-1, $2, $1, $4, $11}'
            """;

    private static final String MYSQL_SCRIPT = """
            echo "mysqld进程数=$(pgrep -f '[m]ysqld' | wc -l)"
            port=$((netstat -lnt 2>/dev/null || ss -lnt) | grep -q ':3306 ' && echo 已监听 || echo 未监听)
            echo "端口3306状态=$port"
            ver=$(mysql --version 2>/dev/null | head -1 || mysqladmin --version 2>/dev/null)
            echo "版本=${ver:-未安装}"
            ping=$(mysqladmin ping 2>/dev/null | head -1)
            echo "连通性=${ping:-探测失败}"
            st=$(mysqladmin status 2>/dev/null | head -1)
            echo "运行状态=${st:-需登录凭据，已跳过}"
            """;

    private static final String MONGODB_SCRIPT = """
            echo "mongod进程数=$(pgrep -f '[m]ongod' | wc -l)"
            port=$((netstat -lnt 2>/dev/null || ss -lnt) | grep -q ':27017 ' && echo 已监听 || echo 未监听)
            echo "端口27017状态=$port"
            ver=$(mongod --version 2>/dev/null | head -1)
            echo "版本=${ver:-未安装}"
            ping=$(mongosh --quiet --eval 'db.runCommand({ping:1}).ok' 2>/dev/null || mongo --quiet --eval 'db.runCommand({ping:1}).ok' 2>/dev/null)
            echo "服务可达=${ping:-探测失败}"
            """;

    private static final String REDIS_SCRIPT = """
            echo "redis进程数=$(pgrep -f '[r]edis-server' | wc -l)"
            port=$((netstat -lnt 2>/dev/null || ss -lnt) | grep -q ':6379 ' && echo 已监听 || echo 未监听)
            echo "端口6379状态=$port"
            ver=$(redis-server --version 2>/dev/null | head -1)
            echo "版本=${ver:-未安装}"
            ping=$(redis-cli ping 2>/dev/null | head -1)
            echo "连通性=${ping:-探测失败}"
            mem=$(redis-cli info memory 2>/dev/null | grep 'used_memory_human' | cut -d: -f2 | tr -d '\\r')
            echo "内存使用=${mem:-未知}"
            cli=$(redis-cli info clients 2>/dev/null | grep 'connected_clients' | cut -d: -f2 | tr -d '\\r ')
            echo "客户端连接数=${cli:-未知}"
            """;

    private static final String NGINX_SCRIPT = """
            echo "nginx进程数=$(pgrep -f '[n]ginx' | wc -l)"
            ports=$((netstat -lnt 2>/dev/null || ss -lnt) | grep -E ':80 |:443 ' | awk '{print $4}' | tr '\\n' ' ')
            echo "端口80/443状态=${ports:-未监听}"
            ver=$(nginx -v 2>&1 | head -1)
            echo "版本=${ver:-未安装}"
            code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://127.0.0.1/ 2>/dev/null)
            echo "HTTP状态码=${code:-探测失败}"
            cost=$(curl -s -o /dev/null -w '%{time_total}s' --max-time 5 http://127.0.0.1/ 2>/dev/null)
            echo "响应耗时=${cost:-探测失败}"
            err=$(tail -3 /var/log/nginx/error.log 2>/dev/null | tr '\\n' ' | ')
            echo "最近错误日志=${err:-无}"
            """;

    private static final List<BuiltinScript> BUILTIN_SCRIPTS = List.of(
            new BuiltinScript("Linux 系统监控", "主机 / 负载 / CPU / 内存 / 磁盘 / 进程 TOP5", LINUX_SCRIPT),
            new BuiltinScript("MySQL 监控", "进程 / 端口 / 版本 / 连通性 / 运行状态", MYSQL_SCRIPT),
            new BuiltinScript("MongoDB 监控", "进程 / 端口 / 版本 / 服务可达性", MONGODB_SCRIPT),
            new BuiltinScript("Redis 监控", "进程 / 端口 / 版本 / 连通性 / 内存与连接数", REDIS_SCRIPT),
            new BuiltinScript("Nginx 监控", "进程 / 端口 / 版本 / HTTP 探测 / 错误日志", NGINX_SCRIPT));

    // ------------------------------------------------------------------
    // 结果数据模型
    // ------------------------------------------------------------------

    /** 一行监控结果：属于哪一轮 + 指标名 + 值 + 是否异常 */
    record MetricRow(int round, String time, String name, String value, boolean abnormal) {
    }

    // ------------------------------------------------------------------
    // 界面与状态
    // ------------------------------------------------------------------

    private final ComboBox<ConnectionInfo> hostCombo = new ComboBox<>();
    private final ComboBox<BuiltinScript> scriptCombo = new ComboBox<>();
    private final NumberField intervalField = new NumberField();
    private final NumberField hoursField = new NumberField();
    private final CodeEditor scriptEditor = new CodeEditor(CodeEditor.MODE_SHELL);
    private final Grid<MetricRow> resultGrid = new Grid<>();
    private final Button startBtn;
    private final Button stopBtn;
    private final Span statusLabel = new Span("未开始");

    private final transient com.sl.mapper.ConnectionInfoMapper connectionInfoMapper;

    /** 最近 10 轮的指标行，队首最新；展示时按轮次倒序摊平进 Grid */
    private final transient Deque<List<MetricRow>> rounds = new ArrayDeque<>();

    /** 监控循环的停止位；停止按钮 / 关标签 / 达到时长上限都会置位 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    private transient volatile Thread worker;
    private transient SSHClientUtil ssh;
    private String connectedHost;

    public ScriptMonitorView(com.sl.mapper.ConnectionInfoMapper connectionInfoMapper) {
        this.connectionInfoMapper = connectionInfoMapper;
        add(title("自定义脚本监控"));
        add(subtitle("选服务器与监控脚本（可编辑），配置间隔后循环执行，结果按指标逐行展示，"
                + "保留最近 10 轮。CPU>80%、内存>85%、磁盘>90%、负载>8、进程/连接数为 0、"
                + "端口未监听或探测失败会标红。"));

        hostCombo.setItemLabelGenerator(info -> info.getIdHost()
                + (StrUtil.isBlank(info.getIdUser()) ? "" : "（" + info.getIdUser() + "）"));
        hostCombo.setWidth("280px");
        hostCombo.setPlaceholder("选择要监控的服务器");

        scriptCombo.setItems(BUILTIN_SCRIPTS);
        scriptCombo.setWidth("220px");
        scriptCombo.setValue(BUILTIN_SCRIPTS.get(0));

        intervalField.setValue(MIN_INTERVAL_SECONDS * 6.0); // 默认 30 秒
        intervalField.setMin(MIN_INTERVAL_SECONDS);
        intervalField.setStep(1.0);
        intervalField.setWidth("110px");

        hoursField.setValue(DEFAULT_DURATION_HOURS);
        hoursField.setMin(0);
        hoursField.setStep(1.0);
        hoursField.setWidth("110px");

        scriptEditor.setHeight("220px");
        scriptEditor.setWidth("80%");

        startBtn = UiFactory.primary("开始监控", this::startMonitor);
        stopBtn = UiFactory.danger("停止监控", this::stopMonitor);
        stopBtn.setEnabled(false);

        resultGrid.addColumn(MetricRow::round).setHeader("轮次").setAutoWidth(true)
                .setTextAlign(ColumnTextAlign.END);
        resultGrid.addColumn(MetricRow::time).setHeader("时间").setAutoWidth(true);
        resultGrid.addColumn(MetricRow::name).setHeader("指标").setAutoWidth(true);
        resultGrid.addComponentColumn(this::valueCell).setHeader("值").setFlexGrow(1);
        resultGrid.addComponentColumn(this::statusCell).setHeader("状态").setAutoWidth(true);
        resultGrid.setWidthFull();

        HorizontalLayout bar = toolbar(
                UiFactory.fieldRow("监控服务器", "92px", hostCombo),
                UiFactory.fieldRow("监控脚本", "92px", scriptCombo),
                startBtn, stopBtn);
        add(bar);
        HorizontalLayout bar2 = toolbar(
                UiFactory.fieldRow("执行间隔（秒）", "92px", intervalField),
                UiFactory.fieldRow("监控时长（小时）", "92px", hoursField),
                spacer(), statusLabel);
        add(bar2);

        add(section("监控脚本（可直接编辑，修改后下一轮生效）"));
        add(scriptEditor);

        add(section("监控结果（最近 10 轮）"));
        add(resultGrid);
        setFlexGrow(1, resultGrid);

        scriptCombo.addValueChangeListener(e -> {
            if (e.getValue() != null) {
                scriptEditor.setValue(e.getValue().script());
            }
        });
        scriptEditor.setValue(BUILTIN_SCRIPTS.get(0).script());

        loadHosts();
    }

    // ------------------------------------------------------------------
    // 主机列表（与远程日志搜索同一套数据：connection_info 表 + remoteServerList.conf）
    // ------------------------------------------------------------------

    private void loadHosts() {
        List<ConnectionInfo> hosts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try {
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ConnectionInfo> wrapper =
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            List<ConnectionInfo> fromDb = connectionInfoMapper.selectList(wrapper);
            if (fromDb != null) {
                for (ConnectionInfo info : fromDb) {
                    addHost(hosts, seen, info);
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
                addHost(hosts, seen, new ConnectionInfo(split[0], split[3], split[1], split[2], keyPath));
            }
        } catch (Exception e) {
            log.warn("读取 remoteServerList.conf 失败：{}", e.getMessage());
        }
        hostCombo.setItems(hosts);
    }

    private static void addHost(List<ConnectionInfo> hosts, Set<String> seen, ConnectionInfo info) {
        if (info == null || StrUtil.isBlank(info.getIdHost())) {
            return;
        }
        String key = info.getIdHost() + ":" + StrUtil.blankToDefault(info.getCdPort(), "22") + ":"
                + StrUtil.nullToEmpty(info.getIdUser());
        if (seen.add(key)) {
            hosts.add(info);
        }
    }

    // ------------------------------------------------------------------
    // 监控循环
    // ------------------------------------------------------------------

    /** 开始监控：参数校验在 UI 线程做，连接与循环都在后台线程。 */
    private void startMonitor() {
        if (running.get()) {
            Dialogs.warn("监控已在进行中");
            return;
        }
        ConnectionInfo info = hostCombo.getValue();
        if (info == null) {
            Dialogs.warn("请先选择要监控的服务器");
            return;
        }
        String script = scriptEditor.getValue();
        if (StrUtil.isBlank(script)) {
            Dialogs.warn("监控脚本不能为空，请选择内置脚本或自行编写");
            return;
        }
        Double interval = intervalField.getValue();
        if (interval == null || interval < MIN_INTERVAL_SECONDS) {
            Dialogs.warn("执行间隔不能小于 " + MIN_INTERVAL_SECONDS + " 秒");
            return;
        }
        Double hours = hoursField.getValue();
        long durationMs = (hours == null || hours <= 0)
                ? Long.MAX_VALUE
                : (long) (hours * 3600_000.0);

        running.set(true);
        startBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        rounds.clear();
        resultGrid.setItems(new ArrayList<>());
        statusLabel.setText("正在连接 " + info.getIdHost() + " ……");

        getUI().ifPresent(ui -> {
            Thread t = new Thread(() -> runLoop(ui, info, script,
                    (long) (interval * 1000), System.currentTimeMillis() + durationMs));
            t.setDaemon(true);
            t.setName("script-monitor-" + info.getIdHost());
            worker = t;
            t.start();
        });
    }

    /** 监控主循环：每轮连接检查 → 执行脚本 → 回 UI 解析并刷新 Grid → 睡到下一轮。 */
    private void runLoop(com.vaadin.flow.component.UI ui, ConnectionInfo info,
                         String script, long intervalMs, long deadline) {
        int round = 0;
        while (running.get() && System.currentTimeMillis() < deadline) {
            round++;
            String out;
            try {
                ensureConnected(info);
                out = ssh.executeCommandMerged(script);
            } catch (Exception e) {
                // 连接断了不直接终止：置空让下一轮重连，本轮输出记错误
                closeSshQuietly();
                out = "执行结果=【执行失败】"
                        + StrUtil.emptyToDefault(e.getMessage(), e.getClass().getSimpleName());
            }
            if (!running.get()) {
                return; // 期间被停止/关页，不再碰 UI
            }
            final int roundNo = round;
            final String outText = out;
            final String time = nowTime();
            safeAccess(ui, () -> {
                long abnormal = pushRound(roundNo, time, outText);
                statusLabel.setText("监控中 · 第 " + roundNo + " 轮 · 最新轮异常 " + abnormal + " 项 · 每 "
                        + (intervalMs / 1000) + " 秒一次 · "
                        + (deadline == Long.MAX_VALUE ? "不限时" : "截止 " + formatDeadline(deadline)));
            });
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        closeSshQuietly();
        if (running.get()) {
            // 走到这里 = 达到时长上限自然结束（被停止时 running 已为 false，由 stopMonitor 收尾）
            running.set(false);
            safeAccess(ui, () -> {
                statusLabel.setText("监控已结束（达到设定时长）");
                startBtn.setEnabled(true);
                stopBtn.setEnabled(false);
            });
        }
    }

    /** 停止按钮：置停止位 + 中断睡眠，让循环在 1 秒内退出并收尾。 */
    private void stopMonitor() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Thread t = worker;
        if (t != null) {
            t.interrupt();
        }
        closeSshQuietly();
        statusLabel.setText("已停止");
        startBtn.setEnabled(true);
        stopBtn.setEnabled(false);
        Dialogs.info("监控已停止");
    }

    /** 建立并保持监控会话的 SSH 连接（已在同一主机上则复用）。 */
    private synchronized void ensureConnected(ConnectionInfo info) throws IOException {
        if (ssh != null && ssh.isConnected() && info.getIdHost().equals(connectedHost)) {
            return;
        }
        closeSshQuietly();
        SSHClientUtil client = SSHClientUtil.connect(info);
        ssh = client;
        connectedHost = info.getIdHost();
    }

    private synchronized void closeSshQuietly() {
        if (ssh != null) {
            try {
                ssh.closeConnection();
            } catch (Exception ignore) {
                // 收尾阶段的关闭异常不影响主流程
            }
            ssh = null;
            connectedHost = null;
        }
    }

    /** 关标签 / 页面被顶掉时：立即停止循环并释放连接（需求：关闭 tab 则停止监控）。 */
    @Override
    protected void onDetach(DetachEvent detachEvent) {
        running.set(false);
        Thread t = worker;
        if (t != null) {
            t.interrupt();
        }
        closeSshQuietly();
        super.onDetach(detachEvent);
    }

    // ------------------------------------------------------------------
    // 结果解析与展示
    // ------------------------------------------------------------------

    /**
     * 解析一轮输出并压入结果队列（保留最近 {@value #MAX_ROUNDS} 轮），返回本轮异常数。
     * 必须在 UI 线程调用。
     */
    private long pushRound(int round, String time, String output) {
        List<MetricRow> rows = parseMetrics(round, time, output);
        rounds.addFirst(rows);
        while (rounds.size() > MAX_ROUNDS) {
            rounds.removeLast();
        }
        List<MetricRow> flat = new ArrayList<>();
        for (List<MetricRow> r : rounds) {
            flat.addAll(r);
        }
        resultGrid.setItems(flat);
        return rows.stream().filter(MetricRow::abnormal).count();
    }

    /**
     * 把一轮的原始输出解析成指标行。
     * <p>
     * 优先按 {@code 指标名=值} 解析（第一个 {@code =} 切分，等号前过长视为普通文本）；
     * 解析不出来的非空行归入「其他输出」原样展示。空值行丢弃——脚本里
     * {@code ${var:-探测失败}} 的写法已保证失败场景有兜底文案。
     */
    private static List<MetricRow> parseMetrics(int round, String time, String output) {
        List<MetricRow> rows = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return rows;
        }
        if (output.length() > ROUND_OUTPUT_LIMIT) {
            output = output.substring(0, ROUND_OUTPUT_LIMIT) + "\n……（本轮输出过长已截断）";
        }
        for (String line : output.split("\\r?\\n")) {
            String text = line.trim();
            if (text.isEmpty()) {
                continue;
            }
            String name;
            String value;
            int eq = text.indexOf('=');
            if (eq > 0 && eq <= 60) {
                name = text.substring(0, eq).trim();
                value = text.substring(eq + 1).trim();
            } else {
                name = "其他输出";
                value = text;
            }
            if (value.isEmpty()) {
                continue;
            }
            if (value.length() > VALUE_LIMIT) {
                value = value.substring(0, VALUE_LIMIT) + "…";
            }
            rows.add(new MetricRow(round, time, name, value, isAbnormal(name, value)));
        }
        return rows;
    }

    /**
     * 异常判定（集中在这一处，别散到脚本里）：
     * <ul>
     *   <li>百分比类：CPU &gt; 80%、内存 &gt; 85%、磁盘 &gt; 90%，其余百分比 &gt; 95%；</li>
     *   <li>负载（1 分钟）&gt; 8；</li>
     *   <li>进程数 / 连接数为 0（实例大概率没起来）；</li>
     *   <li>HTTP 状态码 &ge; 400；</li>
     *   <li>兜底文本：失败 / 未监听 / 未安装 / 未启动 / 无法 / 不可达 / 未找到 / 错误。</li>
     * </ul>
     */
    static boolean isAbnormal(String name, String value) {
        String v = value.trim();
        if (v.endsWith("%")) {
            Double pct = tryParseDouble(v.substring(0, v.length() - 1));
            if (pct != null) {
                if (name.contains("CPU")) {
                    return pct > CPU_THRESHOLD;
                }
                if (name.contains("内存")) {
                    return pct > MEM_THRESHOLD;
                }
                if (name.startsWith("磁盘")) {
                    return pct > DISK_THRESHOLD;
                }
                return pct > 95;
            }
        }
        if (name.contains("负载")) {
            Double load = tryParseDouble(v.split("\\s+")[0]);
            return load != null && load > LOAD_THRESHOLD;
        }
        if (name.contains("进程数") || name.contains("连接数")) {
            Integer n = tryParseInt(v);
            return n != null && n == 0;
        }
        if (name.contains("HTTP")) {
            Integer code = tryParseInt(v);
            return code != null && code >= 400;
        }
        return v.contains("失败") || v.contains("未监听") || v.contains("未安装")
                || v.contains("未启动") || v.contains("无法") || v.contains("不可达")
                || v.contains("未找到") || v.contains("错误");
    }

    private static Double tryParseDouble(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer tryParseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** 值单元格：异常时红字加粗。 */
    private Span valueCell(MetricRow row) {
        Span span = new Span(row.value());
        if (row.abnormal()) {
            span.getStyle().set("color", "var(--lumo-error-color)");
            span.getStyle().set("font-weight", "600");
        }
        return span;
    }

    /** 状态单元格：异常红字，正常绿字。 */
    private Span statusCell(MetricRow row) {
        Span span = new Span(row.abnormal() ? "异常" : "正常");
        span.getStyle().set("color", row.abnormal()
                ? "var(--lumo-error-color)" : "var(--lumo-success-color)");
        return span;
    }

    /** ui.access 的防抖包装：UI 已分离（页面刚被关掉）时静默丢弃。 */
    private static void safeAccess(com.vaadin.flow.component.UI ui, com.vaadin.flow.server.Command action) {
        try {
            ui.access(action);
        } catch (Exception ignore) {
            // 页面已关闭，这轮输出无处可去，丢弃即可
        }
    }

    private static String nowTime() {
        return java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    private static String formatDeadline(long deadline) {
        return java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(deadline),
                java.time.ZoneId.systemDefault()).format(TIME_FMT);
    }
}
