-- Runs may add one evidence task that uses the sandboxed analysis tool (contracts.md section 2.2).
alter table run add column analysis_mode varchar(20) not null default 'NONE';
