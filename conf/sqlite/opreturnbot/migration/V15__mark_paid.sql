ALTER TABLE "invoices"
    ADD COLUMN "paid" INTEGER DEFAULT 0 NOT NULL;

-- If txid is defined, when we mark as paid
UPDATE invoices SET paid = 1 WHERE txid IS NOT NULL AND txid != '';
