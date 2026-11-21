-- Đánh dấu khiếu nại OPEN đã quá deadline_at (Dev Guide §3 mục 14). Chỉ ghi nhận, không tự phân xử.
ALTER TABLE disputes ADD COLUMN deadline_overdue_at timestamptz;
CREATE INDEX ix_disputes_overdue_pending ON disputes (deadline_at) WHERE status = 'OPEN' AND deadline_overdue_at IS NULL;
