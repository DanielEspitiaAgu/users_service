ALTER TABLE user_results
    ADD COLUMN difficulty varchar(10);

ALTER TABLE user_results
    ADD CONSTRAINT ck_user_results_difficulty
        CHECK (difficulty IS NULL OR difficulty IN ('EASY', 'MEDIUM', 'HARD'));
