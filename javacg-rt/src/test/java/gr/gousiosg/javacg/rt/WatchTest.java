package gr.gousiosg.javacg.rt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WatchTest {

    @Test
    void bucketsAreMonotonicAndBounded() {
        int last = -1;
        for (long us = 0; us < 10_000_000; us = us < 64 ? us + 1 : us + us / 7) {
            int b = Watch.bucket(us);
            assertTrue(b >= last, "bucket order at " + us);
            assertTrue(Watch.lowerBound(b) <= us, "lower bound of " + us);
            assertTrue(us < 16 || us - Watch.lowerBound(b) <= us / 8 + 1, "precision at " + us);
            last = b;
        }
        assertEquals(15, Watch.lowerBound(Watch.bucket(15)));
        assertEquals(16, Watch.lowerBound(Watch.bucket(16)));
    }

    @Test
    void percentilesFromProbes() throws Exception {
        Watch.State s = Watch.create("t.T.m", Watch.Capture.TYPES, 2, 0, false);
        for (int i = 0; i < 20; i++) {
            Watch.enter(new Object[] { "x", i }, s.id());
            Thread.sleep(i < 18 ? 0 : 30);
            Watch.exit(i, s.id());
        }
        String json = Watch.json(s);
        assertTrue(json.contains("\"calls\":20"), json);
        assertTrue(json.contains("\"args\":[\"java.lang.String\",\"java.lang.Integer\"]"), json);
        long p99 = Long.parseLong(json.replaceAll(".*\"p99_us\":(\\d+).*", "$1"));
        long p50 = Long.parseLong(json.replaceAll(".*\"p50_us\":(\\d+).*", "$1"));
        assertTrue(p99 >= 25_000 && p50 < 5_000, json);
        // the two slow calls are the slowest samples
        assertTrue(json.replaceAll(".*\"slowest\":\\[", "").startsWith("{\"thread\""), json);
    }
}
