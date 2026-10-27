package com.jsnx.edsp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsnx.edsp.model.ApiModels.ResponseCodeMapping;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseCodeMappingCompatibilityTest {
    @Test
    void oldPublishedSnapshotIgnoresRemovedRetryFlag() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ResponseCodeMapping code = mapper.readValue("""
                {"id":1,"vendorCode":"0000","standardCode":"EXT-SUCCESS",
                 "resultStatus":"HIT","retryable":true,"billable":true}
                """, ResponseCodeMapping.class);

        assertThat(code.billable()).isTrue();
        assertThat(mapper.writeValueAsString(code)).doesNotContain("retryable");
    }
}
