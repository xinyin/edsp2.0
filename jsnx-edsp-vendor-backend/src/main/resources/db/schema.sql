CREATE DATABASE IF NOT EXISTS vendor_demo DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE vendor_demo;

CREATE TABLE IF NOT EXISTS vendor_data_source (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_code VARCHAR(64) NOT NULL UNIQUE,
    source_name VARCHAR(128) NOT NULL,
    description VARCHAR(500),
    base_path VARCHAR(180) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS vendor_api_definition (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    data_source_id BIGINT NOT NULL,
    api_code VARCHAR(64) NOT NULL UNIQUE,
    api_name VARCHAR(128) NOT NULL,
    service_path VARCHAR(180) NOT NULL UNIQUE,
    http_method VARCHAR(16) NOT NULL DEFAULT 'POST',
    request_example JSON NOT NULL,
    response_body JSON NOT NULL,
    response_code VARCHAR(16) NOT NULL DEFAULT '0000',
    delay_ms INT NOT NULL DEFAULT 80,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version INT NOT NULL DEFAULT 1,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_vendor_api_source FOREIGN KEY (data_source_id) REFERENCES vendor_data_source(id)
);

CREATE TABLE IF NOT EXISTS vendor_enterprise_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    enterprise_code VARCHAR(64) NOT NULL UNIQUE,
    credit_code VARCHAR(32) NOT NULL UNIQUE,
    enterprise_name VARCHAR(180) NOT NULL,
    legal_person VARCHAR(64),
    sex_code VARCHAR(8),
    risk_level VARCHAR(16),
    registration_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_enterprise_name (enterprise_name)
);
