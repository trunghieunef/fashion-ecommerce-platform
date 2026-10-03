# Contracts

`TASK:PLT-02` · REQ: ORD-01/07, XCT-02 · dependency: PLT-01 S1-local accepted.
Assignee: Codex (GPT-6); reviewer: chủ dự án. Parent task còn In progress.

## Phạm vi thực có

- `openapi/catalog.yaml`: operation đã implement duy nhất là `GET /api/v1/catalog/products`.
- `events/event-envelope.schema.json`: envelope Draft 2020-12 theo 03 §5.1:
  `version` là schema version, `correlation_id` là trace context. Timestamp UTC `Z`,
  UUID hợp lệ, sequence dương trong giới hạn PostgreSQL bigint.
- `fixtures/valid/event-envelope.json`: dữ liệu synthetic minh họa CATALOG_CHANGED.
  Chỉ validate envelope, chưa validate schema payload hoặc tuyên bố catalog đã phát event.
- `fixtures/invalid/envelope-cases.json`: các mutation và loại lỗi phải bị từ chối;
  test kiểm cả reason và field path, không chấp nhận một lỗi bất kỳ thay thế.

## Kiểm tra

Python 3.10+; cài tooling vào virtualenv của bạn:

```bash
python3 -m venv /tmp/fashion-contracts-venv
source /tmp/fashion-contracts-venv/bin/activate
python3 -m pip install -r tests/contracts/requirements.txt
python3 -B -m unittest discover -s tests/contracts -p 'test_*.py' -v
```

Kiểm cả OpenAPI và event envelope (cần Node/npm đúng `.tool-versions` và `npm ci`):

```bash
bash scripts/validate-contracts.sh
```

Application CI cài requirements và gọi cùng script. Python docs checker vẫn chỉ cần
stdlib. Thư viện `jsonschema` và dependencies được khóa theo bộ cài đã kiểm tra local;
không có dependency mới trong runtime Java/React. Không có network `$ref` khi validate.

Trên Windows, dùng virtualenv trong thư mục scratch bạn chọn; chạy Python bằng đường
dẫn `<venv>/Scripts/python.exe` thay cho activation nếu PowerShell chặn script.

## Phần còn lại

OpenAPI core ngoài catalog, schemas từng event/payload (gồm VND integer), fixtures,
mock, producer/consumer review và Mermaid preview còn thiếu. Test envelope không chứng
minh dedupe, thứ tự Kafka, transaction hoặc recovery; những bằng chứng đó thuộc PLT-03.
PLT-03 implementation chờ contract review và chạy PostgreSQL/Kafka thật; chưa tạo
module/migration cho primitive chưa kiểm thử.
