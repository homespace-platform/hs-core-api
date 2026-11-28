# Kiểm kê: dịch vụ ngoài Core + vận hành/giám sát kỹ thuật

Ngày 28/11/2026. Chỉ đọc mã (không chạy dịch vụ, không đọc `.env`). Đường dẫn tính từ `D:\Projects\homespace\`. "GW" = hs-gateway-server. Mọi dịch vụ Nest/FastAPI tin `X-Internal-Secret` + header danh tính từ Gateway (không tự kiểm JWT).

## 1. Bảng endpoint (tổng: 85 = 77 trong dịch vụ ngoài Core + 5 vận hành trong Core + 3 Eureka có sẵn; thêm 14 route Gateway, 10 sự kiện socket chat, 1 namespace socket thông báo)

Gồm: Gateway 7, Discovery 2 (+3 Eureka), Notification 12, Chat 12, News 12, AI 32, Core vận hành 5.
### 1.1 Gateway (`hs-gateway-server`) — 7 endpoint
| METHOD | path | file:line | Ai gọi | Mục đích |
|---|---|---|---|---|
| GET/POST/PUT/PATCH/DELETE | `/fallback/{serviceName}` (5) | `GW/src/main/java/com/hs/gateway/controller/GatewayFallbackController.java:65,70,75,80,85` | công khai (`SecurityConfig.java:33`), chỉ forward nội bộ | trả 503 `SERVICE_UNAVAILABLE` khi circuit breaker mở/timeout |
| GET | `/actuator/health` (+`/liveness`,`/readiness`) | `application.properties:37-41` | công khai (`SecurityConfig.java:32`) | sức khỏe CỦA GATEWAY, cổng 8080; Dockerfile HEALTHCHECK dùng (`Dockerfile:14-15`) |
| GET | `/actuator/info` | `application.properties:37-38` | công khai | thông tin build/env |

Lưu ý đường đi: `/api/v1/actuator/health` (cả hai frontend gọi: `hs-admin-portal/src/lib/backend-health.ts:9`, mobile `backend-health.ts:14`) KHÔNG phải health của Gateway: khớp `core-service-route` (`/api/v1/**`, `HsGatewayServerApplication.java:209`) rồi bỏ tiền tố → đến `/actuator/health` của Core; khi Core chết thì circuit breaker trả 503. `dokploy.md:714` mô tả sai ("health của Gateway").
### 1.2 Bảng route Gateway (14 route, `HsGatewayServerApplication.java`)
| # | route id (dòng) | path | đích | Rate limit | Circuit breaker / timeout |
|---|---|---|---|---|---|
| 1 | news-service-route (69) | `/api/v1/admin/news/**`, `/api/v1/public/news/**` | news | có | `newsServiceCircuitBreaker` / 6s |
| 2 | news-diagnostics (82) | `/api/v1/news/**` | news | có | như trên |
| 3 | notification-broadcast (96) | POST `/api/v1/notifications/admin/broadcast` | **core** | có | `coreServiceCircuitBreaker` / 20s |
| 4 | notification-websocket (105) | `/api/v1/notifications/socket.io/**` (Upgrade) | notification (ws) | **không** | **không** |
| 5 | notification-service-route (110) | `/api/v1/notifications/**` | notification | có | `notificationServiceCircuitBreaker` / 6s |
| 6 | chat-websocket (123) / 7 chat-socket (131) | `/api/v1/chat/socket.io/**` | chat (ws/polling) | **không** | **không** |
| 8 | chat-service-route (138) | `/api/v1/chat/**` | chat | có | `chatServiceCircuitBreaker` / 6s |
| 9 | ai-service-admin-route (152) | `/api/v1/admin/ai/**` → `/admin/**` | ai | có | `aiServiceCircuitBreaker` (đếm cả 500) / 45s; chỉ ADMIN (`GatewayRouteAuthorization.java:20-23`) |
| 10 | ai-service-route (169) | `/api/v1/ai/**` | ai | có | `aiServiceCircuitBreaker` / 45s |
| 11 | contract-preview-route (184) | POST `/api/v1/contracts/*/previews` | core | có | `contractPreviewCircuitBreaker` / 90s |
| 12 | vnpt-ekyc-route (197) | POST `/api/v1/users/me/kyc/vnpt/sessions/*/sdk/**` | core | có | `vnptEkycCircuitBreaker` / 50s, fallback `/fallback/core-service` |
| 13 | core-service-route (209) | `/api/v1/**` | core | có | `coreServiceCircuitBreaker` / 20s |
| 14 | mobile-proof-upload-route (223) | `/u/**` | core | **không** | **không** |

Rate limit chung: Redis token bucket, mặc định 50 token/giây, burst 100, 1 token/request (`application.properties:62-64`, `RateLimiterConfig.java:123-129`); khóa = IP socket `getRemoteAddress()` (`RateLimiterConfig.java:115-121`, không đọc `X-Forwarded-For`; grep `forward` trong `src/main` không có) nên sau proxy Dokploy/Traefik cả hệ thống có thể dùng chung 1 xô (chưa kiểm chứng trên prod). Vượt hạn → 429 mã 1006 (`InternalRateLimiterGatewayFilter.java:34`), không đếm ở đâu. CB: 20 call gần nhất, tối thiểu 5, mở khi ≥50% lỗi, chờ 10s, thử 3 call (`application.properties:76-82`). `coreServiceCircuitBreaker` dùng chung cho MỌI route Core → một tính năng lỗi 5xx hàng loạt làm 503 cả Core. `userServiceCircuitBreaker` khai báo (dòng 84, 94) nhưng không route nào dùng.
Allow-list công khai chết: `POST /api/v1/ai/property-search`, `GET /api/v1/ai/property-search/suggestions` (`SecurityConfig.java:50-51`) nhưng grep `property-search` không thấy trong hs-ai-service/src, hs-core-api, hs-web-app. `/api/v1/actuator/prometheus` công khai (`SecurityConfig.java:40`) nhưng không có `micrometer-registry-prometheus` trong mọi pom (chỉ `micrometer-core`, `hs-listing-service/pom.xml:16`) → nhiều khả năng 404; nếu sau này thêm thư viện, số liệu Core lộ công khai.
### 1.3 Discovery (`hs-discovery-server`) — 2 endpoint tự khai báo + Eureka sẵn có
| METHOD | path | file:line | Ai gọi | Mục đích |
|---|---|---|---|---|
| GET | `/actuator/health`, `/actuator/info` | `hs-discovery-server/src/main/resources/application.properties:26-30`; Dockerfile:14 | nội bộ (cổng 8761, không route qua GW) | health container |
| GET | `/`, `/eureka/apps`, `/eureka/apps/{app}` | thư viện Eureka, không có mã | nội bộ; dashboard công khai nếu bật domain (`dokploy.md:35` khuyên bảo vệ) | danh sách instance UP/DOWN, `lastDirtyTimestamp`, `status` |

Không có Spring Security trong pom (`pom.xml:37-57`) → dashboard không xác thực nếu public.
### 1.4 Notification (`hs-notification-service`, qua GW `/api/v1/notifications/…`, bỏ tiền tố) — 12 HTTP + socket
| METHOD | path | file:line | Ai gọi | Mục đích |
|---|---|---|---|---|
| GET | `/ping` | `src/modules/health/presentation/health.controller.ts:5` | công khai (`SecurityConfig.java:47`) | chỉ trả "pong", KHÔNG kiểm Mongo/Kafka |
| GET | `/auth/me` | `.../authentication.controller.ts:9` | user | chẩn đoán danh tính |
| GET | `/feed` | `.../notification.controller.ts:45` | user | feed thông báo (cursor) |
| GET | `/unread-count` | `:55` | user | số chưa đọc |
| PATCH | `/read-all` | `:64` | user | đọc hết |
| GET / PUT / PATCH | `/preferences` | `:75 / :84 / :96` | user | tuỳ chọn kênh (push/email) theo loại |
| PATCH | `/:id/read` | `:113` | user | đánh dấu đã đọc |
| DELETE | `/:id` | `:125` | user | xoá mềm |
| PUT | `/devices` | `.../device.controller.ts:68` | user | đăng ký thiết bị push |
| DELETE | `/devices/:installationId` | `:79` | user (có bí mật cài đặt) | huỷ đăng ký |
| WS | namespace `/notifications` | `.../notification-realtime.gateway.ts:7` | user (JWT lúc bắt tay) | realtime |
Không có endpoint admin nào dù `AdminGuard` đã sẵn (`authentication.module.ts:8-9`, không controller dùng). Broadcast admin nằm ở Core: POST `/api/v1/notifications/admin/broadcast` (`hs-core-api/hs-api-service/.../NotificationBroadcastController.java:19-26`, ADMIN + `NOTIFICATION_BROADCAST`, trả 202 `QUEUED`, không có API xem tiến độ).

### 1.5 Chat (`hs-chat-service`, qua GW `/api/v1/chat/…`) — 12 HTTP + 10 sự kiện socket
| METHOD | path | file:line | Ai gọi | Mục đích |
|---|---|---|---|---|
| GET | `/ping` | `src/modules/health/presentation/health.controller.ts:5` | công khai | sống |
| GET | `/auth/me` | `.../authentication.controller.ts:9` | user | danh tính |
| GET / POST | `/conversations` | `src/modules/chat/presentation/chat.controller.ts:34 / :46` | user | danh sách / tạo hoặc dùng lại hội thoại |
| GET / POST | `/conversations/:id/messages` | `:58 / :72` | user (thành viên) | đọc / gửi tin |
| PATCH | `/conversations/:id/read` | `:86` | user | đã đọc |
| PATCH | `/conversations/:id/participant-role` | `:98` | user | đổi vai trò (chủ nhà/người thuê) |
| POST | `/conversations/:id/call-token` | `:112` | user | token gọi (LiveKit) |
| POST | `/conversations/:id/media/uploads` | `.../chat-media.controller.ts:14` | user | xin upload |
| POST | `/conversations/:id/media/:storageId/complete` | `:25` | user | hoàn tất upload |
| GET | `/conversations/:id/media/:storageId/view-url` | `:40` | user | URL xem |
| WS | `chat:presence:subscribe/unsubscribe`, `chat:send`, `chat:message:pin/delete/recall`, `chat:call:history-ready/invite/accept/end` (10 `@SubscribeMessage`) | `.../chat.gateway.ts:170-352` | user | realtime |

### 1.6 News (`hs-news-service`) — 12 HTTP
| METHOD | path (GW) | file:line | Ai gọi | Mục đích |
|---|---|---|---|---|
| GET | `/api/v1/news/ping` | `src/modules/health/presentation/health.controller.ts:5` | công khai | sống |
| GET | `/api/v1/news/auth/me` | `.../authentication.controller.ts:9` | user | danh tính |
| POST/GET/GET/PUT/DELETE | `/api/v1/admin/news` , `/{newsId}` | `.../news-admin.controller.ts:32,43,55,60,72` | ADMIN (GW + `AdminGuard`) | CRUD tin |
| POST | `/api/v1/admin/news/media/uploads`, `/{storageId}/complete` | `.../media.controller.ts:27,38` | ADMIN | upload ảnh |
| GET | `/api/v1/public/news`, `/{slug}` | `.../public-news.controller.ts:15,27` | công khai | đọc tin |
| POST | `/api/v1/public/news/{slug}/view` | `:34` | công khai, không chống trùng (`news.service.ts:93` `$inc views` mỗi lần) | tăng lượt xem |

### 1.7 AI (`hs-ai-service`, GW `/api/v1/ai/…`; admin `/api/v1/admin/ai/knowledge/…` ↔ `/admin/knowledge/…`) — 32 HTTP
| METHOD | path (sau `/api/v1/ai`) | file:line (`src/homespace_ai/…`) | Ai gọi | Mục đích |
|---|---|---|---|---|
| GET | `/ping` | `api/v1/router.py:19` | công khai | sống |
| GET | `/auth/me` | `router.py:24` | user | danh tính |
| GET | `/health/live` | `main.py:101` | cần JWT bất kỳ (GW `/api/v1/**` authenticated) | liveness |
| GET | `/health/ready` | `main.py:106` | cần JWT bất kỳ | readiness: secret GW, Postgres AI, Mongo; báo `eureka UP/DEGRADED`; 503 kèm lý do |
| POST | `/agent/ask` | `agent_ask.py:28` | user | hỏi trợ lý |
| GET/POST/DELETE | `/agent/conversations` | `agent_conversations.py:43,52,61` | user | list / tạo / xoá hết |
| POST | `/agent/conversations/claim-guest` | `:72` | user | nhận hội thoại khách |
| GET/PATCH/DELETE | `/agent/conversations/{id}`, `/{id}/pin` | `:87,99,115` | user | chi tiết / ghim / xoá |
| POST | `/agent/rental-requests/review`, `/review/ask` | `rental_request_review.py:70,79` | user (chủ nhà) | duyệt yêu cầu thuê bằng AI |
| GET/POST/POST | `/guest/agent/conversations`, `/ask` | `guest_agent.py:52,57,70` | khách (công khai) | chat khách (chi phí LLM, không cần đăng nhập) |
| POST/POST/GET/PUT | `/admin/knowledge/documents`, `/batch`, `/{id}/content` | `admin_knowledge.py:53,93,120,126` | ADMIN | nạp tài liệu tri thức |
| GET/GET/PATCH | `/admin/knowledge/documents`, `/{id}` | `:135,186,266` | ADMIN | danh sách / chi tiết / sửa metadata |
| POST×4 | `/{id}/approve`, `/publish`, `/reindex`, `/archive` | `:303,319,336,424` | ADMIN | vòng đời tài liệu |
| GET | `/admin/knowledge/jobs/{jobId}` | `:353` | ADMIN | trạng thái job nạp (attempts, error) |
| GET | `/admin/knowledge/worker-health` | `:388` | ADMIN (`security.py:86` `require_admin`) | worker nhúng còn sống (heartbeat ≤45s); đã dùng ở `KnowledgeManagementPage.tsx:79` |
| POST | `/admin/knowledge/search`, `/test-ask` | `:440,493` | ADMIN | chẩn đoán tìm / hỏi thử (tốn LLM) |
`hs-ai-service` KHÔNG có Dockerfile, compose, workflow CI (liệt kê thư mục) và worker nạp tri thức là tiến trình riêng (`RUN.md:24`); admin chỉ biết worker chết khi mở trang tri thức.

### 1.8 Vận hành trong Core (thuộc phạm vi giám sát)
| METHOD | path | file:line | Ai gọi | Mục đích |
|---|---|---|---|---|
| GET | `/api/v1/admin/operational-incidents` | `hs-core-api/hs-api-service/.../OperationalIncidentController.java:12-16` | ADMIN + `INCIDENT_VIEW` | 100 sự cố gần nhất (cả đã đóng); trả thẳng entity gồm `sourceKey` (vd `proof:{documentId}`) |
| GET | `/actuator/health(/liveness,/readiness)`, `/info`, `/prometheus` | `hs-api-service/src/main/resources/application.properties:277-284` | mạng nội bộ (Core `permitAll`, `SecurityConfig.java:26`); qua GW: health công khai, prometheus công khai nhưng nhiều khả năng 404 | health/probe; `show-details` không đặt nên chỉ thấy UP/DOWN, không thấy db/kafka |
Hạn chế: `component` chỉ nhận `BLOCKCHAIN`/`GATEWAY` (`OperationalIncidentService.java:57`); chỉ 2 nơi gọi `observe`: `OperationalHealthMonitor.java:37` (Gateway, 3 lần lỗi liên tiếp) và `ContractDocumentProofWorker.java:194`.

## 2. Tín hiệu giám sát
Cột P: đã có trong P1..P6 / THIẾU. Nguồn "(mới)" = cần thêm API.

### 2.1 Vận hành-kỹ thuật (admin)
| # | Tín hiệu | Vì sao quan trọng | Nguồn | P |
|---|---|---|---|---|
| O1 | Dịch vụ sống/chết: 6 dịch vụ (gateway, core, chat, news, notification, ai) + discovery | mất dịch vụ = mất tính năng; core chỉ theo dõi Gateway (`OperationalHealthMonitor.java:25`) | Eureka `/eureka/apps` (status, lastRenewal) qua `DiscoveryClient` (Core đã có) | THIẾU (P4 chỉ có A4 sự cố mở, `P4:42`) |
| O2 | Backlog + tuổi bản ghi cũ nhất của 8 bảng outbox | thông báo/sự kiện không phát đi = người dùng không biết hạn, tiền | `contract_notification_outbox`, `listing_`, `appointment_`, `rental_request_` , `payment_`, `membership_`, `account_` `…_notification_outbox` và `notification_broadcast_outbox` (cột `sent_at`,`created_at`) | CÓ một phần (`P4:48` khối E). Sai tên: P4 ghi `notification_broadcast_` + `_notification_outbox`; tên thật là `notification_broadcast_outbox` (`BroadcastNotificationOutbox.java:13`) |
| O3 | "Bản ghi độc" chặn outbox | claim `ORDER BY id LIMIT 1` + gửi lỗi → rollback → mỗi nhịp chọn lại đúng hàng đó, mọi hàng sau đứng yên (`ContractNotificationPublisher.java:28-34,70-79`; mẫu như `ListingNotificationPublisher.java:25`); log chỉ có tên lớp lỗi | cùng bảng O2: `unsent>0` và tuổi cũ nhất tăng dần trong khi `sent_at` mới nhất cũng cũ | THIẾU (O2 chỉ báo trễ, không phân biệt) |
| O4 | Outbox chat và news (nằm trong bảng nghiệp vụ Mongo) | tin chat chưa phát thông báo; tin đã đăng chưa gửi broadcast | chat: `messages` có `pendingNotificationRecipientId` và chưa `notificationPublishedAt` (`chat-notification-publisher.ts:73-75`); news: `news_articles` `status=PUBLISHED, publicationSentAt=null` (`news-publication.publisher.ts:58-63`), poll 5s | THIẾU |
| O5 | Kafka DLT: số bản ghi + lệch consumer | DLT chỉ chứa toạ độ nguồn + sha256, phát lại cần quyền (`notification-events.consumer.ts:140-158`); 2 DLT: `KAFKA_DLT_TOPIC` (thông báo), `KAFKA_NEWS_DLT_TOPIC` (Core, `NewsBroadcastConsumer.java:36-45`) | `kafkajs` Admin trong notification-service (đã có `kafka.admin()`, dòng 65); Core không có AdminClient (`P4:48` đã nêu) | THIẾU |
| O6 | Consumer thông báo kẹt vì lỗi lưu | lỗi `PERSISTENCE_FAILED` ném lại, không commit offset, lặp vô hạn (`:134-138`); sự kiện sai định dạng thì vào DLT | độ lệch nhóm `KAFKA_GROUP_ID` | THIẾU |
| O7 | `UserSyncConsumer` nuốt mọi lỗi | lỗi đồng bộ người dùng Keycloak→Core chỉ ghi log rồi coi như xong (`UserSyncConsumer.java:70-72`) → số liệu người dùng lệch Keycloak | không có bảng; so `users` với Keycloak thủ công | THIẾU (cần bảng lỗi/DLT) |
| O8 | Email Resend: pending, đang xử lý, failed, skipped theo `errorCode`; tuổi pending cũ nhất; kênh bật/tắt | email hạn thanh toán/hợp đồng là kênh ngoài app; giới hạn 2 mail/giây = 7.200/giờ (`email-delivery.service.ts:22`); tối đa 5 lần, lùi 1p→1h (`email-outbox.ts:7`, `:33-34`) | Mongo `notification_email_deliveries` (index `status,nextAttemptAt`, `email-outbox.ts:83`); errorCode: `http-4xx/5xx`, `send-failed`, `directory-unavailable`, `document-unavailable`, `lease-expired`, `no-address`, `opted-out`, `attachment-not-ready` | THIẾU; chưa có API đọc |
| O9 | Email bị tắt âm thầm | `provider.enabled=false` → `enqueue` bỏ qua, không để lại dòng nào (`email-delivery.service.ts:85`) | cờ cấu hình trong notification-service | THIẾU |
| O10 | Push (FCM): pending/failed/accepted, token bị vô hiệu hoá | token sai → `active=false` (`notification-delivery.service.ts:349-358`); `PUSH_ENABLED=false` → việc nằm chờ vô hạn (`:238`) rồi bắn dồn khi bật | `notification_deliveries` (index `status,nextAttemptAt`), `notification_devices` | THIẾU |
| O11 | Lịch/cron: lần chạy cuối, lỗi liên tiếp | có 34 `@Scheduled` trong Core + 4 vòng lặp Nest (mục 2.5); không job nào ghi lần chạy/kết quả, lỗi chỉ ở log | KHÔNG CÓ nguồn; thay bằng truy vấn "việc quá hạn mà job lẽ ra đã xử lý" (mục 2.5 cột "Dấu hiệu") | THIẾU |
| O12 | Proof blockchain + sự cố | đã thiết kế | `contract_document_proofs`, `operational_incidents` | CÓ (`P6:42,58`; `P4:42` A4) |
| O13 | Biên lai PDF kẹt | PDF phiếu thu không tạo được → email/tải về lỗi | `payment_receipts`, `membership_receipts` (`pdf_status`,`attempts`,`next_attempt_at`, index `idx_receipt_pdf_queue`; `PaymentReceipt.java:46-55`) | THIẾU |
| O14 | Chữ ký số SmartCA kẹt | hợp đồng không ký được | `signature_requests` status `CREATED`/`PENDING_USER_CONFIRMATION`/embedding quá hạn, `FAILED`/`EXPIRED` | THIẾU |
| O15 | Gateway: số 429, trạng thái từng CB (CLOSED/OPEN/HALF_OPEN), số fallback 503, Redis rate-limit lỗi | bão lỗi/DDoS; Redis lỗi → `RedisRateLimiter` mặc định cho qua (thư viện) | Actuator chỉ mở `health,info` (`application.properties:37`); cần mở `circuitbreakers`,`metrics` + registry | THIẾU |
| O16 | Hàng đợi AI nạp tri thức: job QUEUED/RUNNING/FAILED, tuổi job cũ nhất, worker online | worker chết → tài liệu không tìm được | `knowledge_ingestion_jobs`, `worker_heartbeats` (`models/knowledge.py:146,187`); `/admin/knowledge/worker-health` | CÓ API (đã dùng ở trang Tri thức), P4 THIẾU |
| O17 | Lỗi/độ trễ/token LLM, câu bị cắt | chi phí + chất lượng; khách vãng lai gọi được `/guest/agent/ask` | chỉ log: `openai_api_error`, `openai_usage`, `openai_answer_truncated` (`clients/generative.py:307,337,562`) | THIẾU (không có bảng) |
| O18 | Nearby (TomTom): hạn mức ngày/tháng, job lỗi | mất dữ liệu POI; hạn 40/phút, 2000/ngày, 20000/tháng (`application.properties:156-158`) | `NearbyMetrics` (counter/timer) nhưng không có `/actuator/metrics`; trang `NearbyScansPage.tsx` có file nhưng route `/operations/nearby` chuyển hướng về `/dashboard` (`router/index.tsx:69`) | THIẾU |
| O19 | Container: healthy/unhealthy, restart, bộ nhớ | tất cả service có `restart: unless-stopped`; `mem_limit` GW 768m, Core 1536m, còn lại 512m, AI không đặt | Docker HEALTHCHECK mỗi 30s, 5 lần (`Dockerfile` các repo); Nest chỉ kiểm `/ping`; Dokploy Containers (thủ công) | THIẾU trong app |
| O20 | Hạ tầng: Postgres, Mongo, Redis, Kafka, Gotenberg, Keycloak | nền của mọi thứ | healthcheck trong `hs-infra/docker-compose.yml:19,38,57,80,93,123,193`; **không có Prometheus/Grafana/Alertmanager** (`dokploy.md:207`) | THIẾU (chỉ UI quản trị pgAdmin/RedisInsight/Kafka UI) |

### 2.2 Chủ nhà / Người thuê / Admin-nội dung (tín hiệu từ dịch vụ ngoài Core)
- L1 Thông báo chưa đọc: GET `/api/v1/notifications/unread-count` (`notification.controller.ts:55`). P2:122 chỉ nói "dùng hệ thống thông báo có sẵn"; P1 THIẾU huy hiệu.
- L2 Chat chưa đọc: `GET /api/v1/chat/conversations` (`chat.controller.ts:34`, chưa kiểm có trả số chưa đọc). THIẾU ở P1 và P2.
- L3 Kênh push/email đang tắt nên bỏ lỡ hạn: GET `/preferences` (`:75`); email mặc định chỉ bật PAYMENT/CONTRACT (`email-outbox.ts:42`). THIẾU.
- A1 Tin tức DRAFT/PUBLISHED: `GET /api/v1/admin/news` (`news-admin.controller.ts:43`); lượt xem không tin được. THIẾU ở P4/P5.
- A2 Tiến độ broadcast (`recipient_cursor`,`sent_at` của `notification_broadcast_outbox`), không có API. THIẾU.
- A3 Khối lượng hội thoại chat/AI: Mongo `conversations`,`messages`, không có endpoint admin tổng hợp. THIẾU.

### 2.4 Đề xuất khối "Sức khỏe hệ thống" (P4, thay khối E hiện tại; trả bởi `GET /api/v1/admin/dashboard/overview` hoặc endpoint con, quyền `INCIDENT_VIEW`)
1. **Dịch vụ** (O1, O19): 7 ô UP/DOWN/DEGRADED + "đăng ký lúc"; nguồn Eureka trong Core; thêm sự cố `component` mới (`CHAT`,`NEWS`,`NOTIFICATION`,`AI`,`DISCOVERY`) để tái dùng thông báo cho admin (`OperationalIncidentService.java:57` phải mở rộng).
2. **Outbox 8 bảng + chat + news** (O2-O4): mỗi dòng: chưa gửi, tuổi cũ nhất (giây), mốc gửi gần nhất; cờ "nghi bản ghi độc" khi tuổi cũ nhất > N phút mà có hàng sau đã gửi; ngưỡng cấu hình (đề xuất giữ 60/300 giây của P4).
3. **Kafka** (O5, O6): độ sâu 2 DLT + độ lệch nhóm `KAFKA_GROUP_ID`; cần thêm endpoint trong notification-service dùng `kafkajs` Admin, chạy sau `AdminGuard`.
4. **Email & Push** (O8-O10): pending/failed/skipped 24 giờ theo `errorCode`, tuổi pending cũ nhất, cờ bật/tắt kênh, số token push bị vô hiệu hoá; endpoint admin mới trong notification-service (GW đã coi `notifications/admin/**` là ADMIN: `GatewayRouteAuthorization.java:43`).
5. **Việc nền quá hạn** (O11-O14): mỗi dòng 1 truy vấn đếm "lẽ ra đã xử lý" (xem mục 2.5): tin PUBLISHED hết hạn, thanh toán chờ xác nhận quá hạn, biên lai PDF PENDING/FAILED > 5 phút, `signature_requests` kẹt, KYC quá hạn chưa nhắc.
6. **Blockchain** (O12): 1 dòng tóm tắt + link `/blockchain` (P6), không lặp.
7. **AI** (O16, O17): worker online, job FAILED, số job QUEUED, tuổi cũ nhất; link trang Tri thức. Không làm "Dự báo AI".
8. **Gateway** (O15): 429/giờ, CB mở; cần bật actuator `circuitbreakers`/metrics trước.
Mỗi khối: trạng thái OK/CHÚ Ý/LỖI kèm chữ (không chỉ màu), "cập nhật lúc", lỗi một nguồn không làm hỏng cả khối (như `P4:73`). Quyền: đọc-only; không trả `payload` outbox (chứa userId/nội dung), không trả `sourceKey`.

### 2.5 Mọi lịch/cron (thread: pool 6 luồng dùng chung `SchedulingConfig.java:15-20`; Nearby có pool riêng 2)
Quy ước: hạn "ms" lấy mặc định trong `@Scheduled`, `${…}` không mặc định = giá trị từ env (chưa xem). Mọi job đều chỉ ghi log khi lỗi.
| Job (file:line) | Chu kỳ | Lỗi trông như thế nào / Dấu hiệu truy vấn được |
|---|---|---|
| OperationalHealthMonitor.checkGateway (`hs-api-service/.../OperationalHealthMonitor.java:21`) | 60s (trễ đầu 120s) | 3 lần liền không thấy Gateway UP → mở sự cố `GATEWAY` |
| BlockchainProofScheduler.dispatch (`.../blockchain/worker/BlockchainProofScheduler.java:22`) | 10s | proof `PENDING/SUBMITTED` quá lâu; `FAILED` + `error_code` (P6) |
| BranchDigestScheduler.sendDaily (`.../notification/BranchDigestScheduler.java:35`) | cron 08:00 ICT | lần lỡ không bù (`:` chú thích lớp); không có digest hôm nay trong `contract_notification_outbox` |
| ContractNotificationPublisher ×2 (`:56`, `:73`) | 1s | outbox hợp đồng chưa gửi tăng (O2/O3) |
| Appointment/Listing/RentalRequest/Membership/Payment/Account/Broadcast publisher (`AppointmentNotificationPublisher.java:38`, `ListingNotificationPublisher.java:38`, `RentalRequestNotificationPublisher.java:38`, `MembershipNotificationPublisher.java:39`, `PaymentNotificationPublisher.java:39`, `AccountNotificationPublisher.java:39`, `BroadcastNotificationPublisher.java:47`) | 1s (`notification.outbox.poll-delay-ms`) | outbox bảng tương ứng chưa gửi; cờ `notification.outbox.enabled=false` tắt cả nhóm im lặng |
| SignatureEmbedding (`SignatureEmbeddingScheduler.java:31`) + SignaturePolling (`SignaturePollingScheduler.java:32`) | 5s | `signature_requests` kẹt `CREATED`/`PENDING_USER_CONFIRMATION`; embedding bị `failStaleEmbedding` sau 3 lần/2 phút |
| Nhắc hạn: AgreementSigningReminder (`AgreementSigningReminderScheduler.java:54`, 300s), MembershipExpiryReminder (`MembershipExpiryReminderScheduler.java:28`, 1 giờ), AppointmentReminder (`AppointmentReminderScheduler.java:22`, 60s), PaymentDueReminder (`PaymentDueReminderScheduler.java:26`, 60s) | như ghi | mục sắp hạn trong 24 giờ chưa có nhắc (hợp đồng chờ ký, gói sắp hết, lịch xem, thanh toán); lỗi chỉ log "deferred" |
| ContractAmendment.synchronize (`ContractAmendmentService.java:387`), ContractLifecycle.scheduledSync (`:338`), MonthlyBilling.scheduledSync (`MonthlyBillingService.java:1045`), RentalOnboarding.tick (`RentalOnboardingService.java:772`) | 60s / 60s / 60s / 30s | log `LEASE_LIFECYCLE_SYNC_FAILED`, "Monthly billing sync failed", `RENTAL_ONBOARDING_SYNC_FAILED`; HĐ `ACTIVE` quá `endDate` chưa `EXPIRED`, thiếu hoá đơn kỳ tới, phụ lục/yêu cầu `ACCEPTED` quá hạn |
| NearbyWorker.tick / purge (`.../nearby/NearbyWorker.java:41,101`) | 5s / 1 giờ (pool `nearbyScheduler`) | log `nearby_worker_failed`; job quét lỗi; hạn mức TomTom |
| ListingExpiration (`ListingExpirationScheduler.java:16`, 60s), RentalHoldExpiration (`:18`, 30s), ViewingAppointment (`:20`, cron 15 phút), ListingViewHistory (`:19`, 03:00) | như ghi | tin `PUBLISHED` quá `expiresAt`; giữ chỗ quá hạn còn `PENDING`; lịch `PENDING` quá hạn / `CONFIRMED` đã qua chưa `COMPLETED`; bảng lịch sử xem phình |
| Membership/PaymentReceiptPdfWorker (`.../receipt/MembershipReceiptPdfWorker.java:35`, `PaymentReceiptPdfWorker.java:38`) | 15s (luồng riêng) | `pdf_status` PENDING/FAILED, log `PAYMENT_RECEIPT_PDF_FAILED`; phụ thuộc Gotenberg |
| ReceiptAutoConfirmScheduler (`:30`), RefundAutoConfirmScheduler (`:33`) | 300s | `TRANSFER_REPORTED` quá hạn vẫn chưa tự xác nhận (tiền!) |
| KycRetryNoticeScheduler (`.../KycRetryNoticeScheduler.java:22`) | 60s | lượt VNPT hết hạn chưa `retry_notice_sent_at` |
| VnptKycStore.purgeTemporaryEvidence (`:159`) | 1 giờ | dữ liệu tạm eKYC > 1 ngày còn tồn (riêng tư, cần xoá) |
Ngoài Core: EmailDeliveryService.tick 5s (`email-delivery.service.ts:66-71`), NotificationDeliveryService.tick 5s (`notification-delivery.service.ts:209`), ChatNotificationPublisher 5s (`chat-notification-publisher.ts:40-45`), NewsPublicationPublisher 5s (`news-publication.publisher.ts:33-36`), Eureka heartbeat Nest 30s (`eureka.service.ts:39`), AI worker poll 2s + heartbeat 10s (`config.py:131`, `worker.py:66`).

## 3. Thiếu sót
Dữ liệu chưa có (cần ghi mới):
1. **Không có nơi lưu lần chạy job** (O11): đề xuất bảng `scheduled_job_runs(job_name PK, last_started_at, last_finished_at, last_success_at, consecutive_failures, last_error_class)` ghi bằng một aspect quanh `@Scheduled`; trước mắt dùng truy vấn "quá hạn" ở 2.5.
2. **Outbox thiếu `attempts`, `last_error`, `failed_at`** (cả 8 bảng): không phân biệt "chờ" và "độc"; thêm cột hoặc DLT outbox; nếu không, O3 chỉ ước đoán bằng tuổi.
3. **Notification-service không có endpoint admin** (O8-O10): cần `GET /admin/delivery-health` (đếm theo `status`, theo `errorCode` 24 giờ, tuổi pending cũ nhất, cờ `provider.enabled`, DLT depth/lag). Không lộ email/địa chỉ/`providerMessageId`.
4. **Không có metric**: không có `micrometer-registry-prometheus`; Gateway chỉ `health,info`; Core `prometheus` mở nhưng không có thư viện (nhiều khả năng 404, chưa chạy thử). Không Prometheus/Grafana/Alertmanager trong `hs-infra` (`dokploy.md:207`); `NearbyMetrics` ghi vào registry không ai đọc.
5. **Health nông**: `/ping` ở Nest không kiểm Mongo/Kafka (`health.controller.ts:5`); Core `show-details` tắt. AI `ready` kiểm kỹ nhưng chỉ dùng được với JWT. Mất Mongo → HEALTHCHECK vẫn xanh.
6. **Core chỉ canh Gateway**, 5 dịch vụ còn lại chết không ai báo (O1). Eureka có sẵn dữ liệu.
7. **Mất sự kiện im lặng**: `UserSyncConsumer` nuốt lỗi (O7); email tắt không để dấu vết (O9); `PUSH_ENABLED=false` để việc ứ đọng rồi bắn dồn (O10); broadcast `QUEUED` không có API xem tiến độ (A2).
8. **Lượt xem tin không tin được** (`news.service.ts:93`): `$inc` mỗi POST công khai, không chống trùng; đừng đưa vào P5 như số thật.
9. **Rate limit theo IP socket**: sau reverse proxy có thể gom mọi khách vào một xô 50 rps/burst 100; 429 không được đếm; 4 route (2 socket chat, 1 socket thông báo, `/u/**`) không có giới hạn/CB.

Endpoint đáng ngờ/không an toàn cho dashboard:
- `GET /api/v1/admin/operational-incidents` (`OperationalIncidentController.java:16`): trả entity (có `sourceKey`), giới hạn 100, không có đếm `open` → P4 A4 phải đếm bằng truy vấn riêng.
- `/api/v1/actuator/prometheus` công khai ở GW (`SecurityConfig.java:40`): bỏ allow-list, hoặc chỉ nội bộ.
- `/api/v1/ai/property-search*` công khai nhưng không có handler: xoá allow-list (mở bề mặt không dùng).
- `POST /guest/agent/ask`, `/admin/knowledge/test-ask`: tốn LLM; GW chỉ có giới hạn IP chung.
- Eureka dashboard không xác thực nếu bật domain (`dokploy.md:35`).
- `dokploy.md:714` mô tả sai đường health (xem 1.1); cần sửa khi làm P4.
- Trang `/operations/knowledge` không bọc `RequirePermission` (`router/index.tsx:131`); chặn thật ở GW (`ADMIN`), nhưng không theo quyền chi tiết.
- Số `8 bảng outbox` ở `P4:48` đúng, nhưng tên bảng broadcast sai (xem O2).

Chưa kiểm chứng (không chạy dịch vụ): giá trị env thật (`NOTIFICATION_OUTBOX_POLL_DELAY_MS`, ngưỡng nearby…), có/không 404 ở `/actuator/prometheus`, GW có thấy IP thật sau proxy, chat `list` có trả số chưa đọc (L2).
