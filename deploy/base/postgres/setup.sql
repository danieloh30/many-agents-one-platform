-- Idempotent role and schema setup (one role per owning service, docs/security.md §3.3, §7).
-- Run by Job postgres-setup with psql variables :triage_pw and :tools_pw (never interpolated by
-- the shell; quoted with format(%L) inside the server). Safe to rerun: roles and schemas are created
-- only when absent, passwords are re-synchronised with Secret postgres-roles every run.
\set ON_ERROR_STOP on

SELECT format('CREATE ROLE %I LOGIN', r)
FROM unnest(ARRAY['triage', 'tools']) AS r
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r)
\gexec

SELECT format('ALTER ROLE triage WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD %L', :'triage_pw')
\gexec
SELECT format('ALTER ROLE tools WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD %L', :'tools_pw')
\gexec

CREATE SCHEMA IF NOT EXISTS triage AUTHORIZATION triage;
CREATE SCHEMA IF NOT EXISTS tools AUTHORIZATION tools;
ALTER SCHEMA triage OWNER TO triage;
ALTER SCHEMA tools OWNER TO tools;

-- Isolation: nobody but the owner uses a schema; the public schema is closed.
REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON SCHEMA triage FROM PUBLIC;
REVOKE ALL ON SCHEMA tools FROM PUBLIC;
REVOKE ALL ON DATABASE maop FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE maop TO triage, tools;

ALTER ROLE triage SET search_path = triage;
ALTER ROLE tools SET search_path = tools;
