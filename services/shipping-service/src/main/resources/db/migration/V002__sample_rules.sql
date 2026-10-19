-- TASK:SHP-01: SAMPLE fee table until O04 is decided (labelled "(mẫu)"); OPS replaces it through the rules API.
-- Province codes follow user_addresses.province_code: '01' Hà Nội, '79' TP. Hồ Chí Minh (sample).
INSERT INTO shipping_rules (id, name, rule_type, priority, config_json) VALUES
  ('5b0e7c1a-0000-4000-8000-000000000001', 'Nội thành HN/HCM dưới 3 kg (mẫu)', 'WEIGHT', 10,
   '{"provinces":["01","79"],"tiers":[{"max_weight_grams":3000,"fee":25000},{"max_weight_grams":null,"fee":40000}],"cod_fee":10000}'),
  ('5b0e7c1a-0000-4000-8000-000000000002', 'Toàn quốc theo cân nặng (mẫu)', 'WEIGHT', 50,
   '{"tiers":[{"max_weight_grams":1000,"fee":30000},{"max_weight_grams":3000,"fee":45000},{"max_weight_grams":null,"fee":70000}],"cod_fee":15000}'),
  ('5b0e7c1a-0000-4000-8000-000000000003', 'Mặc định (mẫu)', 'FLAT', 1000, '{"fee":35000,"cod_fee":15000}');
