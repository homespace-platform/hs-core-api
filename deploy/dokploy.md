# Core API — Compose riêng trên Dokploy

Core dùng cùng cách với Discovery/Gateway: service name `hs-core-api`, external network `hs-network`. Không gộp Core vào infra, không chỉnh Advanced → Networks hoặc thêm alias trùng tên. Domain API vẫn thuộc Gateway: `api.homespace.id.vn`.

## 1. File build và Environment

- [compose.production.yml](../compose.production.yml): image đọc từ `${CORE_IMAGE:?}`, restart `unless-stopped`, memory limit 1536 MiB, không publish port host hoặc mount dữ liệu. Đặt `CORE_IMAGE=nvminh162/hs-core-api:latest` ở dòng đầu `.env.prod`; nếu pin SHA, đổi tag trong biến này. Không thêm biến image vào `.env` hoặc `.env.example` của dev.
- Dockerfile hiện có: Java 21, Maven build/test assembly `hs-api-service`, runtime non-root, healthcheck `http://127.0.0.1:8081/actuator/health`, start period 180 giây.
- Workflow `publish-image.yml` giữ nguyên: GitHub Actions test/build/push tag SHA và `latest`, không gọi Dokploy API.
- `.env.prod`: env production riêng tư, Git ignore. Không dán env infra/Gateway thay cho Core. `.env` và `.env.example` dành cho local, không dùng để triển khai production.

Compose yêu cầu `CORE_IMAGE` và đủ **68 biến runtime** từ `.env.prod`, không có fallback. Các cờ `SPRING_CLOUD_DISCOVERY_ENABLED`, `EUREKA_CLIENT_ENABLED` đặt cạnh `EUREKA_CLIENT_SERVICE_URL` trong nhóm Eureka. Mọi biến dùng `${VARIABLE:?}` để chặn thiếu/rỗng, trừ `HOMESPACE_TIME_SIMULATED_AT` và `SMARTCA_WEBHOOK_PUBLIC_URL` dùng `${VARIABLE?}`: bắt buộc khai báo nhưng được phép rỗng như cấu hình hiện tại. Không điền thời gian giả lập/callback giả để vượt validation.

`application.properties` đọc hai cờ Eureka từ env cho cả dev/prod, không ghi cứng `true`. Dev đặt biến trong nhóm **EUREKA SERVER** của `.env` theo `.env.example`; production dùng `.env.prod` rồi truyền qua Compose. Đặt cả hai `true` khi dùng Eureka; nếu chủ đích chạy standalone thì đặt cả hai `false`. Không lấy URL/credential production đưa vào file dev.

`KEY='value'` là cú pháp dotenv hợp lệ của Dokploy. Giữ quote quanh secret có ký tự đặc biệt; không đưa env, private key hoặc webhook token lên Git/chat.

## 2. Kiểm tra infra hiện tại

Giữ nguyên infra, volumes, Discovery và Gateway. Core chỉ tham gia mạng đã có:

```bash
sudo docker network inspect hs-network --format 'driver={{.Driver}} attachable={{.Attachable}}'
```

Cần `driver=overlay attachable=true`. Nếu chưa có mạng, hoàn tất infra trước, không tạo mạng khác cùng tên.

| Biến | Giá trị/điều kiện |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `EUREKA_CLIENT_SERVICE_URL` | `http://hs-discovery-server:8761/eureka/` |
| `SPRING_CLOUD_DISCOVERY_ENABLED`, `EUREKA_CLIENT_ENABLED` | `true` |
| `DATASOURCE_URL` | `jdbc:postgresql://postgres:5432/homespace_core` |
| `DATASOURCE_USERNAME`, `DATASOURCE_PASSWORD` | Khớp PostgreSQL infra |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | `redis`, `6379`, password khớp infra |
| `KAFKA_BOOTSTRAP_SERVERS` | `kafka:19092` |
| `KAFKA_CHAT_PROFILE_TOPIC` | `homespace.user.chat-profile.v1`, cùng giá trị với Infra và Chat |
| `KEYCLOAK_SERVER_URL` | `http://keycloak:8080` |
| `KEYCLOAK_ADMIN_USERNAME` | `homespace` theo env infra hiện tại; phải là tài khoản có thật và có quyền |
| `KEYCLOAK_ADMIN_PASSWORD` | Mật khẩu thật của tài khoản quản trị Keycloak đang tồn tại |
| `KEYCLOAK_CURRENT_REALM` | `homespace-platform` |
| `CONTRACT_GOTENBERG_URL` | `http://gotenberg:3000` |
| `GATEWAY_INTERNAL_SECRET` | Giống Gateway; không đổi để vượt lỗi auth |
| `PAYMENT_PROOF_UPLOAD_PUBLIC_BASE_URL` | `https://api.homespace.id.vn` |
| `PAYMENT_SEPAY_RETURN_URL` | `https://homespace.id.vn/dashboard/membership/return` |

Database `homespace_core` phải tồn tại. Init SQL chỉ chạy khi volume trống; nếu thiếu DB, kiểm tra bằng pgAdmin và xử lý có chủ đích, không reset/xóa volume để ép init.

Giữ `JPA_DDL_AUTO` hiện có. Env đang dùng `update`, Core có thể thay đổi schema khi khởi động; sao lưu database đã có dữ liệu trước khi deploy image/schema mới. Không tự chọn chế độ tạo lại/xóa schema để vượt lỗi.

Bootstrap username Keycloak không đổi tên user trong database cũ. Nếu hệ thống thực tế vẫn dùng `homespace-admin`, dùng đúng tài khoản đã tồn tại; không reset Keycloak/realm để khớp file env. `BOOTSTRAP_ADMIN_*` của Core là tài khoản ứng dụng, khác tài khoản quản trị Keycloak.

## 3. Chuẩn bị image

1. GitHub `homespace-platform/hs-core-api` → Settings → Secrets and variables → Actions: repo cần `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN` có quyền push.
2. Chạy **Actions → Build and publish Core API → Run workflow → master**, hoặc push thay đổi được review; chờ thành công, kiểm tra image/tag trên Docker Hub. Không cần rebuild chỉ vì thêm YAML nếu dùng image đã publish.
3. Nếu image private, cấu hình registry pull credential riêng trên Dokploy; không đưa Docker Hub token vào Environment của Core.

## 4. Tạo Compose Core — Raw

Luồng như Discovery/Gateway Raw: **push code → Actions test/build/push → Docker Hub webhook → Dokploy deploy riêng Core**. Infra vẫn Git và deploy thủ công.

1. **Projects → homespace → production → Create Service → Docker Compose**, tên `hs-core-api`, cùng server với infra. Không tạo Application/Nixpacks.
2. General → **Raw**, dán toàn bộ [compose.production.yml](../compose.production.yml), Compose Type **Docker Compose**, không chọn Stack → Save.
3. **Environment**: dán đầy đủ `hs-core-api/.env.prod`, có `CORE_IMAGE` ở dòng đầu → Save. File local không tự cập nhật Dokploy; YAML đã ánh xạ đủ biến runtime vào container.
4. **Preview Compose**: service `hs-core-api`, default network external tên `hs-network`, đúng image, đầy đủ env; không có host `ports:`. Giữ tắt Isolated Deployment, xác nhận Preview giữ `hs-network`. Không cần network/alias ở Advanced.
5. **Không tạo domain public cho Core**, không mở cổng `8081` trên EC2. Gateway gọi `lb://hs-core-api` qua Eureka.
6. Core không cần File Mount SQL/pgAdmin hoặc volume database; chúng thuộc infra.
7. Nếu có Core cũ, ghi lại image/tag/config, tắt Autodeploy và vô hiệu hóa webhook của bản cũ rồi Stop đúng Core cũ trước khi chạy bản mới. Không dừng database/infra và không chạy song song hai Core ngoài kế hoạch.
8. **General → Deploy**, chờ container healthy. Xem Logs nếu thiếu env hoặc lỗi DB/Redis/Kafka/Keycloak; không đổi JPA/auth hoặc bỏ secret để vượt lỗi.

## 5. Kiểm tra Core, Eureka và Gateway

Trên EC2:

```bash
sudo docker ps --filter label=com.docker.compose.service=hs-core-api --format 'table {{.ID}}\t{{.Names}}\t{{.Status}}'
CORE_CONTAINER='DAN_CONTAINER_ID_CORE_DANG_CHAY_O_TREN'
sudo docker exec "$CORE_CONTAINER" getent hosts postgres redis kafka keycloak hs-discovery-server
sudo docker exec "$CORE_CONTAINER" curl -fsS --max-time 5 http://127.0.0.1:8081/actuator/health
sudo docker exec "$CORE_CONTAINER" curl -fsS --max-time 5 http://127.0.0.1:8081/actuator/health/readiness
sudo docker exec "$CORE_CONTAINER" curl -fsS --max-time 5 -H 'Accept: application/json' http://hs-discovery-server:8761/eureka/apps/HS-CORE-API
```

Health/readiness cần `UP`; Eureka cần **HS-CORE-API — UP** bên cạnh HS-GATEWAY-SERVER. Chờ client đăng ký/Gateway cập nhật registry; không tắt self-preservation hoặc kiểm tra auth để che lỗi.

Trên máy cá nhân:

```bash
curl --fail --silent --show-error https://api.homespace.id.vn/actuator/health
curl --fail --silent --show-error https://api.homespace.id.vn/api/v1/actuator/health/readiness
curl --fail --silent --show-error 'https://api.homespace.id.vn/api/v1/public/listings?page=1&size=1'
```

Lệnh đầu kiểm tra Gateway, hai lệnh sau qua Core. Gateway UP không tự chứng minh Core healthy. Kiểm tra anonymous vẫn bị từ chối ở API quản trị và đăng nhập frontend để xác minh identity propagation. Health không xác minh quyền/hạn dùng credential AWS, VNPT, SmartCA, SePay hoặc blockchain.

Nếu chuyển đổi lỗi: Stop Compose Core mới trước rồi Start bản cũ với cấu hình đã lưu. Chỉ quay về image cũ khi schema tương thích; không xóa database để rollback.

## 6. Docker Hub webhook

1. Sau lần Deploy đầu thành công, **Compose Core → General → bật Autodeploy**.
2. **Deployments → Webhook URL**: copy URL đúng Compose Core, dạng `https://dokploy.homespace.id.vn/api/deploy/compose/TOKEN`.
3. Docker Hub → `nvminh162/hs-core-api` → **Webhooks → New webhook**, name `dokploy-core-api`, URL vừa copy → Save. Vô hiệu hóa webhook Core Application cũ; không dùng URL `/api/deploy/TOKEN` hoặc webhook infra/Discovery/Gateway.
4. Khi push image mới, kiểm tra Docker Hub delivery, deployment Core mới và health/Eureka/Gateway. Raw giữ `pull_policy: always`; dùng tag `latest` nếu muốn cập nhật theo latest mới.
5. Raw Compose v0.31.4 không lọc tag: workflow push SHA và latest có thể gây hai delivery/deploy. Sự kiện SHA chưa chứng minh latest đã cập nhật; xác nhận deployment sau lượt push latest và image đang chạy.
6. Khi sửa YAML, cập nhật bản Raw trên Dokploy và Save; Git không tự sửa Raw. Không thêm webhook GitHub chạy trước khi image build xong.

## 7. Nếu dùng Git provider

Repository URL `https://github.com/homespace-platform/hs-core-api.git`, branch `master`, Compose Path `./compose.production.yml`; repo private cần quyền đọc. File YAML phải có trên Git, Environment vẫn phải dán đầy đủ.

Tắt Autodeploy, không nối Docker Hub webhook trực tiếp vào Compose Git v0.31.4 vì payload thiếu branch. Chờ image publish thành công rồi Deploy thủ công; workflow hiện chưa có bước deploy sau CI. Network/DNS và cách kiểm tra Core không đổi giữa Git và Raw.

[Dokploy Compose](https://docs.dokploy.com/docs/core/docker-compose) · [Compose webhook v0.31.4](https://github.com/Dokploy/dokploy/blob/v0.31.4/apps/dokploy/pages/api/deploy/compose/%5BrefreshToken%5D.ts)
