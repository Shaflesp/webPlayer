package MPD.service;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests SyncJob's wait/notify signalling in isolation — no Spring context,
 * no yt-dlp, no MPD. This class lives in the same package as SyncService
 * specifically so it can use SyncJob's package-private constructor directly.
 */
class SyncServiceJobTest {

    @Test
    void awaitChangeBeyond_returnsImmediatelyIfLineAlreadyAvailable() throws InterruptedException {
        SyncService.SyncJob job = newJob();
        job.log("first line");

        long start = System.nanoTime();
        job.awaitChangeBeyond(0); // idx=0, but a line is already there
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 50, "should not have blocked at all, took " + elapsedMs + "ms");
    }

    @Test
    void awaitChangeBeyond_returnsImmediatelyIfAlreadyDone() throws InterruptedException {
        SyncService.SyncJob job = newJob();
        job.finish();

        long start = System.nanoTime();
        job.awaitChangeBeyond(0);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 50, "should not have blocked at all, took " + elapsedMs + "ms");
    }

    @Test
    void awaitChangeBeyond_blocksUntilLogIsCalled() throws InterruptedException {
        SyncService.SyncJob job = newJob();
        CountDownLatch waiterStarted = new CountDownLatch(1);
        AtomicLong elapsedMs = new AtomicLong(-1);

        Thread waiter = new Thread(() -> {
            try {
                waiterStarted.countDown();
                long start = System.nanoTime();
                job.awaitChangeBeyond(0); // nothing logged yet — must actually block
                elapsedMs.set((System.nanoTime() - start) / 1_000_000);
            } catch (InterruptedException ignored) {}
        });
        waiter.start();
        waiterStarted.await();
        Thread.sleep(150); // give the waiter time to actually enter wait()

        job.log("wakes the waiter");
        waiter.join(2000);

        assertFalse(waiter.isAlive(), "waiter should have returned after log()");
        assertTrue(elapsedMs.get() >= 100,
            "should have genuinely blocked for roughly the sleep duration, was " + elapsedMs.get() + "ms");
    }

    @Test
    void awaitChangeBeyond_blocksUntilFinishIsCalled() throws InterruptedException {
        SyncService.SyncJob job = newJob();
        Thread waiter = new Thread(() -> {
            try { job.awaitChangeBeyond(0); } catch (InterruptedException ignored) {}
        });
        waiter.start();
        Thread.sleep(100);
        assertTrue(waiter.isAlive(), "should still be blocked — nothing logged, not finished yet");

        job.finish();
        waiter.join(2000);
        assertFalse(waiter.isAlive(), "waiter should have returned after finish()");
    }

    /**
     * Regression test for the exact race awaitChangeBeyond is designed to
     * avoid: if the "is there a new line?" check and the wait() call weren't
     * inside the SAME synchronized block as the producer's notify, a notify
     * landing in the gap between them would be silently lost, and the
     * consumer would hang forever waiting for a wakeup that already happened.
     * Hammering log() rapidly from one thread while a consumer repeatedly
     * re-enters awaitChangeBeyond from another is the most direct way to
     * surface that race if it existed.
     */
    @Test
    void log_neverLosesAWakeupUnderConcurrentRacing() throws InterruptedException {
        SyncService.SyncJob job = newJob();
        int totalLines = 200;
        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch consumerDone = new CountDownLatch(1);

        Thread consumer = new Thread(() -> {
            int idx = 0;
            try {
                while (true) {
                    while (idx < job.lineCount()) received.add(job.lineAt(idx++));
                    if (job.done && idx >= job.lineCount()) break;
                    job.awaitChangeBeyond(idx);
                }
            } catch (InterruptedException ignored) {}
            consumerDone.countDown();
        });
        consumer.start();

        for (int i = 0; i < totalLines; i++) {
            job.log("line " + i);
        }
        job.finish();

        assertTrue(consumerDone.await(5, TimeUnit.SECONDS),
            "consumer never finished — likely a missed wakeup (lost notify)");
        assertEquals(totalLines, received.size());
        for (int i = 0; i < totalLines; i++) {
            assertEquals("line " + i, received.get(i));
        }
    }

    private SyncService.SyncJob newJob() {
        return new SyncService.SyncJob("test-job", "https://example.com/playlist?list=test");
    }
}
