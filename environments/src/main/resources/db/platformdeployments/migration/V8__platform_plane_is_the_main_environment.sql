-- A platform service deploys TO the main environment, and says which plane it is on in a column.
--
-- WHAT CHANGED ABOVE THIS FILE. "Platform" used to be spelled as an ABSENCE: a platform deployment
-- had no environment_id, carried no environment label, was started without QITS_ENVIRONMENT and
-- announced null on all four lifecycle events. Every reader that needed to know which plane a row
-- was on therefore asked whether its tier was missing. That is dropped. A platform service is
-- deployed to the designated platform environment (pd_environment.platform, true on exactly one
-- row) like any other application — same tier, same labels, same injected environment, same event
-- attribution — and what stays different is stated rather than inferred: it keeps the BARE wire
-- alias (`qits-ci`, not `dev-qits-ci`) and it joins every environment's networks.
--
-- SO THE PLANE BECOMES A COLUMN. `deployment_target` on pd_deployment is the same word pd_service
-- has carried since V1, recorded on the execution row so that nothing has to re-derive it: the
-- listing that answers `?environmentId=platform`, the id a client joins the two listings on
-- (`platform:<name>`, ApplicationKeys), the wire alias the startup sweep rebuilds when it adopts a
-- self-update, and the conversion that absorbs an environment application's history. Every one of
-- those asked `environment_id is null` before, and every one of them would have answered wrongly
-- the moment a platform deployment gained a tier.
--
-- THE BACKFILL IS DECIDABLE, WHICH IS WHY THERE IS ONE. V7's header argues the opposite case and
-- both are the same rule: backfill only what the old rows unambiguously meant. Here they do — a
-- null environment_id meant "the platform plane" and nothing else, by V1's own header — so the two
-- statements below rewrite exactly that statement into its new spelling. Nothing is guessed and no
-- row changes what it says.
--
-- The environment_id half is not cosmetic. The cutover reads "the ACTIVE rows of this (application,
-- tier)" to decommission them, and a platform service's successor now asks about the main
-- environment; a pre-change ACTIVE row left at null would never be found, would never be
-- decommissioned, and two rows would claim to be serving one place — the invariant
-- listActiveByApplication, the rollback pins and the observation pass are all written around.
--
-- On a database with no designated platform environment (a fresh install, and every database the
-- suite migrates) the subselect answers null, the update matches its own null and nothing moves.

alter table pd_deployment add column deployment_target varchar(32);

-- The old spelling, read once: no tier meant the platform plane.
update pd_deployment set deployment_target = case
    when environment_id is null then 'PLATFORM'
    else 'ENVIRONMENT'
end;

-- No default and not null, pd_service.deployment_target's rule verbatim: every writer states which
-- plane a row is on, and a row that could not say would be the inference this file exists to remove.
alter table pd_deployment alter column deployment_target set not null;

-- ...and the tier those platform rows deploy to now, so the successor's cutover finds them.
update pd_deployment
   set environment_id = (select id from pd_environment where platform)
 where environment_id is null
   and deployment_target = 'PLATFORM';

-- "Which rows are the platform plane's" — the listing behind `?environmentId=platform`, which was a
-- null scan on an index that could not serve it.
create index idx_pd_deployment_deployment_target on pd_deployment (deployment_target);

-- The same two statements for the REQUEST row, whose environment_id carried the same null. It has
-- no plane column: nothing reads a request by plane, and the one reader it has (ReleaseTips' floor)
-- asks by application alone. What it does need is the tier, so a request written before this file
-- names the place its deployment now names.
update pd_deployment_request
   set environment_id = (select id from pd_environment where platform)
 where environment_id is null;

-- --- the resource registry follows the same key --------------------------------------------------
--
-- pd_resource is keyed (application_name, environment_name, resource_name) with NULLS NOT DISTINCT,
-- and the platform plane's rows carried a null environment_name for exactly the reason
-- pd_deployment did. ResourceProvisioning looks a deployment's credential up by that key before
-- every deploy, so leaving those rows at null while the deployment that reads them names a tier is
-- not a cosmetic mismatch: the lookup would miss, the provisioner would take its RECONCILE arm, and
-- every platform service's password would be rotated once — including this component's own, whose
-- connection pools are holding the old one open while it performs the deployment.
--
-- THE STALE ROW GOES FIRST. A platform that ran the plane per tier before it was a plane has rows
-- keyed ('qits-ci', 'dev', 'db') left over from that era; the null-keyed row is the one that has
-- been written and read ever since, so the leftover is deleted rather than allowed to collide with
-- it. There is nothing to lose — a resource row is a copy of a credential the platform is using,
-- and the one in use is the one the plane wrote.
delete from pd_resource stale
 where stale.environment_name = (select name from pd_environment where platform)
   and exists (select 1 from pd_resource live
                where live.application_name = stale.application_name
                  and live.resource_name = stale.resource_name
                  and live.environment_name is null);

update pd_resource
   set environment_name = (select name from pd_environment where platform)
 where environment_name is null
   and (select name from pd_environment where platform) is not null;

-- --- and the branch semantics leave the component ------------------------------------------------
--
-- `environment/<name>` was how a build found its tier: a green build named a branch, and the tiers
-- listening to that branch were where it went. A RELEASE names a tag, so the tier a version enters
-- is a property of the platform — `pd_environment.platform`, which this file has just made the
-- platform plane's tier as well. The column stopped being read on the intake path when the
-- SoftwareRelease trigger landed; this is it leaving the schema.
--
-- No replacement column and no promotion ladder yet: a version promoted to the next tier is a
-- deployment REQUEST of its own against another environment (V6), which is a decision somebody
-- makes rather than a ref somebody pushes. WHAT MUST NOT COME BACK IS A BRANCH.
drop index idx_pd_environment_branch;
alter table pd_environment drop column branch;
