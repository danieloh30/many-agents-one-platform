-- tool-service schema: audit + idempotency, approval grants, simulated side effects.
create table tool_invocation (
    invocation_id     uuid primary key,
    idempotency_key   varchar(300) not null unique,
    args_digest       varchar(80)  not null,
    run_id            uuid,
    task_id           uuid,
    tool_name         varchar(80)  not null,
    tool_class        varchar(20)  not null,
    status            varchar(16)  not null,
    caller_spiffe_id  varchar(300) not null,
    caller_serial     varchar(80),
    caller_not_after  timestamptz,
    decision_allow    boolean      not null default false,
    decision_id       varchar(80),
    policy_revision   varchar(80),
    reasons           jsonb        not null default '[]'::jsonb,
    arguments         jsonb        not null,
    result            jsonb,
    approval_id       uuid,
    requests          integer      not null default 1,
    executions        integer      not null default 0,
    created_at        timestamptz  not null default now(),
    last_requested_at timestamptz  not null default now()
);
create index tool_invocation_run on tool_invocation (run_id, created_at);

create table approval_grant (
    approval_id    uuid primary key,
    run_id         uuid         not null,
    action_digest  varchar(80)  not null,
    approver       varchar(120) not null,
    approved_at    timestamptz  not null,
    expires_at     timestamptz  not null,
    registered_by  varchar(300) not null,
    created_at     timestamptz  not null default now()
);
create index approval_grant_run on approval_grant (run_id);

create table simulated_action (
    action_id      uuid primary key,
    invocation_id  uuid not null unique references tool_invocation (invocation_id) on delete cascade,
    run_id         uuid,
    action_type    varchar(40)  not null,
    target         varchar(120) not null,
    parameters     jsonb        not null,
    created_at     timestamptz  not null default now()
);
