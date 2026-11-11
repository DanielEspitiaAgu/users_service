ALTER TABLE user_results
    ADD COLUMN match_id varchar(255),
    ADD COLUMN first_win_rewarded boolean NOT NULL DEFAULT false;

ALTER TABLE user_results
    ADD CONSTRAINT uq_user_results_user_match UNIQUE (user_id, match_id),
    ADD CONSTRAINT ck_user_results_match_id_nonblank
        CHECK (match_id IS NULL OR btrim(match_id) <> ''),
    ADD CONSTRAINT ck_user_results_first_win_rewarded
        CHECK (NOT first_win_rewarded OR (won AND mode = 'CLASSIC'));

CREATE TABLE classic_case_rewards (
    user_id bigint NOT NULL,
    case_id varchar(255) NOT NULL,
    mode varchar(20) NOT NULL,
    first_result_id bigint NOT NULL,
    xp_awarded integer NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    CONSTRAINT pk_classic_case_rewards PRIMARY KEY (user_id, case_id, mode),
    CONSTRAINT ck_classic_case_rewards_mode CHECK (mode = 'CLASSIC'),
    CONSTRAINT ck_classic_case_rewards_xp CHECK (xp_awarded >= 0),
    CONSTRAINT uq_classic_case_rewards_result UNIQUE (first_result_id),
    CONSTRAINT fk_classic_case_rewards_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_classic_case_rewards_result FOREIGN KEY (first_result_id) REFERENCES user_results (id)
);
