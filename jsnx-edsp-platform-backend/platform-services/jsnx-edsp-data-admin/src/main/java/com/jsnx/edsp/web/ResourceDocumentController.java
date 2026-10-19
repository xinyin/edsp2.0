package com.jsnx.edsp.web;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/resource-documents")
public class ResourceDocumentController {
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "doc", "docx", "xls", "xlsx", "txt");
    private final JdbcTemplate jdbc;
    private final Path storageRoot;

    public ResourceDocumentController(JdbcTemplate jdbc,
            @Value("${edsp.documents.storage-root:./var/documents}") String storageRoot) {
        this.jdbc = jdbc;
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    @PostConstruct
    void initialize() throws IOException {
        Files.createDirectories(storageRoot);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS resource_document (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    provider_id BIGINT NOT NULL, data_source_id BIGINT NULL, resource_api_id BIGINT NULL,
                    document_name VARCHAR(255) NOT NULL, storage_name VARCHAR(64) NOT NULL UNIQUE,
                    content_type VARCHAR(128) NOT NULL, file_size BIGINT NOT NULL,
                    description VARCHAR(500), uploaded_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_document_provider (provider_id, uploaded_at),
                    CONSTRAINT fk_document_provider FOREIGN KEY (provider_id) REFERENCES data_provider(id),
                    CONSTRAINT fk_document_source FOREIGN KEY (data_source_id) REFERENCES data_source(id),
                    CONSTRAINT fk_document_api FOREIGN KEY (resource_api_id) REFERENCES resource_api(id))
                """);
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest request,
            @RequestParam(required = false) Long providerId) {
        requireSession(request);
        String sql = """
                SELECT d.id, d.provider_id providerId, p.provider_name providerName,
                       d.data_source_id dataSourceId, ds.source_name dataSourceName,
                       d.resource_api_id resourceApiId, ra.resource_api_name resourceApiName,
                       d.document_name documentName, d.content_type contentType,
                       d.file_size fileSize, d.description, d.uploaded_at uploadedAt
                FROM resource_document d JOIN data_provider p ON p.id=d.provider_id
                LEFT JOIN data_source ds ON ds.id=d.data_source_id
                LEFT JOIN resource_api ra ON ra.id=d.resource_api_id
                """;
        return providerId == null
                ? jdbc.queryForList(sql + " ORDER BY d.uploaded_at DESC, d.id DESC LIMIT 500")
                : jdbc.queryForList(sql + " WHERE d.provider_id=? ORDER BY d.uploaded_at DESC, d.id DESC LIMIT 500", providerId);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(HttpServletRequest request,
            @RequestHeader("X-Edsp-Operator-Token") String token,
            @RequestParam long providerId, @RequestParam(required = false) Long dataSourceId,
            @RequestParam(required = false) Long resourceApiId,
            @RequestParam(required = false, defaultValue = "") String description,
            @RequestParam MultipartFile file) throws IOException {
        requireWriteToken(request, token);
        String name = file.getOriginalFilename() == null ? "" :
                Path.of(file.getOriginalFilename()).getFileName().toString();
        if (name.isBlank() || name.length() > 255 || name.contains("\\") || name.contains("/")
                || name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件名不合法");
        }
        String extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (!ALLOWED_EXTENSIONS.contains(extension) || file.isEmpty() || file.getSize() > MAX_BYTES
                || description.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持 20 MB 以内的接口文档文件");
        }
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM data_provider WHERE id=?", Integer.class, providerId);
        if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "资源提供商不存在");
        if (dataSourceId != null && !Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT COUNT(*)>0 FROM data_source WHERE id=? AND provider_id=?", Boolean.class, dataSourceId, providerId))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "数据源不属于该资源提供商");
        }
        if (resourceApiId != null && !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT COUNT(*)>0 FROM resource_api ra JOIN data_source ds ON ds.id=ra.data_source_id
                WHERE ra.id=? AND ds.provider_id=? AND (? IS NULL OR ra.data_source_id=?)
                """, Boolean.class, resourceApiId, providerId, dataSourceId, dataSourceId))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "资源 API 与所选提供商或数据源不匹配");
        }
        String storageName = UUID.randomUUID() + "." + extension;
        Path target = storageRoot.resolve(storageName);
        try {
            Files.copy(file.getInputStream(), target);
            jdbc.update("""
                    INSERT INTO resource_document(provider_id,data_source_id,resource_api_id,document_name,
                        storage_name,content_type,file_size,description) VALUES(?,?,?,?,?,?,?,?)
                    """, providerId, dataSourceId, resourceApiId, name, storageName,
                    file.getContentType() == null ? "application/octet-stream" : file.getContentType(),
                    file.getSize(), description.trim());
            return Map.of("status", "SAVED", "documentName", name);
        } catch (IOException | RuntimeException error) {
            Files.deleteIfExists(target);
            throw error;
        }
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<FileSystemResource> download(HttpServletRequest request, @PathVariable long id) {
        requireSession(request);
        Map<String, Object> row = document(id);
        Path path = storagePath(row);
        if (!Files.isRegularFile(path)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(String.valueOf(row.get("document_name")), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(path));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(HttpServletRequest request,
            @RequestHeader("X-Edsp-Operator-Token") String token, @PathVariable long id) throws IOException {
        requireWriteToken(request, token);
        Map<String, Object> row = document(id);
        Path path = storagePath(row);
        jdbc.update("DELETE FROM resource_document WHERE id=?", id);
        Files.deleteIfExists(path);
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> document(long id) {
        try { return jdbc.queryForMap("SELECT document_name,storage_name FROM resource_document WHERE id=?", id); }
        catch (EmptyResultDataAccessException error) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在");
        }
    }

    private Path storagePath(Map<String, Object> row) {
        String name = String.valueOf(row.get("storage_name"));
        if (!name.matches("[0-9a-fA-F-]{36}\\.(pdf|doc|docx|xls|xlsx|txt)")) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "文档存储名不合法");
        }
        return storageRoot.resolve(name);
    }

    private void requireSession(HttpServletRequest request) {
        if (request.getSession(false) == null ||
                request.getSession(false).getAttribute(ChainTestToolController.SESSION_TOKEN) == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
    }

    private void requireWriteToken(HttpServletRequest request, String token) {
        requireSession(request);
        String expected = String.valueOf(request.getSession(false).getAttribute(ChainTestToolController.SESSION_TOKEN));
        if (!java.security.MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "操作令牌无效");
        }
    }
}
