-- TASK:CAT-01a follow-up: new SKU canonicalization; legacy identities remain unchanged.
-- A pre-existing case collision stops migration for an explicit data decision; no backfill.
CREATE UNIQUE INDEX product_variants_sku_key_ci ON product_variants (upper(sku COLLATE "C"));

CREATE FUNCTION enforce_new_variant_sku() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF (NEW.sku COLLATE "C") !~ '^[A-Z0-9][A-Z0-9._-]*$' THEN
    RAISE EXCEPTION 'new variant SKU must use canonical uppercase ASCII'
      USING ERRCODE='23514', CONSTRAINT='product_variants_sku_canonical';
  END IF;
  RETURN NEW;
END;
$$;
-- INSERT only: a CHECK would also reject mutable updates to legacy lowercase identities.
CREATE TRIGGER product_variants_sku_canonical_insert BEFORE INSERT ON product_variants
  FOR EACH ROW EXECUTE FUNCTION enforce_new_variant_sku();
