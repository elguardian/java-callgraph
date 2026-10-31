package gr.gousiosg.javacg.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AgentConfigTest {

    @Test
    void parsesTheOriginalArgumentFormat() {
        AgentConfig c = AgentConfig.parse("incl=org.jboss.as.jmx.*,org.wildfly.mypackage.*;excl=org.jboss.as.jmx.internal.*;");
        assertTrue(c.warnings.isEmpty(), c.warnings.toString());
        assertTrue(c.shouldInstrument("org.jboss.as.jmx.PluggableMBeanServer"));
        assertTrue(c.shouldInstrument("org.wildfly.mypackage.Foo$Bar"));
        assertFalse(c.shouldInstrument("org.jboss.as.jmx.internal.X"));
        assertFalse(c.shouldInstrument("org.jboss.as.controller.X"));
        assertTrue(c.recordAtStartup);
        assertEquals("none", c.args);
    }

    @Test
    void neverInstrumentsTheJdkOrItself() {
        AgentConfig c = AgentConfig.parse("incl=.*");
        assertFalse(c.shouldInstrument("java.lang.String"));
        assertFalse(c.shouldInstrument("jdk.internal.misc.Unsafe"));
        assertFalse(c.shouldInstrument("gr.gousiosg.javacg.rt.Probe"));
        assertTrue(c.shouldInstrument("com.example.App"));
    }

    @Test
    void invalidInputIsReportedNotFatal() {
        AgentConfig c = AgentConfig.parse("incl=com.ok.*,[broken;args=everything;bogus=1;noequals;jmx=soon");
        assertEquals(1, c.include.size());
        assertEquals(5, c.warnings.size(), c.warnings.toString());
        assertTrue(c.shouldInstrument("com.ok.A"));
        assertTrue(AgentConfig.parse(null).include.isEmpty());
    }

    @Test
    void expandsSystemPropertiesInLabelAndOut() {
        System.setProperty("javacg.test.node", "node-2");
        try {
            AgentConfig c = AgentConfig.parse("incl=a.*;label=%{javacg.test.node:client};out=/tmp/%{javacg.test.none:x}/r");
            assertEquals("node-2", c.label);
            assertEquals("/tmp/x/r", c.out);
            assertEquals("run-", AgentConfig.expand("run-%{javacg.test.none}"));
        } finally {
            System.clearProperty("javacg.test.node");
        }
    }

    @Test
    void parsesNewOptions() {
        AgentConfig c = AgentConfig.parse("incl=a.*;out=/tmp/x;label=t1;record=manual;args=ToString;locks=true;jmx=off;verbose=true");
        assertEquals("/tmp/x", c.out);
        assertEquals("t1", c.label);
        assertFalse(c.recordAtStartup);
        assertEquals("tostring", c.args);
        assertTrue(c.locks);
        assertEquals(AgentConfig.JMX_OFF, c.jmxDelay);
        assertTrue(c.verbose);
        assertEquals(1234, AgentConfig.parse("incl=a.*;jmx=1234").jmxDelay);
    }
}
