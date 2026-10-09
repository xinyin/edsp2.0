package com.jsnx.edsp.data.enterprise;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsnx.edsp.sdk.DemoEventClient;
import com.jsnx.edsp.sdk.LocalPlanRuntime;
import com.jsnx.edsp.sdk.StandardDataApplicationExecutor;
import com.jsnx.edsp.sdk.UpstreamClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;

@Import(com.jsnx.edsp.sdk.PublishedDataApplicationController.class)
@SpringBootApplication
public class EnterpriseDataApplication {
    public static void main(String[] args) {
        SpringApplication.run(EnterpriseDataApplication.class, args);
    }

    @Bean
    LocalPlanRuntime localPlanRuntime(ObjectMapper mapper,
                                      @Value("${edsp.admin-url:http://127.0.0.1:8081}") String adminUrl,
                                      @Value("${spring.application.name}") String applicationName,
                                      Environment environment) {
        return new LocalPlanRuntime(mapper, adminUrl, applicationName, environment::getProperty);
    }

    @Bean
    UpstreamClient upstreamClient(ObjectMapper mapper) { return new UpstreamClient(mapper); }

    @Bean(destroyMethod = "close")
    DemoEventClient demoEventClient(
            @Value("${edsp.observability-url:http://127.0.0.1:8083}") String observabilityUrl) {
        return new DemoEventClient(observabilityUrl);
    }

    @Bean
    StandardDataApplicationExecutor executor(LocalPlanRuntime plans, UpstreamClient upstream,
                                             DemoEventClient events) {
        return new StandardDataApplicationExecutor(plans, upstream, events);
    }
}
