package gr.gousiosg.javacg.devtools.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import gr.gousiosg.javacg.devtools.table.Table.Format;

class TableTest {

    private static Table sample() {
        return Table.of("test", "time", "status").row("a.B#ok", "1.5 s", "pass").row("a.B#bad|pipe", "12 s", "fail");
    }

    @Test
    void ascii() {
        assertEquals("""
                +--------------+-------+--------+
                | test         | time  | status |
                +--------------+-------+--------+
                | a.B#ok       | 1.5 s | pass   |
                | a.B#bad|pipe |  12 s | fail   |
                +--------------+-------+--------+
                """, sample().render(Format.ASCII));
    }

    @Test
    void markdownEscapesPipesAndAlignsNumbers() {
        assertEquals("""
                | test | time | status |
                | --- | ---: | --- |
                | a.B#ok | 1.5 s | pass |
                | a.B#bad\\|pipe | 12 s | fail |
                """, sample().render(Format.MARKDOWN));
    }

    @Test
    void jira() {
        assertEquals("""
                *Results*
                ||test||time||status||
                |a.B#ok|1.5 s|pass|
                |a.B#bad\\|pipe|12 s|fail|
                | |x|\\{code\\}|
                """, sample().title("Results").row("", "x", "{code}").render(Format.JIRA));
    }

    @Test
    void slackIsACodeBlock() {
        String s = Table.of("a").row(1).title("T").render(Format.SLACK);
        assertEquals("*T*\n```\n+---+\n| a |\n+---+\n| 1 |\n+---+\n```\n", s);
    }

    @Test
    void csvRoundTrip() {
        Table t = Table.of("k", "v").row("x,y", "say \"hi\"").row("n", null);
        String csv = t.render(Format.CSV);
        assertEquals("k,v\n\"x,y\",\"say \"\"hi\"\"\"\nn,\n", csv);
        assertEquals(csv, Table.fromCsv(csv, ',').render(Format.CSV));
        assertEquals("k\tv\nx,y\tsay \"hi\"\nn\t\n", t.render(Format.TSV));
    }

    @Test
    void htmlEscapes() {
        assertEquals("""
                <table>
                  <thead><tr><th>a</th><th>n</th></tr></thead>
                  <tbody>
                    <tr><td>&lt;b&gt;</td><td style="text-align:right">3</td></tr>
                  </tbody>
                </table>
                """, Table.of("a", "n").row("<b>", 3).render(Format.HTML));
    }

    @Test
    void mapsAndWidthLimit() {
        Table t = Table.fromMaps(List.of(Map.of("name", "averyveryverylongname"), Map.of("name", "x", "n", 2.0)),
                null).maxWidth(6);
        assertEquals(List.of("name", "n"), t.columns());
        assertEquals("| avery… |   |", t.render(Format.ASCII).lines().toList().get(3));
        assertEquals("| x      | 2 |", t.render(Format.ASCII).lines().toList().get(4));
    }

    @Test
    void rejectsTooManyCells() {
        assertThrows(IllegalArgumentException.class, () -> Table.of("a").row("1", "2"));
    }
}
