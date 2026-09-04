-- The released coordinate gets a column of its own, and commit_sha goes back to meaning a commit.
--
-- WHAT CHANGED ABOVE THIS FILE. A deployment used to be identified by a commit sha end to end: the
-- image was `qits/<app>:<sha>`, the spec was read at that sha, and the startup sweep compared the
-- running image against it. A release deploys `qits/<app>:<version>` now — the CalVer stamp is what
-- the pipeline tagged the image with and what the git tag is called — so the row has to record the
-- version, and every one of those three readers has to read it.
--
-- commit_sha SURVIVES AND IS NOT REPURPOSED. It becomes what the tag RESOLVED to: the spec read
-- addresses `refs/tags/<version>` and the git host answers with a `Git-Commit-Sha` header, so one
-- request gets both the file and the commit it came from. That is a strictly better answer than the
-- column ever held before — it is the commit the deployed artifact was built from, recorded rather
-- than assumed — and it is what lets a reader walk from a container back to a diff.
--
-- IT IS NULLABLE NOW, and the null is a real answer rather than a gap: a repository that carries no
-- `.config/qits/deployments.yml` gets a 404 from the blob read, and a 404 says nothing about which
-- commit the tag points at. Such a deployment records its version and no commit, which is honest.
-- The other null is a spec read that failed outright.
--
-- NO BACKFILL, and version is left NULL on every existing row deliberately. A row written before
-- this file describes a deployment whose image really is tagged with a sha, and writing that sha
-- into `version` would make the rollback pins claim an image tag that exists under a different
-- name. The readers coalesce instead — `PdDeployment.imageTag()` is the single spelling of "what
-- tag is this deployment's image", version first and commit_sha behind it — so historical rows keep
-- pinning and adopting exactly what they always did.
alter table pd_deployment add column version varchar(64);
alter table pd_deployment alter column commit_sha drop not null;

-- "Which deployments of this application carry this version" — the promotion question, and the one
-- a reader asks when a release is traced forward into the tiers it reached.
create index idx_pd_deployment_version on pd_deployment (application_name, version);
