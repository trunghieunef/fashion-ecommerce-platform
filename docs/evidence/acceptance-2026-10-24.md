# Nghiệm thu task local — 2026-10-24

## TASK:WEB-02 phần a — phiên đăng nhập, tài khoản, địa chỉ

Chủ dự án yêu cầu Claude Code nghiệm thu **WEB-02a** sau khi quyết định merge PR #38 khi CI xanh. **Không độc lập:**
WEB-02a do Claude Code viết (8 task inline, TDD); review toàn nhánh bằng một subagent (0 Critical / 5 Important + 2 Minor
nâng lên Important, sửa ở `cc6311e`); review của chủ dự án trên PR #38 (head `cc6311e`): 5 lỗi chặn merge, đều kiểm lại
đúng và sửa ở `2463881`; 4 follow-up nhỏ để lại. Phạm vi: phần a của dòng WEB-02 ở [10](../delivery/10_backlog.md)
(REQ USR-05/06/07) và SF-04 ở [14](../design/14_frontend_behavior.md) theo
[spec đã duyệt](../superpowers/specs/2026-10-23-web-02a-session-account-design.md), mức local trước G0. Chỉ frontend:
contract, service, dependency không đổi. **`TASK:WEB-02` chưa Done** cho tới khi phần b (giỏ hàng, gộp giỏ) Done.

### Bằng chứng chung

- CI PR #38 trên `2463881` SUCCESS: `s1-local` ([run 40065777974](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/40065777974); verify toolchain/lockfile, backend reactor, frontend
  typecheck/test/build, contract, local stack + smoke, **browser smoke** gồm `web-02.spec.ts` chuỗi thật trên
  `http://localhost:4173` ở desktop và 360px) và `docs-check`.
- Merge: chủ dự án merge PR #38 vào `main` tại `227bad9` (2026-10-24). Documentation CI trên `227bad9` SUCCESS; Application
  CI trên `main` SUCCESS ([run 40071567589](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/40071567589)).
- Local (Windows, 7.4 GB RAM): typecheck, **122 test** Vitest (66 mới), build, contract 95 OK, docs check; Playwright test
  fixture 6/6 (WEB-01 + WEB-02) trên `vite preview`; phản chứng: tắt Web Locks → test hai tab FAIL. Stack thật không chạy
  được trên máy này; bằng chứng chuỗi thật là CI `s1-local`. Chi tiết: [evidence WEB-02a](web-02a-local-2026-10-24.md).

### Kết quả theo tiêu chí

| Tiêu chí (10 WEB-02 phần a / 14 SF-04 / spec) | Bằng chứng | Kết quả |
|---|---|---|
| Access token chỉ trong bộ nhớ; khôi phục phiên bằng một lần refresh | `session.test.ts` (13): `the access token never reaches web storage`, `two concurrent refreshes send one request`; `AuthProvider.test.tsx`: `boot refresh 200 makes the visitor a member` | Pass |
| Refresh single-flight cả nhiều tab (refresh token dùng một lần) | `serialises refreshes across tabs through one Web Lock`; e2e fixture hai tab không chồng request, có phản chứng | Pass (trình duyệt không có Web Locks: chỉ trong tab) |
| 401 → refresh một lần, thử lại một lần; refresh muộn không hồi sinh/không đá phiên mới | `authRequest refreshes once after a 401 and retries once`, `…gives up after a second 401`, `a refresh that resolves after endSession…`, `a refresh superseded by a sign-in…`, `a boot refresh answered after a sign-in…` | Pass |
| Đồng bộ đăng nhập/đăng xuất giữa tab; đổi tài khoản ở tab khác tải lại hồ sơ | `a logout broadcast from another tab signs this tab out`, `a sign-in in another tab reloads the profile…`, `a successful reset tells the other tabs to sign out` | Pass |
| Validation và lỗi gắn field; tóm tắt lỗi nhận focus; không chặn paste; nút submit bận | `LoginPage.test.tsx` (11), `PasswordPages.test.tsx` (7), `AccountPage.test.tsx` (7); e2e fixture câu lỗi đăng nhập trong hộp tóm tắt đã focus | Pass (chưa đo axe) |
| Forgot không tiết lộ tài khoản; reset đọc token từ fragment rồi xóa; link thiếu token → trạng thái không hợp lệ | `forgot shows the same sentence for every accepted email`, `reset reads the token from the fragment and removes it from the URL`, `an empty or missing reset token…` | Pass |
| `next` sau đăng nhập chỉ nội bộ (chống open redirect) | `ignores an external next` (5 biến thể gồm `/\`, tab, newline) | Pass |
| Đăng xuất chỉ khi server xóa cookie; rời phiên có chủ đích dùng điểm đến một lần | `a failed sign out keeps the member signed in and says so`, `sign out returns home as a guest`, `a deliberate exit is used once…` | Pass |
| Chỉ 401 từ `/users/me` là đăng xuất | `a 503 from /users/me keeps a valid session signed in`, `a 401 from /users/me after the retry…` | Pass |
| Hồ sơ, đổi mật khẩu (đăng nhập lại), địa chỉ: thêm/sửa/xóa xác nhận/mặc định theo `user.yaml` | `AccountPage.test.tsx`, `AddressesPage.test.tsx` (10) gồm `DEFAULT_ADDRESS_REQUIRED`, `district_code` khi đổi tỉnh | Pass (danh mục tỉnh/phường mẫu, O04) |
| Chuỗi thật: đăng ký → reload vẫn đăng nhập → hồ sơ → địa chỉ → đăng xuất | e2e `web-02.spec.ts` "real chain" trên CI ([run 40065777974](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/40065777974), Browser smoke) | Pass |
| Docs đồng bộ (README, 14, 08, 10, spec §4, evidence) | Commit `fdbcb23`, `cc6311e`, `2463881` | Pass |

**WEB-02a: Done (local, trước G0)**; CI trên `2463881` PASS; chủ dự án đã sửa review và merge PR #38 vào `main`
(`227bad9`, 2026-10-24). `TASK:WEB-02`: In progress (còn phần b).

### Ngoại lệ và việc còn lại (không chặn Done local của phần a)

- Follow-up review PR #38: `StatusMessage` không đọc lại câu trùng; ngoài secure context không có Web Locks; `AccountPage`
  gọi `/users/me` lần hai, lưu hồ sơ không cập nhật `me`; `http.ts` lặp logic `catalog/api.ts`.
- Minor từ review toàn nhánh: tin `login` lúc refresh cũ còn chạy nhập chung request; mọi 400 khi reset hiện "liên kết
  không hợp lệ"; **bọc callback `onSignedIn` trước WEB-02b** (lỗi callback làm `signIn` báo lỗi); select ngôn ngữ email chưa
  dùng `SelectField`; `CLAUDE.md` còn ghi storefront "chưa có giỏ/login".
- Local phải mở storefront bằng `http://localhost:4173` (allowlist Origin ADR-20); `127.0.0.1` làm refresh 403.
- Danh mục tỉnh/phường là mẫu (O04); chưa đo accessibility tự động (axe).
