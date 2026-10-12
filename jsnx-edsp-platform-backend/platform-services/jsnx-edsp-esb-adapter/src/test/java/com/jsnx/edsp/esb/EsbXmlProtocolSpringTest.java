package com.jsnx.edsp.esb;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class EsbXmlProtocolSpringTest {
    @Test
    void componentCanBeCreatedBySpring() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(EsbXmlProtocol.class);
            context.refresh();
            assertNotNull(context.getBean(EsbXmlProtocol.class));
        }
    }
}
