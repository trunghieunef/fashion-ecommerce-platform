-- TASK:CAT-02a: accent-insensitive public search (spec §3); index deferred until measured load.
CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE FUNCTION f_unaccent(text) RETURNS text
  LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
  AS $$ SELECT public.unaccent('public.unaccent'::regdictionary, $1) $$;
