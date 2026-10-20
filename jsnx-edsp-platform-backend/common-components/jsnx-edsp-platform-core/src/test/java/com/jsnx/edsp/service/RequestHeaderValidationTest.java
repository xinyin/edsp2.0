package com.jsnx.edsp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

class RequestHeaderValidationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final InterfaceService service = new InterfaceService(
            mock(JdbcTemplate.class, withSettings().mockMaker("mock-maker-subclass")),
            new JsonSupport(mapper), mapper, new PublishedPlanRegistry(), new MockEnvironment());

    @Test
    void acceptsPlainApiKeys() {
        Map<String, String> headers = ReflectionTestUtils.invokeMethod(service, "validRequestHeaders",
                Map.of("x-api-key", "plain-test-key"));
        assertThat(headers).containsEntry("x-api-key", "plain-test-key");
    }

    @Test
    void stillRejectsControlledHeadersAndLineBreaks() {
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "validRequestHeaders",
                Map.of("Content-Type", "text/plain"))).hasMessageContaining("不允许自定义受控请求头");
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "validRequestHeaders",
                Map.of("x-api-key", "one\r\ntwo"))).hasMessageContaining("请求头名称或内容不合法");
    }
}
