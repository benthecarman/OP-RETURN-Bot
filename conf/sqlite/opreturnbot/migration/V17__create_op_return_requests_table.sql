-- Step 1: Create op_return_requests table
CREATE TABLE "op_return_requests"
(
    "id"                 INTEGER PRIMARY KEY AUTOINCREMENT,
    "message_bytes"      BLOB                              NOT NULL,
    "no_twitter"         INTEGER                           NOT NULL,
    "fee_rate"           TEXT                              NOT NULL,
    "node_id"            TEXT,
    "telegram_id"        INTEGER,
    "nostr_key"          TEXT,
    "dvm_event"          TEXT,
    "time"               INTEGER NOT NULL,
    "transaction"        TEXT,
    "txid"               TEXT,
    "profit"             INTEGER,
    "chain_fee"          INTEGER,
    "vsize"              INTEGER,
    "closed"             INTEGER NOT NULL DEFAULT 0
);

-- Step 2: Create payments table
CREATE TABLE "payments"
(
    "r_hash"                 TEXT PRIMARY KEY NOT NULL,
    "op_return_request_id"   INTEGER                  NOT NULL,
    "invoice"                TEXT             NOT NULL,
    "paid"                   INTEGER DEFAULT 0        NOT NULL,
    FOREIGN KEY ("op_return_request_id") REFERENCES "op_return_requests" ("id") ON DELETE CASCADE ON UPDATE NO ACTION
);

-- Step 3: Create new nip5 table
CREATE TABLE "nip5_new"
(
    "op_return_request_id"   INTEGER                  NOT NULL,
    "name"       TEXT             NOT NULL,
    "public_key" TEXT             NOT NULL,
    constraint nip5_fk foreign key ("op_return_request_id") references op_return_requests ("id") ON DELETE CASCADE ON UPDATE NO ACTION
);

-- Step 4: Create temporary table to store r_hash to op_return_request_id mapping
-- with a unique identifier for each invoice
CREATE TEMPORARY TABLE hash_to_id_map (
    r_hash TEXT PRIMARY KEY,
    op_return_request_id INTEGER
);

-- Step 5: First create the ID mapping with a ROW_NUMBER() approach to ensure uniqueness
INSERT INTO hash_to_id_map (r_hash, op_return_request_id)
SELECT r_hash, ROW_NUMBER() OVER(ORDER BY time ASC, r_hash ASC) AS op_return_request_id
FROM invoices;

-- Step 6: Migrate data to op_return_requests using the mapping
INSERT INTO op_return_requests (
    id, message_bytes, no_twitter, fee_rate, node_id, telegram_id, nostr_key, dvm_event,
    "time", "transaction", txid, profit, chain_fee, vsize, closed
)
SELECT
    m.op_return_request_id, i.message_bytes, i.hash, i.fee_rate, i.node_id, i.telegram_id,
    i.nostr_key, i.dvm_event, i."time", i."transaction", i.txid, i.profit,
    i.chain_fee, i.vsize, i.closed
FROM invoices i
JOIN hash_to_id_map m ON i.r_hash = m.r_hash;

-- Step 7: Migrate data to payments using the mapping
INSERT INTO payments (r_hash, op_return_request_id, invoice, paid)
SELECT
    i.r_hash,
    m.op_return_request_id,
    i.invoice,
    i.paid
FROM invoices i
JOIN hash_to_id_map m ON i.r_hash = m.r_hash;

-- Step 8: Migrate data to nip5 using the mapping
INSERT INTO nip5_new (op_return_request_id, name, public_key)
SELECT
    m.op_return_request_id,
    n.name,
    n.public_key
FROM nip5 n
JOIN hash_to_id_map m ON n.r_hash = m.r_hash;

-- Step 9: Drop the old NIP5 table
DROP TABLE IF EXISTS nip5;
ALTER TABLE nip5_new RENAME TO nip5;

-- Step 10: Create indexes for new tables
CREATE UNIQUE INDEX op_return_requests_txid_index on op_return_requests (txid);
CREATE INDEX op_return_requests_closed_index on op_return_requests (closed);
CREATE INDEX op_return_requests_time_index on op_return_requests ("time");
CREATE UNIQUE INDEX payments_invoice_idx ON payments (invoice);
CREATE UNIQUE INDEX payments_op_return_request_id_idx ON payments (op_return_request_id);
CREATE INDEX payments_paid_idx ON payments (paid);

-- Step 11: Drop the old tables
DROP TABLE IF EXISTS invoices;
DROP TABLE IF EXISTS hash_to_id_map;
