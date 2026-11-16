-- triage-agent schema: the durable business record of each run.
create table run (
    run_id                 uuid primary key,
    version                integer      not null default 1,
    state                  varchar(32)  not null,
    outcome                varchar(40),
    outcome_detail         text,
    scenario               varchar(20)  not null,
    remediation_mode       varchar(30)  not null,
    fault_injection        varchar(20)  not null,
    plan_source            varchar(10)  not null,
    burst_id               uuid,
    title                  varchar(160) not null,
    description            text         not null,
    service                varchar(120) not null,
    classification         jsonb,
    budget                 jsonb        not null,
    usage                  jsonb        not null,
    model_mode             varchar(20)  not null,
    model_name             varchar(120),
    trace_id               varchar(32),
    traceparent            varchar(64),
    recommendation         jsonb,
    execution_attempt      jsonb,
    evidence_completeness  varchar(10),
    approval               jsonb,
    labels                 jsonb        not null default '{}'::jsonb,
    created_at             timestamptz  not null default now(),
    updated_at             timestamptz  not null default now(),
    deadline               timestamptz  not null,
    approval_deadline      timestamptz
);
create index run_active on run (state) where state not in ('COMPLETED', 'NEEDS_REVIEW', 'TIMED_OUT', 'FAILED', 'CANCELLED');
create index run_created on run (created_at desc);
create index run_burst on run (burst_id);

create table run_task (
    task_id         uuid primary key,
    run_id          uuid         not null references run (run_id) on delete cascade,
    kind            varchar(20)  not null,
    topic           varchar(32),
    state           varchar(20)  not null,
    deliveries      integer      not null default 0,
    failure_reason  varchar(40),
    detail          text,
    evidence        jsonb,
    usage           jsonb,
    created_at      timestamptz  not null default now(),
    finished_at     timestamptz
);
create index run_task_run on run_task (run_id);

create table run_timeline (
    seq         bigserial primary key,
    run_id      uuid         not null references run (run_id) on delete cascade,
    at          timestamptz  not null default now(),
    kind        varchar(32)  not null,
    from_state  varchar(32),
    to_state    varchar(32),
    task_id     uuid,
    message     text         not null,
    data        jsonb
);
create index run_timeline_run on run_timeline (run_id, seq);

create table idempotency_key (
    idem_key      varchar(200) primary key,
    request_hash  varchar(80)  not null,
    run_id        uuid         not null,
    created_at    timestamptz  not null default now()
);

create table burst (
    burst_id    uuid primary key,
    active      boolean      not null,
    runs        integer      not null,
    started_at  timestamptz  not null default now(),
    stopped_at  timestamptz
);
