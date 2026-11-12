package com.cb.agent.capability.extract.engine;

import com.cb.agent.core.llm.OutputTruncatedException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发执行模型调用：结果的顺序、并发数的上限、出错时的表现。
 */
class BatchRunnerTest {

    @Test
    void 结果的顺序和输入的顺序相同() {
        // 排在前面的故意慢一些：先跑完的是后面的，但结果不能因此乱序
        List<Integer> out = new BatchRunner(4).map(n -> {
            pause((5 - n) * 20L);
            return n * 10;
        }, List.of(1, 2, 3, 4));

        assertEquals(List.of(10, 20, 30, 40), out);
    }

    @Test
    void 同时进行的调用数不超过上限() {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();

        new BatchRunner(2).map(n -> {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            pause(30);
            running.decrementAndGet();
            return n;
        }, List.of(1, 2, 3, 4, 5, 6));

        assertEquals(2, peak.get(), "上限是 2，而且确实并发了");
    }

    @Test
    void 上限是一的时候在当前线程里依次执行() {
        Thread caller = Thread.currentThread();

        List<Thread> threads = new BatchRunner(1).map(n -> Thread.currentThread(), List.of(1, 2, 3));

        for (Thread thread : threads) {
            assertSame(caller, thread);
        }
    }

    @Test
    void 某一项出错时原样抛出那个异常() {
        AtomicInteger finished = new AtomicInteger();
        BatchRunner runner = new BatchRunner(4);

        // 引擎靠异常的类型来区分"输出被截断"和"调用失败"，所以不能被包成别的异常
        assertThrows(OutputTruncatedException.class, () -> runner.map(n -> {
            if (n == 2) {
                throw new OutputTruncatedException("截断");
            }
            pause(30);
            finished.incrementAndGet();
            return n;
        }, List.of(1, 2, 3)));
        assertEquals(2, finished.get(), "其余的调用会跑完，不会被半路丢下");
        assertTrue(runner.map(n -> n, List.of(1, 2)).equals(List.of(1, 2)), "出过错之后还能接着用");
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
