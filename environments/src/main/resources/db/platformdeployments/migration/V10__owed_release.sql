-- THE ACCEPTANCE LEDGER: every released version this component has taken responsibility for, and
-- whether it has discharged that responsibility yet.
--
-- WHY IT EXISTS, measured rather than feared. qits-eventstream already makes the bus door durable up
-- to the point where the handler returns: a `SoftwareRelease` broadcast while this component is down
-- is read back out of the log by the catch-up sweep, claimed in `consumed_event`, and handed to the
-- subscriber exactly once. What that guarantee cannot see is what the handler DOES with it —
-- `ReleaseAnnouncements.announce` validates the identifiers, puts a runnable on the single-threaded
-- `pd-deploy-worker` and returns. The claim commits there.
--
-- The worker queue is in memory, it is deliberately serialized (one deployment at a time, platform
-- wide), and on a busy afternoon it is an hour deep. `DeployService`'s @PreDestroy calls
-- shutdownNow() on it. So a cutover of THIS component — which it performs on itself, stop-first —
-- drops every release sitting in that queue while the bus's own ledger says, correctly, that the
-- event was handled. The successor's catch-up sweep finds the claim, skips the event, and no
-- deployment request is ever written. On 2026-09-05 that lost seven applications' releases between
-- 13:00 and 17:15 UTC; four were replayed by hand through the manual door and three were only
-- rescued because a later release folded their tags.
--
-- THE CURE IS A SECOND LEDGER AND IT HAS TO BE A SECOND ONE. `consumed_event` lives in the
-- `eventstream` database, this component's rows live in `platformdeployments`, both datasources are
-- non-XA, and Narayana refuses two of those in one transaction — `ReleaseTips` already carries that
-- note. So the claim and the obligation cannot commit together, and the ordering is chosen instead:
-- the obligation is written FIRST, before anything is queued. The failure that leaves is a duplicate
-- (obligation written, claim rolled back) and never a loss, and a duplicate is what the re-drive
-- guards collapse: a release whose request row already exists is settled rather than announced.
--
-- WHAT SETTLES A ROW. The worker settles it when `deploy()` has returned — the spec was read, the
-- catalogue was brought up to date, a request row was written per place, the container was cut over.
-- Nothing else does. A row still owed when a process dies is picked up by the next process's sweep,
-- which is the whole feature.
create table pd_owed_release (
    id varchar(255) not null primary key,
    -- The platform's uniform trace column: the `SoftwareRelease` frame this obligation came from,
    -- the same value every request and deployment row it produces carries.
    causation_id uuid,
    -- THE NATURAL KEY, and what makes accepting idempotent. It is the event's id AS QITS-EVENTS
    -- SPELLS IT — varchar(255) rather than a uuid, because the log makes no format promise about it
    -- and this column must be able to hold whatever it sent. A re-drive re-accepts the SAME row
    -- rather than opening a second one, so an obligation cannot fan out across restarts.
    event_id varchar(255) not null,
    -- What was accepted, in exactly the arguments `ReleaseAnnouncements.announce` takes. They are
    -- copied rather than re-derived: a re-drive happens in another process, minutes or hours later,
    -- and there is nothing left to ask. `run_id` is null on every bus acceptance (a release event
    -- carries no run) and is here because the port takes one.
    application_name varchar(64) not null,
    version varchar(64) not null,
    package_name varchar(255),
    repo_id varchar(255),
    project_id varchar(255),
    repo_name varchar(255),
    run_id varchar(255),
    -- WHICH PROCESS HOLDS IT — a uuid minted once per JVM, and the whole of the re-drive predicate.
    -- A row this process accepted is either in the worker queue or being deployed right now, and
    -- must NOT be re-driven however long it sits there; a row some earlier process accepted is
    -- orphaned by definition, because the queue that held it did not survive. Null means "held by
    -- nobody": the worker releases a row it could not discharge so this process's own sweep retries
    -- it without waiting for a restart.
    --
    -- A time-based predicate was the alternative and it cannot work here: the queue is legitimately
    -- an hour deep, so any grace period long enough to be safe is longer than the outage it is
    -- supposed to cover.
    accepted_by varchar(255),
    accepted_at timestamp(6) with time zone not null,
    -- How many times this obligation has been taken up, the live acceptance included. It bounds the
    -- re-drive: an obligation that fails the same way on every boot would otherwise be announced
    -- again at every boot forever, which is the poison-event shape the bus library warns about,
    -- moved one layer down.
    attempts integer not null default 0,
    -- Null is the whole of "still owed". The row stays after it settles, as the account of what this
    -- component accepted and what became of it.
    settled_at timestamp(6) with time zone,
    -- DISCHARGED, ALREADY_REQUESTED, SUPERSEDED or EXHAUSTED. A varchar with no check constraint,
    -- V1's rule for every enum column in this schema.
    outcome varchar(32),
    detail text,
    -- The sweep's order, and V6's identity-column arrangement for the same reason: obligations
    -- accepted from one catch-up page land in one tick, so `accepted_at` cannot order them and the
    -- id is a random uuid. Owed work is re-driven oldest first, which is the order it arrived in.
    seq bigint generated always as identity
);

-- Accepting is an upsert on this key: the live acceptance inserts, every re-drive finds. Unique
-- rather than the primary key because the id is this schema's own and a caller must never have to
-- know it.
create unique index uq_pd_owed_release_event on pd_owed_release (event_id);

-- The sweep's one query — "what is still owed", oldest first. Partial, because the settled rows are
-- the ones that accumulate forever and no reader of this index ever wants them.
create index idx_pd_owed_release_owed on pd_owed_release (seq) where settled_at is null;
