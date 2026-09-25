package sl.test1;

import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 虚拟线程示例
 */
public class VirtualThreadTest {


    public static void main(String[] args) throws Exception {
        int taskCount = 10_000;
        List<Future<String>> futures = new ArrayList<>();

        // 使用 newVirtualThreadPerTaskExecutor，为每个请求分配一个虚拟线程
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < taskCount; i++) {
                int taskId = i;
                futures.add(executor.submit(() -> {
                    // 模拟阻塞的 I/O 操作（如 HTTP 调用、DB 查询）
                    Thread.sleep(1000);
                    return "Task " + taskId + " done";
                }));
            }

            // 等待全部完成
            for (Future<String> f : futures) {
                f.get();
            }
        }
        System.out.println("所有 " + taskCount + " 个 I/O 任务完成");
    }

    /**
     * 示例 3：避免“线程固定”（使用 ReentrantLock）
     * 这是虚拟线程最关键的避坑点。错误地使用 synchronized 会导致虚拟线程在被阻塞时，死死占住底层的载体线程。
     *规则：在虚拟线程中，只要保护的代码块里包含可能阻塞的操作（如 I/O、sleep），就一律使用 ReentrantLock，而不是 synchronized。
     *
     * 如果你在 Spring Boot 3.2+ 环境下，还可以直接加一行配置 spring.threads.virtual.enabled=true，让整个 Web 请求链路自动跑在虚拟线程上，体验会更无感
     *
     */
    private static final Object syncLock = new Object();
    private static final ReentrantLock reentrantLock = new ReentrantLock();

    // ❌ 错误做法：在虚拟线程中执行阻塞操作时使用 synchronized
    // 这会导致“线程固定”，载体线程被阻塞，丧失并发能力
    public void badMethod() {
        synchronized (syncLock) {
            try {
                Thread.sleep(1000); // 阻塞 I/O
            } catch (InterruptedException e) { /* ... */ }
        }
    }

    // ✅ 正确做法：使用 ReentrantLock 保护可能阻塞的代码
    // JVM 可以识别并卸载持有 ReentrantLock 的虚拟线程
    public void goodMethod() {
        reentrantLock.lock();
        try {
            Thread.sleep(1000); // 阻塞 I/O
        } catch (InterruptedException e) { /* ... */ }
        finally {
            reentrantLock.unlock();
        }
    }
}
