package net.d33.numpwd;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/**
 * A key name such as "rounds=999999999$x" makes the derivation run for many minutes, as it
 * does in openssl. Interrupting the computing thread must stop it promptly, otherwise the
 * app's worker stays busy and every later derivation waits behind it.
 */
public class CancellationTest {
    private static final long START_MS = 300;
    private static final long STOP_TIMEOUT_MS = 2_000;

    private static void assertStopsOnInterrupt(String keyName) throws Exception {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                NumericPassword.derive(keyName, "abc");
            } catch (Throwable e) {
                thrown.set(e);
            }
        });
        // Without cancellation the thread would outlive the test; it must not keep the JVM up.
        t.setDaemon(true);
        t.start();
        Thread.sleep(START_MS);
        assertTrue("derivation for " + keyName + " finished too early to test", t.isAlive());

        t.interrupt();
        t.join(STOP_TIMEOUT_MS);
        assertFalse("derivation for " + keyName + " ignored the interrupt", t.isAlive());
        assertTrue("expected InterruptedException, got " + thrown.get(),
                thrown.get() instanceof InterruptedException);
    }

    @Test
    public void maximumRoundsStopsOnInterrupt() throws Exception {
        assertStopsOnInterrupt("rounds=999999999$x");
    }

    @Test
    public void negativeRoundsClampedToMaximumStopsOnInterrupt() throws Exception {
        assertStopsOnInterrupt("rounds=-1$x");
    }
}
