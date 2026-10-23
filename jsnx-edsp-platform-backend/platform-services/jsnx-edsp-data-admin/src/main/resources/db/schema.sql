CREATE DATABASE IF NOT EXISTS edsp_demo DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE edsp_demo;

CREATE TABLE IF NOT EXISTS data_provider (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    provider_code VARCHAR(64) NOT NULL UNIQUE,
    provider_name VARCHAR(128) NOT NULL,
    credit_code VARCHAR(32),
    provider_type VARCHAR(32) NOT NULL DEFAULT 'DATA_VENDOR',
    contact_name VARCHAR(64),
    access_date DATE,
    message_encoding VARCHAR(32) NOT NULL DEFAULT 'UTF-8',
    charge_required TINYINT(1) NOT NULL DEFAULT 0,
    encryption_required TINYINT(1) NOT NULL DEFAULT 0,
    login_required TINYINT(1) NOT NULL DEFAULT 0,
    contact_phone VARCHAR(32),
    contact_email VARCHAR(128),
    service_phone VARCHAR(32),
    contract_no VARCHAR(64),
    contract_start_date DATE,
    contract_end_date DATE,
    security_level VARCHAR(16) NOT NULL DEFAULT 'L2',
    settlement_mode VARCHAR(32) NOT NULL DEFAULT 'MONTHLY',
    office_address VARCHAR(256),
    description VARCHAR(500),
    access_mode VARCHAR(32) NOT NULL DEFAULT 'HTTP',
    access_config JSON NOT NULL DEFAULT (JSON_OBJECT()),
    application_name VARCHAR(128),
    runtime_url VARCHAR(500),
    server_port INT NOT NULL DEFAULT 8199,
    config_version INT NOT NULL DEFAULT 1,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_provider_application (application_name)
);

CREATE TABLE IF NOT EXISTS provider_account (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    provider_id BIGINT NOT NULL,
    username VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(64) NOT NULL,
    mobile VARCHAR(32),
    email VARCHAR(128),
    role_code VARCHAR(32) NOT NULL DEFAULT 'PROVIDER_OPERATOR',
    permissions JSON NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    last_login_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_provider_account_provider FOREIGN KEY (provider_id) REFERENCES data_provider(id)
);

CREATE TABLE IF NOT EXISTS data_source (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    provider_id BIGINT NOT NULL,
    source_code VARCHAR(64) NOT NULL UNIQUE,
    source_name VARCHAR(128) NOT NULL,
    application_name VARCHAR(128) NOT NULL,
    runtime_url VARCHAR(500),
    server_port INT NOT NULL DEFAULT 8199,
    protocol_type VARCHAR(32) NOT NULL DEFAULT 'HTTP_JSON',
    endpoint_url VARCHAR(500),
    default_timeout_ms INT NOT NULL DEFAULT 3000,
    environment VARCHAR(16) NOT NULL DEFAULT 'TEST',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    instance_count INT NOT NULL DEFAULT 2,
    config_version INT NOT NULL DEFAULT 1,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_source_provider FOREIGN KEY (provider_id) REFERENCES data_provider(id)
);

CREATE TABLE IF NOT EXISTS resource_api (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    data_source_id BIGINT NOT NULL,
    resource_api_code VARCHAR(64) NOT NULL UNIQUE,
    resource_api_name VARCHAR(128) NOT NULL,
    http_method VARCHAR(16) NOT NULL DEFAULT 'POST',
    upstream_url VARCHAR(500) NOT NULL,
    upstream_path VARCHAR(256),
    config_scope VARCHAR(16) NOT NULL DEFAULT 'OVERRIDE',
    request_example JSON NOT NULL,
    response_example JSON NOT NULL,
    request_headers JSON NOT NULL DEFAULT ('{}'),
    execution_mode VARCHAR(16) NOT NULL DEFAULT 'HTTP',
    sql_template TEXT NULL,
    sql_parameters JSON NOT NULL DEFAULT ('{}'),
    timeout_ms INT,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    current_version INT NOT NULL DEFAULT 1,
    verified_at DATETIME NULL,
    verified_checksum VARCHAR(64) NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_resource_api_source FOREIGN KEY (data_source_id) REFERENCES data_source(id)
);

CREATE TABLE IF NOT EXISTS published_route (
    interface_id BIGINT PRIMARY KEY,
    interface_code VARCHAR(64) NOT NULL UNIQUE,
    service_path VARCHAR(160) NOT NULL,
    http_method VARCHAR(16) NOT NULL,
    route_version INT NOT NULL,
    data_source_id BIGINT NOT NULL,
    published_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_published_route_method_path (http_method, service_path),
    INDEX idx_published_route_source (data_source_id)
);

CREATE TABLE IF NOT EXISTS api_interface (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    resource_api_id BIGINT NULL,
    data_source_id BIGINT NOT NULL,
    interface_code VARCHAR(64) NOT NULL UNIQUE,
    interface_name VARCHAR(128) NOT NULL,
    service_path VARCHAR(160) NOT NULL,
    http_method VARCHAR(16) NOT NULL DEFAULT 'POST',
    upstream_url VARCHAR(500) NOT NULL,
    upstream_path VARCHAR(256),
    config_scope VARCHAR(16) NOT NULL DEFAULT 'OVERRIDE',
    dispatch_mode VARCHAR(16) NOT NULL DEFAULT 'DIRECT',
    router_service_code VARCHAR(128),
    request_schema JSON NOT NULL,
    response_schema JSON NOT NULL,
    request_example JSON NOT NULL,
    upstream_request_example JSON NULL,
    upstream_response_example JSON NOT NULL,
    standard_response_example JSON NOT NULL,
    upstream_request_headers JSON NOT NULL DEFAULT ('{}'),
    timeout_ms INT,
    retry_count INT NOT NULL DEFAULT 0,
    vendor_code_path VARCHAR(256) NOT NULL DEFAULT 'result.code',
    response_policy JSON NULL,
    upstream_status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    current_version INT NOT NULL DEFAULT 1,
    published_version INT,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_interface_source FOREIGN KEY (data_source_id) REFERENCES data_source(id),
    CONSTRAINT fk_interface_resource_api FOREIGN KEY (resource_api_id) REFERENCES resource_api(id)
);

CREATE TABLE IF NOT EXISTS field_mapping (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    interface_id BIGINT NOT NULL,
    mapping_direction VARCHAR(16) NOT NULL,
    source_path VARCHAR(256) NOT NULL,
    target_path VARCHAR(256) NOT NULL,
    transform_type VARCHAR(32) NOT NULL DEFAULT 'DIRECT',
    transform_config JSON,
    default_value VARCHAR(512),
    required_flag TINYINT(1) NOT NULL DEFAULT 0,
    sort_order INT NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_mapping_interface FOREIGN KEY (interface_id) REFERENCES api_interface(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS interface_validation_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    interface_id BIGINT NOT NULL,
    validation_type VARCHAR(32) NOT NULL,
    config_checksum VARCHAR(64) NOT NULL,
    validated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_interface_validation(interface_id, validation_type),
    CONSTRAINT fk_validation_interface FOREIGN KEY (interface_id) REFERENCES api_interface(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS api_security_policy (
    interface_id BIGINT PRIMARY KEY,
    enabled TINYINT(1) NOT NULL DEFAULT 0,
    encrypt_mode VARCHAR(32) NOT NULL DEFAULT 'NONE',
    signature_mode VARCHAR(32) NOT NULL DEFAULT 'NONE',
    request_fields VARCHAR(1000),
    secret_reference VARCHAR(256),
    encryption_key_reference VARCHAR(256),
    signing_key_reference VARCHAR(256),
    signature_field VARCHAR(256) NOT NULL DEFAULT 'signature',
    description VARCHAR(500),
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_security_interface FOREIGN KEY (interface_id) REFERENCES api_interface(id) ON DELETE CASCADE
);

DROP PROCEDURE IF EXISTS migrate_security_key_separation;
DELIMITER $$
CREATE PROCEDURE migrate_security_key_separation()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='edsp_demo' AND table_name='api_security_policy' AND column_name='encryption_key_reference') THEN
        ALTER TABLE api_security_policy ADD COLUMN encryption_key_reference VARCHAR(256) AFTER secret_reference;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='edsp_demo' AND table_name='api_security_policy' AND column_name='signing_key_reference') THEN
        ALTER TABLE api_security_policy ADD COLUMN signing_key_reference VARCHAR(256) AFTER encryption_key_reference;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='edsp_demo' AND table_name='api_security_policy' AND column_name='signature_field') THEN
        ALTER TABLE api_security_policy ADD COLUMN signature_field VARCHAR(256) NOT NULL DEFAULT 'signature' AFTER signing_key_reference;
    END IF;
END$$
DELIMITER ;
CALL migrate_security_key_separation();
DROP PROCEDURE migrate_security_key_separation;

CREATE TABLE IF NOT EXISTS response_code_mapping (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    interface_id BIGINT NOT NULL,
    vendor_code VARCHAR(64) NOT NULL,
    standard_code VARCHAR(64) NOT NULL,
    result_status VARCHAR(16) NOT NULL,
    retryable TINYINT(1) NOT NULL DEFAULT 0,
    billable TINYINT(1) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_interface_vendor_code (interface_id, vendor_code),
    CONSTRAINT fk_code_interface FOREIGN KEY (interface_id) REFERENCES api_interface(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS platform_response_code (
    code VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    result_status VARCHAR(16) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS platform_response_contract (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    contract_code VARCHAR(64) NOT NULL UNIQUE,
    contract_name VARCHAR(128) NOT NULL,
    version_no INT NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    fields_json JSON NOT NULL,
    active_fields_json JSON NULL,
    is_active TINYINT(1) NOT NULL DEFAULT 0,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    is_default TINYINT(1) NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS vendor_mock_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    interface_id BIGINT NOT NULL UNIQUE,
    mock_name VARCHAR(128) NOT NULL,
    response_body JSON NOT NULL,
    http_status INT NOT NULL DEFAULT 200,
    delay_ms INT NOT NULL DEFAULT 80,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_mock_interface FOREIGN KEY (interface_id) REFERENCES api_interface(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS release_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    interface_id BIGINT NOT NULL,
    release_version INT NOT NULL,
    release_status VARCHAR(16) NOT NULL,
    checksum VARCHAR(64) NOT NULL,
    release_note VARCHAR(500),
    released_by VARCHAR(64) NOT NULL,
    released_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_interface_release_version (interface_id, release_version),
    CONSTRAINT fk_release_interface FOREIGN KEY (interface_id) REFERENCES api_interface(id)
);

CREATE TABLE IF NOT EXISTS release_plan_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    interface_id BIGINT NOT NULL,
    release_version INT NOT NULL,
    checksum VARCHAR(64) NOT NULL,
    plan_json JSON NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_plan_snapshot_version (interface_id, release_version),
    CONSTRAINT fk_plan_snapshot_interface FOREIGN KEY (interface_id) REFERENCES api_interface(id)
);

CREATE TABLE IF NOT EXISTS release_approval (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    interface_id BIGINT NOT NULL,
    release_version INT NOT NULL,
    applicant VARCHAR(64) NOT NULL,
    release_note VARCHAR(500),
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    approval_node VARCHAR(64) NOT NULL DEFAULT '技术审批',
    submitted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_by VARCHAR(64),
    review_comment VARCHAR(500),
    reviewed_at DATETIME,
    checksum VARCHAR(64),
    INDEX idx_approval_status (status, submitted_at),
    CONSTRAINT fk_approval_interface FOREIGN KEY (interface_id) REFERENCES api_interface(id)
);

CREATE TABLE IF NOT EXISTS interface_lifecycle_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    interface_id BIGINT NOT NULL,
    action VARCHAR(24) NOT NULL,
    operated_by VARCHAR(64) NOT NULL,
    reason VARCHAR(500),
    operated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_lifecycle_interface (interface_id, operated_at),
    CONSTRAINT fk_lifecycle_interface FOREIGN KEY (interface_id) REFERENCES api_interface(id)
);

CREATE TABLE IF NOT EXISTS api_client (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    client_code VARCHAR(64) NOT NULL UNIQUE,
    client_name VARCHAR(128) NOT NULL,
    institution_code VARCHAR(64) NOT NULL,
    institution_name VARCHAR(128) NOT NULL,
    owner_name VARCHAR(64),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS api_authorization (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    client_id BIGINT NOT NULL,
    interface_id BIGINT NOT NULL,
    environment VARCHAR(16) NOT NULL DEFAULT 'TEST',
    daily_quota INT NOT NULL DEFAULT 10000,
    valid_from DATE NOT NULL,
    valid_to DATE NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    approved_by VARCHAR(64),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_client_interface_env (client_id, interface_id, environment),
    CONSTRAINT fk_auth_client FOREIGN KEY (client_id) REFERENCES api_client(id),
    CONSTRAINT fk_auth_interface FOREIGN KEY (interface_id) REFERENCES api_interface(id)
);

CREATE TABLE IF NOT EXISTS billing_rule (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    interface_id BIGINT NOT NULL UNIQUE,
    billing_mode VARCHAR(32) NOT NULL DEFAULT 'SUCCESS_COUNT',
    unit_price DECIMAL(12,4) NOT NULL DEFAULT 0,
    currency VARCHAR(8) NOT NULL DEFAULT 'CNY',
    hit_billable TINYINT(1) NOT NULL DEFAULT 1,
    miss_billable TINYINT(1) NOT NULL DEFAULT 0,
    effective_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_billing_interface FOREIGN KEY (interface_id) REFERENCES api_interface(id)
);

CREATE TABLE IF NOT EXISTS usage_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trace_id VARCHAR(64) NOT NULL UNIQUE,
    client_id BIGINT,
    interface_id BIGINT NOT NULL,
    result_status VARCHAR(16) NOT NULL,
    standard_code VARCHAR(64) NOT NULL,
    billable TINYINT(1) NOT NULL DEFAULT 0,
    unit_price DECIMAL(12,4) NOT NULL DEFAULT 0,
    amount DECIMAL(14,4) NOT NULL DEFAULT 0,
    duration_ms INT NOT NULL DEFAULT 0,
    upstream_duration_ms INT NOT NULL DEFAULT 0,
    gateway_duration_ms INT NOT NULL DEFAULT 0,
    request_status VARCHAR(16) NOT NULL DEFAULT 'SUCCESS',
    node_name VARCHAR(64) NOT NULL DEFAULT 'jsnx-edsp-data-source-demo-01',
    called_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_usage_status_time (request_status, called_at),
    INDEX idx_usage_interface_time (interface_id, called_at),
    INDEX idx_usage_called_at (called_at, interface_id),
    CONSTRAINT fk_usage_client FOREIGN KEY (client_id) REFERENCES api_client(id),
    CONSTRAINT fk_usage_interface FOREIGN KEY (interface_id) REFERENCES api_interface(id)
);

CREATE TABLE IF NOT EXISTS platform_operation_audit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    operator_name VARCHAR(64) NOT NULL,
    action VARCHAR(16) NOT NULL,
    target_path VARCHAR(512) NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    http_status INT NOT NULL,
    trace_id VARCHAR(64),
    client_ip VARCHAR(64),
    occurred_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_audit_time (occurred_at, id),
    INDEX idx_audit_operator_time (operator_name, occurred_at),
    INDEX idx_audit_target_time (target_type, occurred_at)
);

CREATE TABLE IF NOT EXISTS resource_document (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    provider_id BIGINT NOT NULL,
    data_source_id BIGINT NULL,
    resource_api_id BIGINT NULL,
    document_name VARCHAR(255) NOT NULL,
    storage_name VARCHAR(64) NOT NULL UNIQUE,
    content_type VARCHAR(128) NOT NULL,
    file_size BIGINT NOT NULL,
    description VARCHAR(500),
    uploaded_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_document_provider (provider_id, uploaded_at),
    CONSTRAINT fk_document_provider FOREIGN KEY (provider_id) REFERENCES data_provider(id),
    CONSTRAINT fk_document_source FOREIGN KEY (data_source_id) REFERENCES data_source(id),
    CONSTRAINT fk_document_api FOREIGN KEY (resource_api_id) REFERENCES resource_api(id)
);

CREATE TABLE IF NOT EXISTS api_call_node_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trace_id VARCHAR(64) NOT NULL,
    node_key VARCHAR(64) NOT NULL,
    node_name VARCHAR(128) NOT NULL,
    node_category VARCHAR(32) NOT NULL,
    node_status VARCHAR(16) NOT NULL,
    node_detail VARCHAR(500),
    duration_ms INT NOT NULL DEFAULT 0,
    input_data JSON,
    output_data JSON,
    sort_order INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_trace_node (trace_id, node_key),
    INDEX idx_trace_order (trace_id, sort_order),
    INDEX idx_node_status_time (node_status, created_at)
);

CREATE TABLE IF NOT EXISTS demo_async_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_type VARCHAR(64) NOT NULL,
    trace_id VARCHAR(64),
    payload JSON NOT NULL,
    consume_status VARCHAR(16) NOT NULL DEFAULT 'CONSUMED',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_demo_event_trace (trace_id, created_at),
    INDEX idx_demo_event_type_time (event_type, created_at)
);
