package com.sl.service;
//package com.sl.service;
//
//import java.io.File;
//import java.lang.management.ManagementFactory;
//import java.text.DecimalFormat;
//import java.text.SimpleDateFormat;
//import java.util.Date;
//import java.util.concurrent.TimeUnit;
//
//import oshi.SystemInfo;
//import oshi.hardware.CentralProcessor;
//
///**
// * 系统监控
// */
//public class SystemMonitor {
//
//	public void printOsInfo() {
//		try {
//
//			SystemInfo systemInfo = new SystemInfo();
//
//			// 获得线程总数
//			ThreadGroup parentThread;
//			for (parentThread = Thread.currentThread().getThreadGroup(); parentThread.getParent() != null; parentThread = parentThread.getParent()) {
//
//			}
//
//			int totalThread = parentThread.activeCount();
//
//			// 磁盘使用情况
//			File[] files = File.listRoots();
//			for (File file : files) {
//				String total = new DecimalFormat("#.#").format(file.getTotalSpace() * 1.0 / 1024 / 1024 / 1024) + "G";
//				String free = new DecimalFormat("#.#").format(file.getFreeSpace() * 1.0 / 1024 / 1024 / 1024) + "G";
//				String un = new DecimalFormat("#.#").format(file.getUsableSpace() * 1.0 / 1024 / 1024 / 1024) + "G";
//				String path = file.getPath();
//				System.err.println(path + "总:" + total + ",可用空间:" + un + ",空闲空间:" + free);
//				System.err.println("=============================================");
//			}
//
//			System.err.println(
//					"程序启动时间:" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(ManagementFactory.getRuntimeMXBean().getStartTime())));
//			System.err.println("pid:" + System.getProperty("PID"));
//			System.err.println("cpu核数:" + Runtime.getRuntime().availableProcessors());
//			printlnCpuInfo(systemInfo);
//			System.err.println("JAVA_HOME:" + System.getProperty("java.home"));
//			System.err.println("JAVA_VERSION:" + System.getProperty("java.version"));
//			System.err.println("USER_HOME:" + System.getProperty("user.home"));
//			System.err.println("USER_NAME:" + System.getProperty("user.name"));
//			System.err.println(
//					"总的物理内存:" + new DecimalFormat("#.##").format(systemInfo.getHardware().getMemory().getTotal() * 1.0 / 1024 / 1024 / 1024) + "M");
//			System.err.println("剩余的物理内存:"
//					+ new DecimalFormat("#.##").format(systemInfo.getHardware().getMemory().getAvailable() * 1.0 / 1024 / 1024 / 1024) + "M");
//			System.err.println("已使用的物理内存:" + new DecimalFormat("#.##")
//					.format((systemInfo.getHardware().getMemory().getTotal() - systemInfo.getHardware().getMemory().getAvailable()) * 1.0 / 1024
//							/ 1024 / 1024)
//					+ "M");
//			System.err.println("总线程数:" + totalThread);
//			System.err.println("===========================");
//		} catch (Exception e) {
//			e.printStackTrace();
//		}
//	}
//
//	/**
//	 * 打印 CPU 信息
//	 *
//	 * @param systemInfo
//	 */
//	private void printlnCpuInfo(SystemInfo systemInfo) throws InterruptedException {
//		CentralProcessor processor = systemInfo.getHardware().getProcessor();
//		long[] prevTicks = processor.getSystemCpuLoadTicks();
//		// 睡眠1s
//		TimeUnit.SECONDS.sleep(1);
//		long[] ticks = processor.getSystemCpuLoadTicks();
//		long nice = ticks[CentralProcessor.TickType.NICE.getIndex()] - prevTicks[CentralProcessor.TickType.NICE.getIndex()];
//		long irq = ticks[CentralProcessor.TickType.IRQ.getIndex()] - prevTicks[CentralProcessor.TickType.IRQ.getIndex()];
//		long softirq = ticks[CentralProcessor.TickType.SOFTIRQ.getIndex()] - prevTicks[CentralProcessor.TickType.SOFTIRQ.getIndex()];
//		long steal = ticks[CentralProcessor.TickType.STEAL.getIndex()] - prevTicks[CentralProcessor.TickType.STEAL.getIndex()];
//		long cSys = ticks[CentralProcessor.TickType.SYSTEM.getIndex()] - prevTicks[CentralProcessor.TickType.SYSTEM.getIndex()];
//		long user = ticks[CentralProcessor.TickType.USER.getIndex()] - prevTicks[CentralProcessor.TickType.USER.getIndex()];
//		long iowait = ticks[CentralProcessor.TickType.IOWAIT.getIndex()] - prevTicks[CentralProcessor.TickType.IOWAIT.getIndex()];
//		long idle = ticks[CentralProcessor.TickType.IDLE.getIndex()] - prevTicks[CentralProcessor.TickType.IDLE.getIndex()];
//		long totalCpu = user + nice + cSys + idle + iowait + irq + softirq + steal;
//		System.err.println("cpu核数:" + processor.getLogicalProcessorCount());
//		System.err.println("cpu系统使用率:" + new DecimalFormat("#.##%").format(cSys * 1.0 / totalCpu));
//		System.err.println("cpu用户使用率:" + new DecimalFormat("#.##%").format(user * 1.0 / totalCpu));
//		System.err.println("cpu当前等待率:" + new DecimalFormat("#.##%").format(iowait * 1.0 / totalCpu));
//		System.err.println("cpu当前空闲率:" + new DecimalFormat("#.##%").format(idle * 1.0 / totalCpu));
//		System.err.format("CPU load: %.1f%% (counting ticks)%n", processor.getSystemCpuLoadBetweenTicks() * 100);
//		System.err.format("CPU load: %.1f%% (OS MXBean)%n", processor.getSystemCpuLoad() * 100);
//	}
//}