-- THE SECOND RESOURCE TYPE: `resources: idp:client` PROVISIONS A QITS-IDP SERVICE CLIENT, NOT A
-- POSTGRES ROLE AND DATABASE.
--
-- `database_name` and `role_name` are NOT NULL today because every row so far is a postgres one and
-- both columns are facts a postgres row always has. An idp-client row has neither fact: qits-idp
-- keys a service client by its client id, not by a database or a role, so forcing a value into
-- either column would be a fact this component does not hold — the V1 rule for every column here,
-- applied to a row that simply has less to say.
alter table pd_resource alter column database_name drop not null;
alter table pd_resource alter column role_name drop not null;

-- `client_id` is qits-idp's own key for the client — the wire alias this component already derives
-- (`PdNetworks.alias`), so it names nothing new. It is what a rotate call is addressed by, and it is
-- read for every resource type: a postgres row simply leaves it null, the mirror of the two columns
-- above.
alter table pd_resource add column client_id varchar(128);

-- "Is this client id already claimed by a resource" — the idp analogue of the database index above,
-- read the same way `idx_pd_resource_database_name` is: not enforced here (two applications naming
-- the same client id is refused by the derivation, `PdNetworks.alias`, being one-to-one on
-- (application, environment, plane) before it ever reaches this table), but a lookup worth an index.
create index idx_pd_resource_client_id on pd_resource (client_id);

-- NO CHECK CONSTRAINT ON `resource_type`, V1's rule again: the vocabulary grows in code
-- (`idp-client`, beside `postgresql`) and no migration is needed to add a word. NO BACKFILL: every
-- row this schema already holds is a postgres row and every one of the columns above already has a
-- value for it — there is nothing to convert.
