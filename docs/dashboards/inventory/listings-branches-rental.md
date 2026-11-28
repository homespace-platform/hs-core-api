# Kiểm kê API: Tin đăng, Chi nhánh, Lịch hẹn, Yêu cầu thuê, Hạn mức hội viên (phía tin)

Ngày 2026-11-28. Chỉ đọc code (không sửa, không đọc `.env`). Phạm vi `hs-core-api`. **AC** = `hs-api-service/src/main/java/com/hs/api/controller`, **LS** = `hs-listing-service/src/main/java/com/hs/listing`, **CS** = `hs-contract-service/src/main/java/com/hs/contract`. Đường dẫn là đường dẫn controller; qua Gateway thêm tiền tố `/api/v1` (Gateway cắt tiền tố, `AC/listing/AppointmentAiController.java:25`).
**Quyền**: `SecurityConfig` mở hết (`hs-api-service/.../config/SecurityConfig.java:26 anyRequest().permitAll()`), quyền do controller/service tự kiểm. Ký hiệu: **PUB** = không cần đăng nhập; **U** = cần đăng nhập, dữ liệu lọc theo `CurrentUser`/`UserContext` (chủ nhà và người thuê là cùng loại tài khoản USER, role chỉ ADMIN/USER; "chủ nhà" = có quyền `LISTING_POST`, "người thuê" = có `RENTAL_REQUEST_CREATE`/`APPOINTMENT_BOOK`); **U+P** = U và `@PreAuthorize` quyền P; **ADM+P** = ADMIN và quyền P. Khu này không có endpoint secret nội bộ.

## 1. Bảng endpoint: tổng cộng **120** handler (gồm 4 handler `/membership/*` phần hạn mức; phần thanh toán membership thuộc khu Payment)
Một số hàng gộp nhiều handler cùng quyền, mỗi handler vẫn ghi đủ `METHOD path:dòng`.
### 1.1 Tin đăng, chủ nhà (7) `AC/listing/ListingController.java`
| METHOD | path | dòng | quyền | mục đích |
|---|---|---|---|---|
| POST | /listings, /listings/upsert | :38 | U+LISTING_POST (+KYC, hồ sơ đủ) | tạo/sửa/gửi duyệt tin; giữ 1 lượt hạn mức khi gửi |
| GET | /listings/me | :52 | U | tin của tôi, phân trang, lọc status/branchId/keyword (có `viewCount`, `expiresAt`) |
| GET | /listings/me/counts | :62 | U | đếm tin của tôi theo `status` (PUBLISHED gồm cả tin đã quá `expires_at`, `LS/service/ListingQueryService.java:75-88`) |
| GET | /listings/{listingId} | :69 | U (chủ tin; người khác chỉ thấy PUBLISHED/RESERVED hoặc tin mình đã gửi yêu cầu, `ListingQueryService.java:99-108`) | chi tiết tin |
| GET | /listings/by-slug/{slug} | :76 | như trên | chi tiết theo slug |
| PATCH | /listings/{listingId}/status | :85 | U+LISTING_POST | chủ tin đổi trạng thái (ẩn/hiện, đã cho thuê ngoài...) |
| PATCH | /listings/{listingId}/hide | :97 | U+LISTING_POST | ẩn tin |
### 1.2 Tin đăng, admin kiểm duyệt (7) `AC/admin/ListingAdminController.java` (lớp: ADMIN+LISTING_VIEW, :26)
| GET | /admin/listings | :30 | ADM+LISTING_VIEW | danh sách duyệt (lọc status/owner/ngày/sort, size<=100); kèm `owner.phone` |
| GET | /admin/listings/counts | :44 | ADM+LISTING_VIEW | đếm tin theo status toàn hệ thống (+ALL; `LS/service/ListingAdminService.java:95-107`) |
| GET | /admin/listings/{id} | :51 | ADM+LISTING_VIEW | chi tiết kiểm duyệt (có `statusHistory`, SĐT) |
| POST | /admin/listings | :59 | ADM+LISTING_CREATE | admin đăng thay (bỏ qua hạn mức: `reserve(..., adminActor)`, `LS/membership/MembershipService.java:157-162`) |
| PUT | /admin/listings/{id} | :69 | ADM+LISTING_UPDATE | admin sửa tin |
| PATCH | /admin/listings/{id}/status | :80 | ADM+LISTING_MODERATE | duyệt/từ chối/vi phạm (ghi `listing_status_history`) |
| PATCH | /admin/listings/{id}/edits/{editId} | :94 | ADM+LISTING_MODERATE | duyệt/từ chối bản sửa chờ duyệt (`pending_edit_id`) |
### 1.3 Lịch sử xem tin (6) `AC/listing/ListingViewHistoryController.java`; Yêu thích (4, ngoài danh sách nhưng cùng khu) `AC/listing/FavoriteController.java`
| POST | /history/{listingId} | :27 | U | ghi lịch sử xem (trùng việc với `POST /public/listings/{id}/view` khi đã đăng nhập, `ListingViewService.java:38-45`) |
| GET | /history :36; /history/ids :44; /history/count :52 | | U | tin đã xem (trang <=40); toàn bộ id (không giới hạn); số lượng |
| DELETE | /history/{listingId} :60; /history :69 | | U | xóa một / xóa hết |
| POST | /favorites/{listingId}/toggle | :30 | U+FAVORITE_USE | lưu/bỏ lưu |
| DELETE | /favorites/{listingId} | :40 | U | bỏ lưu |
| GET | /favorites/ids :49; /favorites :58 | | U | id đã lưu (không giới hạn); danh sách phân trang |
### 1.4 Tin đăng công khai + AI search (12) `AC/publish/PublicListingController.java` (tất cả PUB)
| POST | /public/listings/ai/search | :53 | PUB (không thấy rate-limit trong code) | tìm tin theo bộ lọc AI (limit<=maxResults, `LS/service/AiListingSearchService.java:134-136`) |
| POST | /public/listings/ai/costs | :74 | PUB | ước chi phí thuê |
| POST | /public/listings/ai/value-opportunity | :86 | PUB | so sánh giá trị (truy vấn DB giới hạn) |
| GET | /public/listings/ai/fields :40; /ai/catalog :47; /ai/{listingId} :100 | | PUB | danh mục trường; catalog có phiên bản; chi tiết tin cho AI (có kiểm `expiresAt`) |
| GET | /public/listings/city-counts | :111 | PUB | số tin PUBLISHED của 5 thành phố lớn |
| GET | /public/listings/{listingId} :119; /by-slug/{slug} :126 | | PUB | chi tiết công khai (PUBLISHED/RESERVED, không kiểm `expires_at`, `ListingQueryService.java:103-104`) |
| POST | /public/listings/{listingId}/view | :133 | PUB (ghi DB `view_count`) | ghi lượt xem; chống spam Redis 1 phút/IP-user (`ListingViewService.java:57-82`) |
| GET | /public/listings | :144 | PUB | tìm kiếm công khai (size<=50, ~25 bộ lọc) |
| GET | /public/listings/owners/{ownerId}/count | :182 | PUB | số tin PUBLISHED của một chủ (gồm tin quá hạn, `LS/service/ListingPublicService.java:513-515`) |
### 1.5 Danh mục, địa phương, POI lân cận (1+2+4+3=10)
| GET | /public/listing-catalog?category= | `AC/publish/ListingCatalogController.java:19` | PUB | tùy chọn tiện ích/chính sách theo loại |
| GET | /public/locations/cities :14; /cities/{id}/wards :19 | `AC/publish/ListingLocationsController.java` | PUB | tỉnh/phường (không phân trang, dữ liệu tĩnh) |
| GET | /public/listings/{id}/nearby-places | `AC/publish/NearbyPlacesController.java:17` | PUB | POI quanh tin (radius, groups, page) |
| POST | /public/listings/ai/nearby-summary | :22 | PUB | tóm tắt POI hàng loạt (BatchRequest) |
| GET | /public/listings/ai/nearby-places/search?keyword= | :24 | PUB | tìm địa điểm đích |
| POST | /public/listings/ai/nearby-places/{id}/listings | :28 | PUB | tìm tin gần một địa điểm |
| GET | /admin/nearby-scans | `AC/admin/NearbyScanAdminController.java:16` | ADM+NEARBY_SCAN_VIEW | 100 job quét gần nhất + cờ bật/tắt (`LS/nearby/NearbyRepository.java:231-233`) |
| POST | /admin/listings/{id}/nearby-scans | :22 | ADM+NEARBY_SCAN_MANAGE | xếp hàng quét POI một tin |
| POST | /admin/nearby-scans/{id}/cancel | :30 | ADM+NEARBY_SCAN_MANAGE | hủy job |
### 1.6 Chi nhánh (5+2+1+2+7=17)
| POST | /branches | `AC/listing/PropertyBranchController.java:30` | U+LISTING_POST | tạo chi nhánh |
| GET | /branches :41; /branches/{id} :48 | | U | chi nhánh của tôi (không phân trang); một chi nhánh |
| PUT | /branches/{id} :57; DELETE /branches/{id} :69 | | U+LISTING_POST | sửa; xóa mềm |
| GET | /branches/{branchId}/layout | `AC/listing/BranchLayoutController.java:23` | U (chủ chi nhánh) | sơ đồ tầng/dãy/ô |
| PUT | /branches/{branchId}/layout | :32 | U+LISTING_POST | lưu sơ đồ |
| GET | /branches/{branchId}/overview?month=YYYY-MM | `AC/listing/BranchOverviewController.java:27` | U (chủ) | tổng quan phòng/thanh toán/đồng hồ/hạn HĐ theo tháng; `Cache-Control: no-store` |
| GET | /branches/{branchId}/attention?page&size | `AC/listing/BranchAttentionController.java:23` | U (chủ) | "Cần chú ý" 22 loại việc, size<=100 |
| GET | /branches/attention-summary | :32 | U (chủ) | số việc theo từng chi nhánh (chạy `collect` cho mọi chi nhánh, transaction REPEATABLE_READ) |
| GET | /branches/{b}/assignments :37; /assignment-candidates?kind= :43 | `AC/listing/BranchAssignmentsController.java` | U (chủ) | tin/HĐ đã gán phòng, ứng viên gán (phân trang) |
| PUT | /branches/{b}/rooms/{room}/listings/{listing} :51; DELETE cùng path :62 | | U+LISTING_POST | gán/bỏ gán tin vào phòng |
| PUT | /branches/{b}/contracts/{contractId} :72; DELETE cùng path :91 | | U+LISTING_POST | gán/bỏ gán HĐ vào phòng hoặc khay |
| PUT | /branches/{b}/rooms/{room}/manual-status | :99 | U+LISTING_POST | đặt trạng thái tay NONE/EXTERNAL_RENT/REPAIR |
### 1.7 Lịch hẹn xem nhà (15) `AC/listing/ViewingAppointmentController.java`
| GET | /appointments/availability/{listingId}?date | :41 | PUB (user tùy chọn) | khung giờ trống |
| GET | /appointments/{id} | :51 | U (chủ/người đặt) | chi tiết (có SĐT/email khách) |
| POST | /appointments | :60 | U+APPOINTMENT_BOOK (+KYC) | đặt lịch |
| GET | /appointments/my-bookings :70; /my-bookings/by-listing/{listingId} :80 | | U | lịch của người thuê (lọc status/1 ngày, sắp giảm dần) |
| PUT | /appointments/{id}/reschedule :90 (U+APPOINTMENT_BOOK); /{id}/cancel :101 (U) | | người thuê | xin đổi giờ; hủy |
| GET | /appointments/owner :116; /owner/counts :127 | | U (chủ) | danh sách chủ nhà (size<=50); đếm theo status (7 truy vấn count, `LS/service/ViewingAppointmentService.java:618-628`) |
| PUT | /{id}/approve :137; /reject :150; /reschedule/approve :163; /reschedule/reject :174; /owner-cancel :186; /complete :199 | | U+APPOINTMENT_MANAGE | chủ nhà xử lý lịch |
### 1.8 Trợ lý AI cho lịch hẹn (4, chỉ đọc) `AC/listing/AppointmentAiController.java`
| GET | /appointments/ai/summary | :35 | U | tổng theo status/vai trò/pha thời gian, khoảng <=366 ngày (`LS/service/ai/AiAppointmentQueryService.java:110-135`) |
| GET | /appointments/ai/search :40; /items/{id} :45; /listings/{id}/availability :50 | | U | tìm (<=20/trang), chi tiết, khung giờ |
### 1.9 Yêu cầu thuê (13) `AC/listing/RentalRequestController.java` (mọi path map thêm `/api/v1/...`, :30)
| GET | /rental-requests/policy :44; /reservation-policy :50 | | PUB | phút giữ chỗ + giờ phản hồi (`rental.hold-duration-minutes=60`, `response-hours=24`); văn bản chính sách |
| POST | /rental-requests/estimate | :59 | U | ước chi phí thuê |
| POST | /rental-requests | :68 | U+RENTAL_REQUEST_CREATE (+KYC) | gửi yêu cầu thuê |
| GET | /rental-requests/my-requests :78; /by-listing/{listingId} :87; /{id} :108; /{id}/initial-payment :116 | | U (người thuê/chủ liên quan) | danh sách (<=50); yêu cầu của tôi cho tin; chi tiết; phiếu thanh toán đầu kỳ |
| PUT | /rental-requests/{id}/cancel | :98 | U | hủy yêu cầu chưa thanh toán |
| GET | /rental-requests/owner :128; /owner/conflicts :139 | | U (chủ) | danh sách chủ (<=50, có SĐT/email khách + snapshot); tin có >=2 yêu cầu PENDING ("hỏi AI") |
| PUT | /rental-requests/{id}/accept :148; /reject :160 | | U+RENTAL_REQUEST_MANAGE | chấp nhận (vào giữ chỗ, từ chối các yêu cầu còn lại) / từ chối |
### 1.10 Onboarding sau chấp nhận (12) `AC/listing/RentalOnboardingController.java` (map thêm `/api/v1/...`, :11; U, bên/vai kiểm trong `CS/service/RentalOnboardingService.java`)
| GET | /rental-requests/{id}/onboarding | :20 | U (chủ/người thuê) | view: stage, hạn thanh toán/ký, `nextAction` (21 mã), giữ chỗ, lịch sử, tiền cọc |
| POST | .../onboarding/refresh | :19 | U | tính lại trạng thái (ghi) |
| POST/PUT/DELETE | .../earlier-start :21; .../earlier-start/{changeId} :22 (PUT), :23 (DELETE) | | U | xin/duyệt/rút đổi ngày vào ở sớm |
| POST/PUT | .../cancellation :24 (POST), :26 (PUT) | | U | xin/quyết định hủy trước ở |
| POST | .../reopen-signing | :25 | U (chủ) | mở lại hạn ký |
| PUT | .../payments/{paymentId}/review | :27 | U (chủ) | quyết định bằng chứng thanh toán bị từ chối/quá hạn |
| POST | .../default-notice :16; /default-dispute :17; /default-completion :18 | | U | thông báo vi phạm cọc; phản đối; kết thúc (theo vai) |
### 1.11 Workflow hủy / thoát sớm của yêu cầu thuê (9, ranh giới với khu Hợp đồng) `AC/contract/RentalWorkflowController.java` (map thêm `/api/v1/...`, :14)
| GET/POST | /rental-requests/{id}/workflow/{kind}/documents :21 (GET), :22 (POST) | | U | liệt kê/soạn tài liệu workflow |
| GET/POST | .../workflow/cancellation :23 (GET), :24 (POST); POST .../cancellation/{action} :25 | | U | xem/đề xuất/hành động hủy |
| GET/POST | .../workflow/early-exit :26 (GET), :28 (POST); GET .../early-exit/calculation :27; POST .../early-exit/{action} :29 | | U | xem/đề xuất thoát sớm, tính tiền, hành động |
### 1.12 Hạn mức đăng tin (4) `AC/payment/MembershipController.java` (checkout/đơn/ETH/IPN còn lại: khu Payment)
| GET | /membership/me | :61 | U | tier, `remainingUses`, `reservedUses`, `expiresAt`, `freeUsed`, `quotaResetsAt`, addon (`LS/membership/MembershipService.java:76-90`, chỉ đọc) |
| GET | /membership/plans :56; /addons :43; /quote?tier= :66 | | PUB; PUB; U | bảng gói; giá lượt thêm; báo giá nâng cấp |

## 2. Tín hiệu giám sát (Đ = đã có trong P1..P6; MP = một phần; THIẾU = chưa nằm trong thiết kế nào)
### 2.1 Chủ nhà
| # | Tín hiệu | Vì sao | Nguồn | P1..P6 |
|---|---|---|---|---|
| C1 | Tin đang đăng hiệu lực (PUBLISHED và chưa quá `expires_at`) | doanh thu tiềm năng | `listings` `idx_listings_owner_status` (`Listing.java:15`); KHÔNG dùng `/me/counts` | Đ P1 §3.2 |
| C2 | Tin sắp hết hạn <=7 ngày | mất khách | `listings.expires_at`, `idx_listings_status_expires` | Đ P1 §3.8 |
| C3 | Tin REJECTED/VIOLATION/HIDDEN + `status_reason` | cần sửa để đăng lại | `listings.status_reason`; `GET /listings/me?status=` | MP: P1 §3.5 chỉ LISTING_REJECTED/EXPIRED của tin thuộc chi nhánh; VIOLATION, HIDDEN không có |
| C4 | Tin chờ duyệt đã bao lâu | SLA duyệt, ảnh hưởng hạn gói | `listings.submitted_at` (`Listing.java:65`) | THIẾU (P1 §3.2 chỉ đếm) |
| C5 | Lượt đăng còn lại, lượt đang giữ, hạn gói | chặn đăng, tiền gói | `GET /membership/me` | Đ P1 §3.9 |
| C6 | Lịch sử dùng lượt (RESERVE/CONSUME/RELEASE/PURCHASE/UPGRADE) | đối soát vì sao mất lượt | `membership_events` (`LS/membership/MembershipEvent.java:13-34`) | THIẾU (không endpoint) |
| C7 | Yêu cầu thuê PENDING còn hạn `respond_by` | mất khách/tự hết hạn | `rental_requests` `idx_rental_request_response_status`; attention RENTAL_REQUEST_PENDING | Đ P1 §3.5 (+ phần tin không chi nhánh) |
| C8 | Tin có >=2 yêu cầu cạnh tranh | chọn người thuê | `GET /rental-requests/owner/conflicts` (`LS/service/RentalRequestService.java:532-551`) | THIẾU ở P1 (endpoint đã có) |
| C9 | Lịch hẹn chờ xác nhận / xin đổi giờ / sắp tới | phản hồi chậm làm hẹn EXPIRED | `viewing_appointments`; `/appointments/owner/counts`; `/appointments/ai/summary` | Đ P1 §3.5, §3.7 (P1 đề xuất truy vấn mới; có thể tái dùng `/appointments/ai/summary` byStatus/byPhase) |
| C10 | Giữ chỗ đang chạy: `hold_expires_at`, `nextAction`, hạn trả cọc/ký phía chủ | tiền cọc, mất phòng | `RentalRequest.java:120-126`; `RentalOnboardingService.java:77-85` | MP: P1 §3.5 chỉ PAYMENT_CONFIRM/OWNER_SIGN/CANCELLATION_DECISION; không có danh sách "giữ chỗ" |
| C11 | Thông báo vi phạm cọc (default) và hạn phản hồi | tiền | `rental_requests.default_notice_at/default_respond_by/default_disputed_at` (`RentalRequest.java:140-145`) | THIẾU (WorkType không có DEFAULT, `CS/dto/branch/BranchAttentionResponse.java:15-23`) |
| C12 | Lượt xem tích lũy/tin, phễu hẹn→yêu cầu→HĐ | hiệu quả tin | `listings.view_count` (`Listing.java:119`) | Đ P3 §3.4; P1 §3.8 |
| C13 | Lượt xem theo ngày / người xem duy nhất | xu hướng | không có dữ liệu (`listing_view_histories` 1 dòng/người, xóa sau 30 ngày) | P3 §3.4 đã nêu, đề xuất `listing_view_daily` (chưa tồn tại) |
| C14 | Phòng đang thuê/trống/giữ chỗ | lấp đầy | `BranchOverviewService.room(...)` | Đ P1 §3.1 |
| C15 | Tin/HĐ chưa gán chi nhánh | dữ liệu rời, không có việc | attention UNPLACED_* | MP P1 §3.5 ("Khác") |
| C16 | Sức chứa bãi xe vs đặt chỗ | tranh chấp chỗ xe | `parking_reservations` (`ParkingReservation.java:14-61`), sức chứa `PropertyBranch.java:55,59` | THIẾU (có bảng, không API tổng hợp) |
| C17 | Thời gian chủ nhà phản hồi TB (yêu cầu thuê, lịch hẹn) | uy tín, SLA | KHÔNG có `responded_at` (xem §3) | THIẾU |
| C18 | Tin hết hạn mà chưa từng có hẹn/yêu cầu | gợi ý đổi giá/gia hạn | suy ra từ `listings` + `viewing_appointments` + `rental_requests` | THIẾU |
### 2.2 Người thuê
| T1 | Yêu cầu thuê theo trạng thái + `respond_by` | biết bị bỏ rơi hay hết hạn | `idx_rental_request_renter_status` | Đ P2 W8 |
| T2 | Giữ chỗ sắp hết hạn | mất phòng + tiền | `rental_requests.hold_expires_at` | Đ P2 W5 |
| T3 | Bước kế tiếp: trả cọc, ký, trả tiền kỳ đầu | hạn tiền/ký | `RentalOnboardingService.get` (nặng, P2 giới hạn 10) | Đ P2 W4 |
| T4 | Lịch xem sắp tới / chờ chủ xác nhận | không lỡ hẹn | `viewing_appointments` `idx_appointment_renter_status` | Đ P2 W6 |
| T5 | Đề nghị đổi ngày vào ở đang chờ mình trả lời | hạn `rental.start-change-response-hours=24` | `rental_start_changes` (`RentalStartChange.java:11`), `View.startChanges` | THIẾU (P2 W4 không liệt kê) |
| T6 | Tin đã lưu bị hạ/đã cho thuê/đổi giá | quyết định thuê | `favorites`; KHÔNG có lịch sử giá | MP: P2 W9 chỉ liệt kê thẻ, không cảnh báo thay đổi; THIẾU |
| T7 | Tin đã xem gần đây | nhớ tin | `GET /history`, `/history/count` | THIẾU (ưu tiên thấp) |
### 2.3 Admin
| A1 | Tin chờ duyệt (+bản sửa chờ) | hàng đợi | `/admin/listings/counts`; `countByActiveTrueAndPendingEditIdIsNotNull` (`ListingRepository.java:38`, chưa có endpoint) | Đ P4 A1 |
| A2 | Tuổi tin chờ duyệt lâu nhất, thời gian duyệt trung vị/p90 | SLA, chủ nhà bỏ đi | `listings.submitted_at`, `listing_status_history` (`ListingStatusHistory.java:12-47`) | THIẾU |
| A3 | Tỉ lệ duyệt/từ chối/vi phạm theo kỳ, theo người duyệt | chất lượng duyệt, tải việc | `listing_status_history.changed_by_type/changed_by` | MP: P5 (2) đếm trạng thái hiện tại, (9b) tỉ lệ được đăng; không theo kỳ/người duyệt |
| A4 | Phễu tin, tin theo tỉnh, chủ nhà mới | tăng trưởng | `listings`, `listing_addresses` | Đ P5 (1),(2),(3),(9b) |
| A5 | Tin hết hạn/sắp hết hạn toàn hệ thống; tin quá hạn chưa quét | nguồn cung, độ trễ job | `listings.expires_at`; `ListingExpirationScheduler.java:16` (60s) | THIẾU |
| A6 | Yêu cầu thuê toàn hệ thống: mới/ngày, tỉ lệ chấp nhận/từ chối/EXPIRED/CANCELLED_BY_SYSTEM | sức khỏe marketplace | `rental_requests.status`; KHÔNG có endpoint admin | THIẾU (P5 phễu dừng ở tin→HĐ) |
| A7 | Lịch hẹn: tỉ lệ EXPIRED (chủ nhà bỏ qua)/CANCELLED/COMPLETED | trải nghiệm xem nhà | `viewing_appointments.status`; KHÔNG có endpoint admin | THIẾU |
| A8 | Giữ chỗ hết hạn / kẹt (ACCEPTED quá `hold_expires_at`, tin còn RESERVED) | doanh thu, tin kẹt | `idx_rental_request_expires_status`; `RentalHoldExpirationScheduler.java:18` (30s) | THIẾU |
| A9 | Vi phạm cọc: notice → dispute → completed, số đang chờ phản hồi | tiền, khiếu nại | `rental_requests.default_*` | THIẾU (P4 A3 chỉ `dispute_cases`) |
| A10 | Doanh thu hội viên theo ngày/gói/cổng | tiền | `membership_orders` | Đ P4 D; P5 (6)(7) |
| A11 | Hạn mức: phân bố tier, số chủ nhà hết lượt/sắp hết hạn gói, lượt dùng/ngày, tỉ lệ nâng cấp | tiền gói, churn | `membership_periods` (quota/used/reserved/end_at, `MembershipPeriod.java:28-32`), `membership_events` | THIẾU |
| A12 | Quét POI: job theo trạng thái (PENDING/RUNNING/RETRY_WAIT/PARTIAL/FAILED...), `ATTEMPTS_EXHAUSTED`, hạn mức TomTom (`application.properties:156-158`: 40/phút, 2.000/ngày, 20.000/tháng) | chi phí nhà cung cấp, thiếu POI | `nearby_scan_jobs` (`NearbyScanJob.java:16-47`); `GET /admin/nearby-scans` (100 dòng mới nhất) | THIẾU (không P nào) |
| A13 | AI search: số lượt gọi, lỗi `TOO_COMPLEX`/`INVALID_LIMIT`, kết quả rỗng, độ trễ | chi phí, chất lượng, lạm dụng | không ghi gì (PUB, `PublicListingController.java:53-96`) | THIẾU |
| A14 | Lượt xem toàn nền tảng, top tin | nhu cầu | `SUM(listings.view_count)` | THIẾU |
### 2.4 Vận hành - kỹ thuật
| O1 | Backlog outbox `listing_/appointment_/rental_request_notification_outbox` | thông báo trễ | `sent_at IS NULL`, `created_at` (`LS/notification/AppointmentNotificationOutbox.java:10-31`) | MP: Đ P4 E (đếm/tuổi); bảng không có `attempts/failed` nên không thấy tin lỗi vĩnh viễn |
| O2 | Sức khỏe scheduler: expiry tin 60s, hết giữ chỗ 30s, dọn hẹn cron 15 phút, nhắc hẹn 60s, xóa lịch sử xem 03:00, nhắc hạn gói 1h, onboarding tick 30s | tin không hết hạn, giữ chỗ kẹt, quên nhắc | chỉ `log.info/warn` (`ViewingAppointmentScheduler.java:25,30`; `RentalOnboardingService.java:772`) | THIẾU (không heartbeat/last-run) |
| O3 | Prometheus `nearby.provider.requests/duration`, `nearby.sync.duration`, `nearby.jobs.completed` | lỗi/độ trễ nhà cung cấp | `NearbyMetrics.java:9-14`; `management.endpoints.web.exposure.include=health,info,prometheus` (`application.properties:277`) | THIẾU (có số liệu, chưa ai hiển thị; P6 là blockchain) |
| O4 | Lượt đăng bị giữ kẹt: `publication_reservation` khác null nhưng tin không còn PENDING_REVIEW; `membership_periods.reserved` > số tin đang giữ | mất lượt oan | `Listing.java:71-75`; `MembershipService.java:157-205` | THIẾU |
| O5 | Bộ đếm lượt xem: Redis lỗi vẫn tăng (`ListingViewService.java:69-71`), profile `dev` tăng mọi lần (`:47-53`) | số liệu bị thổi phồng | chỉ log warn | THIẾU |
| O6 | Lỗi 5xx/độ trễ theo nhóm endpoint của khu này | phát hiện sự cố | `http.server.requests` mặc định Actuator (nếu bật registry) | THIẾU trong P4 E (chỉ outbox) |

## 3. Thiếu sót
**Dữ liệu chưa được ghi (cần thêm cột/bảng trước khi làm tín hiệu):**
1. Thời điểm chủ nhà phản hồi: `ViewingAppointment` chỉ có `completedAt` (`ViewingAppointment.java:111`), không có `approvedAt/rejectedAt/cancelledAt`; `RentalRequest` chỉ có `acceptedAt` (`RentalRequest.java:120`), từ chối/hủy chỉ có `updatedAt` chung. Không đo được SLA phản hồi (C17, A6, A7).
2. `COMPLETED` của lịch hẹn do scheduler tự đặt cho mọi hẹn CONFIRMED đã qua (`ViewingAppointmentService.java:649-661`); chủ nhà không xác nhận, nên không đo được "khách không đến" thật.
3. Lượt xem theo ngày/duy nhất (P3 §3.4 đã nêu); không có log lượt hiển thị/tìm kiếm, nên không có tỉ lệ xem/hiển thị.
4. Lịch sử giá tin (T6) và lịch sử gia hạn `expires_at`.
5. Outbox thiếu `attempts`, `last_error`, `failed_at` (O1); không có bảng "last run" cho scheduler (O2).
6. `membership_events` là sổ cái đầy đủ nhưng chưa endpoint nào đọc (C6, A11).
7. AI search/nearby: không đếm lượt dùng, không đếm kết quả rỗng (A13); hạn mức TomTom chỉ nằm trong cấu hình, không có bộ đếm đã dùng để hiển thị (A12).

**Endpoint đáng ngờ / không nên dùng cho dashboard:**
- `GET /listings/me/counts` (`ListingQueryService.java:75-88`) và `GET /public/listings/owners/{id}/count` (`ListingPublicService.java:513-515`) đếm PUBLISHED gồm tin đã quá hạn, lệch với "đang đăng" thật (P1 §3.2 đã cảnh báo). Cái sau là PUB nên lộ số tin của bất kỳ chủ nào.
- `GET /admin/listings` trả `owner.phone` mỗi dòng và tra user từng dòng (N+1, `ListingAdminService.java:160-161`, size<=100): không dùng cho widget; dùng `/admin/listings/counts` (1 truy vấn GROUP BY). `/counts` không có tuổi tin, không đếm bản sửa chờ.
- `GET /rental-requests/owner`, `/appointments/owner`, `GET /appointments/{id}` trả `renterPhone/renterEmail` và snapshot chi phí/tin (`RentalRequestResponse.java:21-22,35-37`): chứa PII, không làm nguồn dashboard (P1 §3.7 đã loại đúng).
- `GET /rental-requests/owner/conflicts` nạp mọi yêu cầu PENDING của chủ không giới hạn và truy vấn địa chỉ theo từng tin (`RentalRequestService.java:536-549`).
- Thiếu endpoint đếm cho yêu cầu thuê (chủ nhà và người thuê chỉ có danh sách phân trang <=50), trong khi lịch hẹn có `/owner/counts` (7 truy vấn count riêng lẻ, `ViewingAppointmentService.java:623-626`, nên gộp một GROUP BY).
- `POST /public/listings/{id}/view` ghi DB không cần đăng nhập (O5); `POST /history/{id}` ghi trùng dữ liệu mà `recordView` đã ghi cho user đăng nhập (`ListingViewService.java:38-45`).
- 4 endpoint AI POST (`/ai/search`, `/ai/costs`, `/ai/value-opportunity`, `/ai/nearby-summary`) là PUB, không thấy giới hạn tần suất hay số liệu trong code: rủi ro tải và chi phí.
- `/rental-requests/*`, `/appointments/ai/*`, `/membership/*`, `/rental-requests/{id}/onboarding|workflow/*` map hai lần (`/x` và `/api/v1/x`): thừa sau khi Gateway cắt tiền tố.
- `GET /rental-requests/{id}/onboarding` dựng view rất lớn (thanh toán, lịch sử, cọc); P2 W4 đã giới hạn 10; `POST .../refresh` là ghi, không dùng cho dashboard.
- `GET /branches`, `/history/ids`, `/favorites/ids` không giới hạn (ổn với quy mô một người dùng, không dùng làm widget).
- `MembershipService.View.unlimited` luôn `false` (`MembershipService.java:85`): trường chết.

**Gợi ý tái dùng (ít code nhất):** `GET /appointments/ai/summary` (byStatus/byRole/byPhase theo khoảng) cho widget lịch hẹn P1/P2; `GET /membership/me` (chỉ đọc, không khóa); `GET /admin/listings/counts` cho P4 A1.
