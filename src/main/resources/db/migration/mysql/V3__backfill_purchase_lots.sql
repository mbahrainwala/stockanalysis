-- Holdings created before purchases were tracked get a single opening purchase with their totals.
INSERT INTO purchase_lot (holding_id, shares, price, purchased_on)
SELECT h.id, h.shares, COALESCE(h.average_cost, 0), CURRENT_DATE
FROM holding h
WHERE NOT EXISTS (SELECT 1 FROM purchase_lot l WHERE l.holding_id = h.id);
