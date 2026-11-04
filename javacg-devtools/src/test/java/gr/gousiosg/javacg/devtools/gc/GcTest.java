package gr.gousiosg.javacg.devtools.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import gr.gousiosg.javacg.devtools.heap.HeapHistogram;

class GcTest {

    static String text(String name) throws Exception {
        return Files.readString(Path.of(GcTest.class.getResource("/gc/" + name).toURI()));
    }

    @Test
    void g1UnifiedLogOfALeak() throws Exception {
        GcLog log = GcLogParser.parse("g1.log", text("g1.log"));
        assertEquals("unified", log.format());
        assertEquals("G1", log.collector());
        GcEvent first = log.events().get(0);
        assertEquals(0, first.id());
        assertEquals("Pause Young", first.kind());
        assertEquals("Normal, G1 Evacuation Pause", first.detail());
        assertEquals(48 * 1024, first.before());
        assertEquals(1024, first.after());
        assertEquals(128 * 1024, first.capacity());
        assertEquals(0.514, first.millis(), 1e-9);
        assertEquals(0.028, first.uptime(), 1e-9);
        assertTrue(first.wall() > 0);
        GcAnalysis a = new GcAnalysis(log, -1, -1);
        assertEquals("all pauses", a.pauseStats().get(0).kind());
        assertTrue(a.pauseStats().stream().anyMatch(s -> s.kind().equals("Pause Full") && s.count() == 11), a.pauseStats().toString());
        assertTrue(a.concurrentStats().stream().anyMatch(s -> s.kind().equals("Concurrent Mark Cycle")));
        GcAnalysis.Trend t = a.heapTrend();
        assertTrue(t.suspicious(), t.toString());
        assertTrue(t.kbPerMinute() > 0);
        assertTrue(a.allocationRate() > 0);
        assertTrue(a.fullGcs().stream().anyMatch(e -> e.detail().equals("System.gc()")));
        assertTrue(a.fullGcs().stream().anyMatch(e -> e.detail().equals("G1 Compaction Pause")));
    }

    @Test
    void parallelAndZgc() throws Exception {
        GcLog p = GcLogParser.parse("parallel.log", text("parallel.log"));
        assertEquals("Parallel", p.collector());
        assertEquals(Long.MIN_VALUE, p.events().get(0).wall());
        assertTrue(p.events().stream().anyMatch(e -> e.full() && e.detail().equals("Allocation Failure")));
        GcLog z = GcLogParser.parse("zgc.log", text("zgc.log"));
        assertEquals("The Z Garbage Collector", z.collector());
        GcEvent major = z.events().get(0);
        assertEquals("Major Collection", major.kind());
        assertEquals(false, major.pause());
        assertEquals(26 * 1024, major.before());
        assertEquals(2.0, major.millis(), 1e-9);
        assertTrue(new GcAnalysis(z, -1, -1).pauseStats().isEmpty());
    }

    @Test
    void jdk8Log() throws Exception {
        GcLog log = GcLogParser.parse("jdk8.log", text("jdk8.log"));
        assertEquals("jdk8", log.format());
        assertEquals("Parallel", log.collector());
        List<GcEvent> e = log.events();
        assertEquals(5, e.size());
        assertEquals(33280, e.get(0).before());
        assertEquals(6120, e.get(0).after());
        assertEquals(6.123, e.get(0).millis(), 1e-9);
        assertEquals("Pause Full", e.get(2).kind());
        assertEquals(85104, e.get(2).before());
        assertEquals(60000, e.get(2).after());
        assertEquals("Ergonomics", e.get(2).detail());
        GcEvent g1 = e.get(4);
        assertEquals(24 * 1024, g1.before());
        assertEquals(4096, g1.after());
        GcAnalysis a = new GcAnalysis(log, -1, -1);
        assertEquals(2, a.fullGcs().size());
        assertEquals(10000 * 60 / 4.0, a.heapTrend().kbPerMinute(), 1e-6);
        assertEquals(300.123, a.longest(1).get(0).millis(), 1e-6);
    }

    @Test
    void histogramGrowth() throws Exception {
        List<HeapHistogram.Snapshot> s = HeapHistogram.parse("h", text("histograms.txt"), 0);
        assertEquals(3, s.size());
        HeapHistogram.Entry top = s.get(0).entries().get(0);
        assertEquals("[B", top.className());
        assertEquals("java.base@25.0.4.1", top.module());
        List<HeapHistogram.Growth> g = HeapHistogram.diff(s);
        assertEquals("[B", g.get(0).className());
        assertTrue(g.get(0).growingEverywhere());
        assertTrue(g.get(0).bytesDelta() > 1_000_000_000L);
    }
}
