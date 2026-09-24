-- THE PLATFORM PLANE IS DELETED. Every service is an environment service in the one tier, and a
-- service is defined by its LINKS.
--
-- WHAT CHANGED ABOVE THIS FILE. `deployment_target` said which of two planes a row was on:
-- ENVIRONMENT (one instance per linked tier) or PLATFORM (one instance for the whole platform,
-- carrying no link at all, reached under its BARE name from every tier). That distinction is gone.
-- The wire alias is `<env>-<app>` unconditionally, the container name always carries the tier, the
-- read surface keys `<env>:<name>`, the `qits-platform` overlay and the
-- `qits.platform.deployments.target=platform` label are not written or read, and both the enum and
-- the refusals built on it are deleted from the code.
--
-- SO THE NINE ROWS HAVE TO BE CONVERTED, NOT MERELY UNCOLUMNED. `pd_service` holds nine rows that
-- say PLATFORM and carry NO `pd_service_link`, because carrying none WAS how the plane spelled
-- "present everywhere". Dropping the column alone would leave exactly those nine linked nowhere —
-- which after this change reads as "runs nowhere": the link query would stop returning them, the
-- flat listing would report them with no tier, and the deployer would no longer manage the nine
-- services the platform is built out of. So each of them is linked into the designated platform
-- environment (`pd_environment.platform`, true on exactly one row — `dev` on this install), which
-- is the tier the plane already deployed into since V8. Nothing is guessed: V8 put the plane's
-- deployments in that tier, and this is the catalogue saying the same thing.
--
-- THE BACKFILL IS DECIDABLE, WHICH IS WHY THERE IS ONE — V8's rule, applied again. A PLATFORM row
-- meant "deployed into the designated tier and reachable from all of them", and the tier half is
-- exactly what survives the plane's deletion.
--
-- On a database with no designated platform environment (a fresh install, and every database the
-- suite migrates) the select answers no rows, the insert inserts nothing, and there is nothing to
-- convert — the same way V8 moves nothing there.

-- The link each of the nine is owed. `not exists` rather than `on conflict`: uq_pd_service_link is
-- (service_id, environment_id) and a service ALREADY linked into the designated tier is an
-- environment service that needs nothing, not a collision to swallow.
insert into pd_service_link (id, service_id, environment_id, created_at)
select gen_random_uuid()::text, s.id, e.id, now()
  from pd_service s
 cross join (select id from pd_environment where platform) e
 where s.deployment_target = 'PLATFORM'
   and not exists (select 1 from pd_service_link l
                    where l.service_id = s.id
                      and l.environment_id = e.id);

-- ...and the column that said which plane, on both tables. The index goes with the column it was on.
--
-- ON `pd_deployment` IT HAS TO GO, and that is not a tidiness argument. V8 made it NOT NULL with no
-- default, on the rule that every writer states the plane; the writer is deleted here, so leaving
-- the column would fail every INSERT of a deployment row from the first release onwards. There is
-- no nullable half-step worth taking either: a column no row written after today can fill is a
-- history whose reader would take "null" for a plane.
drop index idx_pd_service_target;
alter table pd_service drop column deployment_target;

drop index idx_pd_deployment_deployment_target;
alter table pd_deployment drop column deployment_target;
