-- V11: end-user profile (CG-6)

alter table users
    add column region   text,
    add column audience text;

-- codes are frozen: CG-154 and CG-22 read the same values
alter table users add constraint chk_users_region
    check (region is null or region in ('central', 'north', 'south', 'east', 'west'));
alter table users add constraint chk_users_audience
    check (audience is null or audience in ('GENERAL_PUBLIC', 'VULNERABLE', 'HEALTHCARE_WORKER'));

-- in sync with HealthCondition.java
create table user_health_conditions (
    user_id uuid not null references users (id) on delete cascade, --delete cascade
    condition_code text not null,
    primary key (user_id, condition_code),
    constraint chk_user_health_conditions_code
        check (condition_code in ('ASTHMA', 'COPD', 'HEART_DISEASE', 'DIABETES', 'PREGNANCY'))
);
