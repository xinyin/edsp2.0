package com.jsnx.edsp.sdk;

import com.jsnx.edsp.contracts.CapabilityContracts.CompleteRequest;
import com.jsnx.edsp.contracts.CapabilityContracts.CompleteResponse;
import com.jsnx.edsp.contracts.CapabilityContracts.PrepareRequest;
import com.jsnx.edsp.contracts.CapabilityContracts.PrepareResponse;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** Small stable client; capability implementations remain outside data applications. */
public final class CapabilityClient {
    private final RestClient client;

    public CapabilityClient(String baseUrl) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    public PrepareResponse prepare(String interfaceCode, Map<String, Object> payload) {
        return client.post().uri("/internal/capability/prepare")
                .body(new PrepareRequest(interfaceCode, payload)).retrieve().body(PrepareResponse.class);
    }

    public CompleteResponse complete(String interfaceCode, Map<String, Object> upstreamResponse) {
        return client.post().uri("/internal/capability/complete")
                .body(new CompleteRequest(interfaceCode, upstreamResponse)).retrieve().body(CompleteResponse.class);
    }

    public void refresh(String interfaceCode) {
        client.post().uri(uriBuilder -> uriBuilder.path("/internal/capability/refresh")
                        .queryParam("interfaceCode", interfaceCode).build())
                .retrieve().toBodilessEntity();
    }
}
