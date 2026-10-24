# WEB-02a — Session, account, addresses Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Storefront có phiên đăng nhập (access token trong bộ nhớ, refresh single-flight giữa các tab), các màn đăng nhập,
đăng ký, quên/đặt lại mật khẩu, tài khoản và địa chỉ, theo giao diện WEB-01b.

**Architecture:** `src/http.ts` đọc envelope JSON chung; `src/auth/session.ts` giữ token trong biến module, gom mọi refresh
vào một Promise (trong tab) bọc Web Locks (giữa tab) và cung cấp `authRequest` (Bearer + retry một lần sau 401);
`AuthProvider` khôi phục phiên khi tải trang và phát `onSignedIn` cho WEB-02b. Màn hình dùng thành phần form chung
`src/ui/form.tsx`.

**Tech Stack:** React 19.2.8, React Router 8.4.0, Tailwind 4.3.3, Vitest 5 + jsdom (harness tự viết), Playwright 1.63.
Web Locks API, BroadcastChannel, `crypto` có sẵn trong trình duyệt.

**Spec:** [WEB-02a](../specs/2026-10-23-web-02a-session-account-design.md) (đã duyệt 2026-10-23). Phần b:
[WEB-02b](../specs/2026-10-23-web-02b-cart-merge-design.md) — plan riêng, viết sau.

## Global Constraints

- Không thêm dependency. Không đổi `contracts/` hay service; fixture mới phải qua `tests/contracts/test_storefront_fixtures.py`.
- Access token chỉ ở biến module `session.ts`; không bao giờ ghi `localStorage`/`sessionStorage`/`document.cookie`.
- Lock name `storefront.auth.refresh`; BroadcastChannel name `storefront.auth`, message `{ type: 'login' | 'logout' }`.
- Refresh trước khi gọi nếu token còn **< 30 s**; sau 401 chỉ refresh **một lần** và thử lại **một lần**.
- Mật khẩu: tối thiểu 8 ký tự, tối đa **72 byte UTF-8**; email tối đa 254; họ tên 1–200; điện thoại `^(0|\+84)\d{9}$`;
  người nhận 1–100; địa chỉ chi tiết 1–255.
- `next` sau đăng nhập chỉ nhận chuỗi bắt đầu bằng `/` và không bắt đầu bằng `//`; ngoài ra dùng `/account`.
- Giao diện theo WEB-01b: token màu, `LABEL`/`BUTTON`/`BUTTON_OUTLINE`/`PAGE` từ `catalog/ui.tsx`, `Drawer`; vùng chạm ≥ 44px;
  mọi chuỗi có VI và EN trong `src/i18n.tsx`.
- Câu cố định (VI / EN):
  - đăng nhập sai: "Email hoặc mật khẩu không đúng." / "Incorrect email or password."
  - quên mật khẩu (mọi 202): "Nếu email này có tài khoản, chúng tôi đã gửi hướng dẫn đặt lại mật khẩu." /
    "If this email has an account, we have sent password reset instructions."
  - 429: "Bạn thử quá nhiều lần. Vui lòng thử lại sau ít phút." / "Too many attempts. Please try again in a few minutes."
  - email đã đăng ký (409): "Email này đã có tài khoản." / "This email already has an account."
  - phiên kết thúc sau đổi/đặt lại mật khẩu: "Mật khẩu đã đổi. Vui lòng đăng nhập lại." / "Password changed. Please sign in again."
- Mỗi file mới mở đầu bằng `// TASK:WEB-02a: …`. Ledger ở `.superpowers/sdd/2026-10-23-web-02a-session-account/progress.md`.
- Máy local 7.4 GB RAM: không dựng stack; e2e chuỗi thật để CI chạy, e2e fixture chạy trên `vite preview`.

## Review Focus

1. Hai tab cùng hết hạn token và cùng refresh → chỉ một `POST /auth/refresh` đi ra mỗi lúc; tab sau dùng cookie đã xoay
   → test `serialises refreshes across tabs through one Web Lock` (Task 2).
2. `next` là URL ngoài (`https://evil.example`, `//evil.example`) → sau đăng nhập về `/account`, không rời site
   → test `ignores an external next` (Task 4).
3. Mật khẩu tiếng Việt nhiều byte (30 ký tự `ệ` = 90 byte) → báo lỗi tại ô trước khi gửi, không gọi API
   → test `rejects a password over 72 UTF-8 bytes before sending` (Task 4).
4. Mở link đặt lại mật khẩu thiếu `#token=` hoặc token rỗng → báo liên kết không hợp lệ kèm link "Quên mật khẩu", không gửi
   → test `an empty or missing reset token shows the invalid-link state` (Task 5).
5. Đăng xuất ở tab khác trong lúc đang ở `/account` → tab này về khách và chuyển `/login?next=/account`
   → test `a logout broadcast from another tab signs this tab out` (Task 3).

---

## File map

| File | Trách nhiệm | Task |
|---|---|---|
| `src/http.ts` (+ `http.test.ts`) | `request<T>` envelope, `HttpError` | 1 |
| `src/test/harness.tsx`, `tests/contracts/test_storefront_fixtures.py`, `src/test/fixtures/{session,refresh,profile,addresses,address,forgot}.json` | mock auth/user, fixture có contract | 1 |
| `src/auth/session.ts` (+ test) | token, refresh single-flight, `authRequest`, broadcast | 2 |
| `src/auth/api.ts` | gọi API auth/user/address (typed) | 2 |
| `src/auth/AuthProvider.tsx`, `src/auth/RequireAuth.tsx` (+ test), `src/app/App.tsx`, `src/app/Header.tsx`, `src/app/styles.css` | context, bảo vệ route, nút tài khoản, `scroll-padding-top` | 3 |
| `src/ui/form.tsx`, `src/auth/validate.ts`, `src/auth/LoginPage.tsx`, `src/auth/RegisterPage.tsx` (+ test) | form chung, đăng nhập, đăng ký | 4 |
| `src/auth/ForgotPasswordPage.tsx`, `src/auth/ResetPasswordPage.tsx` (+ test) | quên/đặt lại mật khẩu | 5 |
| `src/account/AccountPage.tsx` (+ test) | hồ sơ, đổi mật khẩu, đăng xuất | 6 |
| `src/account/AddressesPage.tsx`, `src/account/AddressForm.tsx`, `src/account/geo.sample.json` (+ test) | địa chỉ | 7 |
| `tests/e2e/web-02.spec.ts`, docs, evidence | e2e + tài liệu | 8 |

(Đường dẫn `src/…` là `web/storefront/src/…`.)

---

### Task 1: `http.ts`, harness và fixture có contract

**Files:**
- Create: `src/http.ts`, `src/http.test.ts`, fixtures `session.json` (`SessionResponse`), `refresh.json` (`RefreshResponse`),
  `profile.json` (`ProfileResponse`), `addresses.json` (200 của `GET /api/v1/users/me/addresses`), `address.json`
  (`AddressResponse`), `forgot.json` (`ForgotAcceptedResponse`) — dữ liệu synthetic, email `@example.test`, địa chỉ mã
  `79`/`26734`.
- Modify: `src/test/harness.tsx`, `tests/contracts/test_storefront_fixtures.py`

**Interfaces:**
- Produces:
  - `class HttpError extends Error { readonly status: number; readonly code: string | null; readonly fields: { field: string; message: string }[] }`
    (status 0 = lỗi mạng).
  - `request<T>(path: string, init?: { method?: string; body?: unknown; signal?: AbortSignal; headers?: Record<string, string> }): Promise<T>`
    — `path` đầy đủ (`/api/v1/...`); body → JSON + `Content-Type: application/json`; trả `data` của envelope; 204 → `undefined`;
    non-2xx → `HttpError` với `code`/`errors` của `ApiError` (body không phải JSON → `code: null`); fetch reject khi signal
    đã abort thì ném lại nguyên lỗi abort.
  - Harness: `Call` thêm `method: string`, `auth: string | null` (header `Authorization`), `body: unknown` (JSON đã parse).
    Fallback mặc định thêm `POST /api/v1/auth/refresh` → 401 `UNAUTHORIZED` (mọi test cũ chạy với trạng thái khách).
    URL không thuộc `/api/v1/catalog` được truyền nguyên (`/api/v1/auth/...`) cho handler.
  - Contract test: `SCHEMAS` đổi sang giá trị `"<file>#<json-pointer>"` (ví dụ `"catalog.yaml#/components/schemas/ProductPageResponse"`,
    `"user.yaml#/paths/~1api~1v1~1users~1me~1addresses/get/responses/200/content/application~1json/schema"`), registry nạp
    thêm `user.yaml`.

- [ ] **Step 1: Test** `src/http.test.ts`:
  - `returns the envelope data`; `204 resolves to undefined`;
  - `a 400 carries code and field errors` (body `{code:'VALIDATION_ERROR', message:'x', errors:[{field:'email', message:'invalid'}], metadata}` → `fields[0].field === 'email'`);
  - `a non-JSON error body has a null code`; `a network failure is status 0`;
  - `sends JSON with a content type` (method/body/headers ghi nhận qua `mockFetch`).
- [ ] **Step 2:** `npx vitest run src/http.test.ts` → FAIL (module chưa có). Thêm 6 fixture và map vào contract test →
  `bash scripts/validate-contracts.sh` (venv) → test fixture FAIL cho tới khi map đúng, rồi PASS.
- [ ] **Step 3:** Implement `http.ts`, sửa harness như Interfaces.
- [ ] **Step 4:** `npm test` → 56 cũ + 6 mới PASS; `validate-contracts` PASS; `npm run typecheck` PASS.
- [ ] **Step 5: Commit** — `feat(WEB-02a): shared JSON request helper, auth-aware harness and contract-checked user fixtures`

### Task 2: Phiên đăng nhập (`session.ts`) và API auth/user

**Files:**
- Create: `src/auth/session.ts`, `src/auth/session.test.ts`, `src/auth/api.ts`

**Interfaces:**
- Consumes: `request`, `HttpError` (Task 1).
- Produces — `session.ts`:
  - `getAccessToken(): string | null`; `startSession(accessToken: string, expiresIn: number): void`; `endSession(): void`.
  - `refreshAccessToken(): Promise<boolean>` — single-flight: cùng một Promise cho mọi lời gọi đang chờ trong tab; thân gọi
    trong `navigator.locks.request('storefront.auth.refresh', …)` nếu có `navigator.locks`; 200 → `startSession`, `true`;
    401/403 → `endSession()`, `false`; lỗi mạng/5xx → ném `HttpError` (không đăng xuất vì lỗi tạm).
  - `authRequest<T>(path, init?)` — như `request` nhưng gắn `Authorization: Bearer`; chưa có token hoặc còn < 30 s → refresh
    trước; 401 → `refreshAccessToken()` một lần rồi thử lại một lần; refresh trả `false` → ném `HttpError(401)`.
  - `onSessionChange(listener: (signedIn: boolean) => void): () => void` — gọi khi `startSession`/`endSession` đổi trạng thái.
  - `broadcast(type: 'login' | 'logout'): void`; nhận `logout` từ tab khác → `endSession()`; nhận `login` → `refreshAccessToken()`.
  - `resetSessionForTests(): void`.
- Produces — `api.ts` (types `Session`, `Profile`, `Address`, `AddressInput` theo `user.yaml`):
  `login(email, password): Promise<Session>`, `register(input: { email; password; full_name; locale }): Promise<Session>`,
  `logout(): Promise<void>`, `forgotPassword(email): Promise<void>`, `resetPassword(token, newPassword): Promise<void>`,
  `changePassword(current, next): Promise<void>` (auth), `getMe(): Promise<Profile>`, `updateMe({ full_name, locale })`,
  `listAddresses()`, `createAddress(input)`, `updateAddress(id, input)`, `deleteAddress(id)` (auth).

- [ ] **Step 1: Test** `session.test.ts` (mockFetch; `navigator.locks`/`BroadcastChannel` stub trong test):
  - `two concurrent refreshes send one request` — gọi `refreshAccessToken()` hai lần cùng lúc → 1 call `/auth/refresh`, cả hai `true`.
  - `serialises refreshes across tabs through one Web Lock` (Review Focus 1) — stub `navigator.locks.request` ghi tên khóa và
    chạy callback tuần tự; assert tên `storefront.auth.refresh` và không có hai callback chồng nhau.
  - `refresh 401 ends the session and resolves false`; `a network error during refresh keeps the session state and throws`.
  - `authRequest refreshes once after a 401 and retries once` — call đầu 401, refresh 200, retry 200 → 3 call, header retry có token mới.
  - `authRequest gives up after a second 401` — không lặp vô hạn (tối đa 1 refresh + 1 retry).
  - `a token about to expire is refreshed before the call` (`startSession(t, 20)` → refresh trước).
  - `the access token never reaches web storage` — sau login/refresh, mọi giá trị trong `localStorage`/`sessionStorage` không chứa token.
  - `a logout message from another tab ends the session` (gửi message vào stub BroadcastChannel).
- [ ] **Step 2:** `npx vitest run src/auth/session.test.ts` → FAIL.
- [ ] **Step 3:** Implement `session.ts`, `api.ts`.
- [ ] **Step 4:** test file PASS; `npm test`, `npm run typecheck` PASS.
- [ ] **Step 5: Commit** — `feat(WEB-02a): in-memory session with cross-tab single-flight refresh`

### Task 3: `AuthProvider`, bảo vệ route, nút tài khoản

**Files:**
- Create: `src/auth/AuthProvider.tsx`, `src/auth/RequireAuth.tsx`, `src/auth/AuthProvider.test.tsx`
- Modify: `src/app/App.tsx` (AuthProvider bọc trong `AppRoutes` để harness không đổi; route mới), `src/app/Header.tsx`,
  `src/app/styles.css`, `src/i18n.tsx`

**Interfaces:**
- Consumes: session + api (Task 2).
- Produces: `useAuth(): { status: 'unknown' | 'guest' | 'member'; me: Profile | null; signIn(email, password): Promise<void>;
  register(input): Promise<void>; signOut(): Promise<void>; reloadMe(): Promise<void>; onSignedIn(cb: () => void): () => void }`.
  `signIn`/`register` gọi `startSession`, đặt `me` từ `Session.user` (nếu có, không thì `getMe`), `broadcast('login')`, rồi gọi
  mọi callback `onSignedIn`. `signOut` gọi `logout()` (lỗi vẫn xóa phiên local), `endSession`, `broadcast('logout')`.
  `<RequireAuth>{children}</RequireAuth>`: `unknown` → `Loading`; `guest` → `<Navigate to={'/login?next=' + encodeURIComponent(path)} replace />`.
  Routes: `/login`, `/register`, `/forgot-password`, `/reset-password`, `/account`, `/account/addresses` (hai route sau bọc
  `RequireAuth`; Task 3 tạo page tạm rỗng có `h1` để test, Task 4–7 thay).
- Header: nút tài khoản (`aria-label` "Tài khoản" / "Đăng nhập" theo trạng thái; `unknown` → nút không nhãn trạng thái,
  `aria-busy`), menu mobile thêm mục tương ứng. `styles.css`: `html { scroll-padding-top: 4rem; }` (chiều cao header).

- [ ] **Step 1: Test** `AuthProvider.test.tsx` (qua `render()` của harness):
  - `boot refresh 200 makes the visitor a member` (refresh + `/users/me` → nút "Tài khoản" trỏ `/account`).
  - `boot refresh 401 makes the visitor a guest without an error` (nút "Đăng nhập" trỏ `/login`, không có text lỗi).
  - `RequireAuth sends a guest to login with next` — `/account` khi khách → URL `/login?next=%2Faccount` (đọc `h1` trang login tạm).
  - `a logout broadcast from another tab signs this tab out` (Review Focus 5) — member ở `/account`, phát `logout` → về `/login?next=%2Faccount`.
  - `onSignedIn callbacks run after signIn`.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** test + `npm test` PASS (test cũ vẫn xanh nhờ fallback refresh 401).
- [ ] **Step 5: Commit** — `feat(WEB-02a): auth provider, protected account routes and header account button`

### Task 4: Form chung, đăng nhập, đăng ký

**Files:**
- Create: `src/ui/form.tsx`, `src/auth/validate.ts`, `src/auth/LoginPage.tsx`, `src/auth/RegisterPage.tsx`, `src/auth/LoginPage.test.tsx`
- Modify: `src/app/App.tsx`, `src/i18n.tsx`

**Interfaces:**
- Produces — `form.tsx` (dùng lại ở WEB-02b/03):
  - `Field(props: { label: string; name: string; error?: string; hint?: string } & InputHTMLAttributes<HTMLInputElement>)` — label
    phía trên, `aria-invalid`, `aria-describedby` → id lỗi; lỗi `<p id>` màu `text-danger`.
  - `PasswordField(props giống Field)` — nút hiện/ẩn (`aria-pressed`, `aria-label` "Hiện mật khẩu"/"Ẩn mật khẩu"), không chặn paste.
  - `ErrorSummary({ errors }: { errors: { name: string; label: string; message: string }[] })` — `role="alert"`,
    `tabIndex={-1}`, tự focus khi `errors` đổi từ rỗng sang có; mỗi mục `<a href="#<name>">` focus ô tương ứng.
  - `StatusMessage({ message }: { message: string | null })` — `role="status"`, tự ẩn sau 5000 ms.
  - `SubmitButton({ pending, children })` — `type="submit"`, `BUTTON`; khi `pending`: `disabled`, `aria-busy="true"`, giữ
    nguyên nhãn và thêm chỉ báo "Đang gửi…" (spec §3.1 Submit Feedback). Mọi form của WEB-02a dùng nút này.
  - `useForm<K extends string>(validators: Record<K, (value: string) => string | null>)` → `{ values, errors, touched, bind(name), submit(onValid) }`:
    lỗi một ô chỉ hiện sau blur ô đó hoặc sau submit; `submit` kiểm mọi ô, có lỗi thì không gọi `onValid` và hiện summary.
- Produces — `validate.ts`: `utf8Bytes(s): number`; `email(v)`, `password(v)` (≥ 8 ký tự, ≤ 72 byte), `required(v)`,
  `maxLength(n)(v)`; trả key i18n hoặc `null`.
- `LoginPage`: form email + password, `autocomplete` `email`/`current-password`; submit → `signIn`; 401 → câu cố định
  (Global Constraints) ở summary; 429 → câu 429; thành công → `navigate(safeNext(next), { replace: true })`.
  `safeNext(next: string | null): string` export từ `LoginPage.tsx`.
- `RegisterPage`: họ tên, email, mật khẩu (`new-password`), `locale` = ngôn ngữ UI; 409 → lỗi tại ô email (câu cố định);
  400 `fields` → lỗi tại ô tương ứng; thành công → `navigate('/account', { replace: true })`.

- [ ] **Step 1: Test** `LoginPage.test.tsx`:
  - `shows field errors on blur and a focused summary on submit` (email sai định dạng; summary là `document.activeElement`).
  - `a 401 shows the fixed message and keeps the typed email`.
  - `signs in and replaces history with next` (`/login?next=%2Faccount%2Faddresses` → `h1` trang địa chỉ).
  - `ignores an external next` (Review Focus 2): `next=https%3A%2F%2Fevil.example` và `next=%2F%2Fevil.example` → `/account`.
  - `rejects a password over 72 UTF-8 bytes before sending` (Review Focus 3): đăng ký với `'ệ'.repeat(30)` → lỗi ô mật khẩu, không có call `/auth/register`.
  - `register maps 409 to the email field` và `register sends the UI locale`.
  - `the submit button is busy while signing in` (response treo → nút `disabled` + `aria-busy="true"`; double-click chỉ 1 call `/auth/login`).
  - `the password toggle switches the input type and aria-pressed`; `password fields allow paste` (không có handler `onPaste` chặn — sự kiện paste không bị `preventDefault`).
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS + `npm test`.
- [ ] **Step 5: Commit** — `feat(WEB-02a): shared form components, sign-in and registration`

### Task 5: Quên và đặt lại mật khẩu

**Files:**
- Create: `src/auth/ForgotPasswordPage.tsx`, `src/auth/ResetPasswordPage.tsx`, `src/auth/PasswordPages.test.tsx`
- Modify: `src/app/App.tsx`, `src/i18n.tsx`

**Interfaces:**
- Consumes: `form.tsx`, `validate.ts`, `api.ts`, `endSession` (Task 2).
- `ForgotPasswordPage`: email; 202 → thay form bằng câu cố định (Global Constraints); 429/503 → thông báo; 400 → lỗi ô.
- `ResetPasswordPage`: khi mount đọc `token` từ `location.hash` (`#token=…`), lưu vào state, rồi
  `history.replaceState(null, '', location.pathname)`; thiếu/rỗng → trạng thái "Liên kết không hợp lệ hoặc đã hết hạn" + link
  `/forgot-password`; submit mật khẩu mới (`new-password`) → 204 → `endSession()` → `/login` kèm câu "Mật khẩu đã đổi…";
  400 → cùng trạng thái liên kết không hợp lệ (token sai/hết hạn).

- [ ] **Step 1: Test**:
  - `forgot shows the same sentence for every accepted email` (2 email khác nhau → cùng text).
  - `forgot shows the rate-limit sentence on 429`.
  - `reset reads the token from the fragment and removes it from the URL` (`window.location.hash` rỗng sau mount; body gửi đi có `token`).
  - `an empty or missing reset token shows the invalid-link state` (Review Focus 4): `/reset-password` và `/reset-password#token=` → không có call `/auth/password/reset`, có link `/forgot-password`.
  - `a successful reset ends the session and asks to sign in again`.
  - `a 400 on reset shows the invalid-link state`.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS + `npm test`.
- [ ] **Step 5: Commit** — `feat(WEB-02a): forgot and reset password pages`

### Task 6: Trang tài khoản

**Files:**
- Create: `src/account/AccountPage.tsx`, `src/account/AccountPage.test.tsx`
- Modify: `src/app/App.tsx`, `src/i18n.tsx`

**Interfaces:**
- Consumes: `useAuth`, `api.updateMe`, `api.changePassword`, `form.tsx`.
- Hồ sơ: email (chỉ đọc), họ tên, ngôn ngữ nhận email (select vi/en — không đổi ngôn ngữ UI); lưu → `StatusMessage` "Đã lưu".
- Đổi mật khẩu: mật khẩu hiện tại (`current-password`), mật khẩu mới (`new-password`); 400 field `current_password` → lỗi
  tại ô; 204 → `endSession()`, `broadcast('logout')`, `navigate('/login', { replace: true, state: { notice } })` với câu
  "Mật khẩu đã đổi…"; trang login hiển thị `state.notice`.
- Link "Địa chỉ" → `/account/addresses`; nút "Đăng xuất" → `signOut()` → `/`.

- [ ] **Step 1: Test**: `shows the profile from /users/me`; `saving the profile sends full_name and locale and shows a status`;
  `changing the email language does not change the UI language`; `a wrong current password is shown on that field`;
  `a successful password change signs out and shows the notice on login`; `sign out returns home as a guest`.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS + `npm test`.
- [ ] **Step 5: Commit** — `feat(WEB-02a): account page with profile, password change and sign out`

### Task 7: Địa chỉ

**Files:**
- Create: `src/account/AddressesPage.tsx`, `src/account/AddressForm.tsx`, `src/account/geo.sample.json`,
  `src/account/AddressesPage.test.tsx`
- Modify: `src/app/App.tsx`, `src/i18n.tsx`

**Interfaces:**
- `geo.sample.json`: `{ "sample": true, "provinces": [{ "code": "79", "name_vi": "TP. Hồ Chí Minh (mẫu)", "name_en": "Ho Chi Minh City (sample)", "wards": [{ "code": "26734", "name_vi": "Phường Bến Thành (mẫu)", "name_en": "Ben Thanh Ward (sample)" }, …] }, …] }`
  — 2–3 tỉnh, mỗi tỉnh 2–3 phường, tên có "(mẫu)"/"(sample)".
- `AddressForm({ initial, onSubmit, onCancel })`: người nhận, điện thoại (`type="tel"`, `autocomplete="tel"`), tỉnh (select),
  phường (select theo tỉnh, reset khi đổi tỉnh), địa chỉ chi tiết (`street-address`), checkbox mặc định; validate theo Global
  Constraints; 400 `fields` → lỗi tại ô.
- `AddressesPage`: danh sách thẻ (người nhận, điện thoại, địa chỉ, nhãn "Mặc định"); nút Thêm/Sửa mở `Drawer` với
  `AddressForm`; Xóa mở Drawer xác nhận (nút Hủy nhận focus mặc định) → `DELETE`; "Đặt làm mặc định" → `PUT` với
  `is_default: true`; sau mỗi thao tác tải lại danh sách và `StatusMessage`; 400 khi xóa → hiện `message` của server.
  Mã tỉnh/phường không có trong danh mục mẫu → hiện mã thô.

- [ ] **Step 1: Test**: `lists addresses with the default marked`; `adds an address through the drawer` (body đúng mã `79`/`26734`);
  `choosing another province resets the ward`; `validates phone and required fields`; `delete asks for confirmation and focuses Cancel`;
  `sets another address as default`; `a server 400 on delete is shown`; `codes outside the sample directory are shown raw`.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS + `npm test`.
- [ ] **Step 5: Commit** — `feat(WEB-02a): address book with a sample province/ward directory`

### Task 8: E2E, tài liệu, evidence

**Files:**
- Create: `tests/e2e/web-02.spec.ts`, `docs/evidence/web-02a-local-<ngày>.md`
- Modify: `web/storefront/README.md`, `docs/design/14_frontend_behavior.md` (SF-04), `docs/delivery/10_backlog.md` (WEB-02 a/b),
  `docs/design/08_decisions.md` (mục "WEB-02a — quyết định"), `docs/superpowers/plans/README.md`

- [ ] **Step 1: E2E** (desktop + 360px; ở 360px tới tài khoản qua nút trên header):
  - chuỗi thật: đăng ký email `web02-<timestamp>@example.test` → `/account` hiện email → reload vẫn ở `/account` (refresh khôi
    phục phiên) → sửa họ tên, thấy "Đã lưu" → thêm địa chỉ (tỉnh/phường mẫu) → đăng xuất → `/account` chuyển `/login?next=`;
  - fixture (`page.route`): đăng nhập sai hiện câu cố định; summary nhận focus; mở hai tab cùng context, cả hai tải trang
    → đếm request `POST /api/v1/auth/refresh` không chồng nhau (request thứ hai bắt đầu sau khi response thứ nhất về).
- [ ] **Step 2:** `npm run build`, `vite preview --port 4174`, `PLAYWRIGHT_BASE_URL=http://127.0.0.1:4174 npx playwright test tests/e2e/web-02.spec.ts -g fixture`
  → 2 project PASS; test chuỗi thật để CI.
- [ ] **Step 3: Docs** — README (route, phiên, giới hạn: không Web Locks → chỉ single-flight trong tab; danh mục địa chỉ mẫu),
  14 SF-04 implemented, 10 (WEB-02a In progress → chờ nghiệm thu, WEB-02b chưa làm), 08 (6 quyết định spec §8), plans README.
- [ ] **Step 4: Evidence** — lệnh, kết quả, phần không chạy và lý do.
- [ ] **Step 5: Verify** — `npm run typecheck && npm test && npm run build && bash scripts/validate-contracts.sh && python3 -B -m unittest discover -s scripts -p 'test_*.py' && python3 -B scripts/check_docs.py && git diff --check` → tất cả PASS.
- [ ] **Step 6: Commit** — `docs(WEB-02a): e2e, behaviour docs, decisions and local evidence`

Sau Task 8: review toàn nhánh bằng subagent, sửa Critical/Important (RED→GREEN). Push `dev` và PR khi chủ dự án yêu cầu.
