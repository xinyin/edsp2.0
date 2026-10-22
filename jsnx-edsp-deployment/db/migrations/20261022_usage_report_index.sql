-- Apply once to existing platform databases during a maintenance window.
-- New databases already receive this index from schema.sql.
ALTER TABLE usage_record ADD INDEX idx_usage_called_at (called_at, interface_id);
