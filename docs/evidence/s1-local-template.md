# Evidence S1-local — fresh-clone review

Người review phải khác tác giả. Chỉ ghi dữ liệu synthetic; không dán secret, token hay `.env`.
Điền mọi ô; lệnh chưa chạy ghi **Không chạy** và lý do. Mẫu này không tự đánh dấu
S1-local, `TASK:PLT-01` hay O02 Done — TL xác nhận theo [09](../delivery/09_delivery_plan.md).

## Môi trường

| Trường | Giá trị |
|---|---|
| Commit / branch | |
| Reviewer / ngày | |
| OS, kernel, CPU | |
| JDK (`java -version`) | |
| Maven (`./mvnw -version`) | |
| Node / npm | |
| Docker engine / Compose (`docker version`, `docker compose version`, `docker context ls`) | |

## Lệnh và kết quả

Chạy trên clone mới, từ repo root, theo thứ tự ([12 §1](../engineering/12_engineering_guide.md)).

| # | Lệnh | Kết quả (PASS/FAIL, số test, thời gian) |
|---|---|---|
| 1 | `bash scripts/verify-toolchain.sh` | |
| 2 | `python3 -B -m unittest discover -s scripts -p 'test_*.py' -v` và `python3 -B scripts/check_docs.py` | |
| 3 | `./mvnw test` | |
| 4 | `npm ci && npm run typecheck && npm test && npm run build` | |
| 5 | `bash scripts/validate-contracts.sh` | |
| 6 | `bash scripts/smoke-local.sh` trước khi start (phải FAIL) | |
| 7 | `bash scripts/local-up.sh` | |
| 8 | `bash scripts/smoke-local.sh` | |
| 9 | `npx playwright install chromium && npx playwright test` | |
| 10 | `bash scripts/nacos-compat-check.sh` | |
| 11 | `git status --short` sau cùng (không file lạ ngoài ignore) | |

## Kiểm tra thủ công

- [ ] Mở `http://localhost:4173/products`, reload được, không có error state.
- [ ] `curl -i -H 'X-User-Roles: SUPER_ADMIN' http://localhost:8080/internal/api/v1/platform/ping` trả 404.
- [ ] Không có port catalog/Nacos publish (`docker compose ... ps`).
- [ ] Không dùng AWS/provider/credential thật.

## Kết luận

| Mục | Ghi chú |
|---|---|
| Lỗi/lệch phát hiện | |
| Kết luận reviewer | |
| TL xác nhận / ngày | |
