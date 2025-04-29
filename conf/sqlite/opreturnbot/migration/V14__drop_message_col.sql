-- Create a new table without the message column
CREATE TABLE "invoices_new" (
  "r_hash" TEXT PRIMARY KEY NOT NULL,
  "invoice" TEXT NOT NULL,
  "hash" INTEGER NOT NULL,
  "fee_rate" TEXT NOT NULL,
  "transaction" TEXT,
  "txid" TEXT,
  "profit" INTEGER,
  "chain_fee" INTEGER,
  "node_id" TEXT,
  "closed" INTEGER NOT NULL DEFAULT 0,
  "telegram_id" INTEGER,
  "nostr_key" TEXT,
  "dvm_event" TEXT,
  "time" INTEGER DEFAULT 0,
  "message_bytes" BLOB NOT NULL DEFAULT X''
);

-- Copy data from old table to new table, excluding the message column
INSERT INTO invoices_new
  (r_hash, invoice, hash, fee_rate, "transaction", txid, profit, chain_fee,
   node_id, closed, telegram_id, nostr_key, dvm_event, time, message_bytes)
SELECT
  r_hash, invoice, hash, fee_rate, "transaction", txid, profit, chain_fee,
  node_id, closed, telegram_id, nostr_key, dvm_event, time, message_bytes
FROM invoices;

-- Drop the old table
DROP TABLE invoices;

-- Rename the new table to the original table name
ALTER TABLE invoices_new RENAME TO invoices;