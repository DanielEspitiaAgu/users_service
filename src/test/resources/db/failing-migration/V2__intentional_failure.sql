ALTER TABLE user_results ADD COLUMN rollback_probe integer;
SELECT 1 / 0;
