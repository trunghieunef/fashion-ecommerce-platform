# Nghiệm thu task local — 2026-10-22

## TASK:NOT-01 — email giao dịch VI/EN

Chủ dự án yêu cầu Claude Code nghiệm thu `TASK:NOT-01` sau khi re-review trên PR #36 đồng ý merge. **Không độc lập:** NOT-01
do Claude Code viết (5 task, mỗi task một subagent reviewer; review toàn nhánh trên Sonnet: 0 Critical / 3 Important, sửa ở
`26a447e`/`a51909c`). Review trên PR #36 (`39ac31f..a51909c`): 0 Critical, 0 Important, 5 Minor, "có thể merge"; sửa ở `9db3196`
(M2 giữ có chủ ý, ghi đánh đổi). Re-review trên `9db3196`: đủ 5 mục, "đồng ý merge". Merge do chủ dự án. Phạm vi: acceptance ở
[10](../delivery/10_backlog.md) dòng NOT-01, REQ NOT-01/05 (seed)/06 ở 02 theo
[spec đã duyệt](../superpowers/specs/2026-10-22-not-01-transactional-email-design.md), mức local trước G0. O07 (provider thật)
chưa chốt: theo 15, task mock/mail sink được hoàn tất trước; gửi qua provider thật là việc sau O07.

### Bằng chứng chung

- CI PR #36 trên `9db3196` SUCCESS: `s1-local`
  ([run 39725489472](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/39725489472/job/119648310016),
  gồm backend reactor, contract, local stack + smoke NOT-01) và `docs-check`.
- Local: `./mvnw -B verify` toàn reactor trên `a51909c` BUILD SUCCESS 1004 test; sau sửa review PR
  `./mvnw -B -pl services/notification-service -am verify` (notification 35: Intake 18, Send 11, ResetPassword 6); contract 95
  test (docker Python 3.13); `local-up` + `smoke-local` → `NOT-01 order emails PASS`, `NOT-01 reset email PASS` (trước
  `26a447e`; sau đó bằng chứng smoke là CI `s1-local`). Mutation 4/4 bị test bắt. Chi tiết: [evidence NOT-01](not-01-local-2026-10-22.md).

### Kết quả theo tiêu chí

| Tiêu chí (10 NOT-01 / 02 REQ / spec) | Bằng chứng | Kết quả |
|---|---|---|
| Email giao dịch chỉ từ `notification.events`; mỗi sự việc tối đa một email (REQ NOT-01, ADR-09) | `eachTransitionWritesOneNotifyEvent`, `noEmailNoOnlineNoEvent`, `theSameEventTwiceOrTheSameDedupeKeyIsOneRow`; mutation bỏ UNIQUE dedupe bị bắt; smoke "đúng một email" mỗi đơn | Pass |
| Snapshot người nhận/template, retry lỗi chắc chắn tối đa 5 (REQ NOT-01) | `anOrderEventBecomesOnePendingEmail`, `aTemporaryRefusalRetriesThenFails`, `aFailureBeforeMailFromIsRetried`, `aPermanentRefusalFailsAtOnce`, `aRecipientThatIsNotOneAddressFailsWithoutSending`, `aBadRecipientIsPermanent` | Pass |
| Template seed VI/EN, fallback vi, escape (REQ NOT-05 seed) | V002 12 dòng; `englishFallsBackToVietnameseWhenMissing`, `renderedDataIsEscaped`, `cancelledShowsTheFixedSentence` | Pass (editor NOT-05 P1 ngoài phạm vi) |
| Lease/`SENDING` recovery; `UNKNOWN` khi không xác minh; `SENT` = server nhận (REQ NOT-06, T22/U20) | `aSendingRowWithAnExpiredLeaseBecomesUnknown`, `silenceAfterDataIsUnknown`, `twoWorkersSendEachEmailOnce`, `aPendingEmailIsSentOnce`; mutation "timeout → SENT", "gửi lại SENDING hết lease" bị bắt | Pass (SMTP không có tra cứu: không query provider, đúng 06) |
| Bàn giao secret reset; secret hết hạn không gửi; không lưu/log token | `aResetEmailCarriesTheLinkOnlyAtSendTime`, `anExpiredResetIsNeverSent`, `theResetTokenIsNeverStored`, `aUserOutageRetries`, `aBodyWithoutTheMarkerFailsWithoutCallingUser`; mutation lưu token bị bắt; smoke đổi mật khẩu bằng link trong email thật | Pass |
| Nội dung chủ dự án chọn: 6 email, kiểu B, câu hủy cố định, không lộ ghi chú OPS, phone che, link không token, email 2 ở `CONFIRMED → PACKING` | `everyCancelPathSendsOneFixedReason`, `confirmedCarriesMaskedShipToOnly`, `aShortPhoneIsHiddenAndNeverBlocksTheMove`; smoke email hủy "Bạn đã hủy đơn." | Pass |
| Email không làm hỏng đơn; không HTTP/SMTP trong DB TX | `aShortPhoneIsHiddenAndNeverBlocksTheMove`; review toàn nhánh và PR (claim một dòng, gửi ngoài TX) | Pass |
| PII/log: không email người nhận, nội dung, token trong log | `logsCarryNoRecipientOrBody`, `theResetTokenIsNeverStored`; `last_error` chỉ tên class | Pass |

**`TASK:NOT-01`: Done (local, trước G0)**; CI trên `9db3196` PASS, re-review PR #36 "đồng ý merge"; chủ dự án quyết định merge.

### Ngoại lệ và việc còn lại (không chặn Done local)

- O07: provider/domain thật, phần plain-text; tên shop, hotline, địa chỉ gửi đang là giá trị mẫu.
- `notification.results` (backlog ghi "leases/results"): hoãn có chủ ý vì chưa có consumer (spec §5.6, NOT-02); chưa xóa secret sau gửi.
- PLT-04: staging phải tạo `notification.events`, `user.events` + DLT (thiếu topic thì event đơn bị chặn sau event email cùng đơn).
- O06: chủ dự án xác nhận PII trên `notification.events`/DLT và retention topic trước staging; job dọn 90 ngày chưa làm.
- `UNKNOWN`/`FAILED` chưa alert hay gửi lại có audit (ADM-02/PLT-05) — ưu tiên alert `notification.unknown` vì rớt ở RCPT không tự gửi lại (M2).
- Email đơn online (PAY-02/03); trang `/orders/{order_no}`, `/reset-password` (WEB-02/04).
