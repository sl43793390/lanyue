package com.sl.ui.tool;

import com.sl.ui.component.Dialogs;
import com.sl.ui.component.UiFactory;
import com.sl.ui.component.ViewBase;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.component.textfield.NumberField;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cron 表达式生成器（其他工具 → Cron表达式生成器）。
 * <p>
 * 按目标调度器的原生字段逐项配置 Cron 表达式，三个页签对应三种主流格式：
 * <ul>
 *   <li><b>Quartz 7 段</b>：秒 分 时 日 月 周 年，支持 {@code ? L W #}；</li>
 *   <li><b>Spring 6 段</b>：秒 分 时 日 月 周（Spring 5.3+ 的 CronExpression 同样认
 *       {@code ? L W #}）；</li>
 *   <li><b>Linux 5 段</b>：分 时 日 月 周，经典 vixie-cron，无秒/年，不支持 {@code ? L W #}。</li>
 * </ul>
 * <p>
 * 每个字段提供「每值 / 周期 / 间隔 / 指定值」四种基础模式（日、周另有
 * 不指定 / 最近工作日 / 本月最后一天 / 本月最后一个星期X / 第N个星期X），
 * 右侧实时给出表达式、复制、反解析回界面、中文解释与最近 10 次触发时间。
 * <p>
 * 几个刻意的设计决策：
 * <ul>
 *   <li><b>不引 Quartz 依赖算触发时间</b>——只为算个「下一次什么时候跑」拉进整个
 *       quartz jar 不值当。这里自己实现标准的「年→月→日粗定位、时分秒细定位」
 *       逐级推进算法，{@code L W #} 按同样语义手写，几十行覆盖全部场景；</li>
 *   <li><b>日 / 周互斥自动处理</b>——Quartz 系语义里「日」和「周」不能同时指定，
 *       用户把日设成具体值时自动把周置为 {@code ?}（反之亦然），不靠用户记住规则；</li>
 *   <li><b>周字段编号</b>——Quartz 原生 1=周日、7=周六；Spring / Linux 0=周日、1=周一。
 *       界面统一展示「周日…周六」芯片和 1~7 的周期输入，生成 / 解析时按目标调度器
 *       换算，用户不需要背各家编号差异；</li>
 *   <li><b>所有变更同步计算</b>——生成 / 解释 / 推算都是请求线程内的纯内存计算
 *       （微秒级），不做防抖、不上后台线程。</li>
 * </ul>
 */
@Service
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class CronGeneratorView extends ViewBase {

    private static final long serialVersionUID = 1L;

    private static final DateTimeFormatter RUN_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 界面上的星期文案，下标 0 = 周日（ISO 的 7 映射到 0，见 {@link #dowName}）。 */
    private static final String[] DOW_NAMES = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};

    // ------------------------------------------------------------------
    // 模型定义
    // ------------------------------------------------------------------

    /** 目标调度器格式。 */
    private enum CronTab {
        QUARTZ("Quartz", 7),
        SPRING("Spring", 6),
        LINUX("Linux", 5);

        final String title;
        final int segments;

        CronTab(String title, int segments) {
            this.title = title;
            this.segments = segments;
        }
    }

    /** 字段种类。 */
    private enum FieldKind { SECOND, MINUTE, HOUR, DAY, MONTH, WEEK, YEAR }

    /** 字段支持的模式。 */
    private enum OptKind {
        EVERY,        // *
        NONE,         // ?（仅日 / 周，Quartz / Spring）
        RANGE,        // a-b
        STEP,         // a/s
        SPECIFIC,     // 逗号列表 / 单值
        LAST,         // L（日：本月最后一天）
        LAST_WEEKDAY, // LW（日：本月最后一个工作日）
        NEAREST,      // nW（日：n 号最近的工作日）
        LAST_WEEK,    // nL（周：本月最后一个星期 n）
        NTH_WEEK      // n#m（周：本月第 m 个星期 n）
    }

    /**
     * 字段定义。
     *
     * @param title   界面名称（秒 / 分钟 / …）
     * @param unit    单位文案（秒 / 分钟 / …）
     * @param min     界面输入的最小值（周字段统一按界面编号 1~7）
     * @param max     界面输入的最大值
     * @param allowed 允许符号提示
     * @param options 按展示顺序排列的可选模式
     */
    private record FieldDef(FieldKind kind, String title, String unit, int min, int max,
                            String allowed, List<OptKind> options) {
    }

    /** 单个字段的当前状态。 */
    private static class FieldState {
        OptKind kind = OptKind.EVERY;
        int from;
        int to;
        int step;
        /** SPECIFIC 模式的值集合；周字段存的是「目标调度器原生编号」。 */
        final TreeSet<Integer> values = new TreeSet<>();
        /** NEAREST 模式的「几号」。 */
        int nearestDay = 1;
        /** LAST_WEEK / NTH_WEEK 的星期（ISO 编号，1=周一 … 7=周日）。 */
        int weekDow = 5;
        /** NTH_WEEK 的第几个。 */
        int weekNth = 3;
    }

    /** 一个页签的完整模型：字段定义顺序 + 各字段状态。 */
    private class TabModel {

        final CronTab tab;
        final List<FieldDef> defs = new ArrayList<>();
        final Map<FieldKind, FieldState> states = new EnumMap<>(FieldKind.class);

        TabModel(CronTab tab) {
            this.tab = tab;
            for (FieldDef def : buildDefs(tab)) {
                defs.add(def);
                states.put(def.kind(), defaultState(tab, def.kind()));
            }
        }

        FieldState st(FieldKind kind) {
            return states.get(kind);
        }

        boolean has(FieldKind kind) {
            return states.containsKey(kind);
        }

        /** 生成表达式：按字段顺序逐段生成后拼接。 */
        String emit() {
            List<String> parts = new ArrayList<>();
            for (FieldDef def : defs) {
                parts.add(emitField(tab, def, st(def.kind())));
            }
            return String.join(" ", parts);
        }
    }

    private final Map<CronTab, TabModel> models = new EnumMap<>(CronTab.class);

    private CronTab currentTab = CronTab.QUARTZ;
    private FieldKind currentField = FieldKind.SECOND;

    private TabModel model() {
        return models.computeIfAbsent(currentTab, TabModel::new);
    }

    // ------------------------------------------------------------------
    // 字段定义与默认值
    // ------------------------------------------------------------------

    private static final List<OptKind> OPTS_BASIC =
            List.of(OptKind.EVERY, OptKind.RANGE, OptKind.STEP, OptKind.SPECIFIC);
    private static final List<OptKind> OPTS_DAY =
            List.of(OptKind.EVERY, OptKind.NONE, OptKind.RANGE, OptKind.STEP,
                    OptKind.NEAREST, OptKind.LAST, OptKind.SPECIFIC);
    private static final List<OptKind> OPTS_WEEK =
            List.of(OptKind.EVERY, OptKind.NONE, OptKind.RANGE, OptKind.STEP,
                    OptKind.LAST_WEEK, OptKind.NTH_WEEK, OptKind.SPECIFIC);

    private static List<FieldDef> buildDefs(CronTab tab) {
        List<FieldDef> defs = new ArrayList<>();
        boolean linux = tab == CronTab.LINUX;
        if (!linux) {
            defs.add(new FieldDef(FieldKind.SECOND, "秒", "秒", 0, 59, ", - * /", OPTS_BASIC));
        }
        defs.add(new FieldDef(FieldKind.MINUTE, "分钟", "分钟", 0, 59, ", - * /", OPTS_BASIC));
        defs.add(new FieldDef(FieldKind.HOUR, "小时", "小时", 0, 23, ", - * /", OPTS_BASIC));
        defs.add(new FieldDef(FieldKind.DAY, "日", "日", 1, 31,
                linux ? ", - * /" : ", - * / ? L W",
                linux ? OPTS_BASIC : OPTS_DAY));
        defs.add(new FieldDef(FieldKind.MONTH, "月份", "月份", 1, 12, ", - * /", OPTS_BASIC));
        defs.add(new FieldDef(FieldKind.WEEK, "周", "周", 1, 7,
                linux ? ", - * /" : ", - * / ? L #",
                linux ? OPTS_BASIC : OPTS_WEEK));
        if (tab == CronTab.QUARTZ) {
            defs.add(new FieldDef(FieldKind.YEAR, "年份", "年份", 1970, 2099, ", - * /", OPTS_BASIC));
        }
        return defs;
    }

    /** 新建页签时各字段的初始状态：凑出默认表达式「0 30 5 * * ? *」。 */
    private static FieldState defaultState(CronTab tab, FieldKind kind) {
        FieldState s = new FieldState();
        switch (kind) {
            case SECOND -> {
                s.kind = OptKind.SPECIFIC;
                s.values.add(0);
            }
            case MINUTE -> {
                s.kind = OptKind.SPECIFIC;
                s.values.add(30);
            }
            case HOUR -> {
                s.kind = OptKind.SPECIFIC;
                s.values.add(5);
            }
            case WEEK -> s.kind = tab == CronTab.LINUX ? OptKind.EVERY : OptKind.NONE;
            default -> s.kind = OptKind.EVERY;
        }
        return s;
    }

    // ------------------------------------------------------------------
    // 表达式生成
    // ------------------------------------------------------------------

    /** 周字段的界面编号 → 目标调度器原生编号。 */
    private static int weekNative(int n, CronTab tab) {
        return tab == CronTab.QUARTZ ? n : (n == 7 ? 0 : n);
    }

    /** 目标调度器原生编号 → 界面编号（生成后展示 / 汇总用，与 weekNative 互逆）。 */
    private static int weekDisplay(int nativeNum, CronTab tab) {
        return tab == CronTab.QUARTZ ? nativeNum : (nativeNum == 0 ? 7 : nativeNum);
    }

    private static String clamp(int v, int min, int max) {
        return String.valueOf(Math.max(min, Math.min(max, v)));
    }

    private String emitField(CronTab tab, FieldDef def, FieldState s) {
        return switch (s.kind) {
            case EVERY -> "*";
            case NONE -> "?";
            case RANGE -> clamp(s.from, def.min(), def.max()) + "-" + clamp(s.to, def.min(), def.max());
            case STEP -> clamp(s.from, def.min(), def.max()) + "/" + Math.max(1, s.step);
            case SPECIFIC -> {
                if (s.values.isEmpty()) {
                    yield "*";
                }
                StringBuilder sb = new StringBuilder();
                for (int v : s.values) {
                    if (sb.length() > 0) {
                        sb.append(',');
                    }
                    sb.append(v);
                }
                yield sb.toString();
            }
            case LAST -> "L";
            case LAST_WEEKDAY -> "LW";
            case NEAREST -> clamp(s.nearestDay, def.min(), def.max()) + "W";
            case LAST_WEEK -> weekNative(s.weekDow, tab) + "L";
            case NTH_WEEK -> weekNative(s.weekDow, tab) + "#" + Math.max(1, s.weekNth);
        };
    }

    // ------------------------------------------------------------------
    // 表达式解析（反解析到 UI）
    // ------------------------------------------------------------------

    /**
     * 把表达式解析进页签模型。任何不合法都抛
     * {@link IllegalArgumentException}（中文消息，直接给人看）。
     */
    private void parseInto(TabModel m, String expr) {
        String[] parts = expr.trim().split("\\s+");
        if (parts.length != m.defs.size()) {
            throw new IllegalArgumentException("期望 " + m.defs.size() + " 段，实际 " + parts.length
                    + " 段（" + m.tab.title + " " + m.tab.segments + " 段格式）");
        }
        for (int i = 0; i < parts.length; i++) {
            parseToken(m, m.defs.get(i), parts[i]);
        }
        if (m.tab != CronTab.LINUX) {
            FieldState dom = m.st(FieldKind.DAY);
            FieldState dow = m.st(FieldKind.WEEK);
            boolean domRestricted = dom.kind != OptKind.EVERY && dom.kind != OptKind.NONE;
            boolean dowRestricted = dow.kind != OptKind.EVERY && dow.kind != OptKind.NONE;
            if (domRestricted && dowRestricted) {
                throw new IllegalArgumentException("「日」和「周」不能同时指定具体值，其中一个须为 ?");
            }
        }
    }

    private static final Pattern P_NEAREST = Pattern.compile("(\\d+)W");
    private static final Pattern P_LAST_WEEK = Pattern.compile("(\\d+)L");
    private static final Pattern P_NTH_WEEK = Pattern.compile("(\\d+)#(\\d+)");

    private void parseToken(TabModel m, FieldDef def, String token) {
        CronTab tab = m.tab;
        FieldState s = m.st(def.kind());
        token = token.trim();

        if ("*".equals(token)) {
            s.kind = OptKind.EVERY;
            return;
        }
        if ("?".equals(token)) {
            if (!def.options().contains(OptKind.NONE)) {
                throw new IllegalArgumentException("「" + def.title() + "」字段不支持 ?（" + token + "）");
            }
            s.kind = OptKind.NONE;
            return;
        }
        if (def.kind() == FieldKind.DAY && "L".equals(token)) {
            s.kind = OptKind.LAST;
            return;
        }
        if (def.kind() == FieldKind.DAY && "LW".equals(token)) {
            s.kind = OptKind.LAST_WEEKDAY;
            return;
        }
        Matcher matcher = P_NEAREST.matcher(token);
        if (def.kind() == FieldKind.DAY && matcher.matches()) {
            s.kind = OptKind.NEAREST;
            s.nearestDay = parseIn(matcher.group(1), def.min(), def.max(), def.title(), token);
            return;
        }
        matcher = P_LAST_WEEK.matcher(token);
        if (def.kind() == FieldKind.WEEK && matcher.matches()) {
            s.kind = OptKind.LAST_WEEK;
            s.weekDow = nativeToDow(parseIn(matcher.group(1), weekMin(tab), weekMax(tab), def.title(), token), tab);
            return;
        }
        matcher = P_NTH_WEEK.matcher(token);
        if (def.kind() == FieldKind.WEEK && matcher.matches()) {
            s.kind = OptKind.NTH_WEEK;
            s.weekDow = nativeToDow(parseIn(matcher.group(1), weekMin(tab), weekMax(tab), def.title(), token), tab);
            s.weekNth = parseIn(matcher.group(2), 1, 5, def.title(), token);
            return;
        }
        if (token.contains("/")) {
            String[] seg = token.split("/", -1);
            if (seg.length != 2) {
                throw new IllegalArgumentException("「" + def.title() + "」字段格式错误：" + token);
            }
            int start = "*".equals(seg[0]) ? def.min() : parseIn(seg[0], def.min(), def.max(), def.title(), token);
            int step = parseIn(seg[1], 1, Math.max(1, def.max()), def.title(), token);
            s.kind = OptKind.STEP;
            s.from = start;
            s.step = step;
            return;
        }
        if (token.contains("-")) {
            String[] seg = token.split("-", -1);
            if (seg.length != 2) {
                throw new IllegalArgumentException("「" + def.title() + "」字段格式错误：" + token);
            }
            s.kind = OptKind.RANGE;
            s.from = parseWeekVal(seg[0], def, tab);
            s.to = parseWeekVal(seg[1], def, tab);
            return;
        }
        // 单值或逗号列表
        Set<Integer> parsed = new LinkedHashSet<>();
        for (String piece : token.split(",")) {
            parsed.add(parseWeekVal(piece, def, tab));
        }
        // 覆盖了全范围就归一成 *
        TreeSet<Integer> all = new TreeSet<>();
        for (int v = def.min(); v <= def.max(); v++) {
            all.add(v);
        }
        if (parsed.size() == all.size() && parsed.containsAll(all)) {
            s.kind = OptKind.EVERY;
            s.values.clear();
            return;
        }
        s.kind = OptKind.SPECIFIC;
        s.values.clear();
        s.values.addAll(parsed);
    }

    private static int weekMin(CronTab tab) {
        return tab == CronTab.QUARTZ ? 1 : 0;
    }

    private static int weekMax(CronTab tab) {
        return tab == CronTab.QUARTZ ? 7 : 7;
    }

    /** 解析一个数值；周字段对 Spring/Linux 接受 0~7 并把 7 归一为 0（同为周日）。 */
    private int parseWeekVal(String raw, FieldDef def, CronTab tab) {
        int v = parseIn(raw, def.kind() == FieldKind.WEEK ? weekMin(tab) : def.min(),
                def.kind() == FieldKind.WEEK ? weekMax(tab) : def.max(), def.title(), raw);
        if (def.kind() == FieldKind.WEEK && tab != CronTab.QUARTZ && v == 7) {
            v = 0;
        }
        return v;
    }

    private int parseIn(String raw, int min, int max, String fieldTitle, String token) {
        int v;
        try {
            v = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("「" + fieldTitle + "」字段不是合法数字：" + token);
        }
        if (v < min || v > max) {
            throw new IllegalArgumentException("「" + fieldTitle + "」字段值超出范围 " + min + "~" + max + "：" + token);
        }
        return v;
    }

    /** 调度器原生编号 → ISO 星期（1=周一 … 7=周日）。 */
    private static int nativeToDow(int nativeNum, CronTab tab) {
        if (tab == CronTab.QUARTZ) {
            return nativeNum == 1 ? 7 : nativeNum - 1;
        }
        return nativeNum == 0 ? 7 : nativeNum;
    }

    // ------------------------------------------------------------------
    // 触发时间推算引擎
    // ------------------------------------------------------------------

    /**
     * 把表达式状态编译成可匹配结构，并支持「从某时刻起下一次触发」的推算。
     * 算法：年 → 月 → 日 逐级粗定位（不匹配直接跳到下一个单位的开头），
     * 再在时 / 分 / 秒的有序集合里做 ceiling 细定位，任何一级跳变后回到
     * 粗定位重查。最坏情况（表达式永远不触发）按迭代上限放弃。
     */
    private class FireTimeMatcher {

        final TreeSet<Integer> seconds = new TreeSet<>();
        final TreeSet<Integer> minutes = new TreeSet<>();
        final TreeSet<Integer> hours = new TreeSet<>();
        final TreeSet<Integer> months = new TreeSet<>();
        final TreeSet<Integer> years; // null = 不限

        final FieldState domState;
        final FieldState dowState;
        final TreeSet<Integer> domValues = new TreeSet<>();
        final TreeSet<Integer> dowValues = new TreeSet<>(); // ISO 星期
        final boolean hasDom;
        final boolean hasDow;

        FireTimeMatcher(TabModel m) {
            if (m.has(FieldKind.SECOND)) {
                seconds.addAll(expandValues(m, FieldKind.SECOND));
            } else {
                seconds.add(0);
            }
            minutes.addAll(expandValues(m, FieldKind.MINUTE));
            hours.addAll(expandValues(m, FieldKind.HOUR));
            months.addAll(expandValues(m, FieldKind.MONTH));
            years = m.has(FieldKind.YEAR) && m.st(FieldKind.YEAR).kind != OptKind.EVERY
                    ? expandValues(m, FieldKind.YEAR) : null;

            domState = m.st(FieldKind.DAY);
            dowState = m.st(FieldKind.WEEK);
            if (domState.kind == OptKind.SPECIFIC || domState.kind == OptKind.RANGE || domState.kind == OptKind.STEP) {
                domValues.addAll(expandValues(m, FieldKind.DAY));
            }
            if (dowState.kind == OptKind.SPECIFIC || dowState.kind == OptKind.RANGE || dowState.kind == OptKind.STEP) {
                for (int v : expandValues(m, FieldKind.WEEK)) {
                    dowValues.add(nativeToDow(v, m.tab));
                }
            }
            hasDom = domState.kind != OptKind.EVERY && domState.kind != OptKind.NONE;
            hasDow = dowState.kind != OptKind.EVERY && dowState.kind != OptKind.NONE;
        }

        /** 把字段状态展开成有序值集合（特殊模式返回空集，不参与此展开）。 */
        private TreeSet<Integer> expandValues(TabModel m, FieldKind kind) {
            FieldDef def = m.defs.stream().filter(d -> d.kind() == kind).findFirst().orElseThrow();
            FieldState s = m.st(kind);
            TreeSet<Integer> set = new TreeSet<>();
            switch (s.kind) {
                case EVERY -> {
                    for (int v = def.min(); v <= def.max(); v++) {
                        set.add(v);
                    }
                }
                case RANGE -> {
                    int lo = Math.max(def.min(), Math.min(s.from, s.to));
                    int hi = Math.min(def.max(), Math.max(s.from, s.to));
                    for (int v = lo; v <= hi; v++) {
                        set.add(v);
                    }
                }
                case STEP -> {
                    int start = Math.max(def.min(), s.from);
                    for (int v = start; v <= def.max(); v += Math.max(1, s.step)) {
                        set.add(v);
                    }
                }
                case SPECIFIC -> set.addAll(s.values);
                default -> {
                    // L / W / ? 等特殊模式不展开
                }
            }
            return set;
        }

        /** 从 from 时刻（含）起下一次触发；算不出返回 null。 */
        LocalDateTime next(LocalDateTime from) {
            LocalDateTime t = from.truncatedTo(ChronoUnit.SECONDS);
            for (int guard = 0; guard < 200_000; guard++) {
                if (years != null && !years.contains(t.getYear())) {
                    Integer y = years.ceiling(t.getYear());
                    if (y == null) {
                        return null;
                    }
                    t = LocalDate.of(y, 1, 1).atStartOfDay();
                }
                if (!months.contains(t.getMonthValue())) {
                    t = LocalDate.of(t.getYear(), t.getMonthValue(), 1).plusMonths(1).atStartOfDay();
                    continue;
                }
                if (!dayMatches(t.toLocalDate())) {
                    t = t.toLocalDate().plusDays(1).atStartOfDay();
                    continue;
                }
                Integer h = hours.ceiling(t.getHour());
                if (h == null) {
                    t = t.toLocalDate().plusDays(1).atStartOfDay();
                    continue;
                }
                if (h != t.getHour()) {
                    t = t.withHour(h).withMinute(0).withSecond(0);
                }
                Integer mi = minutes.ceiling(t.getMinute());
                if (mi == null) {
                    t = t.plusHours(1).withMinute(0).withSecond(0);
                    continue;
                }
                if (mi != t.getMinute()) {
                    t = t.withMinute(mi).withSecond(0);
                }
                Integer sec = seconds.ceiling(t.getSecond());
                if (sec == null) {
                    t = t.plusMinutes(1).withSecond(0);
                    continue;
                }
                return t.withSecond(sec);
            }
            return null;
        }

        /** 「日」与「周」的联合判定；两者都指定时按 OR（与 vixie-cron / Quartz 语义一致）。 */
        boolean dayMatches(LocalDate d) {
            boolean domOk = hasDom && domDayMatch(d);
            boolean dowOk = hasDow && dowDayMatch(d);
            if (hasDom && hasDow) {
                return domOk || dowOk;
            }
            if (hasDom) {
                return domOk;
            }
            if (hasDow) {
                return dowOk;
            }
            return true;
        }

        private boolean domDayMatch(LocalDate d) {
            return switch (domState.kind) {
                case SPECIFIC, RANGE, STEP -> domValues.contains(d.getDayOfMonth());
                case LAST -> d.getDayOfMonth() == d.lengthOfMonth();
                case LAST_WEEKDAY -> {
                    int last = d.lengthOfMonth();
                    DayOfWeek w = LocalDate.of(d.getYear(), d.getMonthValue(), last).getDayOfWeek();
                    yield d.getDayOfMonth() == (w == DayOfWeek.SATURDAY ? last - 1
                            : w == DayOfWeek.SUNDAY ? last - 2 : last);
                }
                case NEAREST -> d.getDayOfMonth() == nearestWeekday(d);
                default -> false;
            };
        }

        /** n 号「最近的工作日」：周六提前到周五（n=1 且是周六则顺延到下周一），周日顺延一天（月末周日提前两天）。 */
        private int nearestWeekday(LocalDate d) {
            int last = d.lengthOfMonth();
            int n = Math.min(Math.max(domState.nearestDay, 1), last);
            DayOfWeek w = LocalDate.of(d.getYear(), d.getMonthValue(), n).getDayOfWeek();
            if (w == DayOfWeek.SATURDAY) {
                return n > 1 ? n - 1 : Math.min(n + 2, last);
            }
            if (w == DayOfWeek.SUNDAY) {
                return n < last ? n + 1 : n - 2;
            }
            return n;
        }

        private boolean dowDayMatch(LocalDate d) {
            return switch (dowState.kind) {
                case SPECIFIC, RANGE, STEP -> dowValues.contains(d.getDayOfWeek().getValue());
                case LAST_WEEK -> d.getDayOfWeek().getValue() == dowState.weekDow
                        && d.getDayOfMonth() + 7 > d.lengthOfMonth();
                case NTH_WEEK -> d.getDayOfWeek().getValue() == dowState.weekDow
                        && (d.getDayOfMonth() - 1) / 7 + 1 == dowState.weekNth;
                default -> false;
            };
        }

        /** 最近 n 次触发时刻。 */
        List<LocalDateTime> nextRuns(int count) {
            List<LocalDateTime> runs = new ArrayList<>();
            LocalDateTime cursor = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
            for (int i = 0; i < count; i++) {
                LocalDateTime next = next(cursor);
                if (next == null) {
                    break;
                }
                runs.add(next);
                cursor = next.plusSeconds(1);
            }
            return runs;
        }
    }

    // ------------------------------------------------------------------
    // 中文解释
    // ------------------------------------------------------------------

    private String dowName(int isoDow) {
        return DOW_NAMES[isoDow % 7];
    }

    /** 有序整数集合的紧凑描述：连续 ≥3 个合并为「a至b」，其余「、」分隔。 */
    private String compact(TreeSet<Integer> values) {
        List<String> pieces = new ArrayList<>();
        List<Integer> sorted = new ArrayList<>(values);
        int i = 0;
        while (i < sorted.size()) {
            int j = i;
            while (j + 1 < sorted.size() && sorted.get(j + 1) == sorted.get(j) + 1) {
                j++;
            }
            if (j - i >= 2) {
                pieces.add(sorted.get(i) + "至" + sorted.get(j));
            } else {
                for (int k = i; k <= j; k++) {
                    pieces.add(String.valueOf(sorted.get(k)));
                }
            }
            i = j + 1;
        }
        return String.join("、", pieces);
    }

    /** 中文解释（尽力可读，不求覆盖所有组合的完美措辞）。 */
    private String explain(TabModel m) {
        FireTimeMatcher mt = new FireTimeMatcher(m);

        // 日期部分
        String dayDesc;
        boolean domR = mt.hasDom;
        boolean dowR = mt.hasDow;
        if (!domR && !dowR) {
            dayDesc = "每天";
        } else if (domR && dowR) {
            dayDesc = domDesc(m, mt) + " 或 " + dowDesc(m, mt);
        } else if (domR) {
            dayDesc = domDesc(m, mt);
        } else {
            dayDesc = dowDesc(m, mt);
        }

        StringBuilder sb = new StringBuilder(dayDesc);
        if (mt.months.size() < 12) {
            sb.append("，仅 ").append(compact(mt.months)).append(" 月");
        }
        if (mt.years != null) {
            sb.append("，年份限 ").append(compact(mt.years));
        }

        // 时间部分：组合少就逐个列出，多了按字段描述
        int combos = mt.hours.size() * mt.minutes.size() * mt.seconds.size();
        sb.append(' ');
        if (combos > 0 && combos <= 20) {
            List<String> times = new ArrayList<>();
            for (int h : mt.hours) {
                for (int mi : mt.minutes) {
                    for (int s : mt.seconds) {
                        times.add(String.format("%02d:%02d:%02d", h, mi, s));
                    }
                }
            }
            sb.append(String.join("、", times));
        } else {
            sb.append(timeFieldPhrase("小时", mt.hours, 24, "时"))
                    .append(' ')
                    .append(timeFieldPhrase("分钟", mt.minutes, 60, "分"))
                    .append(' ')
                    .append(timeFieldPhrase("秒", mt.seconds, 60, "秒"));
        }
        sb.append(" 执行");
        return sb.toString();
    }

    private String domDesc(TabModel m, FireTimeMatcher mt) {
        return switch (m.st(FieldKind.DAY).kind) {
            case SPECIFIC, RANGE, STEP -> "每月 " + compact(mt.domValues) + " 日";
            case LAST -> "每月最后一天";
            case LAST_WEEKDAY -> "每月最后一个工作日";
            case NEAREST -> "每月 " + m.st(FieldKind.DAY).nearestDay + " 日的最近工作日";
            default -> "每天";
        };
    }

    private String dowDesc(TabModel m, FireTimeMatcher mt) {
        FieldState s = m.st(FieldKind.WEEK);
        return switch (s.kind) {
            case SPECIFIC, RANGE, STEP -> {
                List<String> names = new ArrayList<>();
                for (int dow : new TreeSet<>(mt.dowValues)) {
                    names.add(dowName(dow));
                }
                yield "每周 " + String.join("、", names);
            }
            case LAST_WEEK -> "每月最后一个 " + dowName(s.weekDow);
            case NTH_WEEK -> "每月第 " + s.weekNth + " 个 " + dowName(s.weekDow);
            default -> "每天";
        };
    }

    /** 单个时间字段的口语描述（组合数太多时兜底用）。 */
    private String timeFieldPhrase(String label, TreeSet<Integer> set, int full, String unit) {
        if (set.isEmpty()) {
            return "";
        }
        if (set.size() == full) {
            return "每" + unit;
        }
        if (set.size() == 1) {
            return set.first() + unit;
        }
        return label + "[" + compact(set) + unit + "]";
    }

    // ------------------------------------------------------------------
    // UI 组件
    // ------------------------------------------------------------------

    private final Div navColumn = new Div();
    private final Div editorPanel = new Div();
    private final Div summaryRow = new Div();
    private final Div runsList = new Div();
    private final Div examplesBox = new Div();
    private final Span exprSpan = new Span();
    private final Span explainSpan = new Span();
    private final Span exprCardSub = new Span();
    private Tabs tabs;

    public CronGeneratorView() {
        addClassName("cron-generator-view");
        add(title("Cron 表达式生成器"));
        add(subtitle("按目标调度器的原生字段逐项配置 Cron 表达式，支持每值、周期、间隔和指定值；"
                + "默认生成 Quartz 7 段表达式，另支持 Spring 6 段与 Linux 5 段。右侧实时展示表达式、"
                + "中文解释与最近的触发时机。"));
        add(UiFactory.divider());
        buildTabs();
        buildBody();
        selectTab(CronTab.QUARTZ);
    }

    private void buildTabs() {
        tabs = new Tabs();
        tabs.addClassName("cron-tabs");
        tabs.setWidthFull();
        for (CronTab t : CronTab.values()) {
            Tab tab = new Tab(t.title + "  " + t.segments + " 段");
            tab.getElement().setProperty("data-tab", t.name());
            tabs.add(tab);
        }
        tabs.addSelectedChangeListener(e -> {
            int index = tabs.getSelectedIndex();
            selectTab(CronTab.values()[index]);
        });
        add(tabs);
    }

    private void buildBody() {
        Div body = new Div();
        body.addClassName("cron-body");

        navColumn.addClassName("cron-nav");
        editorPanel.addClassName("cron-editor");
        summaryRow.addClassName("cron-summary");

        Div editorColumn = new Div(editorPanel, summaryRow);
        editorColumn.addClassName("cron-editor-column");

        body.add(navColumn, editorColumn, buildRightPanel());
        add(body);
    }

    private Div buildRightPanel() {
        Div panel = new Div();
        panel.addClassName("cron-panel");

        // —— 表达式卡片 ——
        Div exprCard = new Div();
        exprCard.addClassName("cron-card");
        exprCardSub.addClassName("cron-card-sub");
        Span exprTitle = new Span("表达式");
        exprTitle.addClassName("cron-card-title");

        Div exprBox = new Div();
        exprBox.addClassName("cron-expr-box");
        exprSpan.addClassName("cron-expr");
        Button copyBtn = new Button("复制", e -> {
            UiFactory.copyToClipboard(this, exprSpan.getText());
            Dialogs.success("已复制到剪贴板");
        });
        copyBtn.addClassName("cron-copy-btn");
        exprBox.add(exprSpan, copyBtn);

        Button parseBtn = UiFactory.button("反解析到 UI", this::parseToUi);
        Button recalcBtn = UiFactory.button("计算运行时间", () -> rebuildRuns(true));
        Div btnRow = new Div(parseBtn, recalcBtn);
        btnRow.addClassName("cron-btn-row");

        Span explainLabel = new Span("中文解释");
        explainLabel.addClassName("cron-explain-label");
        explainSpan.addClassName("cron-explain-text");
        Div explainBox = new Div(explainLabel, explainSpan);
        explainBox.addClassName("cron-explain");

        exprCard.add(exprTitle, exprCardSub, exprBox, btnRow, explainBox);

        // —— 运行时间卡片 ——
        Div runsCard = new Div();
        runsCard.addClassName("cron-card");
        Span runsTitle = new Span("最近 10 次运行时间");
        runsTitle.addClassName("cron-card-title");
        Span runsSub = new Span("按服务器时间推算，复杂规则以实际调度器为准。");
        runsSub.addClassName("cron-card-sub");
        runsList.addClassName("cron-runs");
        runsCard.add(runsTitle, runsSub, runsList);

        // —— 常用示例卡片 ——
        Div examplesCard = new Div();
        examplesCard.addClassName("cron-card");
        Span exTitle = new Span("常用示例");
        exTitle.addClassName("cron-card-title");
        Span exSub = new Span("点击「应用」直接载入界面。");
        exSub.addClassName("cron-card-sub");
        examplesBox.addClassName("cron-examples");
        examplesCard.add(exTitle, exSub, examplesBox);

        panel.add(exprCard, runsCard, examplesCard);
        return panel;
    }

    // ------------------------------------------------------------------
    // 渲染：页签 / 左侧导航 / 字段编辑器 / 汇总 / 右栏
    // ------------------------------------------------------------------

    private void selectTab(CronTab tab) {
        currentTab = tab;
        TabModel m = model();
        currentField = m.defs.get(0).kind();
        exprCardSub.setText(m.tab.title + " " + m.tab.segments + " 段原生生成与校验。");
        rebuildExamples();
        rebuildEditor();
        updateAll();
    }

    private void selectField(FieldKind kind) {
        currentField = kind;
        rebuildNav();
        rebuildEditor();
    }

    /** 左侧字段导航：名称 + 当前表达式值，点击切换编辑的字段。 */
    private void rebuildNav() {
        navColumn.removeAll();
        TabModel m = model();
        for (FieldDef def : m.defs) {
            Div item = new Div();
            item.addClassName("cron-nav-item");
            if (def.kind() == currentField) {
                item.addClassName("selected");
            }
            Span name = new Span(def.title());
            name.addClassName("cron-nav-name");
            Span value = new Span(emitField(m.tab, def, m.st(def.kind())));
            value.addClassName("cron-nav-value");
            item.add(name, value);
            item.addClickListener(e -> selectField(def.kind()));
            navColumn.add(item);
        }
    }

    /** 底部各字段当前值汇总条。 */
    private void rebuildSummary() {
        summaryRow.removeAll();
        TabModel m = model();
        for (FieldDef def : m.defs) {
            Div chip = new Div();
            chip.addClassName("cron-summary-item");
            Span label = new Span(def.title());
            label.addClassName("cron-summary-label");
            Span value = new Span(emitField(m.tab, def, m.st(def.kind())));
            value.addClassName("cron-summary-value");
            chip.add(label, value);
            chip.addClickListener(e -> selectField(def.kind()));
            summaryRow.add(chip);
        }
    }

    /** 中间字段编辑器：标题 + 允许符号 + 各模式行 + 指定值芯片区。 */
    private void rebuildEditor() {
        editorPanel.removeAll();
        TabModel m = model();
        FieldDef def = m.defs.stream().filter(d -> d.kind() == currentField).findFirst()
                .orElse(m.defs.get(0));
        currentField = def.kind();
        FieldState st = m.st(def.kind());

        Div head = new Div();
        head.addClassName("cron-editor-head");
        Span fieldTitle = new Span(def.title() + "字段");
        fieldTitle.addClassName("cron-field-title");
        Button reset = UiFactory.button("重置当前字段", () -> resetField(def));
        reset.addClassName("cron-reset-btn");
        head.add(fieldTitle, reset);

        Span allowed = new Span("允许符号: " + def.allowed());
        allowed.addClassName("cron-allowed");

        editorPanel.add(head, allowed);
        for (OptKind opt : def.options()) {
            editorPanel.add(optionRow(m, def, st, opt));
        }
        editorPanel.add(chipsArea(m, def, st));
    }

    private void resetField(FieldDef def) {
        TabModel m = model();
        m.states.put(def.kind(), defaultState(m.tab, def.kind()));
        rebuildEditor();
        updateAll();
    }

    /** 一个模式行：单选圆点 + 名称 + 说明 / 参数控件。 */
    private Div optionRow(TabModel m, FieldDef def, FieldState st, OptKind opt) {
        Div row = new Div();
        row.addClassName("cron-option-row");
        if (st.kind == opt) {
            row.addClassName("selected");
        }
        Span dot = new Span();
        dot.addClassName("cron-radio");
        Span name = new Span(optName(def, opt));
        name.addClassName("cron-opt-name");
        row.add(dot, name);

        switch (opt) {
            case EVERY -> row.add(descSpan("使用 *，表示该字段任意值都会匹配。"));
            case NONE -> row.add(descSpan("使用 ?，常用于“日”和“周”互斥时避免冲突。"));
            case RANGE -> {
                row.add(descSpan("从"));
                row.add(numberField(def, opt, st.from, def.min(), def.max(), v -> st.from = v));
                row.add(descSpan("到"));
                row.add(numberField(def, opt, st.to, def.min(), def.max(), v -> st.to = v));
                row.add(descSpan(def.unit()));
            }
            case STEP -> {
                row.add(descSpan("从"));
                row.add(numberField(def, opt, st.from, def.min(), def.max(), v -> st.from = v));
                row.add(descSpan("开始，每"));
                row.add(numberField(def, opt, st.step, 1, def.max(), v -> st.step = v));
                row.add(descSpan(def.unit() + "执行一次"));
            }
            case NEAREST -> {
                row.add(descSpan("每月"));
                row.add(numberField(def, opt, st.nearestDay, 1, 31, v -> st.nearestDay = v));
                row.add(descSpan("号最近的工作日"));
            }
            case LAST -> row.add(descSpan("使用 L，表示每月最后一天。"));
            case LAST_WEEK -> {
                row.add(descSpan("每月最后一个"));
                row.add(weekCombo(def, opt, st.weekDow, v -> st.weekDow = v));
            }
            case NTH_WEEK -> {
                row.add(descSpan("第"));
                row.add(numberField(def, opt, st.weekNth, 1, 5, v -> st.weekNth = v));
                row.add(descSpan("个"));
                row.add(weekCombo(def, opt, st.weekDow, v -> st.weekDow = v));
            }
            case SPECIFIC -> {
                // 芯片区在下方统一渲染，行内只放说明
                row.add(descSpan("勾选一个或多个值，可精确到具体哪一秒、哪一分钟、哪一天。"));
            }
        }
        row.addClickListener(e -> selectKind(def, opt));
        return row;
    }

    private String optName(FieldDef def, OptKind opt) {
        return switch (opt) {
            case EVERY -> "每" + def.unit();
            case NONE -> "不指定";
            case RANGE -> "周期";
            case STEP -> "间隔";
            case NEAREST -> "最近工作日";
            case LAST -> "本月最后一天";
            case LAST_WEEKDAY -> "本月最后一个工作日";
            case LAST_WEEK -> "本月最后一个星期";
            case NTH_WEEK -> "第 N 个星期";
            case SPECIFIC -> "指定具体值";
        };
    }

    private Span descSpan(String text) {
        Span span = new Span(text);
        span.addClassName("cron-opt-desc");
        return span;
    }

    /**
     * 切换字段模式：初始化该模式的默认参数，并处理日 / 周互斥
     * （一方指定了具体值，另一方自动改为不指定）。
     */
    private void selectKind(FieldDef def, OptKind opt) {
        TabModel m = model();
        FieldState st = m.st(def.kind());
        if (st.kind == opt) {
            return;
        }
        st.kind = opt;
        initDefaults(def, st, opt);
        applyExclusion(m, def.kind());
        rebuildEditor();
        updateAll();
    }

    private void initDefaults(FieldDef def, FieldState st, OptKind opt) {
        switch (opt) {
            case RANGE -> {
                if (st.from == 0 && st.to == 0) {
                    st.from = def.min();
                    st.to = def.max();
                }
            }
            case STEP -> {
                if (st.step <= 0) {
                    st.step = 1;
                }
                if (st.from == 0 && def.min() > 0) {
                    st.from = def.min();
                }
            }
            case NEAREST -> {
                if (st.nearestDay <= 0) {
                    st.nearestDay = 1;
                }
            }
            case NTH_WEEK -> {
                if (st.weekNth <= 0) {
                    st.weekNth = 1;
                }
            }
            default -> {
            }
        }
    }

    /** Quartz / Spring 语义：日、周不能同时指定；后指定的赢，先指定的退位为 ?。 */
    private void applyExclusion(TabModel m, FieldKind changed) {
        if (m.tab == CronTab.LINUX) {
            return;
        }
        FieldState changedSt = m.st(changed);
        boolean restricted = changedSt.kind != OptKind.EVERY && changedSt.kind != OptKind.NONE;
        if (!restricted) {
            return;
        }
        FieldKind other = changed == FieldKind.DAY ? FieldKind.WEEK : FieldKind.DAY;
        FieldState otherSt = m.st(other);
        if (otherSt.kind != OptKind.EVERY && otherSt.kind != OptKind.NONE) {
            otherSt.kind = OptKind.NONE;
        }
    }

    /** 数字输入框。修改后自动选中所在模式并刷新。 */
    private NumberField numberField(FieldDef def, OptKind opt, int value, int min, int max,
                                    Consumer<Integer> setter) {
        NumberField field = new NumberField();
        field.setValue((double) value);
        field.setMin(min);
        field.setMax(max);
        field.setStep(1);
        field.setWidth("84px");
        keepFocus(field);
        field.addValueChangeListener(e -> {
            Double v = e.getValue();
            if (v == null) {
                return;
            }
            setter.accept((int) Math.round(v));
            onParamChange(def, opt);
        });
        return field;
    }

    /** 星期下拉框（周日…周六），值以 ISO 星期保存。 */
    private ComboBox<String> weekCombo(FieldDef def, OptKind opt, int dow, Consumer<Integer> setter) {
        ComboBox<String> combo = new ComboBox<>();
        combo.setItems(DOW_NAMES);
        combo.setWidth("110px");
        int idx = dow == 7 ? 0 : dow;
        combo.setValue(DOW_NAMES[idx]);
        keepFocus(combo);
        combo.addValueChangeListener(e -> {
            String v = e.getValue();
            if (v == null) {
                return;
            }
            int i = List.of(DOW_NAMES).indexOf(v);
            setter.accept(i == 0 ? 7 : i);
            onParamChange(def, opt);
        });
        return combo;
    }

    /** 参数控件不触发所在模式行的点击（避免点击输入框时编辑器重建、焦点丢失）。 */
    private void keepFocus(Component c) {
        c.getElement().addEventListener("click", e -> {
        }).stopPropagation();
    }

    private void onParamChange(FieldDef def, OptKind opt) {
        TabModel m = model();
        FieldState st = m.st(def.kind());
        if (st.kind != opt) {
            st.kind = opt;
            initDefaults(def, st, opt);
            applyExclusion(m, def.kind());
            rebuildEditor();
        }
        updateAll();
    }

    /**
     * 「指定具体值」芯片区。芯片值：
     * 秒 / 分 / 时 / 日 / 月用字段数值本身，周按目标调度器原生编号，年直接年份。
     */
    private Div chipsArea(TabModel m, FieldDef def, FieldState st) {
        Div area = new Div();
        area.addClassName("cron-chips-area");
        Div chips = new Div();
        chips.addClassName("cron-chips");

        if (def.kind() == FieldKind.WEEK) {
            for (int i = 0; i < 7; i++) {
                // 界面芯片顺序固定「周日…周六」；Quartz 原生 1=周日…7=周六，
                // Spring / Linux 原生 0=周日、1=周一…6=周六
                int nativeNum = m.tab == CronTab.QUARTZ ? i + 1 : (i == 0 ? 0 : i);
                chips.add(chip(def, st, nativeNum, DOW_NAMES[i], m.tab));
            }
        } else {
            for (int v = def.min(); v <= def.max(); v++) {
                String label = def.kind() == FieldKind.SECOND || def.kind() == FieldKind.MINUTE
                        || def.kind() == FieldKind.HOUR
                        ? String.format("%02d", v)
                        : String.valueOf(v);
                chips.add(chip(def, st, v, label, m.tab));
            }
        }
        area.add(chips);
        return area;
    }

    private Span chip(FieldDef def, FieldState st, int value, String label, CronTab tab) {
        Span chip = new Span(label);
        chip.addClassName("cron-chip");
        if (st.kind == OptKind.SPECIFIC && st.values.contains(value)) {
            chip.addClassName("selected");
        }
        chip.addClickListener(e -> toggleChip(def, value, tab));
        return chip;
    }

    private void toggleChip(FieldDef def, int value, CronTab tab) {
        TabModel m = model();
        FieldState st = m.st(def.kind());
        if (st.kind != OptKind.SPECIFIC) {
            st.kind = OptKind.SPECIFIC;
            st.values.clear();
        }
        if (!st.values.remove(value)) {
            st.values.add(value);
        }
        if (st.values.isEmpty()) {
            // 空集合生成不出合法表达式：回退为「每值」
            st.kind = OptKind.EVERY;
        }
        applyExclusion(m, def.kind());
        rebuildEditor();
        updateAll();
    }

    // ------------------------------------------------------------------
    // 右栏刷新
    // ------------------------------------------------------------------

    private void updateAll() {
        TabModel m = model();
        rebuildNav();
        rebuildSummary();
        exprSpan.setText(m.emit());
        try {
            explainSpan.setText(explain(m));
        } catch (Exception e) {
            explainSpan.setText("无法解释当前表达式：" + e.getMessage());
        }
        rebuildRuns(false);
    }

    /** 重算最近 10 次触发时间；explicit 为 true 时（点了「计算运行时间」）给出提示。 */
    private void rebuildRuns(boolean explicit) {
        runsList.removeAll();
        TabModel m = model();
        List<LocalDateTime> runs;
        try {
            runs = new FireTimeMatcher(m).nextRuns(10);
        } catch (Exception e) {
            runs = List.of();
        }
        if (runs.isEmpty()) {
            Span hint = new Span("无法计算触发时间：当前表达式在该范围内不会触发。");
            hint.addClassName("cron-run-empty");
            runsList.add(hint);
        } else {
            for (int i = 0; i < runs.size(); i++) {
                Div row = new Div();
                row.addClassName("cron-run");
                Span time = new Span(runs.get(i).format(RUN_TIME_FMT));
                time.addClassName("cron-run-time");
                Span label = new Span(i == 0 ? "下一次" : "第 " + (i + 1) + " 次");
                label.addClassName("cron-run-label");
                row.add(time, label);
                runsList.add(row);
            }
        }
        if (explicit) {
            Dialogs.success(runs.isEmpty() ? "当前表达式不会触发" : "已重新计算 " + runs.size() + " 次运行时间");
        }
    }

    /** 把右栏当前表达式反解析回界面。 */
    private void parseToUi() {
        TabModel m = model();
        try {
            parseInto(m, exprSpan.getText());
        } catch (IllegalArgumentException e) {
            Dialogs.warn("反解析失败：" + e.getMessage());
            return;
        }
        currentField = m.defs.get(0).kind();
        rebuildEditor();
        updateAll();
        Dialogs.success("已反解析到界面");
    }

    // ------------------------------------------------------------------
    // 常用示例
    // ------------------------------------------------------------------

    private record Example(String label, String expr) {
    }

    private static List<Example> examples(CronTab tab) {
        return switch (tab) {
            case QUARTZ -> List.of(
                    new Example("每 2 秒", "0/2 * * * * ? *"),
                    new Example("每 5 分钟", "0 0/5 * * * ? *"),
                    new Example("每小时第 15 分", "0 15 * * * ? *"),
                    new Example("每天 05:30", "0 30 5 * * ? *"),
                    new Example("工作日 09:00", "0 0 9 ? * 2-6 *"),
                    new Example("每周一 10:00", "0 0 10 ? * 2 *"),
                    new Example("每月 1 号 00:00", "0 0 0 1 * ? *"),
                    new Example("每月最后一天 23:59", "0 59 23 L * ? *"),
                    new Example("每月最后一个周五 17:00", "0 0 17 ? * 6L *"),
                    new Example("每月第 2 个周一 09:00", "0 0 9 ? * 2#2 *"),
                    new Example("每年 1 月 1 日 00:00", "0 0 0 1 1 ? *"));
            case SPRING -> List.of(
                    new Example("每 2 秒", "0/2 * * * * ?"),
                    new Example("每 5 分钟", "0 0/5 * * * ?"),
                    new Example("每小时第 15 分", "0 15 * * * ?"),
                    new Example("每天 05:30", "0 30 5 * * ?"),
                    new Example("工作日 09:00", "0 0 9 ? * 1-5"),
                    new Example("每周一 10:00", "0 0 10 ? * 1"),
                    new Example("每月 1 号 00:00", "0 0 0 1 * ?"),
                    new Example("每月最后一天 23:59", "0 59 23 L * ?"),
                    new Example("每月最后一个周五 17:00", "0 0 17 ? * 5L"),
                    new Example("每月第 2 个周一 09:00", "0 0 9 ? * 1#2"));
            case LINUX -> List.of(
                    new Example("每 2 分钟", "*/2 * * * *"),
                    new Example("每小时第 15 分", "15 * * * *"),
                    new Example("每天 05:30", "30 5 * * *"),
                    new Example("工作日 09:00", "0 9 * * 1-5"),
                    new Example("每周日 12:00", "0 12 * * 0"),
                    new Example("每月 1 号 00:00", "0 0 1 * *"),
                    new Example("每季度首日 00:00", "0 0 1 1,4,7,10 *"));
        };
    }

    private void rebuildExamples() {
        examplesBox.removeAll();
        for (Example ex : examples(currentTab)) {
            Div row = new Div();
            row.addClassName("cron-example");
            Div text = new Div();
            text.addClassName("cron-example-text");
            Span label = new Span(ex.label());
            label.addClassName("cron-example-label");
            Span expr = new Span(ex.expr());
            expr.addClassName("cron-example-expr");
            text.add(label, expr);
            Button apply = UiFactory.small("应用", () -> applyExample(ex));
            apply.addClassName("cron-apply-btn");
            row.add(text, apply);
            examplesBox.add(row);
        }
    }

    private void applyExample(Example ex) {
        TabModel m = model();
        try {
            parseInto(m, ex.expr());
        } catch (IllegalArgumentException e) {
            Dialogs.error("示例表达式无法解析：" + e.getMessage());
            return;
        }
        currentField = m.defs.get(0).kind();
        rebuildEditor();
        updateAll();
        Dialogs.success("已应用示例：" + ex.label());
    }
}
