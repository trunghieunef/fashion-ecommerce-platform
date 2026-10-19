-- Review PR #29 #3: quotes record (rule_id, rule_version); version 0 of the V002 sample rules had no audit row, so the
-- config that priced such a quote could not be rebuilt once OPS edits the rule. Same snapshot shape as RuleRepository.audit.
INSERT INTO shipping_rule_changes (id, rule_id, version, actor_id, reason, snapshot)
SELECT gen_random_uuid(), r.id, 0, NULL, 'V002 sample rule (mẫu)',
       jsonb_build_object('id', r.id, 'name', r.name, 'rule_type', r.rule_type, 'priority', r.priority, 'config', r.config_json,
                          'active', r.active, 'version', r.version, 'updated_at', r.updated_at)
FROM shipping_rules r
WHERE r.id IN ('5b0e7c1a-0000-4000-8000-000000000001', '5b0e7c1a-0000-4000-8000-000000000002', '5b0e7c1a-0000-4000-8000-000000000003')
  AND r.version = 0
ON CONFLICT (rule_id, version) DO NOTHING;
