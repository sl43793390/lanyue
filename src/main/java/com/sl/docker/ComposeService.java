package com.sl.docker;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.sl.docker.model.ComposeCliInfo;
import com.sl.docker.model.ComposeContainer;
import com.sl.docker.model.ComposeModel;
import com.sl.docker.model.ComposePortMapping;
import com.sl.docker.model.ComposeProject;
import com.sl.docker.model.ComposeResources;
import com.sl.docker.model.ComposeServiceInfo;
import com.sl.docker.model.DockerImage;
import com.sl.docker.model.DockerNetwork;
import com.sl.docker.model.DockerStat;
import com.sl.docker.model.DockerVolume;
import com.sl.util.SSHClientUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Docker Compose 领域操作。
 * <p>
 * <b>项目模型。</b>一个项目 = 宿主机上的一个目录 + 一组有序的 compose 文件。
 * 目录下放一个 {@code .logviewer-meta.json} 记录「文件顺序 / 项目名 / 描述」，
 * 因为 {@code -f base.yml -f override.yml} 的顺序会直接改变叠加结果，不能每次去目录里重扫重排。
 * 所有命令都拼成 {@code cd <dir> && <cli> -p <name> -f a.yml -f b.yml <子命令>}：
 * <ul>
 *   <li>先 {@code cd} 而不是用 {@code --project-directory}：{@code -f} 的相对路径在 v1/v2 里
 *       都按「当前工作目录」解析，只有 cd 进去才是两边一致的行为；</li>
 *   <li>显式 {@code -p}：默认项目名是从目录名推导的（会被转小写、去掉非法字符），
 *       一旦推导结果和我们记录的名字不一致，容器名/卷名前缀就全对不上了。</li>
 * </ul>
 * <p>
 * <b>为什么解析 {@code docker compose config} 而不是用户写的 yml。</b>
 * 只有 config 之后的结果才是 docker 真正执行的那份：多文件叠加完成、{@code ${VAR}} 已替换、
 * {@code env_file} 已合并、默认值已补齐。服务视图、依赖图、端口总览全部基于它。
 * <p>
 * <b>stdout / stderr 必须分开取。</b>{@link DockerExecutor#exec(String)} 会在命令末尾补
 * {@code 2>&1}，而 {@code docker compose config} 的警告（变量没设、字段已废弃）是打在 stderr 的 ——
 * 合并之后这些警告会插进 YAML 正文里，直接把解析搞崩。所以凡是「输出要被解析」的命令
 * 都走 {@link #runSeparated(String)}：重定向到远端临时文件、再分段取回。
 */
public class ComposeService {

    private static final Logger log = LoggerFactory.getLogger(ComposeService.class);

    /** 项目元信息文件名（记录文件顺序 / 项目名 / 描述） */
    public static final String META_FILE = ".logviewer-meta.json";
    /** 默认的项目根目录名，会拼到登录用户的 $HOME 后面 */
    public static final String DEFAULT_BASE_DIR_NAME = "logviewer-compose";
    public static final String DEFAULT_FILE = "docker-compose.yml";
    public static final String OVERRIDE_FILE = "docker-compose.override.yml";
    public static final String ENV_FILE = ".env";

    private static final String MARK_META = "__LV_META__:";
    private static final String MARK_PROJ = "__LV_PROJ__:";
    private static final String MARK_ENV = "__LV_ENV__:";
    private static final String MARK_OK = "__LV_OK__";
    private static final String MARK_ERR = "__LV_ERR__";
    private static final String MARK_RC = "__LV_RC__";
    private static final String MARK_OUT_BEGIN = "__LV_OUT_BEGIN__";
    private static final String MARK_OUT_END = "__LV_OUT_END__";
    private static final String MARK_ERR_BEGIN = "__LV_ERR_BEGIN__";
    private static final String MARK_ERR_END = "__LV_ERR_END__";

    /** 项目名只允许小写字母、数字、短横线和下划线：它就是 compose 的项目名，会变成容器名前缀 */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,38}$");
    /** 从 yml 里抠 ${VAR} / $VAR */
    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::?-[^}]*)?}");
    /** docker ps 的端口串里抠宿主机端口：8080->80/tcp */
    private static final Pattern HOST_PORT_PATTERN = Pattern.compile("(\\d+)->(\\d+)/(tcp|udp)");

    private static final int INSPECT_BATCH = 40;

    private final DockerExecutor executor;

    /** Compose CLI 探测结果，一次页面生命周期内只探一次 */
    private volatile ComposeCliInfo cliInfo;

    public ComposeService(DockerExecutor executor) {
        this.executor = executor;
    }

    public DockerExecutor getExecutor() {
        return executor;
    }

    /* ================================================================== */
    /* Compose CLI                                                         */
    /* ================================================================== */

    public ComposeCliInfo cliInfo() throws IOException {
        ComposeCliInfo cached = cliInfo;
        if (null == cached) {
            synchronized (this) {
                if (null == cliInfo) {
                    cliInfo = probeCli();
                }
                cached = cliInfo;
            }
        }
        return cached;
    }

    /** 用户装完 compose 回来点刷新时要能重新探到 */
    public void invalidateCli() {
        cliInfo = null;
    }

    /**
     * 探测目标机上可用的 compose 命令。
     * <p>
     * 顺序是固定的：先试 v2 插件（{@code docker compose}，官方推荐），没有再退回 v1 独立二进制。
     * 刻意不调 {@code executor.ensureDaemonReady()}：{@code compose version} 不需要 daemon，
     * 而「docker 没起但 compose 装了」这种情况恰好要靠这个探测结果来解释。
     */
    private ComposeCliInfo probeCli() {
        ComposeCliInfo info = new ComposeCliInfo();
        String prefix = StrUtil.emptyToDefault(executor.getCommandPrefix(), "docker");

        try {
            DockerExecutor.CmdResult v2 = executor.exec(prefix + " compose version --short");
            String out = StrUtil.trimToEmpty(v2.getOutput());
            if (v2.isOk() && StrUtil.isNotBlank(out)) {
                info.setInstalled(true);
                info.setKind("v2");
                info.setCommand(prefix + " compose");
                info.setVersion(firstLine(out));
                log.info("{} 上的 compose CLI：v2 插件 {}", executor.hostLabel(), info.getVersion());
                return info;
            }
        } catch (IOException e) {
            log.warn("探测 compose v2 插件失败：{}", e.getMessage());
        }

        try {
            String binary = null;
            DockerExecutor.CmdResult which = executor.exec(
                    "for p in docker-compose /usr/bin/docker-compose /usr/local/bin/docker-compose "
                            + "/usr/sbin/docker-compose; do "
                            + "if command -v \"$p\" >/dev/null 2>&1; then command -v \"$p\"; break; fi; done");
            for (String line : which.getOutput().split("\\R")) {
                String candidate = line.trim();
                if (candidate.startsWith("/")) {
                    binary = candidate;
                    break;
                }
            }
            if (null != binary) {
                DockerExecutor.CmdResult probe = executor.exec(binary + " version --short");
                String command = binary;
                if (!probe.isOk() && isPermissionDenied(probe.getOutput())) {
                    // 普通用户跑 docker-compose 也可能没权限访问 socket
                    DockerExecutor.CmdResult withSudo = executor.exec("sudo -n " + binary + " version --short");
                    if (withSudo.isOk()) {
                        command = "sudo -n " + binary;
                        probe = withSudo;
                    }
                }
                if (probe.isOk()) {
                    info.setInstalled(true);
                    info.setKind("v1");
                    info.setCommand(command);
                    info.setVersion(firstLine(probe.getOutput()));
                    log.info("{} 上的 compose CLI：v1 二进制 {}", executor.hostLabel(), info.getVersion());
                    return info;
                }
            }
        } catch (IOException e) {
            log.warn("探测 compose v1 二进制失败：{}", e.getMessage());
        }

        info.setInstalled(false);
        info.setKind("none");
        info.setDiagnosis("目标机没有可用的 Docker Compose。Rocky 8/9/10 与 Ubuntu 22.04 都建议装 Compose V2 插件，"
                + "装好后用 `docker compose`（中间是空格），不要再装已停止维护的 docker-compose（v1）。");
        return info;
    }

    private static boolean isPermissionDenied(String output) {
        String text = StrUtil.emptyToDefault(output, "").toLowerCase();
        return text.contains("permission denied");
    }

    private static String firstLine(String text) {
        for (String line : StrUtil.trimToEmpty(text).split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return "";
    }

    /* ================================================================== */
    /* 命令拼装                                                            */
    /* ================================================================== */

    /** compose 子命令前面那一串（含 cd），args 由调用方自行加引号 */
    public String buildCommand(ComposeProject project, String... args) throws IOException {
        ComposeCliInfo cli = cliInfo();
        if (!cli.isInstalled()) {
            throw new IOException(cli.getDiagnosis());
        }
        StringBuilder sb = new StringBuilder();
        if (StrUtil.isNotBlank(project.getDirectory())) {
            sb.append("cd ").append(DockerExecutor.q(project.getDirectory())).append(" && ");
        }
        sb.append(cli.getCommand());
        if (StrUtil.isNotBlank(project.getName())) {
            sb.append(" -p ").append(DockerExecutor.q(project.getName()));
        }
        boolean hasFile = false;
        for (String file : project.getFiles()) {
            if (StrUtil.isNotBlank(file)) {
                sb.append(" -f ").append(DockerExecutor.q(file));
                hasFile = true;
            }
        }
        if (!hasFile && StrUtil.isNotBlank(project.getDirectory())) {
            // 目录里有默认文件就交给 compose 自己找，否则 compose 会报 "no configuration file provided"
            sb.append(" -f ").append(DockerExecutor.q(DEFAULT_FILE));
        }
        for (String arg : args) {
            if (StrUtil.isNotBlank(arg)) {
                sb.append(' ').append(arg);
            }
        }
        return sb.toString();
    }

    /** 不绑定项目的 compose 命令（{@code ls} / {@code version} 这类） */
    public String buildGlobalCommand(String... args) throws IOException {
        ComposeCliInfo cli = cliInfo();
        if (!cli.isInstalled()) {
            throw new IOException(cli.getDiagnosis());
        }
        StringBuilder sb = new StringBuilder(cli.getCommand());
        for (String arg : args) {
            if (StrUtil.isNotBlank(arg)) {
                sb.append(' ').append(arg);
            }
        }
        return sb.toString();
    }

    /* ------------------------------------------------------------------ */
    /* 执行                                                                */
    /* ------------------------------------------------------------------ */

    /**
     * 跑一条命令，stdout / stderr 分开带回来，并附上真实退出码。
     * <p>
     * 远端临时文件是必要的：{@link DockerExecutor#exec(String)} 统一补 {@code 2>&1}，
     * 而 compose 的警告在 stderr 上，合并后会把 YAML/JSON 正文污染掉。
     */
    public ExecOutcome runSeparated(String command) throws IOException {
        executor.ensureDaemonReady();
        String tag = UUID.randomUUID().toString().replace("-", "");
        String outPath = "/tmp/.lv-compose-" + tag + ".out";
        String errPath = "/tmp/.lv-compose-" + tag + ".err";
        String script = "(" + command + ") >" + outPath + " 2>" + errPath + "; "
                + "printf '\\n" + MARK_RC + "%s\\n' \"$?\"; "
                + "printf '" + MARK_OUT_BEGIN + "\\n'; cat " + outPath + " 2>/dev/null; "
                + "printf '\\n" + MARK_OUT_END + "\\n'; "
                + "printf '" + MARK_ERR_BEGIN + "\\n'; cat " + errPath + " 2>/dev/null; "
                + "printf '\\n" + MARK_ERR_END + "\\n'; "
                + "rm -f " + outPath + " " + errPath;
        DockerExecutor.CmdResult raw = executor.exec(script);
        return ExecOutcome.parse(raw.getOutput());
    }

    /** 跑一条命令，输出合并（给用户看警告比分离更重要时用，例如 up / down） */
    public String runCombined(String action, String command) throws IOException {
        executor.ensureDaemonReady();
        DockerExecutor.CmdResult result = executor.exec(command);
        if (!result.isOk()) {
            throw new IOException(action + " 失败：" + result.errorMessage());
        }
        return result.getOutput();
    }

    /* ================================================================== */
    /* 项目发现                                                            */
    /* ================================================================== */

    /** 默认项目根目录：登录用户的 $HOME 下，不需要 sudo 就能写 */
    public String defaultBaseDir() {
        try {
            DockerExecutor.CmdResult result = executor.exec("echo \"$HOME\"");
            String home = firstLine(result.getOutput());
            if (home.startsWith("/") && !"/".equals(home)) {
                return home + "/" + DEFAULT_BASE_DIR_NAME;
            }
        } catch (IOException e) {
            log.warn("探测 $HOME 失败：{}", e.getMessage());
        }
        return "/opt/" + DEFAULT_BASE_DIR_NAME;
    }

    /**
     * 列出目标机上的所有 compose 项目：本系统创建过的（扫元信息文件）+ 外来项目（{@code compose ls}）。
     * <p>
     * 一共 4 次 SSH 往返：读元信息 → 批量取服务数 / .env → 一次 docker ps 拿全部 compose 容器
     * → compose ls。刻意全部批量，而不是每个项目一次命令（10 个项目就是 30 次往返）。
     */
    public List<ComposeProject> listProjects(String baseDir) throws IOException {
        executor.ensureDaemonReady();
        List<ComposeProject> managed = loadManagedProjects(baseDir);
        fillProjectMeta(managed);
        Map<String, List<ComposeContainer>> containersByProject = allComposeContainers();

        Map<String, ComposeProject> merged = new LinkedHashMap<String, ComposeProject>();
        for (ComposeProject project : managed) {
            merged.put(project.getName(), project);
        }

        // 外来项目：只登记过 compose ls 的，能力受限但至少能看和启停
        try {
            for (ComposeProject external : loadExternalProjects()) {
                ComposeProject exists = merged.get(external.getName());
                if (null == exists) {
                    merged.put(external.getName(), external);
                } else if (StrUtil.isNotBlank(external.getStatusText())) {
                    exists.setStatusText(external.getStatusText());
                }
            }
        } catch (Exception e) {
            log.warn("读取 docker compose ls 失败：{}", e.getMessage());
        }

        List<ComposeProject> result = new ArrayList<ComposeProject>();
        for (ComposeProject project : merged.values()) {
            applyContainers(project, containersByProject.get(project.getName()));
            result.add(project);
        }
        return result;
    }

    /** 统计容器：项目名 -> 容器列表（一次 docker ps -a 拿全量，再按标签分组） */
    public Map<String, List<ComposeContainer>> allComposeContainers() throws IOException {
        Map<String, List<ComposeContainer>> map = new LinkedHashMap<String, List<ComposeContainer>>();
        String format = containerFormat();
        DockerExecutor.CmdResult result = executor.docker(
                "ps", "-a", "--no-trunc", "--filter", "label=com.docker.compose.project",
                "--format", format);
        if (!result.isOk()) {
            throw new IOException("读取 compose 容器列表失败：" + result.errorMessage());
        }
        for (ComposeContainer container : parseContainers(result.getOutput())) {
            if (StrUtil.isBlank(container.getProject())) {
                continue;
            }
            List<ComposeContainer> list = map.get(container.getProject());
            if (null == list) {
                list = new ArrayList<ComposeContainer>();
                map.put(container.getProject(), list);
            }
            list.add(container);
        }
        return map;
    }

    /** 只取某个项目的容器。详情窗口只关心一个项目，没必要每次把全机的 compose 容器都拉回来。 */
    public List<ComposeContainer> listProjectContainers(String projectName) throws IOException {
        if (StrUtil.isBlank(projectName)) {
            return new ArrayList<ComposeContainer>();
        }
        DockerExecutor.CmdResult result = executor.docker(
                "ps", "-a", "--no-trunc",
                "--filter", "label=com.docker.compose.project=" + projectName,
                "--format", containerFormat());
        if (!result.isOk()) {
            throw new IOException("读取项目 " + projectName + " 的容器失败：" + result.errorMessage());
        }
        return parseContainers(result.getOutput());
    }

    private static String containerFormat() {
        return "{{.ID}}\\t{{.Names}}\\t{{.Image}}\\t{{.Status}}\\t{{.Ports}}"
                + "\\t{{.Label \"com.docker.compose.project\"}}"
                + "\\t{{.Label \"com.docker.compose.service\"}}"
                + "\\t{{.Label \"com.docker.compose.container-number\"}}"
                + "\\t{{.Label \"com.docker.compose.project.config_files\"}}";
    }

    private List<ComposeContainer> parseContainers(String output) {
        List<ComposeContainer> parsed = new ArrayList<ComposeContainer>();
        List<String> ids = new ArrayList<String>();
        for (String line : StrUtil.emptyToDefault(output, "").split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] cells = trimmed.split("\t", 9);
            if (cells.length < 6) {
                log.warn("docker ps 输出无法解析，已跳过：{}", trimmed);
                continue;
            }
            ComposeContainer container = new ComposeContainer();
            container.setId(cells[0].trim());
            container.setName(cells[1].trim());
            container.setImage(cells[2].trim());
            container.setStatus(cells[3].trim());
            container.setPorts(cells.length > 4 ? cells[4].trim() : "");
            container.setProject(cells[5].trim());
            container.setService(cells.length > 6 ? cells[6].trim() : "");
            container.setNumber(parseInt(cells.length > 7 ? cells[7].trim() : ""));
            container.setComposeConfigFile(cells.length > 8 ? cells[8].trim() : "");
            container.resolveStateFromStatus();
            ids.add(container.getId());
            parsed.add(container);
        }
        fillInspectDetails(parsed, ids);
        return parsed;
    }

    /**
     * 健康检查 / 重启次数 / 退出码只能从 inspect 拿（{@code docker ps --format} 没有这些字段）。
     * 分批拼一条命令，避免容器一多命令行超长。
     */
    private void fillInspectDetails(List<ComposeContainer> containers, List<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        Map<String, JSONObject> byId = new HashMap<String, JSONObject>();
        for (int from = 0; from < ids.size(); from += INSPECT_BATCH) {
            int to = Math.min(from + INSPECT_BATCH, ids.size());
            List<String> slice = ids.subList(from, to);
            List<String> args = new ArrayList<String>(Arrays.asList("inspect"));
            args.addAll(slice);
            try {
                DockerExecutor.CmdResult result = executor.docker(args.toArray(new String[0]));
                if (!result.isOk()) {
                    log.warn("批量 inspect 失败，跳过健康状态采集：{}", result.errorMessage());
                    continue;
                }
                JSONArray array = JSON.parseArray(result.getOutput());
                for (int i = 0; i < array.size(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    if (null != item) {
                        byId.put(item.getString("Id"), item);
                    }
                }
            } catch (Exception e) {
                log.warn("解析 inspect 输出失败：{}", e.getMessage());
            }
        }
        for (ComposeContainer container : containers) {
            JSONObject json = byId.get(container.getId());
            if (null == json) {
                continue;
            }
            JSONObject state = json.getJSONObject("State");
            if (null != state) {
                container.setExitCode(state.getIntValue("ExitCode"));
                container.setStartedAt(StrUtil.emptyToDefault(state.getString("StartedAt"), ""));
                JSONObject health = state.getJSONObject("Health");
                container.setHealth(null == health
                        ? ComposeContainer.NO_HEALTHCHECK
                        : StrUtil.emptyToDefault(health.getString("Status"), ComposeContainer.NO_HEALTHCHECK));
            }
            container.setRestartCount(json.getIntValue("RestartCount"));
            container.setCreatedAt(StrUtil.emptyToDefault(json.getString("Created"), ""));
            JSONObject networkSettings = json.getJSONObject("NetworkSettings");
            if (null != networkSettings) {
                JSONObject networks = networkSettings.getJSONObject("Networks");
                if (null != networks) {
                    container.setNetworks(new ArrayList<String>(networks.keySet()));
                }
            }
            // inspect 拿到的 State.Status 比 docker ps 的文本更准（尤其是 restarting / dead）
            if (StrUtil.isNotBlank(state.getString("Status"))) {
                container.setState(state.getString("Status").trim().toLowerCase());
            }
        }
    }

    private void applyContainers(ComposeProject project, List<ComposeContainer> containers) {
        if (null == containers || containers.isEmpty()) {
            project.setTotalContainers(0);
            project.setRunningContainers(0);
            project.setUnhealthyContainers(0);
            project.setHasFailed(false);
            project.setRunningServices(0);
            project.resolveStatus();
            return;
        }
        int running = 0;
        int unhealthy = 0;
        boolean failed = false;
        Set<String> runningServices = new LinkedHashSet<String>();
        for (ComposeContainer container : containers) {
            if (container.isUp()) {
                running++;
                if (StrUtil.isNotBlank(container.getService())) {
                    runningServices.add(container.getService());
                }
            }
            if (ComposeContainer.UNHEALTHY.equals(container.getHealth())) {
                unhealthy++;
            }
            if (container.isFailed()) {
                failed = true;
            }
        }
        project.setTotalContainers(containers.size());
        project.setRunningContainers(running);
        project.setUnhealthyContainers(unhealthy);
        project.setHasFailed(failed);
        project.setRunningServices(runningServices.size());
        project.resolveStatus();
    }

    /** 扫项目根目录下所有元信息文件，并把内容批量取回来（一次 SSH 往返） */
    private List<ComposeProject> loadManagedProjects(String baseDir) throws IOException {
        List<ComposeProject> projects = new ArrayList<ComposeProject>();
        String base = StrUtil.emptyToDefault(baseDir, defaultBaseDir());
        DockerExecutor.CmdResult find = executor.exec("find " + DockerExecutor.q(base)
                + " -maxdepth 2 -name '" + META_FILE + "' -print");
        List<String> paths = new ArrayList<String>();
        for (String line : find.getOutput().split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("/") && trimmed.endsWith(META_FILE)) {
                paths.add(trimmed);
            }
        }
        if (paths.isEmpty()) {
            return projects;
        }
        StringBuilder script = new StringBuilder("set +e; ");
        for (String path : paths) {
            script.append("printf '").append(MARK_META).append("%s\\n' ")
                    .append(DockerExecutor.q(path)).append("; ")
                    .append("cat ").append(DockerExecutor.q(path)).append("; ")
                    .append("printf '\\n'; ");
        }
        DockerExecutor.CmdResult result = executor.exec(script.toString());
        String currentDir = null;
        StringBuilder buffer = new StringBuilder();
        for (String line : result.getOutput().split("\\R", -1)) {
            if (line.startsWith(MARK_META)) {
                if (null != currentDir) {
                    addManagedProject(projects, currentDir, buffer.toString());
                }
                String path = line.substring(MARK_META.length()).trim();
                currentDir = path.substring(0, path.length() - META_FILE.length());
                if (currentDir.endsWith("/")) {
                    currentDir = currentDir.substring(0, currentDir.length() - 1);
                }
                buffer.setLength(0);
                continue;
            }
            if (null != currentDir) {
                buffer.append(line).append('\n');
            }
        }
        if (null != currentDir) {
            addManagedProject(projects, currentDir, buffer.toString());
        }
        return projects;
    }

    private void addManagedProject(List<ComposeProject> projects, String directory, String jsonText) {
        ComposeProject project = new ComposeProject();
        project.setDirectory(directory);
        project.setManaged(true);
        try {
            JSONObject json = JSON.parseObject(StrUtil.trimToEmpty(jsonText));
            if (null != json) {
                project.setName(StrUtil.emptyToDefault(json.getString("name"), baseName(directory)));
                project.setDescription(StrUtil.emptyToDefault(json.getString("description"), ""));
                project.setCreatedAt(StrUtil.emptyToDefault(json.getString("createdAt"), ""));
                JSONArray files = json.getJSONArray("files");
                List<String> list = new ArrayList<String>();
                if (null != files) {
                    for (int i = 0; i < files.size(); i++) {
                        String name = files.getString(i);
                        if (StrUtil.isNotBlank(name)) {
                            list.add(name.trim());
                        }
                    }
                }
                if (list.isEmpty()) {
                    list.add(DEFAULT_FILE);
                }
                project.setFiles(list);
            }
        } catch (Exception e) {
            // 元信息坏了就退化成「目录名 + 默认文件名」，至少还能打开看
            log.warn("解析 {} 下的 {} 失败：{}", directory, META_FILE, e.getMessage());
            project.setName(baseName(directory));
            project.setFiles(new ArrayList<String>(Arrays.asList(DEFAULT_FILE)));
        }
        if (StrUtil.isBlank(project.getName())) {
            project.setName(baseName(directory));
        }
        projects.add(project);
    }

    /**
     * 批量补齐：.env 是否存在、配置里声明了几个服务。
     * <p>
     * 服务数必须问 compose（{@code config --services}），因为它是「声明」而不是「运行」的数量 ——
     * 只看容器的话，没启动过的项目会显示成 0 个服务，看不出规模。
     */
    private void fillProjectMeta(List<ComposeProject> projects) {
        if (projects.isEmpty()) {
            return;
        }
        ComposeCliInfo cli;
        try {
            cli = cliInfo();
        } catch (IOException e) {
            return;
        }
        if (!cli.isInstalled()) {
            return;
        }
        StringBuilder script = new StringBuilder("set +e; ");
        for (ComposeProject project : projects) {
            script.append("printf '").append(MARK_PROJ).append("%s\\n' ")
                    .append(DockerExecutor.q(project.getDirectory())).append("; ");
            script.append("( cd ").append(DockerExecutor.q(project.getDirectory())).append(" 2>/dev/null "
                    + "|| exit 0; "
                    + "if [ -f .env ]; then printf '" + MARK_ENV + "1\\n'; else printf '" + MARK_ENV + "0\\n'; fi; ");
            script.append("out=$(").append(cli.getCommand())
                    .append(" -p ").append(DockerExecutor.q(project.getName()));
            for (String file : project.getFiles()) {
                script.append(" -f ").append(DockerExecutor.q(file));
            }
            script.append(" config --services 2>/dev/null); code=$?; "
                    + "if [ \"$code\" = \"0\" ]; then printf '" + MARK_OK + "\\n'; printf '%s\\n' \"$out\"; "
                    + "else printf '" + MARK_ERR + "\\n'; fi ); ");
        }
        try {
            DockerExecutor.CmdResult result = executor.exec(script.toString());
            ComposeProject current = null;
            int mode = -1;
            int count = 0;
            boolean ok = false;
            for (String line : result.getOutput().split("\\R", -1)) {
                String trimmed = line.trim();
                if (line.startsWith(MARK_PROJ)) {
                    if (null != current) {
                        current.setServiceCount(ok ? count : -1);
                    }
                    String dir = line.substring(MARK_PROJ.length()).trim();
                    current = findProjectByDir(projects, dir);
                    mode = -1;
                    count = 0;
                    ok = false;
                    continue;
                }
                if (null == current) {
                    continue;
                }
                if (trimmed.startsWith(MARK_ENV)) {
                    current.setEnvFilePresent("1".equals(trimmed.substring(MARK_ENV.length()).trim()));
                } else if (MARK_OK.equals(trimmed)) {
                    mode = 1;
                    ok = true;
                } else if (MARK_ERR.equals(trimmed)) {
                    mode = 0;
                } else if (mode == 1 && !trimmed.isEmpty()) {
                    count++;
                }
            }
            if (null != current) {
                current.setServiceCount(ok ? count : -1);
            }
        } catch (IOException e) {
            log.warn("批量读取 compose 服务数失败：{}", e.getMessage());
        }
    }

    private ComposeProject findProjectByDir(List<ComposeProject> projects, String dir) {
        for (ComposeProject project : projects) {
            if (dir.equals(project.getDirectory())) {
                return project;
            }
        }
        return null;
    }

    /** {@code docker compose ls -a --format json}：能顺带发现不是本系统建的项目 */
    private List<ComposeProject> loadExternalProjects() throws IOException {
        List<ComposeProject> projects = new ArrayList<ComposeProject>();
        ComposeCliInfo cli = cliInfo();
        if (!cli.isInstalled() || !cli.isV2()) {
            // compose ls 是 v2 才有的子命令，v1 上直接跳过
            return projects;
        }
        ExecOutcome outcome = runSeparated(buildGlobalCommand("ls", "-a", "--format", "json"));
        if (!outcome.isOk()) {
            return projects;
        }
        JSONArray array = parseJsonArray(outcome.getStdout());
        if (null == array) {
            return projects;
        }
        for (int i = 0; i < array.size(); i++) {
            JSONObject item = array.getJSONObject(i);
            if (null == item) {
                continue;
            }
            ComposeProject project = new ComposeProject();
            project.setName(StrUtil.emptyToDefault(item.getString("Name"), ""));
            project.setStatusText(StrUtil.emptyToDefault(item.getString("Status"), ""));
            project.setManaged(false);
            String configFiles = StrUtil.emptyToDefault(item.getString("ConfigFiles"), "");
            List<String> files = new ArrayList<String>();
            for (String path : configFiles.split(",")) {
                String trimmed = path.trim();
                if (!trimmed.isEmpty()) {
                    files.add(trimmed);
                }
            }
            project.setFiles(files);
            if (!files.isEmpty()) {
                project.setDirectory(parentOf(files.get(0)));
            }
            if (StrUtil.isBlank(project.getName())) {
                continue;
            }
            projects.add(project);
        }
        return projects;
    }

    private static JSONArray parseJsonArray(String text) {
        String trimmed = StrUtil.trimToEmpty(text);
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return JSON.parseArray(trimmed);
        } catch (Exception e) {
            // 有些 compose 版本输出的是 JSONL，一行一个对象
            JSONArray array = new JSONArray();
            for (String line : trimmed.split("\\R")) {
                String candidate = line.trim();
                if (candidate.startsWith("{")) {
                    try {
                        array.add(JSON.parseObject(candidate));
                    } catch (Exception ignore) {
                        // 单行坏了不影响其它行
                    }
                }
            }
            return array.isEmpty() ? null : array;
        }
    }

    /* ================================================================== */
    /* 配置解析                                                            */
    /* ================================================================== */

    /** 校验用：{@code docker compose config} 的原始结果 */
    public ExecOutcome validate(ComposeProject project) throws IOException {
        return runSeparated(buildCommand(project, "config"));
    }

    /**
     * 项目还没创建时的「预校验」：把这几份文件写进目标机的一个临时目录跑一次
     * {@code docker compose config}，把变量替换后的最终配置和报错带回来，然后立刻删掉临时目录。
     * <p>
     * 这样用户在新建窗口里就能看到「配置到底能不能跑」，而不是先建出一个坏项目再回头改。
     * 临时目录放在 {@code /tmp} 下而不是项目根目录：项目根目录可能属于别人（比如 /opt），
     * 而我们只需要一个能写的地方就够了。
     */
    public ExecOutcome validatePreview(Map<String, String> files, String envContent) throws IOException {
        executor.ensureDaemonReady();
        if (null == files || files.isEmpty()) {
            throw new IOException("没有可校验的文件内容");
        }
        String dir = "/tmp/.lv-compose-check-" + UUID.randomUUID().toString().replace("-", "");
        try {
            ensureDirectory(dir);
            for (Map.Entry<String, String> entry : files.entrySet()) {
                writeFile(dir, entry.getKey(), entry.getValue());
            }
            if (StrUtil.isNotBlank(envContent)) {
                writeFile(dir, ENV_FILE, envContent);
            }
            ComposeProject temp = new ComposeProject();
            temp.setDirectory(dir);
            // 临时目录名以点开头、还带随机串，交给 compose 推导项目名会带一堆告警；
            // 显式给一个合法项目名，预览结果才干净
            temp.setName("compose-check");
            temp.setFiles(orderFiles(files.keySet()));
            ExecOutcome outcome = validate(temp);
            // compose 的报错 / 警告里带的是这个随机临时目录的全路径，对用户没有意义
            // （还会让人以为项目就建在那儿），统一抹掉只留文件名
            return new ExecOutcome(outcome.getExitCode(),
                    stripTempPrefix(outcome.getStdout(), dir),
                    stripTempPrefix(outcome.getStderr(), dir));
        } finally {
            try {
                executor.exec("rm -rf " + DockerExecutor.q(dir));
            } catch (Exception e) {
                log.warn("清理临时校验目录 {} 失败：{}", dir, e.getMessage());
            }
        }
    }

    /**
     * 把 compose 输出里的临时校验目录前缀抹掉。
     * <p>
     * 不抹的话用户会看到
     * {@code /tmp/.lv-compose-check-9f2c…/docker-compose.yml: the attribute `version` is obsolete}，
     * 既长又容易被误解成"项目文件在那个目录里"。
     */
    private static String stripTempPrefix(String text, String dir) {
        if (StrUtil.isBlank(text)) {
            return "";
        }
        return text.replace(dir + "/", "").replace(dir, "");
    }

    /**
     * 文件叠放顺序：基础文件必须排第一，其余按名字排序。
     * <p>
     * 顺序直接决定叠加结果（后面的覆盖前面的），交给 map 的遍历顺序是不负责任的 ——
     * {@code LinkedHashMap} 现在看着是按插入序，但调用方换个实现就变了。
     */
    public static List<String> orderFiles(java.util.Collection<String> names) {
        List<String> bases = new ArrayList<String>();
        List<String> rest = new ArrayList<String>();
        for (String name : names) {
            if (StrUtil.isBlank(name) || ENV_FILE.equals(name)) {
                continue;
            }
            if (DEFAULT_FILE.equals(name) || "docker-compose.yaml".equals(name)
                    || "compose.yml".equals(name) || "compose.yaml".equals(name)) {
                bases.add(name);
            } else {
                rest.add(name);
            }
        }
        Collections.sort(rest);
        bases.addAll(rest);
        return bases;
    }

    /**
     * 解析「最终生效的配置」。
     * <p>
     * 解析失败不会抛异常而是返回空模型并记日志：{@code config} 的文本本身仍然有用
     * （文件编辑页要拿它跟用户写的 yml 做对比），不该因为 snakeyaml 解析不动就整页崩掉。
     */
    public ComposeModel loadModel(ComposeProject project) throws IOException {
        ExecOutcome outcome = validate(project);
        if (!outcome.isOk()) {
            throw new IOException("compose 配置校验失败：" + StrUtil.emptyToDefault(outcome.getErrorText(), "未知错误"));
        }
        ComposeModel model = new ComposeModel();
        model.setResolvedYaml(outcome.getStdout());
        for (String line : StrUtil.trimToEmpty(outcome.getStderr()).split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                model.getWarnings().add(trimmed);
            }
        }
        parseResolvedYaml(model, outcome.getStdout());
        return model;
    }

    @SuppressWarnings("unchecked")
    private void parseResolvedYaml(ComposeModel model, String yamlText) {
        if (StrUtil.isBlank(yamlText)) {
            return;
        }
        Map<String, Object> root;
        try {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Object loaded = yaml.load(yamlText);
            if (!(loaded instanceof Map)) {
                return;
            }
            root = (Map<String, Object>) loaded;
        } catch (Exception e) {
            log.warn("解析 compose config 输出失败（服务视图将不可用）：{}", e.getMessage());
            model.getWarnings().add("解析 config 输出失败：" + e.getMessage());
            return;
        }
        model.setProjectName(str(root.get("name")));
        model.setDeclaredNetworks(keys(root.get("networks")));
        model.setDeclaredVolumes(keys(root.get("volumes")));

        Object servicesObj = root.get("services");
        if (!(servicesObj instanceof Map)) {
            return;
        }
        Map<String, Object> services = (Map<String, Object>) servicesObj;
        for (Map.Entry<String, Object> entry : services.entrySet()) {
            if (!(entry.getValue() instanceof Map)) {
                continue;
            }
            ComposeServiceInfo info = parseService(entry.getKey(), (Map<String, Object>) entry.getValue());
            model.getServices().add(info);
            model.getServiceMap().put(info.getName(), info);
        }
    }

    @SuppressWarnings("unchecked")
    private ComposeServiceInfo parseService(String name, Map<String, Object> node) {
        ComposeServiceInfo info = new ComposeServiceInfo();
        info.setName(name);
        info.setImage(str(node.get("image")));
        info.setContainerName(str(node.get("container_name")));
        info.setCommand(flatten(node.get("command")));
        info.setRestartPolicy(str(node.get("restart")));

        Object build = node.get("build");
        if (build instanceof Map) {
            info.setBuildContext(str(((Map<String, Object>) build).get("context")));
        } else {
            info.setBuildContext(str(build));
        }

        Object healthcheck = node.get("healthcheck");
        if (healthcheck instanceof Map) {
            Map<String, Object> hc = (Map<String, Object>) healthcheck;
            if (Boolean.TRUE.equals(hc.get("disable"))) {
                info.setHealthcheck("已显式禁用");
            } else {
                StringBuilder sb = new StringBuilder(flatten(hc.get("test")));
                appendPart(sb, "间隔", hc.get("interval"));
                appendPart(sb, "超时", hc.get("timeout"));
                appendPart(sb, "重试", hc.get("retries"));
                appendPart(sb, "启动期", hc.get("start_period"));
                info.setHealthcheck(sb.length() == 0 ? "已配置（未写 test）" : sb.toString());
            }
        }

        Object deploy = node.get("deploy");
        if (deploy instanceof Map) {
            Object replicas = ((Map<String, Object>) deploy).get("replicas");
            int value = toInt(replicas);
            if (value > 0) {
                info.setReplicas(value);
                info.setReplicasDeclared(true);
            }
        }

        info.setPorts(parsePorts(node.get("ports")));
        info.setDependsOn(keys(node.get("depends_on")));
        info.setNetworks(keys(node.get("networks")));
        info.setVolumes(stringList(node.get("volumes")));
        info.setProfiles(stringList(node.get("profiles")));
        Map<String, String> env = new LinkedHashMap<String, String>();
        Object environment = node.get("environment");
        if (environment instanceof Map) {
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) environment).entrySet()) {
                env.put(entry.getKey(), null == entry.getValue() ? "" : String.valueOf(entry.getValue()));
            }
        }
        info.setEnvironment(env);
        return info;
    }

    /** 端口可能是短语法字符串（"8080:80"），也可能是长语法的 Map，两种都要认 */
    @SuppressWarnings("unchecked")
    private List<String> parsePorts(Object portsNode) {
        List<String> result = new ArrayList<String>();
        if (!(portsNode instanceof List)) {
            return result;
        }
        for (Object item : (List<Object>) portsNode) {
            if (item instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) item;
                String target = str(map.get("target"));
                String published = str(map.get("published"));
                String protocol = StrUtil.emptyToDefault(str(map.get("protocol")), "tcp");
                String hostIp = str(map.get("host_ip"));
                StringBuilder sb = new StringBuilder();
                if (StrUtil.isNotBlank(hostIp)) {
                    sb.append(hostIp).append(':');
                }
                if (StrUtil.isNotBlank(published)) {
                    sb.append(published).append(':');
                }
                sb.append(target);
                if (StrUtil.isNotBlank(protocol)) {
                    sb.append('/').append(protocol);
                }
                result.add(sb.toString());
            } else if (null != item) {
                result.add(String.valueOf(item).trim());
            }
        }
        return result;
    }

    private static void appendPart(StringBuilder sb, String label, Object value) {
        if (null == value) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("　");
        }
        sb.append(label).append('=').append(value);
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object node) {
        List<String> result = new ArrayList<String>();
        if (node instanceof List) {
            for (Object item : (List<Object>) node) {
                if (null == item) {
                    continue;
                }
                if (item instanceof Map) {
                    Map<String, Object> map = (Map<String, Object>) item;
                    String source = str(map.get("source"));
                    String target = str(map.get("target"));
                    if (StrUtil.isNotBlank(source)) {
                        result.add(source + ":" + target);
                    } else {
                        result.add(target);
                    }
                } else {
                    result.add(String.valueOf(item).trim());
                }
            }
        }
        return result;
    }

    /** depends_on / networks 在 config 之后都是 Map（带 condition / aliases），取 key 就行 */
    @SuppressWarnings("unchecked")
    private static List<String> keys(Object node) {
        List<String> result = new ArrayList<String>();
        if (node instanceof Map) {
            for (Object key : ((Map<String, Object>) node).keySet()) {
                result.add(String.valueOf(key));
            }
        } else if (node instanceof List) {
            for (Object item : (List<Object>) node) {
                if (null != item) {
                    result.add(String.valueOf(item).trim());
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static String flatten(Object node) {
        if (null == node) {
            return "";
        }
        if (node instanceof List) {
            List<String> parts = new ArrayList<String>();
            for (Object item : (List<Object>) node) {
                if (null != item) {
                    parts.add(String.valueOf(item));
                }
            }
            return StrUtil.join(" ", parts);
        }
        return String.valueOf(node).trim();
    }

    private static String str(Object node) {
        return null == node ? "" : String.valueOf(node).trim();
    }

    /* ================================================================== */
    /* 端口 / 资源盘点                                                     */
    /* ================================================================== */

    /**
     * 把模型里的端口声明摊平成映射表，并做两级冲突检查：
     * 项目内重复 + 已被本项目之外的容器占用。
     */
    public List<ComposePortMapping> buildPortMappings(ComposeModel model, String projectName) {
        List<ComposePortMapping> mappings = new ArrayList<ComposePortMapping>();
        Map<String, ComposePortMapping> firstByHostPort = new LinkedHashMap<String, ComposePortMapping>();
        Map<String, String> hostUsage = new HashMap<String, String>();
        try {
            hostUsage = hostPortUsage(projectName);
        } catch (Exception e) {
            log.warn("读取宿主端口占用失败，端口冲突检查降级：{}", e.getMessage());
        }
        for (ComposeServiceInfo service : model.getServices()) {
            for (String raw : service.getPorts()) {
                ComposePortMapping mapping = parsePortMapping(service.getName(), raw);
                if (null == mapping) {
                    continue;
                }
                if (StrUtil.isNotBlank(mapping.getHostPort())) {
                    ComposePortMapping previous = firstByHostPort.get(mapping.getHostPort());
                    if (null != previous && sharesHostIp(previous, mapping)) {
                        mapping.setConflict(true);
                        mapping.setInProjectConflict(true);
                        mapping.setConflictWith("本项目服务 " + previous.getService() + " 也在用");
                    } else if (null == previous) {
                        firstByHostPort.put(mapping.getHostPort(), mapping);
                    }
                    if (!mapping.isConflict()) {
                        String owner = hostUsage.get(mapping.getHostPort());
                        if (null != owner) {
                            mapping.setConflict(true);
                            mapping.setConflictWith("已被容器 " + owner + " 占用");
                        }
                    }
                }
                mappings.add(mapping);
            }
        }
        return mappings;
    }

    private static boolean sharesHostIp(ComposePortMapping a, ComposePortMapping b) {
        String left = normalizeHostIp(a.getHostIp());
        String right = normalizeHostIp(b.getHostIp());
        return left.equals(right) || "0.0.0.0".equals(left) || "0.0.0.0".equals(right);
    }

    private static String normalizeHostIp(String ip) {
        String value = StrUtil.trimToEmpty(ip);
        if (value.isEmpty() || "::".equals(value)) {
            return "0.0.0.0";
        }
        return value;
    }

    /** 解析一条端口声明：{@code [ip:][hostPort:]containerPort[/proto]} 或 {@code 8000-8010:8000-8010} */
    public static ComposePortMapping parsePortMapping(String service, String raw) {
        String text = StrUtil.trimToEmpty(raw);
        if (text.isEmpty()) {
            return null;
        }
        ComposePortMapping mapping = new ComposePortMapping();
        mapping.setService(service);
        String protocol = "tcp";
        int slash = text.lastIndexOf('/');
        if (slash > 0) {
            protocol = text.substring(slash + 1).trim();
            text = text.substring(0, slash);
        }
        mapping.setProtocol(protocol);
        List<String> parts = new ArrayList<String>(Arrays.asList(text.split(":")));
        if (parts.size() >= 3) {
            mapping.setHostIp(joinHostIp(parts));
            mapping.setHostPort(joinMiddle(parts));
            mapping.setContainerPort(parts.get(parts.size() - 1).trim());
        } else if (parts.size() == 2) {
            mapping.setHostPort(parts.get(0).trim());
            mapping.setContainerPort(parts.get(1).trim());
        } else {
            // 只写了容器端口，由 docker 随机分配宿主机端口
            mapping.setContainerPort(parts.get(0).trim());
        }
        return mapping;
    }

    private static String joinHostIp(List<String> parts) {
        if (parts.size() <= 3) {
            return parts.get(0).trim();
        }
        // IPv6 字面量（[::1]:8080:80）或者带冒号的地址，中间几段拼回去
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size() - 2; i++) {
            if (sb.length() > 0) {
                sb.append(':');
            }
            sb.append(parts.get(i).trim());
        }
        return sb.toString();
    }

    private static String joinMiddle(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = parts.size() - 2; i < parts.size() - 1; i++) {
            sb.append(parts.get(i).trim());
        }
        return sb.toString();
    }

    /** 宿主机端口 -> 占用它的容器名。只看本项目之外的容器，否则自己人占自己人也算冲突 */
    private Map<String, String> hostPortUsage(String projectName) throws IOException {
        Map<String, String> usage = new HashMap<String, String>();
        List<String> lines = executor.dockerLines("ps", "-a", "--format",
                "{{.Names}}\\t{{.Ports}}\\t{{.Label \"com.docker.compose.project\"}}");
        for (String line : lines) {
            String[] cells = line.split("\t", 3);
            if (cells.length < 2) {
                continue;
            }
            String name = cells[0].trim();
            String owner = cells.length > 2 ? cells[2].trim() : "";
            if (StrUtil.isNotBlank(projectName) && projectName.equals(owner)) {
                continue;
            }
            Matcher matcher = HOST_PORT_PATTERN.matcher(cells[1]);
            while (matcher.find()) {
                usage.put(matcher.group(1), name);
            }
        }
        return usage;
    }

    /** 项目占用的网络 / 卷 / 端口 / 磁盘 */
    public ComposeResources collectResources(ComposeProject project, ComposeModel model,
                                             List<ComposeContainer> containers) {
        ComposeResources resources = new ComposeResources();
        resources.setPorts(buildPortMappings(model, project.getName()));
        try {
            resources.setNetworks(projectNetworks(project, containers));
        } catch (Exception e) {
            resources.addNote("读取项目网络失败：" + e.getMessage());
        }
        try {
            resources.setVolumes(projectVolumes(project, model));
        } catch (Exception e) {
            resources.addNote("读取项目卷失败：" + e.getMessage());
        }
        fillDiskUsage(resources, project, model, containers);
        if (hasBuildServices(model)) {
            resources.addNote("项目里有 build 服务，重建镜像会额外占用磁盘，请在「镜像管理」页查看。");
        }
        return resources;
    }

    private static boolean hasBuildServices(ComposeModel model) {
        for (ComposeServiceInfo service : model.getServices()) {
            if (StrUtil.isNotBlank(service.getBuildContext())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 项目网络：compose 会给每个项目建 {@code <项目名>_default}，网络名是固定的，
     * 所以按名字匹配就够，不需要再 inspect 一遍所有容器。
     */
    private List<DockerNetwork> projectNetworks(ComposeProject project, List<ComposeContainer> containers) throws IOException {
        Set<String> wanted = new LinkedHashSet<String>();
        wanted.add(project.getName() + "_default");
        if (null != containers) {
            for (ComposeContainer container : containers) {
                wanted.addAll(container.getNetworks());
            }
        }
        List<DockerNetwork> result = new ArrayList<DockerNetwork>();
        for (DockerNetwork network : new DockerService(executor).listNetworks(true)) {
            if (wanted.contains(network.getName())) {
                result.add(network);
            }
        }
        return result;
    }

    /** 项目卷：compose 建的卷名固定是 {@code <项目名>_<卷名>}，按前缀匹配（比 label 过滤兼容性更好） */
    private List<DockerVolume> projectVolumes(ComposeProject project, ComposeModel model) throws IOException {
        Set<String> declared = new LinkedHashSet<String>(model.getDeclaredVolumes());
        String prefix = project.getName() + "_";
        List<DockerVolume> result = new ArrayList<DockerVolume>();
        for (DockerVolume volume : new DockerService(executor).listVolumes(true)) {
            String name = StrUtil.emptyToDefault(volume.getName(), "");
            if (name.startsWith(prefix)) {
                result.add(volume);
                declared.remove(name.substring(prefix.length()));
            }
        }
        // 声明了但还没创建的卷，也列出来（用户能看到「差什么」）
        for (String missing : declared) {
            DockerVolume ghost = new DockerVolume();
            ghost.setName(prefix + missing);
            ghost.setDriver("local");
            ghost.setScope("local");
            ghost.setMountpoint("（尚未创建）");
            ghost.setCreatedAt("");
            result.add(ghost);
        }
        return result;
    }

    /** 磁盘占用：镜像（按项目用到的镜像名去重累加）+ 容器可写层；卷的大小另外算 */
    private void fillDiskUsage(ComposeResources resources, ComposeProject project,
                               ComposeModel model, List<ComposeContainer> containers) {
        try {
            Set<String> images = new LinkedHashSet<String>();
            for (ComposeServiceInfo service : model.getServices()) {
                if (StrUtil.isNotBlank(service.getImage())) {
                    images.add(service.getImage());
                }
            }
            if (null != containers) {
                for (ComposeContainer container : containers) {
                    if (StrUtil.isNotBlank(container.getImage())) {
                        images.add(container.getImage());
                    }
                }
            }
            long total = 0;
            List<DockerImage> all = new DockerService(executor).listImages(false);
            for (DockerImage image : all) {
                String tag = StrUtil.emptyToDefault(image.getRepository(), "") + ":" + StrUtil.emptyToDefault(image.getTag(), "");
                if (images.contains(tag) || images.contains(image.getRepository())) {
                    total += DockerStat.parseBytes(image.getSize());
                }
            }
            resources.setImageUsage(total > 0 ? DockerStat.formatBytes(total) + "（" + images.size() + " 个镜像）" : "0B");
        } catch (Exception e) {
            resources.addNote("统计镜像占用失败：" + e.getMessage());
        }

        try {
            DockerExecutor.CmdResult result = executor.docker("ps", "-a", "-s", "--no-trunc",
                    "--filter", "label=com.docker.compose.project=" + project.getName(),
                    "--format", "{{.Names}}\\t{{.Size}}");
            long total = 0;
            int count = 0;
            for (String line : result.getOutput().split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String[] cells = trimmed.split("\t", 2);
                if (cells.length < 2) {
                    continue;
                }
                // "12.3MB (virtual 1.2GB)" -> 取可写层那部分
                String size = cells[1].trim();
                int idx = size.indexOf('(');
                if (idx > 0) {
                    size = size.substring(0, idx).trim();
                }
                total += DockerStat.parseBytes(size);
                count++;
            }
            resources.setContainerUsage(count == 0 ? "0B" : DockerStat.formatBytes(total) + "（" + count + " 个容器）");
        } catch (Exception e) {
            resources.addNote("统计容器可写层失败：" + e.getMessage());
        }

        resources.setVolumeUsage(projectVolumeUsage(project));
    }

    /**
     * 卷磁盘占用要读 {@code /var/lib/docker/volumes}，只有 root 读得到；
     * 拿不到就如实说「需要 root」，不要编一个数字出来。
     */
    private String projectVolumeUsage(ComposeProject project) {
        String prefix = project.getName() + "_";
        String script = "set +e; total=0; for v in $(" + StrUtil.emptyToDefault(executor.getCommandPrefix(), "docker")
                + " volume ls -q 2>/dev/null | grep '^" + prefix + "'); do "
                + "mp=$(" + StrUtil.emptyToDefault(executor.getCommandPrefix(), "docker")
                + " volume inspect -f '{{.Mountpoint}}' \"$v\" 2>/dev/null); "
                + "[ -n \"$mp\" ] && [ -d \"$mp\" ] && echo \"$v $(du -sh \"$mp\" 2>/dev/null | cut -f1)\"; done";
        try {
            DockerExecutor.CmdResult result = executor.exec(script);
            StringBuilder sb = new StringBuilder();
            for (String line : result.getOutput().split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("/") || trimmed.contains("No such")) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append("　");
                }
                sb.append(trimmed);
            }
            return sb.length() == 0 ? "无数据卷" : sb.toString();
        } catch (Exception e) {
            log.warn("统计项目卷占用失败：{}", e.getMessage());
            return "需要 root 权限（未取到）";
        }
    }

    /* ================================================================== */
    /* 生命周期动作                                                        */
    /* ================================================================== */

    /** {@code up -d}：指定 service 为空表示整个项目 */
    public String up(ComposeProject project, String service, boolean build, boolean forceRecreate,
                     boolean removeOrphans) throws IOException {
        List<String> args = new ArrayList<String>(Arrays.asList("up", "-d"));
        if (build) {
            args.add("--build");
        }
        if (forceRecreate) {
            args.add("--force-recreate");
        }
        if (removeOrphans) {
            args.add("--remove-orphans");
        }
        if (StrUtil.isNotBlank(service)) {
            args.add(DockerExecutor.q(service));
        }
        return runCombined("启动 " + describe(project, service), buildCommand(project, args.toArray(new String[0])));
    }

    /** 重新部署：文件改完之后 up -d --force-recreate，让改动真正生效 */
    public String redeploy(ComposeProject project, boolean build) throws IOException {
        return up(project, null, build, true, true);
    }

    public String down(ComposeProject project, boolean removeVolumes, boolean removeLocalImages) throws IOException {
        List<String> args = new ArrayList<String>(Arrays.asList("down", "--remove-orphans"));
        if (removeVolumes) {
            args.add("-v");
        }
        if (removeLocalImages) {
            args.add("--rmi");
            args.add("local");
        }
        return runCombined("停止并移除项目 " + project.getName(),
                buildCommand(project, args.toArray(new String[0])));
    }

    public String stop(ComposeProject project, String service) throws IOException {
        List<String> args = new ArrayList<String>(Arrays.asList("stop"));
        if (StrUtil.isNotBlank(service)) {
            args.add(DockerExecutor.q(service));
        }
        return runCombined("暂停 " + describe(project, service), buildCommand(project, args.toArray(new String[0])));
    }

    public String start(ComposeProject project, String service) throws IOException {
        List<String> args = new ArrayList<String>(Arrays.asList("start"));
        if (StrUtil.isNotBlank(service)) {
            args.add(DockerExecutor.q(service));
        }
        return runCombined("恢复 " + describe(project, service), buildCommand(project, args.toArray(new String[0])));
    }

    /**
     * restart 只重启进程，<b>不会</b>应用 compose 文件的改动（那要用 up -d）。
     * 界面上两个按钮分开，并在提示里讲清楚，避免用户改完 yml 点 restart 发现没生效。
     */
    public String restart(ComposeProject project, String service) throws IOException {
        List<String> args = new ArrayList<String>(Arrays.asList("restart"));
        if (StrUtil.isNotBlank(service)) {
            args.add(DockerExecutor.q(service));
        }
        return runCombined("重启 " + describe(project, service), buildCommand(project, args.toArray(new String[0])));
    }

    /** 扩缩容：{@code up -d --scale svc=n}，配置没变时不会重建已有容器 */
    public String scale(ComposeProject project, String service, int replicas) throws IOException {
        if (replicas < 0) {
            throw new IOException("副本数不能为负数");
        }
        return runCombined("调整 " + service + " 副本数为 " + replicas,
                buildCommand(project, "up", "-d", "--scale", DockerExecutor.q(service + "=" + replicas)));
    }

    public String pull(ComposeProject project, String service) throws IOException {
        List<String> args = new ArrayList<String>(Arrays.asList("pull"));
        args.add("--ignore-pull-failures");
        if (StrUtil.isNotBlank(service)) {
            args.add(DockerExecutor.q(service));
        }
        return runCombined("拉取镜像 " + describe(project, service),
                buildCommand(project, args.toArray(new String[0])));
    }

    public String build(ComposeProject project, String service) throws IOException {
        List<String> args = new ArrayList<String>(Arrays.asList("build"));
        if (StrUtil.isNotBlank(service)) {
            args.add(DockerExecutor.q(service));
        }
        return runCombined("构建镜像 " + describe(project, service),
                buildCommand(project, args.toArray(new String[0])));
    }

    /** 删除项目：先 down（可选删卷删镜像），再删目录 */
    public String deleteProject(ComposeProject project, boolean removeVolumes, boolean removeImages,
                               boolean deleteDirectory) throws IOException {
        StringBuilder report = new StringBuilder();
        try {
            report.append(down(project, removeVolumes, removeImages));
        } catch (Exception e) {
            report.append("清理容器时出错（继续删目录）：").append(e.getMessage()).append('\n');
        }
        if (deleteDirectory && StrUtil.isNotBlank(project.getDirectory())) {
            DockerExecutor.CmdResult result = executor.exec("rm -rf " + DockerExecutor.q(project.getDirectory()));
            if (!result.isOk()) {
                throw new IOException("删除项目目录失败：" + result.errorMessage());
            }
            report.append("已删除目录 ").append(project.getDirectory()).append('\n');
        }
        return report.toString();
    }

    private static String describe(ComposeProject project, String service) {
        return StrUtil.isBlank(service) ? ("项目 " + project.getName()) : ("服务 " + service);
    }

    /* ================================================================== */
    /* 文件读写                                                            */
    /* ================================================================== */

    /** 批量读项目里的文件内容：文件名 -> 内容（不存在时为 null） */
    public Map<String, String> readProjectFiles(ComposeProject project) throws IOException {
        Map<String, String> result = new LinkedHashMap<String, String>();
        StringBuilder script = new StringBuilder("set +e; ");
        for (String file : project.getFiles()) {
            script.append("printf '").append(MARK_META).append("%s\\n' ").append(DockerExecutor.q(file)).append("; ");
            script.append("cat ").append(DockerExecutor.q(project.getDirectory() + "/" + file))
                    .append(" 2>/dev/null; printf '\\n'; ");
        }
        DockerExecutor.CmdResult cmdResult = executor.exec(script.toString());
        String current = null;
        StringBuilder buffer = new StringBuilder();
        for (String line : cmdResult.getOutput().split("\\R", -1)) {
            if (line.startsWith(MARK_META)) {
                if (null != current) {
                    result.put(current, buffer.toString());
                }
                current = line.substring(MARK_META.length()).trim();
                buffer.setLength(0);
                continue;
            }
            if (null != current) {
                buffer.append(line).append('\n');
            }
        }
        if (null != current) {
            result.put(current, buffer.toString());
        }
        for (Map.Entry<String, String> entry : result.entrySet()) {
            entry.setValue(trimOneTrailingNewline(entry.getValue()));
        }
        return result;
    }

    public String readFile(String directory, String fileName) throws IOException {
        DockerExecutor.CmdResult result = executor.exec("cat " + DockerExecutor.q(directory + "/" + fileName));
        if (!result.isOk()) {
            return null;
        }
        return trimOneTrailingNewline(result.getOutput());
    }

    /** 写远端文本文件：本地临时文件 -> SFTP。多行内容绝不能拼成 shell 命令。 */
    public void writeFile(String directory, String fileName, String content) throws IOException {
        File local = File.createTempFile("lv-compose-", ".tmp");
        try {
            Files.write(local.toPath(), StrUtil.emptyToDefault(content, "").getBytes(StandardCharsets.UTF_8));
            ensureDirectory(directory);
            String remotePath = directory + "/" + fileName;
            if (!executor.ssh().uploadFile(local.getAbsolutePath(), remotePath, null)) {
                throw new IOException("写入 " + remotePath + " 失败（远端与本地大小不一致）");
            }
            // compose 会拒绝过于开放的 .env 权限吗？不会，但统一 644 更像常规部署
            executor.exec("chmod 644 " + DockerExecutor.q(remotePath));
        } finally {
            try {
                Files.deleteIfExists(local.toPath());
            } catch (IOException e) {
                log.warn("删除本地临时文件失败：{}", e.getMessage());
            }
        }
    }

    public void deleteFile(String directory, String fileName) throws IOException {
        executor.exec("rm -f " + DockerExecutor.q(directory + "/" + fileName));
    }

    public void ensureDirectory(String directory) throws IOException {
        DockerExecutor.CmdResult result = executor.exec("mkdir -p " + DockerExecutor.q(directory));
        if (!result.isOk()) {
            throw new IOException("创建目录 " + directory + " 失败：" + result.errorMessage());
        }
    }

    public boolean directoryExists(String directory) {
        try {
            return executor.exec("[ -d " + DockerExecutor.q(directory) + " ] && echo yes").getOutput().contains("yes");
        } catch (IOException e) {
            return false;
        }
    }

    /** 上传本地文件到项目目录（用于「上传 compose 文件」） */
    public void uploadFile(String directory, String fileName, File localFile) throws IOException {
        if (null == localFile || !localFile.isFile()) {
            throw new IOException("本地文件不存在，无法上传");
        }
        ensureDirectory(directory);
        if (!executor.ssh().uploadFile(localFile.getAbsolutePath(), directory + "/" + fileName, null)) {
            throw new IOException("上传 " + fileName + " 失败");
        }
    }

    /** 把项目文件下载到本地临时文件（供浏览器下载） */
    public File downloadFile(String directory, String fileName) throws IOException {
        File local = File.createTempFile("lv-compose-dl-", "-" + DockerService.sanitizeFileName(fileName));
        if (!executor.ssh().downloadFile(directory + "/" + fileName, local.getAbsolutePath())) {
            throw new IOException("下载 " + fileName + " 失败");
        }
        return local;
    }

    /* ================================================================== */
    /* 项目创建 / 保存                                                     */
    /* ================================================================== */

    /** 项目名校验：它同时是目录名和 compose 项目名，限制字符集能避免一大堆前缀不匹配的怪问题 */
    public static void checkName(String name) throws IOException {
        String value = StrUtil.trimToEmpty(name);
        if (value.isEmpty()) {
            throw new IOException("项目名不能为空");
        }
        if (!NAME_PATTERN.matcher(value).matches()) {
            throw new IOException("项目名只能用小写字母、数字、短横线、下划线，且以字母或数字开头，最长 39 位");
        }
    }

    /**
     * 创建项目：建目录 → 写 compose 文件 → 写 .env → 写元信息。
     *
     * @param files 文件名 -> 内容，顺序即 {@code -f} 的叠加顺序
     */
    public ComposeProject createProject(String baseDir, String name, String description,
                                        Map<String, String> files, String envContent) throws IOException {
        checkName(name);
        String base = StrUtil.emptyToDefault(StrUtil.trimToEmpty(baseDir), defaultBaseDir());
        String directory = base.endsWith("/") ? base + name : base + "/" + name;
        ensureDirectory(directory);
        ComposeProject project = new ComposeProject();
        project.setName(name);
        project.setDirectory(directory);
        project.setDescription(description);
        project.setManaged(true);
        project.setFiles(new ArrayList<String>(files.keySet()));
        project.setEnvFilePresent(StrUtil.isNotBlank(envContent));
        project.setCreatedAt(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        for (Map.Entry<String, String> entry : files.entrySet()) {
            writeFile(directory, entry.getKey(), entry.getValue());
        }
        if (StrUtil.isNotBlank(envContent)) {
            writeFile(directory, ENV_FILE, envContent);
        }
        saveMeta(project);
        return project;
    }

    /** 保存项目元信息（名称 / 描述 / 文件顺序） */
    public void saveMeta(ComposeProject project) throws IOException {
        JSONObject json = new JSONObject();
        json.put("name", project.getName());
        json.put("description", project.getDescription());
        json.put("files", project.getFiles());
        json.put("createdAt", project.getCreatedAt());
        json.put("updatedAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        writeFile(project.getDirectory(), META_FILE, json.toString());
    }

    /**
     * 保存「文件 + .env」。
     *
     * @param contents 文件名（含 {@code .env}）-> 内容；不在 {@code contents} 里的原文件不动
     */
    public void saveFiles(ComposeProject project, Map<String, String> contents) throws IOException {
        for (Map.Entry<String, String> entry : contents.entrySet()) {
            writeFile(project.getDirectory(), entry.getKey(), entry.getValue());
        }
        saveMeta(project);
    }

    /** 从 Git 拉取项目：目录不存在或为空时 clone，已经是仓库则 fetch + 硬重置到目标分支 */
    public void syncFromGit(ComposeProject project, String repository, String branch) throws IOException {
        if (StrUtil.isBlank(repository)) {
            throw new IOException("Git 仓库地址不能为空");
        }
        String repo = StrUtil.trimToEmpty(repository);
        String ref = StrUtil.isBlank(branch) ? "master" : StrUtil.trimToEmpty(branch);
        String dir = project.getDirectory();
        if (StrUtil.isBlank(project.getName())) {
            String guess = lastSegment(repo);
            project.setName(StrUtil.emptyToDefault(guess.replaceAll("\\.git$", ""), "compose-project"));
        }
        String probe = "test -d " + DockerExecutor.q(dir + "/.git") + " && echo repo || echo empty";
        boolean isRepo = executor.exec(probe).getOutput().contains("repo");
        if (isRepo) {
            String command = "cd " + DockerExecutor.q(dir) + " && git fetch --all --prune && "
                    + "git reset --hard origin/" + ref;
            DockerExecutor.CmdResult result = executor.exec(command);
            if (!result.isOk()) {
                // master/main 猜错时给一个明确的方向，而不是只丢一句 git 报错
                throw new IOException("更新仓库失败（分支 " + ref + " 是否存在？）：" + result.errorMessage());
            }
        } else {
            if (directoryExists(dir)) {
                // clone 要求目标目录为空
                DockerExecutor.CmdResult clean = executor.exec(
                        "rm -rf " + DockerExecutor.q(dir) + " && mkdir -p " + DockerExecutor.q(dir));
                if (!clean.isOk()) {
                    throw new IOException("清理项目目录失败：" + clean.errorMessage());
                }
            }
            ensureDirectory(parentOf(dir));
            String command = "git clone --depth 1 -b " + DockerExecutor.q(ref) + " "
                    + DockerExecutor.q(repo) + " " + DockerExecutor.q(dir);
            DockerExecutor.CmdResult result = executor.exec(command);
            if (!result.isOk()) {
                throw new IOException("git clone 失败：" + result.errorMessage());
            }
        }
        // 目录里有哪些 compose 文件，用 compose 自己的默认规则扫一遍
        List<String> detected = detectComposeFiles(dir);
        if (!detected.isEmpty()) {
            project.setFiles(detected);
        }
        saveMeta(project);
    }

    /** 目录里符合 compose 默认命名规则的文件，按 base → override 排序 */
    public List<String> detectComposeFiles(String directory) {
        String script = "cd " + DockerExecutor.q(directory) + " 2>/dev/null && "
                + "ls -1 2>/dev/null | grep -E '^(docker-compose|compose)\\.(ya?ml)$|^(docker-compose|compose)\\..+\\.ya?ml$'";
        List<String> found = new ArrayList<String>();
        try {
            for (String line : executor.exec(script).getOutput().split("\\R")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.contains(" ")) {
                    found.add(trimmed);
                }
            }
        } catch (IOException e) {
            log.warn("扫描 {} 下的 compose 文件失败：{}", directory, e.getMessage());
        }
        // base 在前、override 在后，顺序直接决定叠加结果，不能交给 ls 的字典序
        List<String> ordered = new ArrayList<String>();
        for (String name : found) {
            if ("docker-compose.yml".equals(name) || "docker-compose.yaml".equals(name)
                    || "compose.yml".equals(name) || "compose.yaml".equals(name)) {
                ordered.add(name);
            }
        }
        if (ordered.isEmpty()) {
            for (String name : found) {
                ordered.add(name);
            }
        }
        for (String name : found) {
            if (!ordered.contains(name)) {
                ordered.add(name);
            }
        }
        return ordered;
    }

    private static String lastSegment(String path) {
        String trimmed = StrUtil.trimToEmpty(path);
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        int idx = trimmed.lastIndexOf('/');
        return idx < 0 ? trimmed : trimmed.substring(idx + 1);
    }

    /* ================================================================== */
    /* .env / 变量                                                         */
    /* ================================================================== */

    /** 解析 .env 文本（忽略注释与空行，去掉成对引号） */
    public static Map<String, String> parseEnv(String text) {
        Map<String, String> env = new LinkedHashMap<String, String>();
        if (StrUtil.isBlank(text)) {
            return env;
        }
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.startsWith("export ")) {
                trimmed = trimmed.substring("export ".length()).trim();
            }
            int idx = trimmed.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            String key = trimmed.substring(0, idx).trim();
            String value = trimmed.substring(idx + 1).trim();
            if (value.length() >= 2
                    && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1);
            }
            env.put(key, value);
        }
        return env;
    }

    /** 从 compose 文本里抠出被 ${VAR} 引用的变量名（保序去重） */
    public static List<String> extractVariables(String text) {
        Set<String> names = new LinkedHashSet<String>();
        if (StrUtil.isBlank(text)) {
            return new ArrayList<String>(names);
        }
        Matcher matcher = VAR_PATTERN.matcher(text);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return new ArrayList<String>(names);
    }

    /* ================================================================== */
    /* 日志                                                                */
    /* ================================================================== */

    /**
     * 聚合日志走 pty 通道（见 {@link DockerTerminalRegistry}），因为 compose 只有在
     * 检测到 TTY 时才会给每行加「服务名」前缀并着色 —— 这正是「多容器合并、颜色区分」要的效果。
     */
    public DockerTerminalRegistry.Spec buildLogsSpec(ComposeProject project, String service, int tailLines,
                                                     boolean timestamps) throws IOException {
        ComposeCliInfo cli = cliInfo();
        if (!cli.isInstalled()) {
            throw new IOException(cli.getDiagnosis());
        }
        return new DockerTerminalRegistry.Spec(
                DockerTerminalRegistry.Kind.COMPOSE_LOGS,
                executor.getInfo(),
                cli.getCommand(),
                project.getDirectory(),
                new ArrayList<String>(project.getFiles()),
                project.getName(),
                service,
                tailLines,
                timestamps);
    }

    /* ================================================================== */
    /* 工具                                                                */
    /* ================================================================== */

    private static int parseInt(String text) {
        try {
            return Integer.parseInt(StrUtil.trimToEmpty(text));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int toInt(Object value) {
        if (null == value) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String baseName(String path) {
        String trimmed = StrUtil.trimToEmpty(path);
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        int idx = trimmed.lastIndexOf('/');
        return idx < 0 ? trimmed : trimmed.substring(idx + 1);
    }

    private static String parentOf(String path) {
        String trimmed = StrUtil.trimToEmpty(path);
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        int idx = trimmed.lastIndexOf('/');
        return idx <= 0 ? "/" : trimmed.substring(0, idx);
    }

    private static String trimOneTrailingNewline(String text) {
        if (null == text) {
            return "";
        }
        if (text.endsWith("\n")) {
            return text.substring(0, text.length() - 1);
        }
        return text;
    }

    /** 去掉结尾的所有 CR / LF（段落正文末尾的空行是标记协议带来的，不是命令输出的一部分） */
    private static String trimTrailingNewlines(String text) {
        if (null == text) {
            return "";
        }
        int end = text.length();
        while (end > 0) {
            char c = text.charAt(end - 1);
            if (c != '\n' && c != '\r') {
                break;
            }
            end--;
        }
        return text.substring(0, end);
    }

    /** SFTP 通道（导出/上传用），与 {@code DockerExecutor.ssh()} 同一份连接 */
    public SSHClientUtil ssh() {
        return executor.ssh();
    }

    /**
     * 一条命令的分离式执行结果。
     */
    public static final class ExecOutcome implements Serializable {

        private static final long serialVersionUID = 1L;

        private final int exitCode;
        private final String stdout;
        private final String stderr;

        private ExecOutcome(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        static ExecOutcome parse(String raw) {
            String text = StrUtil.emptyToDefault(raw, "");
            int rcAt = text.lastIndexOf(MARK_RC);
            int exitCode = -1;
            if (rcAt >= 0) {
                int end = text.indexOf('\n', rcAt);
                String code = (end < 0 ? text.substring(rcAt + MARK_RC.length())
                        : text.substring(rcAt + MARK_RC.length(), end)).trim();
                try {
                    exitCode = Integer.parseInt(code);
                } catch (NumberFormatException e) {
                    exitCode = -1;
                }
                // 只摘掉 __LV_RC__<code> 这一行本身，它后面的 OUT / ERR 段落必须留着。
                // runSeparated 里 RC 是打在两个段落**之前**的，原来这里写 substring(0, rcAt)
                // 等于把正文整段丢掉 —— 于是所有 runSeparated 的调用方（校验预览、config 解析、
                // 服务/端口盘点、compose ls 发现外来项目）拿到的 stdout / stderr 永远是空串，
                // 界面表现为「点校验只跳到✓，预览框一片空白」。
                // 摘行而不是截断，也让这个方法对标记顺序不再敏感。
                int lineStart = text.lastIndexOf('\n', rcAt);
                String before = (lineStart < 0) ? "" : text.substring(0, lineStart);
                String after = (end < 0) ? "" : text.substring(end);
                text = before + after;
            }
            return new ExecOutcome(exitCode, section(text, MARK_OUT_BEGIN, MARK_OUT_END),
                    section(text, MARK_ERR_BEGIN, MARK_ERR_END));
        }

        private static String section(String text, String begin, String end) {
            int from = text.indexOf(begin);
            if (from < 0) {
                return "";
            }
            int start = from + begin.length();
            int to = text.indexOf(end, start);
            String body = to < 0 ? text.substring(start) : text.substring(start, to);
            // 脚本在 END 标记前也打一个换行，正文本身还可能自带行尾换行 —— 全去掉，
            // 否则预览框 / 控制台里会各多一行空行
            return trimTrailingNewlines(body.startsWith("\n") ? body.substring(1) : body);
        }

        public int getExitCode() {
            return exitCode;
        }

        public String getStdout() {
            return stdout;
        }

        public String getStderr() {
            return stderr;
        }

        public boolean isOk() {
            return exitCode == 0;
        }

        /** stderr 优先，其次 stdout 的末尾几行 —— 这是给用户看的失败原因 */
        public String getErrorText() {
            String err = StrUtil.trimToEmpty(stderr);
            if (!err.isEmpty()) {
                return err;
            }
            String out = StrUtil.trimToEmpty(stdout);
            if (out.isEmpty()) {
                return "退出码 " + exitCode + "（命令没有任何输出）";
            }
            String[] lines = out.split("\\R");
            StringBuilder sb = new StringBuilder();
            int from = Math.max(0, lines.length - 5);
            for (int i = from; i < lines.length; i++) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(lines[i].trim());
            }
            return sb.toString();
        }
    }
}
