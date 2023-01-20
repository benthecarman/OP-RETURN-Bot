CREATE TABLE nip5
(
    r_hash     TEXT PRIMARY KEY NOT NULL,
    name       TEXT             NOT NULL,
    public_key TEXT             NOT NULL,
    constraint nip5_fk foreign key (r_hash) references invoices (r_hash) on update NO ACTION on delete NO ACTION
);

CREATE INDEX nip5_name_idx ON nip5 (name);
