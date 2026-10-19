package com.jsnx.edsp.esb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class IbmMqEsbConfigurationTest {
    @Test
    void configuresTwoIndependentActiveListeners() throws Exception {
        var configuration = new IbmMqEsbConfiguration(environment());
        var topology = configuration.esbMqTopology();
        var adapter = mock(EsbMessageAdapter.class);
        var protocol = new EsbXmlProtocol("QM-REPLY", "150", "EDSP");
        var json = new ObjectMapper();
        var g1 = configuration.esbMqG1Listener(topology, adapter, protocol, json, "{\"CALLER\":\"secret\"}");
        var g2 = configuration.esbMqG2Listener(topology, adapter, protocol, json, "{\"CALLER\":\"secret\"}");

        assertEquals("RESPONSE.IN", g1.getDestinationName());
        assertEquals("RESPONSE.IN", g2.getDestinationName());
        assertNotSame(g1.getConnectionFactory(), g2.getConnectionFactory());
        assertNotSame(g1.getMessageListener(), g2.getMessageListener());
        assertTrue(g1.isSessionTransacted());
        assertTrue(g2.isSessionTransacted());
        assertEquals("REQUEST.OUT", topology.g1().responseQueue());
        assertEquals("REQUEST.OUT", topology.g2().responseQueue());
    }

    @Test
    void requiresBothDistinctQueueManagers() {
        var missingG2 = environment();
        missingG2.setProperty("edsp.esb.ibm-mq.g2.host", "");
        assertThrows(IllegalArgumentException.class, () -> new IbmMqEsbConfiguration(missingG2).esbMqTopology());

        var sameQueueManager = environment();
        sameQueueManager.setProperty("edsp.esb.ibm-mq.g2.queue-manager", "QM-G1");
        assertThrows(IllegalArgumentException.class, () -> new IbmMqEsbConfiguration(sameQueueManager).esbMqTopology());
    }

    private MockEnvironment environment() {
        var env = new MockEnvironment();
        for (String name : new String[] {"g1", "g2"}) {
            String prefix = "edsp.esb.ibm-mq." + name + ".";
            env.setProperty(prefix + "host", "mq.example.invalid");
            env.setProperty(prefix + "port", "1414");
            env.setProperty(prefix + "queue-manager", "QM-" + name.toUpperCase());
            env.setProperty(prefix + "channel", "APP.CHANNEL");
            env.setProperty(prefix + "request-queue", "RESPONSE.IN");
            env.setProperty(prefix + "response-queue", "REQUEST.OUT");
        }
        return env;
    }
}
