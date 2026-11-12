package com.cb.agent.capability.extract.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/**
 * 并发执行一批模型调用。对应 Python 版 {@code agent.py} 的 {@code _map}。
 *
 * <p>每个调用跑在一个虚拟线程上：调模型的时间都花在等网络上，虚拟线程在等待时不占系统线程。
 * 同时进行的调用数由闸门限制，免得把模型服务压垮。
 *
 * <p>几份文档共用同一个 {@code BatchRunner} 时，这个上限是它们合在一起的上限，
 * 所以批量处理时并发不会随着文档数成倍增加。
 */
public final class BatchRunner {

    private final int permits;
    private final Semaphore gate;

    /**
     * @param permits 同时最多有几个调用在跑，至少是 1
     */
    public BatchRunner(int permits) {
        this.permits = Math.max(1, permits);
        this.gate = new Semaphore(this.permits);
    }

    /**
     * 对每一项执行 {@code job}，返回的结果和输入的顺序相同。
     *
     * <p>只允许一个调用同时进行，或者只有一项时，直接在当前线程里依次执行。
     * 某一项抛出了异常时，等其余的都结束之后，把那个异常原样抛出来。
     */
    public <T, R> List<R> map(Function<T, R> job, List<T> items) {
        if (permits <= 1 || items.size() <= 1) {
            List<R> out = new ArrayList<>();
            for (T item : items) {
                out.add(job.apply(item));
            }
            return out;
        }
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<R>> futures = new ArrayList<>();
            for (T item : items) {
                futures.add(pool.submit(() -> {
                    gate.acquire();
                    try {
                        return job.apply(item);
                    } finally {
                        gate.release();
                    }
                }));
            }
            List<R> out = new ArrayList<>();
            for (Future<R> future : futures) {
                out.add(future.get()); // 按提交的顺序取，结果的顺序和依次执行时相同
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("抽取被中断了", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime; // 模型调用失败、输出被截断，都原样交给调用方处理
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }
}
