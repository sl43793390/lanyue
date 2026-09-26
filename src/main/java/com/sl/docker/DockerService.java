package com.sl.docker;

import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.sl.docker.model.DockerContainer;
import com.sl.docker.model.DockerFileInfo;
import com.sl.docker.model.DockerImage;
import com.sl.docker.model.DockerNetwork;
import com.sl.docker.model.DockerRunSpec;
import com.sl.docker.model.DockerStat;
import com.sl.docker.model.DockerSystemInfo;
import com.sl.docker.model.DockerVolume;
import com.sl.util.SSHClientUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Docker 领域操作。
 * <p>
 * 每个方法都对应一条（或几条）{@code docker} 子命令，负责把输出解析成模型对象。
 * 解析一律走 {@code --format} 的显式字段 + {@code \t} 分隔，不去解析人类可读的表格
 * —— 表格列宽随内容变化，之前那种"按两个空格切"的写法在真实环境里必炸
 * （{@code RemoteMonitorComponent} 里的 {@code free -m} 就踩过）。
 */
public class DockerService {

    private static final Logger log = LoggerFactory.getLogger(DockerService.class);

    /** docker inspect 一次最多带多少个名字，避免命令行过长 */
    private static final int INSPECT_BATCH = 40;

    private static final String PS_FORMAT =
            "{{.ID}}\\t{{.Names}}\\t{{.Image}}\\t{{.Status}}\\t{{.Ports}}\\t{{.CreatedAt}}\\t{{.Command}}";
    private static final String IMAGE_FORMAT =
            "{{.ID}}\\t{{.Repository}}\\t{{.Tag}}\\t{{.Size}}\\t{{.CreatedAt}}";
    private static final String STATS_FORMAT =
            "{{.ID}}\\t{{.Name}}\\t{{.CPUPerc}}\\t{{.MemUsage}}\\t{{.MemPerc}}\\t{{.NetIO}}\\t{{.BlockIO}}";

    private final DockerExecutor executor;

    public DockerService(DockerExecutor executor) {
        this.executor = executor;
    }

    public DockerExecutor getExecutor() {
        return executor;
    }

    /* ================================================================== */
    /* 容器                                                                */
    /* ================================================================== */

    public List<DockerContainer> listContainers(boolean all) throws IOException {
        List<String> args = new ArrayList<String>(Arrays.asList("ps", "--no-trunc", "--format", PS_FORMAT));
        if (all) {
            args.add(1, "-a");
        }
        List<String> lines = executor.dockerLines(args.toArray(new String[0]));
        List<DockerContainer> result = new ArrayList<DockerContainer>();
        for (String line : lines) {
            // 字段数固定 7，Command 里万一带了 tab 就整段并回最后一列
            String[] cells = line.split("\t", 7);
            if (cells.length < 4) {
                log.warn("docker ps 输出无法解析，已跳过：{}", line);
                continue;
            }
            DockerContainer container = new DockerContainer();
            container.setId(cells[0].trim());
            container.setName(cells[1].trim());
            container.setImage(cells[2].trim());
            container.setStatus(cells[3].trim());
            container.setPorts(cells.length > 4 ? cells[4].trim() : "");
            container.setCreatedAt(cells.length > 5 ? cells[5].trim() : "");
            container.setCommand(cells.length > 6 ? cells[6].trim() : "");
            container.resolveStateFromStatus();
            result.add(container);
        }
        return result;
    }

    public void startContainer(String id) throws IOException {
        executor.dockerChecked("start", id);
    }

    public void stopContainer(String id) throws IOException {
        executor.dockerChecked("stop", id);
    }

    public void restartContainer(String id) throws IOException {
        executor.dockerChecked("restart", id);
    }

    public void pauseContainer(String id) throws IOException {
        executor.dockerChecked("pause", id);
    }

    public void unpauseContainer(String id) throws IOException {
        executor.dockerChecked("unpause", id);
    }

    /**
     * 删除容器。{@code force} 为 true 时带 {@code -f}（运行中的容器会被强杀后删除）。
     */
    public String removeContainer(String id, boolean force, boolean removeVolumes) throws IOException {
        if (force) {
            return removeVolumes
                    ? executor.dockerChecked("rm", "-f", "-v", id)
                    : executor.dockerChecked("rm", "-f", id);
        }
        return removeVolumes
                ? executor.dockerChecked("rm", "-v", id)
                : executor.dockerChecked("rm", id);
    }

    /** 创建容器，返回新容器的 ID */
    public String createContainer(DockerRunSpec spec) throws IOException {
        if (null == spec || StrUtil.isBlank(spec.getImage())) {
            throw new IOException("镜像不能为空");
        }
        List<String> args = spec.buildRunArguments();
        String output = executor.dockerChecked(args.toArray(new String[0]));
        return StrUtil.trimToEmpty(output);
    }

    /** 「创建容器」弹窗允许执行的 docker 子命令 */
    private static final List<String> RUN_SUBCOMMANDS = Arrays.asList("run", "create");

    /**
     * 执行用户在「创建容器」弹窗里粘贴的整条 docker 命令，返回命令输出。
     * <p>
     * 与 {@link #createContainer(DockerRunSpec)} 的关系：那个是表单拼参数，这个是把
     * 用户手里的命令行原样执行 —— 参数不再由界面二次解释，用户贴什么就是什么。
     */
    public String runDockerCommand(String rawCommand) throws IOException {
        String subCommand = normalizeRunCommand(rawCommand);
        DockerExecutor.CmdResult result = executor.dockerRaw(subCommand);
        if (!result.isOk()) {
            throw new IOException("docker " + firstWord(subCommand) + " 执行失败：" + result.errorMessage());
        }
        return StrUtil.trimToEmpty(result.getOutput());
    }

    /**
     * 把用户粘贴的命令规范化成「能直接拼在 docker 前缀后面的子命令」，并做安全检查。
     * <p>
     * 接受 {@code docker run ...}、{@code sudo docker run ...}、
     * {@code /usr/bin/docker run ...}，也接受只写 {@code run ...}，以及多行带
     * {@code \} 续行的写法（从文档里复制过来的命令基本都是这种）。
     * <p>
     * <b>只放行 run / create。</b>这个弹窗的语义就是"创建容器"，放开成任意 docker
     * 子命令没有意义；更要紧的是不能放开成任意 shell 命令 —— 那等于在界面上开了
     * 一个绕过菜单权限的后门（要敲别的命令请走「SSH 管理」）。
     *
     * @return 去掉 docker 前缀后的子命令，例如 {@code run -d --name nginx nginx:1.25}
     * @throws IOException 校验不通过；message 是可以直接弹给用户看的人话
     */
    public static String normalizeRunCommand(String rawCommand) throws IOException {
        String text = StrUtil.trimToEmpty(rawCommand);
        if (text.isEmpty()) {
            throw new IOException("请先粘贴要执行的 docker run 命令");
        }
        // 先摊平成一行：续行符 + 换行 -> 空格，剩下的换行也 -> 空格
        text = text.replaceAll("\\\\\\s*\\R", " ").replaceAll("\\R+", " ").trim();
        // 从文档/教程里复制出来的命令常带着 shell 提示符（$ / # / >），一并去掉
        text = text.replaceFirst("^[>$#]\\s+", "").trim();

        String stripped = text.replaceFirst("^(sudo\\s+(-n\\s+)?)?(\\S*/)?docker(\\.exe)?\\s+", "");
        if (stripped.equals(text)) {
            // 没写 docker 前缀：第一个词本来就是 run / create 时放过，否则提示补前缀
            if (!RUN_SUBCOMMANDS.contains(firstWord(text).toLowerCase())) {
                throw new IOException("命令要以 docker 开头，例如："
                        + "docker run -d --name my-nginx -p 8080:80 nginx:1.25");
            }
            stripped = text;
        }
        String subCommand = stripped.trim();
        if (subCommand.isEmpty()) {
            throw new IOException("docker 后面没有子命令，例如：docker run -d nginx:1.25");
        }
        String name = firstWord(subCommand).toLowerCase();
        if (!RUN_SUBCOMMANDS.contains(name)) {
            throw new IOException("这个弹窗只会执行 docker run / docker create，「" + name
                    + "」请到「SSH 管理」里执行");
        }
        if (containsShellMetachar(subCommand)) {
            throw new IOException("命令里含有 ; & | ` > < 这类 shell 连接符，"
                    + "这里只执行一条 docker 命令，组合命令请到「SSH 管理」里执行");
        }
        return subCommand;
    }

    /** 取第一个空白分隔的词 */
    private static String firstWord(String text) {
        String trimmed = StrUtil.trimToEmpty(text);
        int index = trimmed.indexOf(' ');
        return index < 0 ? trimmed : trimmed.substring(0, index);
    }

    /**
     * 命令里（<b>引号外</b>）是否含 shell 连接符。
     * <p>
     * 逐个字符扫而不是直接 contains：{@code --env MSG="a;b"} 这种引号里的分号是合法参数，
     * 一刀切会把正常命令拦下来。
     */
    private static boolean containsShellMetachar(String text) {
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (c == ';' || c == '|' || c == '&' || c == '`' || c == '>' || c == '<') {
                return true;
            }
            if (c == '$' && i + 1 < text.length() && text.charAt(i + 1) == '(') {
                return true;
            }
        }
        return false;
    }

    /** {@code docker inspect} 的输出本来就是缩进好的 JSON，原样返回 */
    public String inspect(String id) throws IOException {
        DockerExecutor.CmdResult result = executor.docker("inspect", id);
        if (!result.isOk()) {
            throw new IOException("docker inspect 执行失败：" + result.errorMessage());
        }
        return result.getOutput();
    }

    /**
     * 构建 {@code docker logs} 命令。
     *
     * @param follow     是否持续跟踪（{@code -f}）
     * @param tailLines  只取末尾多少行，小于 0 表示全部
     * @param timestamps 是否加时间戳
     */
    public String buildLogsCommand(String id, boolean follow, int tailLines, boolean timestamps) {
        // 这里必须带上探测出来的前缀（可能是 sudo -n /usr/bin/docker）：
        // 走的是 openStream 直连通道，不经过 DockerExecutor.docker() 的自动补前缀，
        // 写死 "docker" 会让 Rocky/Ubuntu 上的普通用户永远拿不到日志（permission denied）
        StringBuilder sb = new StringBuilder(StrUtil.emptyToDefault(executor.getCommandPrefix(), "docker"))
                .append(" logs");
        if (follow) {
            sb.append(" -f");
        }
        if (tailLines >= 0) {
            sb.append(" --tail ").append(tailLines);
        }
        if (timestamps) {
            sb.append(" --timestamps");
        }
        sb.append(' ').append(DockerExecutor.q(id));
        return sb.toString();
    }

    /** 一次性取日志（用于导出下载，流式返回） */
    public InputStream openLogsStream(String id, int tailLines, boolean timestamps) throws IOException {
        return executor.openStream(buildLogsCommand(id, false, tailLines, timestamps));
    }

    /**
     * 取一次 {@code docker stats --no-stream}。传空的 id 列表表示取全部容器。
     */
    public List<DockerStat> stats(List<String> containerIds) throws IOException {
        List<String> args = new ArrayList<String>(Arrays.asList(
                "stats", "--no-stream", "--format", STATS_FORMAT));
        if (null != containerIds) {
            for (String id : containerIds) {
                if (StrUtil.isNotBlank(id)) {
                    args.add(id.trim());
                }
            }
        }
        DockerExecutor.CmdResult result = executor.docker(args.toArray(new String[0]));
        if (!result.isOk()) {
            throw new IOException("docker stats 执行失败：" + result.errorMessage());
        }
        List<DockerStat> list = new ArrayList<DockerStat>();
        for (String line : result.getOutput().split("\\R")) {
            DockerStat stat = parseStatLine(line);
            if (null != stat) {
                list.add(stat);
            }
        }
        return list;
    }

    private DockerStat parseStatLine(String line) {
        if (StrUtil.isBlank(line)) {
            return null;
        }
        String[] cells = line.split("\t", -1);
        if (cells.length < 5) {
            return null;
        }
        DockerStat stat = new DockerStat();
        stat.setContainerId(cells[0].trim());
        stat.setName(cells[1].trim());
        stat.setCpuPercent(parsePercent(cells[2]));
        // MemUsage 形如 "12.34MiB / 1GiB"
        String[] mem = splitPair(cells[3]);
        stat.setMemUsageBytes(DockerStat.parseBytes(mem[0]));
        stat.setMemLimitBytes(DockerStat.parseBytes(mem[1]));
        stat.setMemPercent(parsePercent(cells[4]));
        if (cells.length > 5) {
            String[] net = splitPair(cells[5]);
            stat.setNetInputBytes(DockerStat.parseBytes(net[0]));
            stat.setNetOutputBytes(DockerStat.parseBytes(net[1]));
        }
        if (cells.length > 6) {
            String[] block = splitPair(cells[6]);
            stat.setBlockInputBytes(DockerStat.parseBytes(block[0]));
            stat.setBlockOutputBytes(DockerStat.parseBytes(block[1]));
        }
        return stat;
    }

    private String[] splitPair(String text) {
        String trimmed = StrUtil.trimToEmpty(text);
        int idx = trimmed.indexOf('/');
        if (idx < 0) {
            return new String[]{trimmed, "0B"};
        }
        return new String[]{trimmed.substring(0, idx).trim(), trimmed.substring(idx + 1).trim()};
    }

    private double parsePercent(String text) {
        String value = StrUtil.trimToEmpty(text).replace("%", "").trim();
        if (!NumberUtil.isNumber(value)) {
            return 0d;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

    /**
     * 容器里可用的 shell：优先 bash，没有就退到 sh。
     */
    public String resolveContainerShell(String containerId) {
        try {
            DockerExecutor.CmdResult result = executor.docker(
                    "exec", containerId, "sh", "-c", "command -v bash || command -v sh");
            if (result.isOk()) {
                String shell = StrUtil.trimToEmpty(result.getOutput());
                // 可能有多行，取第一行合法的绝对路径
                for (String line : shell.split("\\R")) {
                    String candidate = line.trim();
                    if (candidate.startsWith("/")) {
                        return candidate;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("探测容器 {} 的 shell 失败：{}", containerId, e.getMessage());
        }
        return "/bin/sh";
    }

    /* ------------------------------------------------------------------ */
    /* 容器内文件                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * 列出容器内某个目录。用 {@code ls -la} 而不是 {@code --time-style} / {@code find -printf}：
     * alpine 等镜像里是 BusyBox 实现，GNU 扩展参数直接报错。
     */
    public List<DockerFileInfo> listContainerFiles(String containerId, String path) throws IOException {
        String target = StrUtil.isBlank(path) ? "/" : path.trim();
        DockerExecutor.CmdResult result = executor.docker("exec", containerId, "ls", "-la", target);
        if (!result.isOk()) {
            // 有些极简镜像 PATH 不全，退回用 sh 显式执行
            result = executor.docker("exec", containerId, "/bin/sh", "-c", "ls -la " + DockerExecutor.q(target));
        }
        if (!result.isOk()) {
            throw new IOException("读取容器内目录 " + target + " 失败：" + result.errorMessage());
        }
        List<DockerFileInfo> files = new ArrayList<DockerFileInfo>();
        String parent = parentOf(target);
        for (String line : result.getOutput().split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("total ")) {
                continue;
            }
            DockerFileInfo info = parseLsLine(trimmed, target, parent);
            if (null != info) {
                files.add(info);
            }
        }
        return files;
    }

    private DockerFileInfo parseLsLine(String line, String dir, String parent) {
        String[] cells = line.split("\\s+", 9);
        if (cells.length < 9) {
            return null;
        }
        String name = cells[8];
        if (StrUtil.isBlank(name) || ".".equals(name) || "..".equals(name)) {
            return null;
        }
        DockerFileInfo info = new DockerFileInfo();
        info.setPermission(cells[0]);
        info.resolveType(cells[0].charAt(0));
        info.setOwner(cells[2]);
        info.setGroup(cells[3]);
        info.setSize(formatSize(cells[4]));
        info.setModifyTime(cells[5] + " " + cells[6] + " " + cells[7]);
        info.setName(name);
        info.setPath("/".equals(dir) ? "/" + name : dir + "/" + name);
        info.setParentPath(parent);
        return info;
    }

    private static String formatSize(String raw) {
        long bytes;
        try {
            bytes = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return raw;
        }
        return DockerStat.formatBytes(bytes);
    }

    private static String parentOf(String dir) {
        if (StrUtil.isBlank(dir) || "/".equals(dir)) {
            return "/";
        }
        String trimmed = dir.endsWith("/") ? dir.substring(0, dir.length() - 1) : dir;
        int idx = trimmed.lastIndexOf('/');
        return idx <= 0 ? "/" : trimmed.substring(0, idx);
    }

    /**
     * 把容器内文件下载到本地临时文件：先 {@code docker cp} 到宿主机临时路径，再走 SFTP 拉回来，
     * 最后清理宿主机临时文件。
     * <p>
     * 不直接读 {@code docker cp id:path -} 的 tar 流：那需要在上层做 tar 解包，
     * 而 SFTP 通道本来就已经建好了，复用即可。
     *
     * @return 本地临时文件，调用方负责删除
     */
    public File downloadContainerFile(String containerId, String containerPath, String fileName) throws IOException {
        String remoteTmp = "/tmp/logviewer-cp-" + UUID.randomUUID().toString().replace("-", "");
        File localTmp = File.createTempFile("docker-dl-", "-" + sanitizeFileName(fileName));
        try {
            executor.dockerChecked("cp", containerId + ":" + containerPath, remoteTmp);
            SSHClientUtil ssh = executor.ssh();
            if (!ssh.downloadFile(remoteTmp, localTmp.getAbsolutePath())) {
                throw new IOException("下载文件失败：远端与本地文件大小不一致");
            }
            return localTmp;
        } catch (IOException e) {
            deleteQuietly(localTmp);
            throw e;
        } finally {
            removeRemoteQuietly(remoteTmp);
        }
    }

    /**
     * 上传本地文件到容器内目录：先 SFTP 推上宿主机临时目录，再 {@code docker cp} 进容器。
     *
     * @param containerDir 容器内的目标目录（会以原文件名放进去）
     */
    public void uploadContainerFile(String containerId, String containerDir, File localFile) throws IOException {
        if (null == localFile || !localFile.isFile()) {
            throw new IOException("本地文件不存在，无法上传");
        }
        String remoteDir = "/tmp/logviewer-up-" + UUID.randomUUID().toString().replace("-", "");
        String remotePath = remoteDir + "/" + localFile.getName();
        try {
            executor.exec("mkdir -p " + DockerExecutor.q(remoteDir));
            SSHClientUtil ssh = executor.ssh();
            if (!ssh.uploadFile(localFile.getAbsolutePath(), remotePath, null)) {
                throw new IOException("上传文件到宿主机失败");
            }
            String target = containerDir.endsWith("/") ? containerDir : containerDir + "/";
            executor.dockerChecked("cp", remotePath, containerId + ":" + target);
        } finally {
            executor.exec("rm -rf " + DockerExecutor.q(remoteDir));
        }
    }

    private void removeRemoteQuietly(String remotePath) {
        try {
            executor.exec("rm -f " + DockerExecutor.q(remotePath));
        } catch (Exception e) {
            log.warn("清理宿主机临时文件 {} 失败：{}", remotePath, e.getMessage());
        }
    }

    private static void deleteQuietly(File file) {
        if (null != file && file.exists()) {
            try {
                Files.deleteIfExists(file.toPath());
            } catch (IOException e) {
                log.warn("删除本地临时文件 {} 失败：{}", file.getAbsolutePath(), e.getMessage());
            }
        }
    }

    public static String sanitizeFileName(String name) {
        if (StrUtil.isBlank(name)) {
            return "download.bin";
        }
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\s]", "_");
        return cleaned.isEmpty() ? "download.bin" : cleaned;
    }

    /* ================================================================== */
    /* 镜像                                                                */
    /* ================================================================== */

    public List<DockerImage> listImages(boolean all) throws IOException {
        List<String> args = new ArrayList<String>(Arrays.asList("images", "--no-trunc", "--format", IMAGE_FORMAT));
        if (all) {
            args.add(1, "-a");
        }
        List<String> lines = executor.dockerLines(args.toArray(new String[0]));
        List<DockerImage> images = new ArrayList<DockerImage>();
        for (String line : lines) {
            String[] cells = line.split("\t", 5);
            if (cells.length < 5) {
                log.warn("docker images 输出无法解析，已跳过：{}", line);
                continue;
            }
            DockerImage image = new DockerImage();
            image.setId(cells[0].trim());
            image.setRepository(cells[1].trim());
            image.setTag(cells[2].trim());
            image.setSize(cells[3].trim());
            image.setCreatedAt(cells[4].trim());
            images.add(image);
        }
        return images;
    }

    /** 拉取镜像。大镜像可能要几分钟，调用方要放到后台线程。 */
    public String pullImage(String reference) throws IOException {
        if (StrUtil.isBlank(reference)) {
            throw new IOException("镜像名称不能为空");
        }
        return executor.dockerChecked("pull", reference.trim());
    }

    public void removeImage(String id, boolean force) throws IOException {
        if (force) {
            executor.dockerChecked("rmi", "-f", id);
        } else {
            executor.dockerChecked("rmi", id);
        }
    }

    /** 清理悬空镜像；{@code all=true} 相当于 {@code docker image prune -a}，会删掉所有未被容器使用的镜像 */
    public String pruneImages(boolean all) throws IOException {
        if (all) {
            return executor.dockerChecked("image", "prune", "-a", "-f");
        }
        return executor.dockerChecked("image", "prune", "-f");
    }

    /**
     * 构建镜像。
     *
     * @param contextDir 宿主机上的构建上下文目录（Dockerfile 所在目录），或 Git 仓库地址
     * @param dockerfile Dockerfile 文件名，null 表示用默认的 {@code Dockerfile}
     */
    public String buildImage(String tag, String contextDir, String dockerfile, boolean noCache) throws IOException {
        if (StrUtil.isBlank(tag)) {
            throw new IOException("镜像标签不能为空");
        }
        if (StrUtil.isBlank(contextDir)) {
            throw new IOException("构建上下文不能为空");
        }
        List<String> args = new ArrayList<String>(Arrays.asList("build", "-t", tag.trim()));
        if (StrUtil.isNotBlank(dockerfile)) {
            args.add("-f");
            args.add(dockerfile.trim());
        }
        if (noCache) {
            args.add("--no-cache");
        }
        args.add(contextDir.trim());
        return executor.dockerChecked(args.toArray(new String[0]));
    }

    /**
     * 导出镜像到本地临时文件（供脚本场景使用）。
     * <p>
     * 页面上走的是 {@link #openImageSaveStream(String)} 直接流给浏览器，
     * 不落盘；这个方法保留给需要拿到文件对象的调用方。
     */
    public File saveImage(String image, String fileName) throws IOException {
        String remoteTmp = "/tmp/logviewer-image-" + UUID.randomUUID().toString().replace("-", "") + ".tar";
        File localTmp = File.createTempFile("docker-image-", ".tar");
        try {
            executor.dockerChecked("save", "-o", remoteTmp, image);
            SSHClientUtil ssh = executor.ssh();
            if (!ssh.downloadFile(remoteTmp, localTmp.getAbsolutePath())) {
                throw new IOException("导出镜像失败：远端与本地文件大小不一致");
            }
            return localTmp;
        } catch (IOException e) {
            deleteQuietly(localTmp);
            throw e;
        } finally {
            removeRemoteQuietly(remoteTmp);
        }
    }

    /**
     * 打开 {@code docker save} 的输出流，直接把 tar 流给浏览器下载。
     * <p>
     * 不落盘再发：镜像动辄几百 MB 到几 GB，先 SFTP 拉到应用服务器再吐给浏览器等于
     * 双倍磁盘写 + 双倍 IO。这里先在服务端做一次 inspect 确认镜像在，
     * 避免用户下到一个 0 字节的空 tar 还不知道为什么。
     */
    public InputStream openImageSaveStream(String image) throws IOException {
        if (StrUtil.isBlank(image)) {
            throw new IOException("镜像不能为空");
        }
        DockerExecutor.CmdResult check = executor.docker("image", "inspect", image.trim());
        if (!check.isOk()) {
            throw new IOException("镜像不存在或不可导出：" + check.errorMessage());
        }
        return executor.openRawStream(executor.command("save", image.trim()));
    }

    /**
     * 上传 Dockerfile 到宿主机临时目录后构建。
     * <p>
     * 构建上下文就是那个临时目录，所以 Dockerfile 里的 {@code COPY}/{@code ADD}
     * 只能引用同一目录下的文件，需要更多上下文时请改用「从 Git 构建」。
     *
     * @param dockerfile     本地 Dockerfile
     * @param dockerfileName 在构建上下文里的文件名，一般是 {@code Dockerfile}
     */
    public String buildFromDockerfile(String tag, File dockerfile, String dockerfileName, boolean noCache)
            throws IOException {
        if (null == dockerfile || !dockerfile.isFile()) {
            throw new IOException("请先选择 Dockerfile");
        }
        String name = StrUtil.isBlank(dockerfileName) ? "Dockerfile" : dockerfileName.trim();
        String remoteDir = "/tmp/logviewer-build-" + UUID.randomUUID().toString().replace("-", "");
        try {
            executor.exec("mkdir -p " + DockerExecutor.q(remoteDir));
            SSHClientUtil ssh = executor.ssh();
            String remoteDockerfile = remoteDir + "/" + name;
            if (!ssh.uploadFile(dockerfile.getAbsolutePath(), remoteDockerfile, null)) {
                throw new IOException("Dockerfile 上传到宿主机失败");
            }
            return buildImage(tag, remoteDir, remoteDockerfile, noCache);
        } finally {
            executor.exec("rm -rf " + DockerExecutor.q(remoteDir));
        }
    }

    private static final String REMOTE_TMP_DIR = "/tmp";

    /** 把镜像从宿主机上的一个临时文件导入 */
    public String loadImage(File localFile) throws IOException {
        if (null == localFile || !localFile.isFile()) {
            throw new IOException("本地文件不存在，无法导入");
        }
        String remoteDir = REMOTE_TMP_DIR + "/logviewer-load-" + UUID.randomUUID().toString().replace("-", "");
        String remotePath = remoteDir + "/" + localFile.getName();
        try {
            executor.exec("mkdir -p " + DockerExecutor.q(remoteDir));
            SSHClientUtil ssh = executor.ssh();
            if (!ssh.uploadFile(localFile.getAbsolutePath(), remotePath, null)) {
                throw new IOException("上传镜像包到宿主机失败");
            }
            return executor.dockerChecked("load", "-i", remotePath);
        } finally {
            executor.exec("rm -rf " + DockerExecutor.q(remoteDir));
        }
    }

    /* ================================================================== */
    /* 数据卷                                                              */
    /* ================================================================== */

    public List<DockerVolume> listVolumes(boolean withUsage) throws IOException {
        List<String> names = executor.dockerLines("volume", "ls", "-q");
        if (names.isEmpty()) {
            return new ArrayList<DockerVolume>();
        }
        Map<String, List<String>> usage = withUsage ? volumeUsageMap() : new HashMap<String, List<String>>();
        List<DockerVolume> volumes = new ArrayList<DockerVolume>();
        for (List<JSONObject> batch : inspectBatches("volume", names)) {
            for (JSONObject json : batch) {
                DockerVolume volume = new DockerVolume();
                volume.setName(json.getString("Name"));
                volume.setDriver(json.getString("Driver"));
                volume.setMountpoint(json.getString("Mountpoint"));
                volume.setScope(json.getString("Scope"));
                volume.setCreatedAt(StrUtil.emptyToDefault(json.getString("CreatedAt"), ""));
                List<String> users = usage.get(volume.getName());
                volume.setUsedBy(null == users ? new ArrayList<String>() : users);
                volumes.add(volume);
            }
        }
        return volumes;
    }

    /** volume 名 -> 正在使用它的容器名，一次 {@code docker ps} 拿到，避免 N 次往返 */
    private Map<String, List<String>> volumeUsageMap() throws IOException {
        Map<String, List<String>> map = new HashMap<String, List<String>>();
        List<String> lines = executor.dockerLines(
                "ps", "-a", "--format", "{{.Names}}\\t{{.Mounts}}");
        for (String line : lines) {
            String[] cells = line.split("\t", 2);
            if (cells.length < 2) {
                continue;
            }
            String containerName = cells[0].trim();
            for (String mount : cells[1].split(",")) {
                String name = mount.trim();
                if (name.isEmpty() || name.contains("/")) {
                    // 匿名卷/绑定挂载不在这里统计
                    continue;
                }
                List<String> list = map.get(name);
                if (null == list) {
                    list = new ArrayList<String>();
                    map.put(name, list);
                }
                if (!list.contains(containerName)) {
                    list.add(containerName);
                }
            }
        }
        return map;
    }

    public void createVolume(String name, String driver) throws IOException {
        if (StrUtil.isBlank(name)) {
            throw new IOException("卷名不能为空");
        }
        if (StrUtil.isBlank(driver) || "local".equals(driver.trim())) {
            executor.dockerChecked("volume", "create", name.trim());
        } else {
            executor.dockerChecked("volume", "create", "--driver", driver.trim(), name.trim());
        }
    }

    public void removeVolume(String name, boolean force) throws IOException {
        if (force) {
            executor.dockerChecked("volume", "rm", "-f", name);
        } else {
            executor.dockerChecked("volume", "rm", name);
        }
    }

    public String pruneVolumes() throws IOException {
        return executor.dockerChecked("volume", "prune", "-f");
    }

    /* ================================================================== */
    /* 网络                                                                */
    /* ================================================================== */

    public List<DockerNetwork> listNetworks(boolean withDetails) throws IOException {
        List<String> lines = executor.dockerLines(
                "network", "ls", "--no-trunc", "--format", "{{.ID}}\\t{{.Name}}\\t{{.Driver}}\\t{{.Scope}}");
        List<DockerNetwork> networks = new ArrayList<DockerNetwork>();
        Map<String, JSONObject> details = new HashMap<String, JSONObject>();
        if (withDetails) {
            List<String> ids = new ArrayList<String>();
            for (String line : lines) {
                String[] cells = line.split("\t", 4);
                if (cells.length > 0 && StrUtil.isNotBlank(cells[0])) {
                    ids.add(cells[0].trim());
                }
            }
            for (List<JSONObject> batch : inspectBatches("network", ids)) {
                for (JSONObject json : batch) {
                    details.put(json.getString("Id"), json);
                }
            }
        }
        for (String line : lines) {
            String[] cells = line.split("\t", 4);
            if (cells.length < 4) {
                log.warn("docker network ls 输出无法解析，已跳过：{}", line);
                continue;
            }
            DockerNetwork network = new DockerNetwork();
            network.setId(cells[0].trim());
            network.setName(cells[1].trim());
            network.setDriver(cells[2].trim());
            network.setScope(cells[3].trim());
            JSONObject detail = details.get(network.getId());
            if (null != detail) {
                fillNetworkDetail(network, detail);
            }
            networks.add(network);
        }
        return networks;
    }

    private void fillNetworkDetail(DockerNetwork network, JSONObject detail) {
        network.setInternal(String.valueOf(Boolean.TRUE.equals(detail.getBoolean("Internal"))));
        network.setSubnet("-");
        network.setGateway("-");
        JSONArray ipam = detail.getJSONArray("IPAM");
        if (null != ipam && !ipam.isEmpty()) {
            JSONObject first = ipam.getJSONObject(0);
            JSONArray configs = null == first ? null : first.getJSONArray("Config");
            JSONObject config = firstOf(configs);
            if (null != config) {
                network.setSubnet(StrUtil.emptyToDefault(config.getString("Subnet"), "-"));
                network.setGateway(StrUtil.emptyToDefault(config.getString("Gateway"), "-"));
            }
        }
        List<String> containers = new ArrayList<String>();
        JSONObject containerMap = detail.getJSONObject("Containers");
        if (null != containerMap) {
            for (String key : containerMap.keySet()) {
                JSONObject item = containerMap.getJSONObject(key);
                String name = null == item ? null : item.getString("Name");
                String shortId = key.length() > 12 ? key.substring(0, 12) : key;
                containers.add(StrUtil.isBlank(name) ? shortId : name + "(" + shortId + ")");
            }
        }
        network.setContainers(containers);
    }

    private static JSONObject firstOf(JSONArray array) {
        if (null == array || array.isEmpty()) {
            return null;
        }
        return array.getJSONObject(0);
    }

    public void createNetwork(String name, String driver, String subnet, String gateway) throws IOException {
        if (StrUtil.isBlank(name)) {
            throw new IOException("网络名不能为空");
        }
        List<String> args = new ArrayList<String>(Arrays.asList("network", "create"));
        if (StrUtil.isNotBlank(driver)) {
            args.add("--driver");
            args.add(driver.trim());
        }
        if (StrUtil.isNotBlank(subnet)) {
            args.add("--subnet");
            args.add(subnet.trim());
        }
        if (StrUtil.isNotBlank(gateway)) {
            args.add("--gateway");
            args.add(gateway.trim());
        }
        args.add(name.trim());
        executor.dockerChecked(args.toArray(new String[0]));
    }

    public void removeNetwork(String id) throws IOException {
        executor.dockerChecked("network", "rm", id);
    }

    public void connectNetwork(String networkId, String container, String alias) throws IOException {
        if (StrUtil.isBlank(alias)) {
            executor.dockerChecked("network", "connect", networkId, container);
        } else {
            executor.dockerChecked("network", "connect", "--alias", alias.trim(), networkId, container);
        }
    }

    public void disconnectNetwork(String networkId, String container, boolean force) throws IOException {
        if (force) {
            executor.dockerChecked("network", "disconnect", "-f", networkId, container);
        } else {
            executor.dockerChecked("network", "disconnect", networkId, container);
        }
    }

    /* ================================================================== */
    /* 系统                                                                */
    /* ================================================================== */

    public DockerSystemInfo systemInfo() throws IOException {
        DockerSystemInfo info = new DockerSystemInfo();
        info.setDockerCommand(executor.getCommandPrefix());

        /*
         * 模板字段刻意只用 Docker 1.13 起就存在的：
         * {{.Server.MinAPIVersion}} 要 20.10+，而 Rocky 8 仓库里自带的还是 19.03，
         * 一旦模板里带上它，整条 docker version 会直接报 "map has no entry for key"，
         * 连版本号都拿不到。宁可能力少一点，也不要整块不可用。
         */
        DockerExecutor.CmdResult version = executor.docker("version", "--format",
                "{{.Server.Version}}\\t{{.Server.APIVersion}}\\t{{.Client.Version}}"
                        + "\\t{{.Server.Os}}\\t{{.Server.Arch}}\\t{{.Server.KernelVersion}}\\t{{.Server.GoVersion}}");
        if (version.isOk()) {
            String[] cells = version.getOutput().split("\t", -1);
            if (cells.length >= 7) {
                info.setServerVersion(cells[0].trim());
                info.setApiVersion(cells[1].trim());
                info.setClientVersion(cells[2].trim());
                info.setOsType(cells[3].trim());
                info.setArch(cells[4].trim());
                info.setKernelVersion(cells[5].trim());
                info.setMinApiVersion("-");
            }
        } else {
            info.setServerError(version.errorMessage());
        }

        DockerExecutor.CmdResult infoResult = executor.docker("info", "--format",
                "{{.Containers}}\\t{{.ContainersRunning}}\\t{{.ContainersPaused}}\\t{{.ContainersStopped}}"
                        + "\\t{{.Images}}\\t{{.Driver}}\\t{{.DockerRootDir}}\\t{{.OperatingSystem}}");
        if (infoResult.isOk()) {
            String[] cells = infoResult.getOutput().split("\t", -1);
            if (cells.length >= 8) {
                info.setContainers(parseInt(cells[0]));
                info.setContainersRunning(parseInt(cells[1]));
                info.setContainersPaused(parseInt(cells[2]));
                info.setContainersStopped(parseInt(cells[3]));
                info.setImages(parseInt(cells[4]));
                info.setStorageDriver(cells[5].trim());
                info.setRootDir(cells[6].trim());
            }
        } else {
            info.setInfoError(infoResult.errorMessage());
        }

        DockerExecutor.CmdResult df = executor.docker("system", "df");
        if (df.isOk()) {
            info.setDiskUsages(parseSystemDf(df.getOutput()));
        }

        probeEnvironment(info);
        return info;
    }

    /**
     * 探测目标主机环境。
     * <p>
     * 这一段是专门为 <b>Rocky Linux 8/9/10 与 Ubuntu 22.04</b> 加的：两个发行版的差异
     * 基本都落在「要不要 sudo」「SELinux 要不要打 :z」「firewalld 要不要放行端口」
     * 「Compose 是 v2 插件还是 v1 独立二进制」这四件事上，提前在页面上讲清楚，
     * 比让用户在报错里猜要省事得多。
     * <p>
     * 只用一条 SSH 命令把信息全捞回来，避免十几次往返。
     */
    private void probeEnvironment(DockerSystemInfo info) {
        String prefix = StrUtil.emptyToDefault(executor.getCommandPrefix(), "docker");
        /*
         * 刻意不用 sed 去抠 /etc/os-release：那个表达式要在 Java 字符串、shell 引号、
         * BRE 三层里各转义一遍（花括号 + 分组括号），几乎不可能一次写对。
         * 直接 source 之后取变量，一层引号就够了。
         */
        String script = "set +e; . /etc/os-release 2>/dev/null; "
                + "echo \"OSNAME=${PRETTY_NAME}\"; "
                + "echo \"UID=$(id -u)\"; "
                + "echo \"LOGINUSER=$(whoami)\"; "
                + "echo \"SELINUX_RAW=$(if command -v getenforce >/dev/null 2>&1; then getenforce; "
                + "elif [ -r /sys/fs/selinux/enforce ]; then cat /sys/fs/selinux/enforce; else echo NotInstalled; fi)\"; "
                + "echo \"FIREWALLD=$(systemctl is-active firewalld 2>/dev/null || echo not-running)\"; "
                + "echo \"COMPOSE2=$(" + prefix + " compose version --short 2>/dev/null | head -n1)\"; "
                + "echo \"COMPOSE1=$(docker-compose version --short 2>/dev/null | head -n1)\"";
        try {
            DockerExecutor.CmdResult result = executor.exec(script);
            Map<String, String> map = new HashMap<String, String>();
            for (String line : result.getOutput().split("\\R")) {
                String trimmed = line.trim();
                int idx = trimmed.indexOf('=');
                if (idx > 0) {
                    map.put(trimmed.substring(0, idx).trim(), trimmed.substring(idx + 1).trim());
                }
            }
            String uid = map.get("UID");
            String loginUser = map.get("LOGINUSER");
            info.setLoginUser(StrUtil.isBlank(loginUser) ? "" : loginUser + "(uid=" + uid + ")");
            info.setOsRelease(map.get("OSNAME"));
            info.setSelinuxMode(normalizeSelinux(map.get("SELINUX_RAW")));
            info.setFirewallState(map.get("FIREWALLD"));

            String compose2 = map.get("COMPOSE2");
            String compose1 = map.get("COMPOSE1");
            if (StrUtil.isNotBlank(compose2)) {
                info.setComposeVersion("v2 插件 " + compose2 + "（命令：docker compose）");
            } else if (StrUtil.isNotBlank(compose1)) {
                info.setComposeVersion("v1 独立二进制 " + compose1 + "（命令：docker-compose，已停止维护）");
            } else {
                info.setComposeVersion("未安装");
            }

            buildEnvironmentHints(info, uid, compose2, compose1);
        } catch (Exception e) {
            log.warn("探测 {} 的环境信息失败：{}", executor.hostLabel(), e.getMessage());
            info.addHint("环境探测失败：" + e.getMessage());
        }
    }

    /** selinuxfs 里的 1/0 换成可读文案 */
    private static String normalizeSelinux(String raw) {
        String value = StrUtil.trimToEmpty(raw);
        if ("1".equals(value)) {
            return "Enforcing";
        }
        if ("0".equals(value)) {
            return "Permissive";
        }
        return StrUtil.emptyToDefault(value, "未知");
    }

    private void buildEnvironmentHints(DockerSystemInfo info, String uid, String compose2, String compose1) {
        String prefix = StrUtil.emptyToDefault(info.getDockerCommand(), "");
        if (!"0".equals(uid) && prefix.contains("sudo")) {
            info.addHint("登录用户不是 root，已自动用 `sudo -n` 调用 docker。"
                    + "推荐把用户加入 docker 组以免每次都要 sudo：sudo usermod -aG docker $USER  （重新登录后生效）");
        }
        if (info.isSelinuxEnforcing()) {
            info.addHint("SELinux 处于 Enforcing：把宿主目录挂进容器时，数据卷要写成 "
                    + "/host/path:/container/path:z（多个容器共享）或 :Z（本容器独占），否则容器内会 permission denied。"
                    + "创建容器的弹窗里已提供该选项。可用 getenforce 查看，临时放宽用 setenforce 0（重启失效）。");
        }
        if ("active".equals(StrUtil.trimToEmpty(info.getFirewallState()))) {
            info.addHint("firewalld 处于 active：容器发布的端口要在防火墙上放行，否则外部访问不通。"
                    + "例如：sudo firewall-cmd --add-port=8080/tcp --permanent && sudo firewall-cmd --reload");
        }
        if (StrUtil.isBlank(compose2) && StrUtil.isBlank(compose1)) {
            String os = StrUtil.emptyToDefault(info.getOsRelease(), "").toLowerCase();
            String install;
            if (os.contains("ubuntu") || os.contains("debian")) {
                install = "sudo apt-get update && sudo apt-get install -y docker-compose-plugin";
            } else if (os.contains("rocky") || os.contains("centos") || os.contains("red hat")
                    || os.contains("almalinux") || os.contains("oracle")) {
                install = "sudo dnf install -y dnf-plugins-core && "
                        + "sudo dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo && "
                        + "sudo dnf install -y docker-compose-plugin";
            } else {
                install = "参考 https://docs.docker.com/compose/install/linux/";
            }
            info.addHint("未检测到 Docker Compose。Rocky 8/9/10 与 Ubuntu 22.04 都建议装 Compose V2 插件，"
                    + "之后用 `docker compose`（中间是空格）而不是已停止维护的 docker-compose。" + install);
        }
    }

    private static int parseInt(String text) {
        try {
            return Integer.parseInt(StrUtil.trimToEmpty(text));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 解析 {@code docker system df} 的表格输出。
     * <p>
     * 这里刻意解析人类可读表格而不是 {@code --format}：{@code system df} 的
     * {@code --format} 直到 20.10 才支持，老环境上直接报 unknown flag。
     * 表格列宽会变，所以按「第一个纯数字列」定位 TOTAL，前面的是 TYPE
     * （{@code Local Volumes} 是两个词），后面依次是 ACTIVE / SIZE / 剩余=REST。
     */
    static List<DockerSystemInfo.DiskUsage> parseSystemDf(String output) {
        List<DockerSystemInfo.DiskUsage> usages = new ArrayList<DockerSystemInfo.DiskUsage>();
        if (StrUtil.isBlank(output)) {
            return usages;
        }
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("TYPE")) {
                continue;
            }
            String[] cells = trimmed.split("\\s+");
            int numericAt = -1;
            for (int i = 0; i < cells.length; i++) {
                if (NumberUtil.isInteger(cells[i])) {
                    numericAt = i;
                    break;
                }
            }
            if (numericAt <= 0 || cells.length < numericAt + 4) {
                continue;
            }
            StringBuilder type = new StringBuilder();
            for (int i = 0; i < numericAt; i++) {
                if (!type.isEmpty()) {
                    type.append(' ');
                }
                type.append(cells[i]);
            }
            String reclaimable = StrUtil.join(" ", (Object) Arrays.copyOfRange(cells, numericAt + 3, cells.length));
            usages.add(new DockerSystemInfo.DiskUsage(type.toString(), cells[numericAt],
                    cells[numericAt + 1], cells[numericAt + 2], reclaimable));
        }
        return usages;
    }

    /**
     * 一键清理。
     *
     * @param what containers / images / volumes / networks / all
     */
    public String prune(String what) throws IOException {
        String target = StrUtil.emptyToDefault(what, "all").trim().toLowerCase();
        StringBuilder report = new StringBuilder();
        if ("containers".equals(target) || "all".equals(target)) {
            report.append("【容器】").append(executor.dockerChecked("container", "prune", "-f")).append('\n');
        }
        if ("images".equals(target) || "all".equals(target)) {
            report.append("【悬空镜像】").append(executor.dockerChecked("image", "prune", "-f")).append('\n');
        }
        if ("volumes".equals(target) || "all".equals(target)) {
            report.append("【未使用卷】").append(executor.dockerChecked("volume", "prune", "-f")).append('\n');
        }
        if ("networks".equals(target) || "all".equals(target)) {
            report.append("【未使用网络】").append(executor.dockerChecked("network", "prune", "-f")).append('\n');
        }
        if ("all".equals(target)) {
            // 容器已清，再清一次未使用的镜像（不带 -a，避免误删还有用的）
            report.append("【悬空镜像补充】").append(executor.dockerChecked("image", "prune", "-f")).append('\n');
        }
        if (report.length() == 0) {
            throw new IOException("未知的清理目标：" + what);
        }
        return report.toString();
    }

    /* ================================================================== */
    /* 公共小工具                                                          */
    /* ================================================================== */

    /**
     * 分批执行 {@code docker <sub> inspect a b c}，把 JSON 数组摊平成一批批结果。
     */
    private List<List<JSONObject>> inspectBatches(String subCommand, List<String> names) throws IOException {
        List<List<JSONObject>> result = new ArrayList<List<JSONObject>>();
        if (null == names || names.isEmpty()) {
            return result;
        }
        Set<String> unique = new LinkedHashSet<String>();
        for (String name : names) {
            if (StrUtil.isNotBlank(name)) {
                unique.add(name.trim());
            }
        }
        List<String> all = new ArrayList<String>(unique);
        for (int from = 0; from < all.size(); from += INSPECT_BATCH) {
            int to = Math.min(from + INSPECT_BATCH, all.size());
            List<String> slice = all.subList(from, to);
            List<String> args = new ArrayList<String>(Arrays.asList(subCommand, "inspect"));
            args.addAll(slice);
            DockerExecutor.CmdResult cmdResult = executor.docker(args.toArray(new String[0]));
            if (!cmdResult.isOk()) {
                throw new IOException("docker " + subCommand + " inspect 执行失败：" + cmdResult.errorMessage());
            }
            List<JSONObject> batch = new ArrayList<JSONObject>();
            try {
                JSONArray array = JSON.parseArray(cmdResult.getOutput());
                for (int i = 0; i < array.size(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    if (null != item) {
                        batch.add(item);
                    }
                }
            } catch (Exception e) {
                throw new IOException("解析 docker " + subCommand + " inspect 输出失败：" + e.getMessage(), e);
            }
            result.add(batch);
        }
        return result;
    }

    /** 调试用：把一段文本写到指定文件，排查解析问题时用得上 */
    static void dumpToFile(String text, File file) throws IOException {
        try (OutputStream out = Files.newOutputStream(file.toPath())) {
            out.write(StrUtil.emptyToDefault(text, "").getBytes(StandardCharsets.UTF_8));
        }
    }

    /** 环境变量文本 / 端口文本 里常见的中文逗号，统一成英文再切分 */
    public static List<String> splitUserInput(String text) {
        List<String> result = new ArrayList<String>();
        if (StrUtil.isBlank(text)) {
            return result;
        }
        for (String line : text.split("[\\r\\n,;，；]+")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /** 保序去重，用于拼接展示 */
    public static Map<String, String> newOrderedMap() {
        return new LinkedHashMap<String, String>();
    }
}
