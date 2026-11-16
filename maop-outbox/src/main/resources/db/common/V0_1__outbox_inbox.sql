-- Transactional outbox and consumer inbox (architecture.md section 6.1 and 6.2).
-- Created in the owning service's schema (Flyway default schema).
create table outbox_event (
    id            uuid primary key,
    kind          varchar(32)  not null,
    subject       varchar(255),
    payload       jsonb        not null,
    created_at    timestamptz  not null default now(),
    available_at  timestamptz  not null default now(),
    published_at  timestamptz,
    attempts      integer      not null default 0,
    last_error    text
);
create index outbox_event_unpublished on outbox_event (id) where published_at is null;

create table inbox_event (
    consumer     varchar(64)  not null,
    event_id     varchar(64)  not null,
    received_at  timestamptz  not null default now(),
    primary key (consumer, event_id)
);
