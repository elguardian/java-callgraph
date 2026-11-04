package gr.gousiosg.javacg.devtools.timeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rows within each lane: a span goes to the first row free at its start, so nested calls stack
 * by depth and overlapping blocks never share a row.
 */
final class Layout {

    final List<String> lanes;
    final Map<String, Integer> rowsPerLane = new LinkedHashMap<>();
    private final Map<Span, Integer> rows = new IdentityHashMap<>();
    /** Spans nested deeper than the row limit: not drawn. */
    int hidden;

    Layout(Timeline t, int maxRows) {
        lanes = t.lanes();
        Map<String, List<Span>> byLane = new LinkedHashMap<>();
        lanes.forEach(l -> byLane.put(l, new ArrayList<>()));
        t.spans().forEach(s -> byLane.get(s.lane()).add(s));
        byLane.forEach((lane, spans) -> {
            spans.sort(Comparator.comparingLong(Span::start).thenComparing(Comparator.comparingLong(Span::duration).reversed()));
            List<Long> ends = new ArrayList<>();
            for (Span s : spans) {
                int row = -1;
                for (int i = 0; i < ends.size(); i++) {
                    // a row is free once its last span ended; a nested call takes the next row down
                    if (ends.get(i) <= s.start()) {
                        row = i;
                        break;
                    }
                }
                if (row < 0) {
                    row = ends.size();
                    ends.add(0L);
                }
                ends.set(row, s.end());
                if (row >= maxRows) {
                    hidden++;
                }
                rows.put(s, row >= maxRows ? -1 : row);
            }
            rowsPerLane.put(lane, Math.max(1, Math.min(ends.size(), maxRows)));
        });
    }

    /** The row of a span, or -1 when it is nested deeper than the row limit. */
    int row(Span s) {
        return rows.getOrDefault(s, 0);
    }

    /** A note for diagrams that left spans out, or null. */
    String hiddenNote() {
        return hidden == 0 ? null : hidden + " block(s) nested deeper than the row limit are not drawn (raise 'rows')";
    }

    int rows(String lane) {
        return rowsPerLane.getOrDefault(lane, 1);
    }
}
