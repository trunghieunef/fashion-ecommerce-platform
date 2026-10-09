-- TASK:CAT-03; spec section 5 lookbook has captions only. V005 added alt columns by mistake; append-only fix.
ALTER TABLE lookbook_images DROP COLUMN alt_vi, DROP COLUMN alt_en;
