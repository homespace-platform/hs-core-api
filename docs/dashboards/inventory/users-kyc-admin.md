# Kiểm kê API — Người dùng, KYC, RBAC, admin chung (28/11/2026)

Chỉ đọc code, không sửa. Phạm vi: `hs-api-service/.../controller/{user,admin,publish/PublicUserController,internal/UserInternalController}` + `FavoriteController`, `ListingViewHistoryController` (nằm ở `controller/listing`, nhưng là dữ liệu theo người dùng). Dịch vụ phía sau: `hs-user-service` (`com.hs.user`).

## 0. Cách đường dẫn được cung cấp (gateway)
- Core nhận đường dẫn đã bỏ `/api/v1` (`hs-gateway-server/.../HsGatewayServerApplication.java:188` `rewritePath(API_V1_PREFIX + "/(?<segment>.*)", "/${segment}")`), nên `GET /users/me/kyc` ở core = `GET /api/v1/users/me/kyc` qua gateway. Cột "Đường dẫn" ghi dạng gateway.
- Gateway (`config/security/SecurityConfig.java`): `/api/v1/public/**` công khai; `/api/v1/internal/**` = `denyAll`; còn lại `/api/v1/**` cần JWT. Đường dẫn có đoạn đầu `admin` (hoặc `notifications/admin`) chỉ cho role ADMIN (`GatewayRouteAuthorization.isAdminPath`). Core tự đặt `anyRequest().permitAll()` (`hs-api-service/.../config/SecurityConfig.java`) nên quyền thật nằm ở `@PreAuthorize` + `CurrentUser`.
- **Core không có endpoint đăng ký/đăng nhập/làm mới token**: xác thực do Keycloak ngoài (`JWT_JWK_SET_URI`, gateway `application.properties`); người dùng đồng bộ về bảng `users` qua Kafka (`UserSyncConsumer.java:30`). Core chỉ có đổi/đặt mật khẩu (mục 1.1).
- Role chỉ có 2: `ADMIN`, `USER` (`Permissions.java:17-18`). "Chủ nhà"/"người thuê" cùng role USER, khác nhau ở hành vi (có tin đăng hay không), không phải quyền. USER nhận `USER_SCOPE` (11 quyền, `Permissions.java:59-62`); ADMIN nhận mọi quyền trong `CATALOG` lúc khởi động.

## 1. Bảng endpoint
Quyền: `U` = đã đăng nhập (JWT, không `@PreAuthorize`; dữ liệu lọc theo `CurrentUser`), `A:<Q>` = `hasAuthority('ADMIN') and hasAuthority('<Q>')`, `PUB` = công khai, `INT` = nội bộ.

### 1.1 Người dùng, hồ sơ, địa chỉ, mật khẩu, bản đồ (`controller/user`, `controller/publish`)
| Method | Đường dẫn | Controller:dòng | Ai gọi | Mục đích |
|---|---|---|---|---|
| GET | /api/v1/users/me/onboarding-status | UserController.java:39 | U | Trạng thái các bước làm quen (onboarding) của tôi |
| GET | /api/v1/users/me/permissions | UserController.java:46 | U | Role + danh sách quyền của tôi |
| PUT | /api/v1/users/me/onboarding | UserController.java:53 | U | Lưu họ tên/SĐT bước 1 (cập nhật cả Keycloak) |
| POST | /api/v1/users/me/password | UserController.java:61 | U | Đổi mật khẩu |
| POST | /api/v1/users/me/password/initial | UserController.java:69 | U | Đặt mật khẩu lần đầu |
| GET | /api/v1/users/me/password/status | UserController.java:77 | U | Đã có mật khẩu chưa (boolean) |
| GET | /api/v1/users/me/profile | UserController.java:84 | U | Hồ sơ đầy đủ của tôi (kèm `cccd`, email, SĐT, `kycVerified`, địa chỉ) |
| GET | /api/v1/users/{userId}/profile | UserController.java:91 | U (mọi user) | Hồ sơ công khai của user bất kỳ theo id |
| POST | /api/v1/users/me/profile | UserController.java:98 | U | Cập nhật hồ sơ |
| PUT | /api/v1/users/me/avatar | UserController.java:106 | U | Đặt ảnh đại diện theo `storageId` |
| GET | /api/v1/users/me/address | AddressController.java:28 | U | Địa chỉ của tôi |
| PUT | /api/v1/users/me/address | AddressController.java:35 | U | Lưu địa chỉ |
| DELETE | /api/v1/users/me/address | AddressController.java:44 | U | Xoá địa chỉ |
| GET | /api/v1/public/users/{username} | PublicUserController.java:24 | PUB | Hồ sơ công khai theo username (cùng DTO với `/users/{userId}/profile`) |
| GET | /api/v1/locations/autocomplete | VietmapController.java:28 | U | Proxy Vietmap gợi ý địa chỉ |
| GET | /api/v1/locations/geocode | VietmapController.java:31 | U | Proxy Vietmap geocode |
| GET | /api/v1/locations/place | VietmapController.java:34 | U | Proxy Vietmap chi tiết địa điểm |
| GET | /api/v1/locations/reverse | VietmapController.java:37 | U | Proxy Vietmap reverse geocode |

### 1.2 KYC của người dùng (VNPT eKYC)
| Method | Đường dẫn | Controller:dòng | Ai gọi | Mục đích |
|---|---|---|---|---|
| GET | /api/v1/users/me/kyc | KycController.java:25 | U | Trạng thái KYC hiện tại (NOT_VERIFIED/PENDING/REVIEW_REQUIRED/VERIFIED/REJECTED/EXPIRED, `rejectionReason`, nhà cung cấp) |
| GET | /api/v1/users/me/kyc/details | KycController.java:33 | U | Thông tin CCCD đã xác minh của chính tôi (PII, giải mã) |
| POST | /api/v1/users/me/kyc/session/cancel | KycController.java:40 | U | Huỷ phiên KYC đang chờ (đặt EXPIRED, lý do "người dùng huỷ") |
| POST | /api/v1/users/me/kyc/vnpt/session | VnptKycController.java:29 | U + KYC_SUBMIT | Mở/lấy lại phiên VNPT (khoá dòng user, kiểm giới hạn ngày) |
| POST | /api/v1/users/me/kyc/vnpt/sessions/{id}/sdk/upload | VnptKycController.java:34 | U + KYC_SUBMIT | Tải ảnh (≤5MB); route riêng `vnpt-ekyc-route`, circuit breaker 50s |
| POST | /api/v1/users/me/kyc/vnpt/sessions/{id}/sdk/{step} | VnptKycController.java:40 | U + KYC_SUBMIT | Chạy bước ocr/compare/face/mask/card qua proxy VNPT |
| POST | /api/v1/users/me/kyc/vnpt/sessions/{id}/finalize | VnptKycController.java:56 | U + KYC_SUBMIT | Chốt phiên: VERIFIED/REJECTED + thông báo |

### 1.3 Tin đã lưu / lịch sử xem (theo người dùng, miền listing)
| Method | Đường dẫn | Controller:dòng | Ai gọi | Mục đích |
|---|---|---|---|---|
| POST | /api/v1/favorites/{listingId}/toggle | FavoriteController.java:30 | U + FAVORITE_USE | Lưu/bỏ lưu tin |
| DELETE | /api/v1/favorites/{listingId} | FavoriteController.java:40 | U | Bỏ lưu tin |
| GET | /api/v1/favorites/ids | FavoriteController.java:49 | U | Toàn bộ id tin đã lưu (không phân trang) |
| GET | /api/v1/favorites | FavoriteController.java:58 | U | Tin đã lưu, phân trang (size ≤50), có `totalElements` |
| POST | /api/v1/history/{listingId} | ListingViewHistoryController.java:27 | U | Ghi lịch sử xem |
| GET | /api/v1/history | ListingViewHistoryController.java:36 | U | Lịch sử xem, phân trang (size ≤40) |
| GET | /api/v1/history/ids | ListingViewHistoryController.java:44 | U | Id tin đã xem |
| GET | /api/v1/history/count | ListingViewHistoryController.java:52 | U | Số tin đã xem |
| DELETE | /api/v1/history/{listingId} | ListingViewHistoryController.java:60 | U | Xoá một mục |
| DELETE | /api/v1/history | ListingViewHistoryController.java:69 | U | Xoá hết |

### 1.4 Admin — người dùng, KYC, RBAC (thuộc phạm vi)
| Method | Đường dẫn | Controller:dòng | Quyền | Mục đích |
|---|---|---|---|---|
| POST | /api/v1/admin/users | UserAdminController.java:40 | A:USER_CREATE | Tạo user (tạo ở Keycloak, đồng bộ qua Kafka) |
| GET | /api/v1/admin/users | UserAdminController.java:51 | A:USER_VIEW | Danh sách phân trang (mặc định 10, createdAt desc, không bộ lọc) |
| GET | /api/v1/admin/users/{userId} | UserAdminController.java:60 | A:USER_VIEW | Chi tiết user |
| PUT | /api/v1/admin/users/{userId} | UserAdminController.java:67 | A:USER_UPDATE | Sửa user (gồm `roleId`) |
| PATCH | /api/v1/admin/users/{userId}/disable | UserAdminController.java:79 | A:USER_UPDATE | Khoá tài khoản (khoá ghi mọi admin đang hoạt động) |
| PATCH | /api/v1/admin/users/{userId}/enable | UserAdminController.java:88 | A:USER_UPDATE | Mở khoá |
| POST | /api/v1/admin/users/{userId}/resend-invitation | UserAdminController.java:96 | A:USER_CREATE | Gửi lại email mời |
| GET | /api/v1/admin/users/{userId}/kyc | AdminKycController.java:16 | A:KYC_VIEW | Chỉ trả `verified/provider/verifiedAt` (không PII, không trạng thái chờ/từ chối) |
| PUT | /api/v1/admin/users/{userId}/kyc | AdminKycController.java:22 | A:KYC_MANAGE | Nhập KYC thủ công (provider ADMIN, điểm tự đặt 100) |
| GET | /api/v1/admin/roles | RoleAdminController.java:29 | A:ROLE_VIEW | Role phân trang (`includePermissions`) |
| GET | /api/v1/admin/roles/all | RoleAdminController.java:41 | A:ROLE_VIEW | Mọi role |
| GET | /api/v1/admin/roles/{id} | RoleAdminController.java:48 | A:ROLE_VIEW | Chi tiết role |
| POST | /api/v1/admin/roles/{id} | RoleAdminController.java:56 | A:ROLE_UPDATE | Sửa role/gán quyền |
| GET | /api/v1/admin/permissions | PermissionAdminController.java:29 | A:PERMISSION_VIEW | Quyền phân trang |
| GET | /api/v1/admin/permissions/all | PermissionAdminController.java:40 | A:PERMISSION_VIEW | Mọi quyền |
| GET | /api/v1/admin/permissions/{id} | PermissionAdminController.java:47 | A:PERMISSION_VIEW | Chi tiết quyền |
| POST | /api/v1/admin/permissions/{id} | PermissionAdminController.java:55 | A:PERMISSION_UPDATE | Sửa quyền |

### 1.5 Admin khác trong `controller/admin` (không thuộc người dùng) — liệt kê, ghi miền
Dạng gộp "X / Y" = hai mapping cùng đường dẫn (khác verb hoặc cùng cây), dòng ghi tương ứng.
| Method | Đường dẫn | Controller:dòng | Quyền | Miền / mục đích |
|---|---|---|---|---|
| GET | /api/v1/admin/operational-incidents | OperationalIncidentController.java:16 | A:INCIDENT_VIEW | **Vận hành** — 100 sự cố mới nhất (cả đã đóng), trả thẳng entity |
| POST | /api/v1/notifications/admin/broadcast | NotificationBroadcastController.java:23 | A:NOTIFICATION_BROADCAST | **Thông báo** — xếp hàng gửi hàng loạt (202 QUEUED); route riêng `notification-broadcast-route` về core |
| GET | /api/v1/admin/storage/{id} | StorageAdminController.java:17 | A:STORAGE_VIEW | **Lưu trữ** — metadata tệp |
| GET | /api/v1/admin/storage/{id}/download-url | StorageAdminController.java:22 | A:STORAGE_VIEW | **Lưu trữ** — URL tải có chữ ký |
| GET | /api/v1/admin/nearby-scans | NearbyScanAdminController.java:16 | A:NEARBY_SCAN_VIEW | **Tin đăng** — 100 job quét tiện ích gần + cờ bật/tắt |
| POST | /api/v1/admin/listings/{id}/nearby-scans | NearbyScanAdminController.java:22 | A:NEARBY_SCAN_MANAGE | **Tin đăng** — xếp job quét |
| POST | /api/v1/admin/nearby-scans/{id}/cancel | NearbyScanAdminController.java:30 | A:NEARBY_SCAN_MANAGE | **Tin đăng** — huỷ job |
| GET | /api/v1/admin/listings | ListingAdminController.java:30 | A:LISTING_VIEW | **Tin đăng** — danh sách có lọc (size ≤100) |
| GET | /api/v1/admin/listings/counts | ListingAdminController.java:44 | A:LISTING_VIEW | **Tin đăng** — đếm theo trạng thái |
| GET | /api/v1/admin/listings/{listingId} | ListingAdminController.java:51 | A:LISTING_VIEW | **Tin đăng** — chi tiết |
| POST | /api/v1/admin/listings | ListingAdminController.java:59 | A:LISTING_CREATE | **Tin đăng** — tạo |
| PUT | /api/v1/admin/listings/{listingId} | ListingAdminController.java:69 | A:LISTING_UPDATE | **Tin đăng** — sửa |
| PATCH | /api/v1/admin/listings/{listingId}/status | ListingAdminController.java:80 | A:LISTING_MODERATE | **Tin đăng** — duyệt/từ chối/ẩn |
| PATCH | /api/v1/admin/listings/{listingId}/edits/{editId} | ListingAdminController.java:94 | A:LISTING_MODERATE | **Tin đăng** — duyệt yêu cầu sửa |
| GET | /api/v1/admin/complaints | ComplaintAdminController.java:20 | A:COMPLAINT_VIEW | **Hợp đồng/khiếu nại** — danh sách lọc status/category/overdue |
| GET | /api/v1/admin/complaints/{id} | ComplaintAdminController.java:22 | A:COMPLAINT_VIEW | **Hợp đồng/khiếu nại** — chi tiết |
| POST | /api/v1/admin/complaints/{id}/actions/{action} | ComplaintAdminController.java:24 | A:COMPLAINT_RESOLVE | **Hợp đồng/khiếu nại** — xử lý |
| GET | /api/v1/admin/contract-template-fields | ContractAdminController.java:50 | A:CONTRACT_TEMPLATE_VIEW | **Hợp đồng/mẫu** — danh mục trường |
| GET / POST | /api/v1/admin/contract-templates | ContractAdminController.java:60 / :75 | VIEW / A:CONTRACT_TEMPLATE_MANAGE | **Hợp đồng/mẫu** — danh sách / tạo |
| GET / PATCH | /api/v1/admin/contract-templates/{templateId} | ContractAdminController.java:89 / :100 | VIEW / MANAGE | **Hợp đồng/mẫu** — chi tiết / sửa |
| POST / GET | /api/v1/admin/contract-templates/{templateId}/versions | ContractAdminController.java:115 / :130 | MANAGE / VIEW | **Hợp đồng/mẫu** — tạo / liệt kê phiên bản |
| GET | .../versions/{versionId} | ContractAdminController.java:140 | A:CONTRACT_TEMPLATE_VIEW | **Hợp đồng/mẫu** — chi tiết phiên bản |
| POST | .../versions/{versionId}/publish | ContractAdminController.java:154 | A:CONTRACT_TEMPLATE_PUBLISH | **Hợp đồng/mẫu** — xuất bản |
| POST | .../versions/{versionId}/test-preview | ContractAdminController.java:168 | A:CONTRACT_TEMPLATE_VIEW | **Hợp đồng/mẫu** — xem thử, sinh tài liệu nặng (route `contractPreview` 90s) |
| GET | /api/v1/admin/contracts | ContractAdminController.java:197 | A:CONTRACT_VIEW | **Hợp đồng** — danh sách lọc status/keyword/documentKind (size ≤100) |
| GET | /api/v1/admin/contracts/{contractId} | ContractAdminController.java:212 | A:CONTRACT_VIEW | **Hợp đồng** — chi tiết |
| GET | /api/v1/admin/contracts/{contractId}/revision | ContractAdminController.java:223 | A:CONTRACT_VIEW | **Hợp đồng** — revision mới nhất |
| GET | /api/v1/admin/contracts/{contractId}/documents | ContractAdminController.java:234 | A:CONTRACT_VIEW | **Hợp đồng** — tài liệu DOCX/PDF |
| GET, POST | /api/v1/admin/contracts/documents/{documentId}/content | ContractDocumentAdminController.java:28 | A:CONTRACT_VIEW | **Hợp đồng** — nội dung tài liệu (1 mapping, 2 verb) |

### 1.6 Nội bộ
| Method | Đường dẫn | Controller:dòng | Ai gọi | Mục đích |
|---|---|---|---|---|
| GET | /internal/users/{userId}/permissions | UserInternalController.java:28 | INT (gateway chặn từ ngoài) | Gateway gọi **mỗi request đã đăng nhập** lấy role+quyền (`UserAccessResolver`, không cache); lỗi nếu user bị khoá |
| POST | /internal/users/emails | UserInternalController.java:36 | INT (notification-service) | Email của user đang hoạt động theo danh sách id |

**Tổng: 86 mapping** = 18 (1.1) + 7 (1.2) + 10 (1.3) + 17 (1.4) + 32 (1.5: 1 sự cố, 1 broadcast, 2 storage, 3 nearby, 7 listing, 3 complaint, 14 mẫu+hợp đồng, 1 tài liệu) + 2 (1.6). Trong 1.5, dòng "X / Y" gộp nhiều mapping đã đếm đủ từng mapping. `ComplaintAdmin`, `ContractAdmin`, `ContractDocumentAdmin` còn map thêm tiền tố `/api/v1/admin` (alias thừa vì gateway đã bỏ tiền tố). Ngoài core còn `/api/v1/admin/news/**` (news-service) và `/api/v1/admin/ai/**` (ai-service): không thuộc kiểm kê này.

## 2. Tín hiệu giám sát
CÓ = đã nằm trong thiết kế (file + widget); THIẾU = chưa có trong P1..P6. "Nguồn" là endpoint/bảng sẵn có; chưa có endpoint tổng hợp thì cần thêm (xem mục 3).

### 2.1 Chủ nhà (P1)
| Tín hiệu | Đo gì / vì sao | Nguồn | P1..P6 |
|---|---|---|---|
| KYC của tôi (chưa xác minh/chờ/bị từ chối/hết hạn + lý do + `expiresAt`) | KYC là cổng bắt buộc (`KycGateService.requireVerifiedIdentity`, `KYC_REQUIRED`): chưa KYC thì không đăng tin/ký được → chặn doanh thu | `GET /users/me/kyc` (`KycController.java:25`) | THIẾU ở P1 (chỉ P2 W0 có, cho người thuê) |
| Hoàn tất onboarding (hồ sơ + ngân hàng) | `users.on_boarded=false` thì chưa nhận tiền được: rủi ro tiền | `GET /users/me/onboarding-status` (`UserController.java:39`) | THIẾU ở P1 và P2 |
| Số lượt "lưu tin" trên từng tin của tôi | Tín hiệu cầu thuê | bảng `listing_favorites` (`idx_favorite_listing`); KHÔNG có endpoint cho chủ nhà | THIẾU (P3:51 chỉ nói lịch sử xem không dùng được) |
| Quyền USER_SCOPE còn hiệu lực | Giải thích vì sao tính năng bị ẩn sau khi admin thu hồi | `GET /users/me/permissions` | THIẾU (giá trị thấp) |

### 2.2 Người thuê (P2)
| Tín hiệu | Đo gì / vì sao | Nguồn | P1..P6 |
|---|---|---|---|
| Banner KYC chưa xác minh | Chặn gửi yêu cầu thuê/ký | `profile.kycVerified` + `GET /users/me/kyc` | CÓ: P2 W0 |
| Tin đã lưu (số + 4 tin đầu) | Giữ chân, gợi ý | `GET /favorites?size=4` (`FavoriteController.java:58`) | CÓ: P2 W9 |
| Onboarding chưa xong | Chưa có hồ sơ/ngân hàng thì chưa thanh toán/nhận hoàn tiền | `GET /users/me/onboarding-status` | THIẾU |
| Số tin đã xem | Chỉ tiện ích; tự xoá sau 30 ngày (`ListingViewHistoryScheduler`) | `GET /history/count` | THIẾU (giá trị thấp, có thể bỏ) |

### 2.3 Admin (P4/P5)
| Tín hiệu | Đo gì / vì sao | Nguồn | P1..P6 |
|---|---|---|---|
| Người dùng mới hôm nay/7 ngày | Tăng trưởng | `users.created_at` (chưa có index) | CÓ: P4 B1; P5 khối 1 |
| KYC chờ (PENDING/REVIEW_REQUIRED) | Hàng đợi, SLA | `kyc_verifications` + quy tắc `KycServiceImpl.getCurrentStatus` (:55) | CÓ: P4 A2. Nhưng **chưa có trang/endpoint danh sách KYC** để xử lý (P4 §8 câu 3) |
| KYC đã xác minh / tổng USER | Độ phủ KYC | như trên | CÓ: P4 B2 |
| KYC theo nhà cung cấp × trạng thái, tỉ lệ thành công | Chất lượng VNPT vs nhập tay | `kyc_verifications.provider,status` | CÓ: P5 khối 8 |
| Tỉ lệ đăng ký → KYC | Phễu | `users` ⋈ `kyc_verifications` | CÓ: P5 khối 9a |
| Lý do từ chối KYC tách loại ("CCCD đã dùng ở tài khoản khác" vs giấy tờ/khuôn mặt không đạt) | CCCD trùng = nghi trùng tài khoản/gian lận | `kyc_verifications.rejection_reason` (2 chuỗi cố định, `VnptKycStore.java:128,130`) | THIẾU (P5 khối 8 chỉ đếm REJECTED) |
| Phiên KYC bỏ dở/hết hạn không chốt, đã gửi nhắc | Người dùng kẹt ở KYC → mất chuyển đổi | `status in (PENDING,REVIEW_REQUIRED) and expires_at<=now`; `retry_notice_sent_at` | THIẾU |
| KYC nhập tay bởi admin (số lượng, theo ngày, ai làm) | Ngoại lệ bỏ qua VNPT, rủi ro gian lận nội bộ | `kyc_verifications.provider='ADMIN'`, `verified_at`; admin id chỉ ở log `AdminKycService` (có thể ở `updated_by` của BaseEntity — chưa kiểm auditor) | THIẾU (P5 khối 8 có cột ADMIN, không theo người/ngày) |
| Số tài khoản bị khoá / đang hoạt động | Rủi ro, hỗ trợ | `users.active` (`BaseEntity.java`) | THIẾU (P4 khối B chỉ có mới + KYC) |
| Số admin đang hoạt động, quyền theo role | Chốt chặn "admin cuối cùng" (`assertAnotherActiveAdminRemains`), đặc quyền | `users`⋈`roles`; `GET /admin/roles?includePermissions=true` | THIẾU |
| User kẹt onboarding (tạo > N ngày, `on_boarded=false`) | Phễu đăng ký → onboarding → KYC | `users.on_boarded, created_at` | THIẾU (P5 9a bỏ qua bước onboarding) |
| Lời mời admin chưa được chấp nhận | Tài khoản tạo hộ nhưng chưa kích hoạt | không có trường (Keycloak giữ) | THIẾU (không có dữ liệu) |
| Lệch user Keycloak vs bảng `users` | Mất user sau đăng ký | không có | THIẾU (không có dữ liệu) |
| Sự cố vận hành mở | Cảnh báo | `GET /admin/operational-incidents` | CÓ: P4 A4 (chỉ đếm `open`) |
| Thời gian khắc phục (mở→đóng), sự cố lặp | SLA/MTTR | `operational_incidents.opened_at,resolved_at` | THIẾU |
| Backlog outbox tài khoản + broadcast | Thông báo kẹt | `account_notification_outbox`, `notification_broadcast_outbox` (`sent_at is null`) | CÓ: P4 khối E (8 bảng) |
| Lịch sử broadcast (ai, khi nào, tới bao nhiêu người) | Kiểm soát gửi hàng loạt (spam/nhầm) | `notification_broadcast_outbox.created_at/sent_at/recipient_cursor`; không có endpoint đọc, không lưu người gửi | THIẾU |
| Job quét tiện ích gần (tồn/lỗi) | Chất lượng dữ liệu tin | `GET /admin/nearby-scans` | THIẾU (miền listing quyết) |

### 2.4 Vận hành / kỹ thuật
| Tín hiệu | Đo gì / vì sao | Nguồn | P1..P6 |
|---|---|---|---|
| Tra quyền gateway→core thất bại (mặc định DENIED, chỉ ghi log) | Người dùng đột ngột bị 403 hàng loạt khi core chậm; đường nóng vì mỗi request đều gọi, không cache | log `UserAccessResolver` ("Could not fetch user access"); `http_server_requests{uri="/internal/users/{userId}/permissions"}` | THIẾU |
| VNPT: tỉ lệ bước FAILED theo `kind`, độ trễ, `vnptEkycCircuitBreaker` mở | Nhà cung cấp ngoài chết = KYC dừng toàn hệ thống | `kyc_provider_steps.state='FAILED'` — **xoá sau 1 ngày** (`VnptKycStore.purgeTemporaryEvidence`, :159); không có Micrometer | THIẾU (và không lưu lâu được) |
| Giới hạn thử KYC/ngày mặc định 0 = không giới hạn (`VnptKycProperties.java:12`); `KYC_NOT_CONFIGURED` | Lạm dụng, chi phí VNPT | cấu hình | THIẾU |
| Vietmap: số lời gọi/ngày, lỗi, hit cache | Chi phí/hạn mức bên thứ ba; `autocomplete` gọi theo từng phím, không cache, không đếm (`hs-common-service/.../VietmapService.java`) | không có | THIẾU |
| Mã lỗi/độ trễ theo nhóm đường dẫn (/users, /admin, /users/me/kyc/vnpt) | SLA | `/actuator/prometheus` có `http.server.requests` + percentile histogram (`application.properties:277-284`) | THIẾU (P4 chỉ đo outbox; Prometheus có sẵn nhưng chưa dùng) |
| Bộ lập lịch còn sống (nhắc KYC 60s, dọn bằng chứng mỗi giờ, dọn lịch sử xem) | Job chết thì nhắc KYC/dọn dữ liệu ngừng | không có heartbeat | THIẾU |
| Đồng bộ Kafka người dùng lỗi | `UserSyncConsumer.java:50-52` nuốt mọi ngoại lệ (chỉ `log.error`), không DLQ, không đếm | không có | THIẾU |

## 3. Thiếu sót
**Dữ liệu cần ghi thêm (chưa có API/dữ liệu)**
1. Hàng đợi KYC cho admin: không có endpoint liệt kê KYC theo trạng thái (chỉ `GET/PUT /admin/users/{id}/kyc`, GET chỉ trả verified/không). Cần endpoint đọc tổng hợp (đếm theo trạng thái/nhà cung cấp/lý do) + danh sách phân trang không PII (id user, trạng thái, `created_at`, `expires_at`).
2. Nhật ký kiểm toán admin: ai khoá/mở khoá, đổi role, sửa quyền, nhập KYC tay, kèm lý do. Hiện chỉ có `updated_by` dòng cuối (BaseEntity) và `log.info`; `AdminKycRequest` không có trường lý do. Cần bảng kiểu `admin_audit_log(actor_id, action, target_id, reason, at)`.
3. Bộ đếm KYC bền vững: `kyc_provider_steps` bị xoá sau 1 ngày; cần cộng dồn theo ngày `(provider, kind, state)` trước khi xoá để tính tỉ lệ lỗi VNPT.
4. Lần đăng nhập cuối / sự kiện đăng nhập: core không lưu (Keycloak giữ) → không đo người dùng hoạt động (DAU/MAU), lời mời chưa nhận, tài khoản ngủ.
5. Lệch đồng bộ Keycloak↔`users`: cần bộ đếm lỗi trong `UserSyncConsumer` hoặc bảng dead-letter.
6. Lịch sử broadcast: `BroadcastNotificationOutbox` không lưu người gửi; cần cột actor + endpoint đọc.
7. Số "lưu tin" theo từng tin cho chủ nhà: có bảng, chưa có endpoint đếm (chỉ đếm, không trả danh sách người).
8. Incident chỉ nhận `BLOCKCHAIN` và `GATEWAY` (`OperationalIncidentService.observe`); VNPT, Keycloak, Kafka, outbox không tạo incident → P4 A4 vẫn hiện 0 khi KYC đã chết.
9. Không có Micrometer tuỳ chỉnh ngoài `NearbyMetrics` (grep `MeterRegistry`); mọi tỉ lệ nghiệp vụ phải tính từ SQL.

**Endpoint trùng / không an toàn / không hợp cho dashboard**
- `GET /users/me/profile` (`UserController.java:84`) trả `cccd` đã giải mã, email, SĐT: không dùng cho khối tổng hợp; P2 W0 chỉ dùng `kycVerified` (đã có ở `useAuth`) — cần kiểm frontend không giữ CCCD trong state/log. `GET /users/me/kyc/details` (:33) trả đủ PII CCCD: không dùng.
- `GET /admin/users` (`UserAdminController.java:51`): không lọc/sắp theo `active`, role, KYC; không giới hạn size tối đa riêng (mặc định Spring); trả email/SĐT. Không dựng được "tài khoản bị khoá" hay "kẹt onboarding" → cần endpoint tổng hợp (P4 §4 `admin/dashboard/overview` chưa có các số này).
- `GET /users/{userId}/profile` (:91, mọi user đăng nhập) và `GET /public/users/{username}` (công khai): cùng DTO (id, role, `kycVerified`, `createdAt`, tỉnh) → trùng; cho phép dò user và nhận diện tài khoản ADMIN qua `role`. Không dùng cho dashboard.
- Có khoá ghi: `PATCH /admin/users/{id}/disable` khoá bi quan mọi admin đang hoạt động (`UserRepository.lockActiveByRoleName`); `POST /users/me/kyc/vnpt/session` và `/finalize` khoá dòng user/phiên. Không gọi từ dashboard. Mọi GET trong phạm vi chỉ đọc (`readOnly`), không khoá.
- `GET /favorites/ids` (không phân trang), `GET /history/ids`: không giới hạn; dùng `GET /favorites?size=1` lấy `totalElements`.
- `GET /admin/operational-incidents`: trả entity JPA nguyên vẹn (`sourceKey`, `resourceId`), cố định 100 dòng, không lọc mở/đóng; P4 A4 nên đếm bằng truy vấn tổng hợp, không tải 100 dòng.
- `/internal/users/{userId}/permissions` là đường nóng không cache (mỗi request đăng nhập một lần, role EAGER): đo tải trước khi thêm dashboard tự làm mới 60s (P4 §4).
- Gateway cho công khai `/api/v1/actuator/prometheus` (`SecurityConfig.java`): lộ số liệu hệ thống khi chưa đăng nhập — nên hạn chế trước khi xây dashboard dựa vào đó.
- Chưa đối chiếu frontend (`hs-web-app`, `hs-admin-portal`) có dùng hết các endpoint trên không; `AdminsPage.tsx`/`MembersPage.tsx` được P4 ghi là trang thừa.
