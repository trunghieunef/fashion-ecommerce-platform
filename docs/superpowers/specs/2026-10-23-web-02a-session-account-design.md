# WEB-02a — Storefront: phiên đăng nhập, tài khoản và địa chỉ

`TASK:WEB-02` (phần a) · `REQ:USR-05/06/07` · dependency WEB-01 (+ WEB-01b giao diện), USR-01, USR-02 (Done local).
Phần b (giỏ hàng, gộp giỏ): [WEB-02b](2026-10-23-web-02b-cart-merge-design.md), làm sau phần a.
Trạng thái: **đã duyệt** 2026-10-23 (chủ dự án). Chủ dự án chọn tách WEB-02 thành a/b (2026-10-23); theo quy ước làm việc, agent tự chốt các
mặc định ở §8, chủ dự án duyệt hoặc sửa trước khi viết plan.

## 1. Mục tiêu

SF-04 trên storefront (giao diện WEB-01b): đăng ký, đăng nhập, đăng xuất, quên/đặt lại mật khẩu, xem/sửa hồ sơ, đổi mật khẩu,
quản lý địa chỉ (có mặc định). Phần a cũng tạo nền phiên đăng nhập (`authFetch`) mà WEB-02b dùng cho giỏ member và gộp giỏ.

Acceptance (10 dòng WEB-02, 14 SF-04): access token chỉ trong bộ nhớ; refresh single-flight (cả nhiều tab); validation và lỗi
gắn với field; forgot không tiết lộ tài khoản có tồn tại.

Không làm: giỏ hàng và gộp giỏ (WEB-02b), checkout (WEB-03), lịch sử đơn (WEB-04), OTP/social login/verify email (Phase 2),
danh mục địa chỉ thật (O04), admin. Không đổi contract hay backend.

Anti-goals: lưu access token trong `localStorage`/`sessionStorage`/cookie đọc được bằng JS; hai request refresh song song;
thêm thư viện (form, state, auth, query).

## 2. Phiên đăng nhập

- **Access token** (JWT 900 s) chỉ nằm trong biến module `session` (kèm `expiresAt`), không ghi vào storage. Mất khi reload.
- **Khởi động app**: gọi `POST /api/v1/auth/refresh` một lần (cookie `refresh_token` HttpOnly, `Path=/api/v1/auth`). 200 →
  đã đăng nhập (sau đó `GET /users/me`); 401/403 → khách (không báo lỗi). Trong lúc chờ, header hiện trạng thái trung tính
  (không nhấp nháy "Đăng nhập" rồi đổi).
- **Single-flight**: mọi nhu cầu refresh đi qua `refreshAccessToken()`:
  - trong một tab: dùng chung một Promise đang chạy;
  - giữa các tab: bọc trong `navigator.locks.request('storefront.auth.refresh', …)` (Web Locks API). Tab nhận khóa sau
    gửi cookie đã được tab trước xoay, nên không bao giờ dùng lại token cũ (dùng lại → server thu hồi cả family).
    Trình duyệt không có Web Locks → chỉ single-flight trong tab (ghi rõ giới hạn).
- **Gọi API cần Bearer**: `authFetch` gắn `Authorization`; token còn < 30 s thì refresh trước; nhận 401 → refresh một lần rồi
  thử lại đúng một lần. Refresh thất bại → chuyển về trạng thái khách, route cần đăng nhập chuyển tới `/login?next=<path>`.
- **Đăng xuất**: `POST /auth/logout` (204, idempotent), xóa token, phát `logout` qua `BroadcastChannel('storefront.auth')`
  để tab khác cũng xóa token. Đăng nhập ở một tab phát `login` để tab khác tự refresh.
- Đổi mật khẩu / đặt lại mật khẩu: server thu hồi mọi phiên và xóa cookie → client xóa token, chuyển `/login` kèm thông báo.
- CSRF: request ghi có cookie đi cùng origin nên trình duyệt tự gửi `Origin`; Gateway kiểm (ADR-20). Client không thêm gì.
- Sự kiện cho WEB-02b: `AuthProvider` cung cấp `status` (`'unknown' | 'guest' | 'member'`) và callback "vừa đăng nhập"
  (`onSignedIn`) để phần giỏ gắn việc gộp giỏ vào, không cần sửa lại phần a.

## 3. Màn hình và route

Giao diện, token và component theo WEB-01b (`Drawer`, `LABEL`, `BUTTON`, …). Mọi form: label hiển thị, lỗi ngay dưới field
(`aria-describedby`), không mất dữ liệu đã nhập khi lỗi.

| Route | Màn | API |
|---|---|---|
| `/login?next=` | Email, mật khẩu; link đăng ký, quên mật khẩu | `POST /auth/login` |
| `/register` | Họ tên, email, mật khẩu (8 ký tự – 72 byte), `locale` = ngôn ngữ đang dùng | `POST /auth/register` |
| `/forgot-password` | Email → luôn báo cùng một câu khi 202 | `POST /auth/password/forgot` |
| `/reset-password#token=…` | Đọc token từ fragment, xóa fragment khỏi URL ngay (`history.replaceState`), nhập mật khẩu mới | `POST /auth/password/reset` |
| `/account` (cần đăng nhập) | Hồ sơ (họ tên, ngôn ngữ nhận email), đổi mật khẩu, đăng xuất | `GET/PUT /users/me`, `POST /auth/password/change` |
| `/account/addresses` (cần đăng nhập) | Danh sách, thêm/sửa trong Drawer, xóa (xác nhận), đặt mặc định | `/users/me/addresses` |

- Header (WEB-01b): thêm nút tài khoản (khách → `/login`; member → `/account`; lúc `unknown` → nút trung tính không nhãn
  trạng thái). Menu mobile thêm mục tương ứng. `RequireAuth` bọc `/account*`: `unknown` → chờ, `guest` → `/login?next=`.
- Lỗi chung: 429 → "Thử lại sau ít phút"; 503/mạng → thông báo + thử lại; 401 login → một câu chung "Email hoặc mật khẩu
  không đúng" (server không phân biệt).

### 3.1 Nguyên tắc UX (tra bằng skill ui-ux-pro-max, `--domain ux`)

| Khuyến nghị | Áp dụng |
|---|---|
| Accessible Authentication (Critical): cho phép trình quản lý mật khẩu và dán, không bắt chép mã | Không chặn paste; `autocomplete` đúng: `email`, `current-password`, `new-password`, `name`, `tel`, `street-address`; form login/register là `<form>` thật để trình duyệt nhận diện |
| Password Visibility | Nút hiện/ẩn mật khẩu (`aria-pressed`, nhãn "Hiện mật khẩu"/"Ẩn mật khẩu") ở mọi ô mật khẩu |
| Inline Validation: kiểm khi rời ô, không chỉ khi submit | Lỗi từng field hiện khi blur và khi submit; không hiện lỗi khi người dùng còn đang gõ lần đầu |
| Focusable Error Summary (High) | Submit lỗi: hộp tóm tắt đầu form (`role="alert"`, nhận focus), mỗi mục là link tới field lỗi; lỗi inline vẫn giữ |
| Input Types / Mobile Keyboards | `type="email"`, `type="tel"` |
| Input Labels | Label luôn hiển thị phía trên ô; placeholder chỉ là ví dụ |
| Submit Feedback (High) | Nút submit hiện trạng thái đang gửi (vẫn giữ nhãn cho screen reader), sau đó báo thành công hoặc lỗi |
| Confirmation Dialogs (High) | Xác nhận trước khi xóa địa chỉ (Drawer có nút hủy được focus mặc định) |
| Confirmation Messages / Toast | Lưu hồ sơ, đổi mật khẩu, lưu địa chỉ: thông báo ngắn tự ẩn sau 5 s (`role="status"`) |
| Focus Not Obscured | `html { scroll-padding-top }` bằng chiều cao header dính, để ô đang focus không bị header che (áp dụng cả màn WEB-01b) |
| Back Button | Đăng nhập thành công dùng `navigate(next, { replace: true })`; `next` chỉ nhận đường dẫn nội bộ bắt đầu bằng `/` (chống open redirect) |

Kết quả `--domain product` cho thương mại điện tử gợi ý style "Vibrant & Block-based"; không áp dụng vì mâu thuẫn với style
đã chốt ở WEB-01b.

## 4. Địa chỉ

- Form: người nhận, điện thoại (`^(0|\+84)\d{9}$`), tỉnh/thành, phường/xã, địa chỉ chi tiết, "đặt làm mặc định".
- Tỉnh/phường chọn từ **danh mục mẫu** `src/account/geo.sample.json` (vài tỉnh/phường, gồm mã `79`/`26734` smoke dùng), ghi
  rõ "danh mục mẫu" trong README; danh mục thật thay khi O04 chốt nguồn. Địa chỉ đã lưu có mã ngoài danh mục mẫu vẫn hiển
  thị mã thô, không lỗi.
- Luôn đúng một địa chỉ mặc định (user.yaml): xóa địa chỉ mặc định thì server tự chọn địa chỉ mới nhất còn lại làm mặc định;
  bỏ đánh dấu "mặc định" của địa chỉ đang mặc định (PUT) mà không chọn địa chỉ khác → 400 `DEFAULT_ADDRESS_REQUIRED`, hiện
  lý do trong form. (Sửa 2026-10-24 theo review: bản trước ghi nhầm là xóa địa chỉ mặc định trả 400.)

## 5. Cấu trúc code

```
src/http.ts                 fetch JSON envelope dùng chung (đọc code/errors, ApiError), có signal
src/auth/session.ts         token trong bộ nhớ, refreshAccessToken() single-flight (Promise + Web Locks), authFetch,
                            BroadcastChannel login/logout
src/auth/AuthProvider.tsx   context: status, me, login/register/logout, onSignedIn
src/auth/*Page.tsx          Login, Register, ForgotPassword, ResetPassword
src/auth/RequireAuth.tsx
src/ui/form.tsx             Field, PasswordField (hiện/ẩn), ErrorSummary, StatusMessage — dùng lại ở WEB-02b/03
src/account/                AccountPage, AddressesPage, AddressForm, geo.sample.json
```

`catalog/api.ts` giữ nguyên (catalog không cần auth). Route mới khai báo ở `App.tsx`. Không thêm dependency.

## 6. Kiểm thử

- **Vitest + harness** (mở rộng `mockFetch` cho `/api/v1/auth`, `/users`; fixture mới trong `src/test/fixtures/` được
  `test_storefront_fixtures.py` validate với `user.yaml`):
  - session: khởi động refresh 200 → member, 401 → guest; hai lời gọi đồng thời chỉ một `POST /auth/refresh`; 401 API →
    refresh một lần rồi retry; refresh 401 → guest + chuyển `/login?next=`; logout phát BroadcastChannel; khóa Web Locks
    gọi đúng tên (stub `navigator.locks`); token không xuất hiện trong `localStorage`/`sessionStorage`.
  - form: validation từng field khi blur/submit, error summary nhận focus và link tới field, lỗi server gắn đúng field (409
    email đã có, 400 field), forgot luôn cùng câu, reset đọc rồi xóa fragment, `next` ngoài site bị bỏ qua.
  - địa chỉ: thêm/sửa/xóa (xác nhận)/mặc định, mã ngoài danh mục mẫu vẫn hiển thị.
- **Playwright** (`tests/e2e/web-02.spec.ts`, desktop + 360px):
  - chuỗi thật (CI): đăng ký tài khoản synthetic → reload vẫn đăng nhập (refresh) → sửa hồ sơ → thêm địa chỉ → đăng xuất;
  - fixture (`page.route`, chạy local trên `vite preview`): lỗi form, hai tab refresh cùng lúc chỉ một request.
- `npm run typecheck`, `npm test`, `npm run build`, contract, docs check.

## 7. Tài liệu

`web/storefront/README.md` (route, phiên, tài khoản), 14 SF-04, 10 (WEB-02 a/b), 08 (mục quyết định §8), evidence
`docs/evidence/web-02a-local-<ngày>.md`.

## 8. Quyết định agent tự chốt (cần chủ dự án duyệt)

1. Khôi phục phiên bằng một lần refresh khi tải trang; access token chỉ trong bộ nhớ.
2. Single-flight refresh giữa các tab bằng Web Locks API (+ Promise trong tab); BroadcastChannel đồng bộ đăng nhập/đăng xuất.
3. Danh mục tỉnh/phường mẫu trong frontend cho tới khi O04 chốt nguồn.
4. Ngôn ngữ hồ sơ (`locale`) chỉ dùng cho email; ngôn ngữ giao diện vẫn theo lựa chọn của người xem.
5. `next` sau đăng nhập chỉ chấp nhận đường dẫn nội bộ.
6. Không thêm thư viện; dùng fetch, React context, Web Locks/BroadcastChannel có sẵn trong trình duyệt.
