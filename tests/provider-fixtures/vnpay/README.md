# Fixture VNPay (synthetic)

TmnCode `TESTTMN1`, secret `TEST-HASH-SECRET`: giá trị giả, không phải secret thật. `canonical`/`data` viết tay theo
tài liệu VNPay 2.1.0; `secure_hash` tính độc lập bằng openssl (không từ code Java):

```bash
printf '%s' "$CANONICAL_OR_DATA" | openssl dgst -sha512 -hmac TEST-HASH-SECRET
```

- `create-link.json`, `ipn-success.json`: canonical = `vnp_*` khác rỗng, bỏ `vnp_SecureHash(Type)`, sắp tên tăng dần, value URL-encode (space -> `+`, `:` -> `%3A`).
- `querydr-request.json`: data = `RequestId|Version|Command|TmnCode|TxnRef|TransactionDate|CreateDate|IpAddr|OrderInfo`.
- `querydr-response-success.json`: data = 15 trường theo thứ tự VNPay; `PromotionCode`/`PromotionAmount` thiếu = rỗng.
