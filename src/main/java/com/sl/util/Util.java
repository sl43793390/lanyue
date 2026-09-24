package com.sl.util;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

// 旧代码这里是 com.so.component.CurrentUser.idOrSystemUser()，
// 而 ComponentUtil 依赖 Vaadin 8 的 VaadinSession，在 Vaadin 24 下没有对应物。
// 换成本项目的 CurrentUser（读 Spring Security 上下文，见该类注释）。
import com.sl.security.CurrentUser;

import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.SM3;
import cn.hutool.system.SystemUtil;

public class Util {

	private static final Logger log = LoggerFactory.getLogger(Util.class);

	/**
	 * 判断文件的编码格式
	 * 
	 * @param fileName
	 *            :file
	 * @return 文件编码格式
	 * @throws Exception
	 */
	public static String getFileEncode(File fileName) throws Exception {
		try (BufferedInputStream bin = new BufferedInputStream(new FileInputStream(fileName))) {
			int p = (bin.read() << 8) + bin.read();
			String code = null;

			switch (p) {
			case 0xefbb:
				code = "UTF-8";
				break;
			case 0xfffe:
				code = "Unicode";
				break;
			case 0xfeff:
				code = "UTF-16BE";
				break;
			default:
				code = "GBK";
			}
			return code;
		}
	}

	/**
	 * 读取 classpath 下的配置文件。
	 * <p>
	 * 原实现在文件缺失/读取失败时返回 null，调用方紧接着 .listIterator() 就会 NPE，
	 * 这里统一返回空列表，由调用方自行判断“配置缺失”。
	 */
	public static List<String> getConfigFileAsLineByClasspathResource(String fileName) {
		ClassPathResource res = new ClassPathResource(fileName);
		if (!res.exists()) {
			log.warn("classpath 下未找到配置文件：{}", fileName);
			return new ArrayList<String>();
		}
		try (InputStream in = res.getInputStream()) {
			return IoUtil.readLines(in, "UTF-8", new ArrayList<String>());
		} catch (IOException e1) {
			log.error("读取配置文件 {} 失败", fileName, e1);
		}

		return new ArrayList<String>();
	}

	public static List<String> getUserGuide() {
		ClassPathResource res = new ClassPathResource("userGuide.txt");
		if (!res.exists()) {
			log.warn("classpath 下未找到 userGuide.txt");
			return new ArrayList<String>();
		}
		try (InputStream in = res.getInputStream()) {
			return IoUtil.readLines(in, "UTF-8", new ArrayList<String>());
		} catch (IOException e1) {
			log.error("读取 userGuide.txt 失败", e1);
		}

		return new ArrayList<String>();
	}

	/**
	 * 写入用户配置文件，普通路径为##后缀 ssh配置为##ssh后缀
	 * 
	 * @param lines
	 * @throws IOException
	 */
	public static void saveUserConfigToFile(String line) {
		if (null == line) {
			return;
		}
		String path = System.getProperty("user.dir") + File.separator + CurrentUser.idOrSystemUser() + ".properties";
		try {
			if (!FileUtil.exist(new File(path))) {
				// 不存在，创建
				File file = new File(path);
				file.createNewFile();
			}
		} catch (IOException e) {
			log.error("创建文件失败{}", e.getMessage());
			e.printStackTrace();
		}
		// 写入
		FileUtil.appendString(System.lineSeparator() + line, path, Charset.forName("UTF-8"));
	}

	/**
	 * 查询用户配置文件，普通路径为##后缀 ssh配置为##ssh后缀
	 * 
	 * @param lines
	 * @throws IOException
	 */
	public static List<String> getUserConfig() {
		List<String> configs = new ArrayList<String>();
		String path = System.getProperty("user.dir") + File.separator + CurrentUser.idOrSystemUser() + ".properties";
		File file = new File(path);
		if (!FileUtil.exist(file)) {
			return configs;
		} else {
			List<String> readLines = FileUtil.readLines(file, Charset.forName("UTF-8"));
			return readLines;
		}
	}

	/**
	 * 检查用户配置文件是否存在某个key 存在返回true
	 * 
	 * @param lines
	 * @throws IOException
	 */
	public static boolean checkUserConfig(String key) {
		String path = System.getProperty("user.dir") + File.separator + CurrentUser.idOrSystemUser() + ".properties";
		File file = new File(path);
		if (!FileUtil.exist(file)) {
			return false;
		}
		List<String> readLines = FileUtil.readLines(file, Charset.forName("UTF-8"));
		for (String l : readLines) {
			if (!l.trim().equals("") && l.contains("=")) {
				if (l.split("=", 2)[0].trim().equals(key)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * 读取服务器列表配置
	 * 
	 * @return
	 */
	public static List<String> getRemoteServerList() {
		List<String> configFileAsLine = getConfigFileAsLineByClasspathResource("remoteServerList.conf");

		ListIterator<String> listIterator = configFileAsLine.listIterator();
		while (listIterator.hasNext()) {
			String type = listIterator.next();
			if (type.startsWith("#") || type.trim().equals("") || !type.contains("=") || type.endsWith("=path")) {
				listIterator.remove();
			}
		}
		return configFileAsLine;
	}

	/**
	 * 查询配置服务器列表
	 */
	public static List<String> getServerList() {
		List<String> serverList = new ArrayList<String>();
		List<String> configFileAsLine = getConfigFileAsLineByClasspathResource("remoteServerList.conf");
		for (String e : configFileAsLine) {
			if (e.startsWith("#") || e.trim().equals("")) {
				continue;
			} else if (e.contains("=")) {
				serverList.add(e.trim());
			}
		}
		return serverList;
	}

	/**
	 * 查询文件后缀配置
	 */
	public static List<String> getFileSuffix() {
		List<String> suffixList = new ArrayList<String>();
		List<String> configFileAsLine = getConfigFileAsLineByClasspathResource("fileSuffix.conf");
		for (String e : configFileAsLine) {
			if (e.startsWith("#") || e.trim().equals("")) {
				continue;
			} else {
				suffixList.add(e.trim());
			}
		}
		return suffixList;
	}

	/**
	 * 原RequestDateConvert类中的方法
	 * 
	 * @param inDate
	 * @return
	 */
	public static Integer getMonth(String inDate) {
		String s = "0";
		if (inDate.equals("Jan")) {
			s = "01";
		} else if (inDate.equals("Feb")) {
			s = "02";
		} else if (inDate.equals("Mar")) {
			s = "03";
		} else if (inDate.equals("Apr")) {
			s = "04";
		} else if (inDate.equals("May")) {
			s = "05";
		} else if (inDate.equals("Jun")) {
			s = "06";
		} else if (inDate.equals("Jul")) {
			s = "07";
		} else if (inDate.equals("Aug")) {
			s = "08";
		} else if (inDate.equals("Sep")) {
			s = "09";
		} else if (inDate.equals("Oct")) {
			s = "10";
		} else if (inDate.equals("Nov")) {
			s = "11";
		} else if (inDate.equals("Dec")) {
			s = "12";
		}
		return Integer.parseInt(s);
	}

	private static ThreadLocal<DateFormat> threadLocalDateTime = new ThreadLocal<DateFormat>() {
		@Override
		protected DateFormat initialValue() {
			return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
		}
	};

	private static ThreadLocal<DateFormat> threadLocalDate = new ThreadLocal<DateFormat>() {
		@Override
		protected DateFormat initialValue() {
			return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
		}
	};

	public static Date parseDate(String dateStr) throws ParseException {
		if (StrUtil.isBlank(dateStr)) {
			return null;
		}
		return threadLocalDate.get().parse(dateStr);
	}

	public static String formatDate(Date date) {
		if (date != null) {
			return threadLocalDate.get().format(date);
		} else {
			return "";
		}
	}

	public static Date parseDateTime(String dateStr) throws ParseException {
		if (StrUtil.isBlank(dateStr)) {
			return null;
		}
		return threadLocalDateTime.get().parse(dateStr);
	}

	public static String formatDateTime(Date date) {
		if (date != null) {
			return threadLocalDateTime.get().format(date);
		} else {
			return "";
		}
	}

	public static String getSm3DigestStr(String str) {
		SM3 s = new SM3();
		String digestHex = s.digestHex(str);
		return digestHex.toUpperCase();
	}

	/**
	 * 用于将list进行分页
	 * 
	 * @param sourceList
	 * @param pageSize
	 * @return
	 */
	public static <E> List<List<E>> splitListToPage(List<E> sourceList, Integer pageSize) {
		List<List<E>> pageResult = new ArrayList<List<E>>();
		if (null == sourceList || sourceList.isEmpty()) {
			return pageResult;
		}
		List<E> sub = new ArrayList<E>();
		for (E e : sourceList) {
			if (sub.size() >= pageSize) {
				pageResult.add(sub);
				sub = new ArrayList<E>();
			}
			sub.add(e);
		}
		if (!sub.isEmpty()) {
			pageResult.add(sub);
		}
		return pageResult;
	}

	public static <T> List<List<T>> zip(List<T>... lists) {
		List<List<T>> zipped = new ArrayList<List<T>>();
		for (List<T> list : lists) {
			for (int i = 0, listSize = list.size(); i < listSize; i++) {
				List<T> list2;
				if (i >= zipped.size()) {
					zipped.add(list2 = new ArrayList<T>());
				}else {
					list2 = zipped.get(i);
				}list2.add(list.get(i));
			}
		}
		return zipped;
	}

	/**
	 * 确保运行目录下存在 server.sh 与 bin/server.sh。
	 * <p>
	 * - {@code <运行目录>/server.sh} 供页面直接下载；<br>
	 * - {@code <运行目录>/bin/server.sh} 在上传 jar 包到远程机器时会一并推送过去，
	 * 远程用 {@code sh server.sh start xxx.jar} 启动。<br>
	 * 之前这两处分别由 Application1.main 和 LoginView.enter 各写一份，
	 * 以 servlet 容器方式部署时 main 不执行，bin/server.sh 就会缺失。
	 */
	public static void ensureServerScript() {
		String base = System.getProperty("user.dir");
		File rootScript = new File(base, "server.sh");
		File binScript = new File(base + File.separator + "bin", "server.sh");
		// bin 下的脚本每次启动都刷新，保证和当前版本一致
		extractClasspathResource("server.sh", binScript, false);
		// 运行目录下的脚本已存在则不覆盖，避免冲掉用户改过的内容
		extractClasspathResource("server.sh", rootScript, true);

		if (!SystemUtil.getOsInfo().isWindows()) {
			executeLinuxCmd("chmod 777 '" + binScript.getAbsolutePath() + "'");
			if (rootScript.exists()) {
				executeLinuxCmd("chmod 777 '" + rootScript.getAbsolutePath() + "'");
			}
		}
	}

	/**
	 * 把 classpath 下的资源释放到指定位置
	 *
	 * @param resource     classpath 资源名
	 * @param target       目标文件
	 * @param skipIfExists true 表示目标已存在时跳过
	 */
	private static void extractClasspathResource(String resource, File target, boolean skipIfExists) {
		if (skipIfExists && target.exists()) {
			return;
		}
		ClassPathResource res = new ClassPathResource(resource);
		if (!res.exists()) {
			log.warn("classpath 下未找到 {}，跳过释放", resource);
			return;
		}
		File parent = target.getParentFile();
		if (parent != null && !parent.exists() && !parent.mkdirs()) {
			log.warn("创建目录失败：{}", parent.getAbsolutePath());
			return;
		}
		try (InputStream in = res.getInputStream()) {
			FileUtil.writeFromStream(in, target);
		} catch (IOException e) {
			log.error("释放 {} 到 {} 失败", resource, target.getAbsolutePath(), e);
		}
	}

	/**
	 * 执行一条命令
	 * <p>
	 * 原实现用 {@code Runtime.exec(String)}，命令按空白切分，路径里带空格会直接失败；
	 * 且读流在 waitFor 之前没读完时会阻塞。这里改为 shell 执行并先读完输出再等待。
	 * 
	 * @param cmd
	 * @return 命令输出，执行失败返回 null
	 */
	public static String executeLinuxCmd(String cmd) {
		log.info("got cmd job : {}", cmd);
		Process process = null;
		try {
			ProcessBuilder builder;
			if (SystemUtil.getOsInfo().isWindows()) {
				builder = new ProcessBuilder("cmd.exe", "/c", cmd);
			} else {
				builder = new ProcessBuilder("/bin/sh", "-c", cmd);
			}
			builder.redirectErrorStream(true);
			process = builder.start();
			StringBuffer out = new StringBuffer();
			try (BufferedReader bs = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
				String line;
				while ((line = bs.readLine()) != null) {
					out.append(line).append(System.lineSeparator());
				}
			}
			process.waitFor();
			log.info("job result [{}]", out.toString().trim());
			return out.toString();
		} catch (IOException e) {
			log.error(ExceptionUtil.stacktraceToString(e));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.error("执行命令被中断：{}", cmd, e);
		} finally {
			if (null != process) {
				process.destroy();
			}
		}
		return null;
	}

	/**
	 * 打开一个连接，
	 * 
	 * @param commands
	 * @return
	 */
	public static List<String> executeNewFlow(List<String> commands) {
		List<String> results = new ArrayList<String>();
		try {
			// 原实现用 Runtime.exec("/bin/bash")，没有合并 stderr，
			// 命令往 stderr 写满管道缓冲区（约 64KB）时会卡死在这里
			ProcessBuilder builder;
			if (SystemUtil.getOsInfo().isWindows()) {
				builder = new ProcessBuilder("cmd.exe");
			} else {
				builder = new ProcessBuilder("/bin/bash");
			}
			builder.redirectErrorStream(true);
			Process proc = builder.start();
			BufferedReader in = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
			PrintWriter out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8)), true);
			for (String line : commands) {
				out.println(line);
				log.info("发送命令：{}", line);
			}
			// 这个命令必须执行，否则in流不结束。
			out.println("exit");
			String rspLine;
			while ((rspLine = in.readLine()) != null) {
				results.add(rspLine);
			}
			proc.waitFor();
			in.close();
			out.close();
			proc.destroy();
		} catch (IOException e1) {
			log.error("执行命令失败", e1);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.error("执行命令被中断", e);
		}
		return results;
	}
	/**
	 * 执行python脚本
	 */
	public static void executePythonScript(String path) {
//       String path = "/data/logs/newblog/getbaidu.py";
        Process proc;
        try {
            proc = Runtime.getRuntime().exec("python3 " + path);// 执行py文件
            BufferedReader in = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String line;
            while ((line = in.readLine()) != null) {
                log.info(line);
            }
            in.close();
            proc.waitFor();
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
	}

	public static void main(String[] args) {
		String sm3DigestStr = getSm3DigestStr("sunlong567");
		System.out.println(sm3DigestStr);
		String getkeyByAlgorithm = EncryptionUtils.getkeyByAlgorithm("SM3", "admin");
		System.out.println(getkeyByAlgorithm);
	}

}
