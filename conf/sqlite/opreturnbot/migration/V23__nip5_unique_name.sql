-- Prevent the same NIP-05 name from being sold twice.
-- Step 1: remove duplicate reservations, keeping the earliest paid row per
-- (case-insensitive) name, or the earliest unpaid row when none are paid.
DELETE FROM nip5
WHERE op_return_request_id NOT IN (
    SELECT keep_id
    FROM (
        SELECT n.op_return_request_id AS keep_id,
               ROW_NUMBER() OVER (
                   PARTITION BY lower(n.name)
                   ORDER BY (r.txid IS NOT NULL) DESC, n.op_return_request_id ASC
               ) AS rn
        FROM nip5 n
        JOIN op_return_requests r ON r.id = n.op_return_request_id
    )
    WHERE rn = 1
);

-- Step 2: enforce case-insensitive uniqueness at the database level
CREATE UNIQUE INDEX nip5_name_unique_idx ON nip5 (lower(name));
