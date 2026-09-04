-- The deployment REQUEST: what a released version asks the platform for, and whether it may have it.
--
-- WHY A SECOND TABLE RATHER THAN COLUMNS ON pd_deployment. pd_deployment is an execution record —
-- one attempt to put one image live, owned by the worker's state machine from QUEUED to a terminal
-- word. A request is the decision that precedes it: this version of this application is asked for,
-- here, and something has to say yes before anything is queued. Today the something is a
-- placeholder that says yes immediately, which is exactly why the row exists NOW: the shape has to
-- be in the schema before a real gate has an opinion, or the first real gate would arrive as a
-- migration of live history. A request with an UNMET gate is a deployment that never happened, and
-- there is no state on pd_deployment that can spell that.
--
-- ONE REQUEST PER PLACE, and it points AT the deployment rather than the other way round
-- (deployment_id, nullable). The request is written first — it is the cause — so it cannot carry a
-- foreign key to a row that does not exist yet, and pd_deployment keeps the shape every reader of
-- it already has. A request whose gate came back UNMET keeps deployment_id null forever, which is
-- the whole record of a refusal.
--
-- NO FK ANYWHERE, the pd_deployment stance verbatim (V1's header argues it): application_name and
-- environment_id are plain strings so a request outlives the catalogue row and the tier that
-- described it, and deployment_id is a plain string for the same reason a torn-down environment
-- must not cascade its history away.
--
-- environment_id is nullable and THE NULL IS THE STATEMENT, exactly as on pd_deployment: a request
-- for the platform plane belongs to no tier.
--
-- quality_gate is a varchar with NO CHECK CONSTRAINT — V1's rule for every enum column here, and it
-- matters more for this one than for any other: the vocabulary of gates is the thing this table
-- exists to grow.
create table pd_deployment_request (
    id varchar(255) not null primary key,
    causation_id uuid,
    application_name varchar(64) not null,
    -- The released coordinate: the CalVer stamp the release minted, which is also the git tag and
    -- the image tag. Never a commit sha — a request is about a version.
    version varchar(64) not null,
    environment_id varchar(255),
    -- The package the release announced, verbatim and unqualified (`qits/qits-ci`). It is what the
    -- application name was derived FROM, kept so a reader can see the derivation rather than
    -- re-perform it, and null on a request made through the manual door.
    package_name varchar(255),
    -- The repository the release came from, in the git host's own storage key. A plain string, no
    -- FK, no resolution — the repo_id stance this component takes at every boundary.
    repo_id varchar(255),
    project_id varchar(255),
    quality_gate varchar(32) not null,
    gate_detail text,
    deployment_id varchar(255),
    created_at timestamp(6) with time zone not null,
    gate_settled_at timestamp(6) with time zone,
    -- The listing tiebreak, V1's identity-column arrangement: created_at is not unique, and the
    -- requests of one release land in the same tick.
    seq bigint generated always as identity
);

-- "What has this application been asked for, newest first" — the listing, and the floor the
-- monotonic collapse falls back to across a restart.
create index idx_pd_deployment_request_application on pd_deployment_request (application_name);
create index idx_pd_deployment_request_deployment on pd_deployment_request (deployment_id);
