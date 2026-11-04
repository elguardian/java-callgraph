package gr.gousiosg.javacg.commons.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TemplatesTest {

    @Test
    void templates() {
        assertEquals("request <*> took <*> from <*> id <*>",
                Templates.of("request 42 took 15ms from 10.0.0.1:8080 id 123e4567-e89b-12d3-a456-426614174000"));
        assertEquals("user <*> not found in <*>", Templates.of("user 'bob' not found in \"db1\"\nsecond line"));
        assertEquals("WFLYSRV0039: Creating", Templates.of("WFLYSRV0039: Creating"));
    }
}
