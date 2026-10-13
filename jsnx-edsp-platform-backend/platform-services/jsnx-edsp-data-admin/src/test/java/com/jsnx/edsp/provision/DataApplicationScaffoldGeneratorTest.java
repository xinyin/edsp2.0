package com.jsnx.edsp.provision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsnx.edsp.model.ApiModels.DataSourceSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class DataApplicationScaffoldGeneratorTest {
    @TempDir Path root;
    private DataApplicationScaffoldGenerator generator() {
        return new DataApplicationScaffoldGenerator(new MockEnvironment()
                .withProperty("edsp.code-generation.output-root", root.toString()), new ObjectMapper());
    }
    static DataSourceSummary source(String app) {
        return new DataSourceSummary(7, 3, "资源方", "CREDIT_V2", "征信\"2.0\"\n业务", app,
                "http://127.0.0.1:8198", "HTTP_JSON", "https://vendor.example", 3000, "TEST", "TRIAL", 1, 1, 0);
    }
    @Test void generatesCompleteJdk17ProjectAndEscapesYaml() throws Exception {
        var result = generator().generate(source("jsnx-edsp-data-credit-v2"));
        assertThat(result.status()).isEqualTo("SUCCESS");
        Path project = Path.of(result.outputDirectory());
        assertThat(result.generatedFiles()).hasSize(8);
        assertThat(Files.readString(project.resolve("pom.xml"))).contains("<artifactId>jsnx-edsp-data-credit-v2</artifactId>");
        assertThat(Files.readString(project.resolve("src/main/java/com/jsnx/edsp/data/creditv2/CreditV2DataApplication.java")))
                .contains("package com.jsnx.edsp.data.creditv2", "class CreditV2DataApplication");
        assertThat(Files.readString(project.resolve("src/main/resources/application.yml")))
                .contains("nacos:jsnx-edsp-data-credit-v2.yaml").doesNotContain("password: nacos");
        assertThat(Files.readString(project.resolve("config/nacos/jsnx-edsp-data-credit-v2.yaml")))
                .contains("data-source-code: \"CREDIT_V2\"", "\\n业务", "${SERVER_PORT:8199}");
        // Compile generated sources, not just substring assertions.
        Path classes = Files.createDirectories(root.resolve("compiled"));
        java.util.List<String> args = new java.util.ArrayList<>(java.util.List.of("--release", "17", "-classpath",
                System.getProperty("java.class.path"), "-d", classes.toString()));
        try (var paths = Files.walk(project.resolve("src/main/java"))) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> args.add(p.toString()));
        }
        assertThat(javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new))).isZero();
    }
    @Test void reusesWithoutOverwritingDeveloperEdits() throws Exception {
        var first = generator().generate(source("jsnx-edsp-data-credit-v2"));
        Path pom = Path.of(first.outputDirectory()).resolve("pom.xml");
        Files.writeString(pom, "developer changes");
        assertThat(generator().generate(source("jsnx-edsp-data-credit-v2")).status()).isEqualTo("REUSED");
        assertThat(Files.readString(pom)).isEqualTo("developer changes");
    }
    @Test void rejectsTraversalForeignDirectoriesAndSymlinks() throws Exception {
        assertThatThrownBy(() -> generator().generate(source("../../escape"))).isInstanceOf(IllegalArgumentException.class);
        Path target = Files.createDirectory(root.resolve("jsnx-edsp-data-existing"));
        assertThatThrownBy(() -> generator().generate(source(target.getFileName().toString())))
                .hasMessageContaining("禁止覆盖");
        Files.createSymbolicLink(root.resolve("jsnx-edsp-data-link"), target);
        assertThatThrownBy(() -> generator().generate(source("jsnx-edsp-data-link"))).hasMessageContaining("安全");
    }
}
