package com.jsnx.edsp.provision;

import com.jsnx.edsp.model.ApiModels.DataSourceSummary;
import java.util.List;

public final class ProvisionModels {
    private ProvisionModels() {}
    public record Step(String status, String message) {}
    public record Generation(String status, String message, String outputDirectory,
                             String packageName, List<String> generatedFiles) {}
    public record Nacos(String status, String message, String dataId, String group, String namespace) {}
    public record Result(DataSourceSummary dataSource, Step routeSave, Generation generation,
                         Nacos nacos, Step routeRefresh) {}
    public record Settings(boolean enabled, String outputRoot, String group, String namespace) {}
}
