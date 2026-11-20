# Deploy Core API thành Application riêng trên Dokploy

Mỗi backend là một **Application riêng**, có image, Environment, deployment history, webhook và CI/CD riêng. `homespace-infra` chỉ quản lý 11 service hạ tầng. Core dùng image `nvminh162/hs-core-api:latest`, cổng nội bộ 8081; không gộp vào Compose infra.

## 1. File và secret đã chuẩn bị

- `Dockerfile`: Java 21, Maven Wrapper build/test toàn bộ assembly `hs-api-service`, JAR thực thi, runtime non-root, health check `/actuator/health` cổng 8081.
- `.dockerignore`: chỉ đưa Maven inputs vào build; không đưa env/private keys/output local vào image.
- `.github/workflows/publish-image.yml`: pull request build/test; push `master` publish tag SHA và `latest`; Docker Hub gửi webhook tới Application Core khi tag `latest` được push.
- `.env.dokploy`: Environment riêng của Core, giữ secret tích hợp, khóa mã hóa, secret Gateway và mật khẩu bootstrap cũ. Địa chỉ/credential PostgreSQL, Redis, Kafka, Keycloak và Gotenberg đã đổi sang infra production. `.env` local giữ nguyên.

Dán file **`D:/Workspace/homespace/hs-core-api/.env.dokploy`** vào Application Core. File `hs-infra/.env.dokploy` chỉ dành cho infra. Cả hai được Git ignore.

`KEY='value'` là cú pháp env hợp lệ; Dokploy dùng dotenv để bỏ quote và giữ nguyên `$`. `KEY=''` là giá trị rỗng. Chỉ `HOMESPACE_TIME_SIMULATED_AT` và `SMARTCA_WEBHOOK_PUBLIC_URL` được phép rỗng theo cấu hình Core. Không bỏ quote quanh secret có `$`.

## 2. Nối mạng Application với infra một lần

Production dùng chung mạng **overlay attachable `hs-network`**. Compose infra tạo mạng trong lần deploy đầu; giữ infra hiện tại khi thêm Core. Không tạo lại mạng hoặc mở database/Kafka ra public IP. File `docker-compose.yml` dành cho local; trên Dokploy infra dùng `compose.production.yml`.

Kiểm tra trên EC2 trước khi deploy Core:

```bash
sudo docker network inspect hs-network \
  --format 'driver={{.Driver}} attachable={{.Attachable}}'
```

Kết quả cần `driver=overlay attachable=true`. Nếu mạng chưa có, deploy infra trước; nếu sai loại, kiểm tra cấu hình hiện có, không xóa mạng đang dùng.

Infra có 11 service, không chứa Core; các named volumes phải giữ nguyên. Không cần deploy lại infra chỉ để thêm Application Core. Discovery dùng Compose riêng với DNS `hs-discovery-server`; Gateway đang thử nghiệm Compose riêng, Core vẫn giữ Application.

1. Xác nhận infra và Discovery healthy.
2. Giữ nguyên App Name, Environment, domains và volumes của infra; không tạo stack infra thứ hai.
3. Gắn Application Core vào `hs-network` theo mục 4. Giữ `dokploy-network` cho routing của Dokploy nếu đang dùng.
4. Nếu đã có Core trong một Compose cũ, dừng đúng Core cũ trước khi chạy Application Core mới; không dừng database hoặc xóa volume.

Sau lần nối mạng này, cập nhật Core không cần deploy lại infra.

## 3. GitHub secrets và image đầu tiên

Trong Docker Hub tạo repository **`nvminh162/hs-core-api`**.

Trong GitHub repo `homespace-platform/hs-core-api` → **Settings → Secrets and variables → Actions**:

| Secret | Nguồn |
| --- | --- |
| `DOCKERHUB_USERNAME` | Organization secret hiện có, username `nvminh162`. |
| `DOCKERHUB_TOKEN` | Organization secret hiện có, PAT có quyền push. |

Org secrets phải cấp quyền cho repo Core. Nếu repo private và gói GitHub không cho dùng org secrets, thêm hai Docker Hub secrets trực tiếp vào repo.

Commit/push các file Core đã chuẩn bị lên `master`. Mở **GitHub → Actions → Build and publish Core API** và chờ `publish` thành công. Workflow chạy Maven tests trước khi publish:

```text
nvminh162/hs-core-api:latest
nvminh162/hs-core-api:<git-sha>
```

GitHub Actions chỉ build/test/push; không có job deploy và không cần secret webhook. Các PostgreSQL integration tests có điều kiện trong repo vẫn skip khi chưa cấp DB test; không dùng DB production để chạy CI.

## 4. Tạo Application Core trên Dokploy

Vào **Projects → homespace → production → Create Service → Application**.

- Name: **`hs-core-api`**.
- Server: cùng Dokploy Server với infra.
- Create, sau đó mở Application vừa tạo.

Trong **General → Provider**, chọn **Docker**:

| Trường | Giá trị |
| --- | --- |
| Docker Image / Image | `nvminh162/hs-core-api:latest` |
| Registry URL, nếu được yêu cầu | `docker.io` |
| Credentials, nếu image private | Docker Hub username và token có quyền pull |

Bấm Save. Dockerfile đã được GitHub Actions build; Dokploy chỉ pull image này. Không chọn build GitHub/Dockerfile thêm lần nữa trên EC2.

Trong **Environment**, dán toàn bộ `hs-core-api/.env.dokploy` rồi Save. Không dán env infra hoặc biến `CORE_*`.

Trong **Advanced → Networks**, chọn `hs-network`, đặt alias `core-api` nếu giao diện có trường Aliases, Save. Nếu dùng **Advanced → Swarm Settings → Network**, thêm Target `hs-network`, Aliases `core-api`, rồi Save Networks. Giữ kết nối mặc định `dokploy-network`; không bật Detach from dokploy-network trong bước này. Nếu mạng vừa tạo bằng CLI chưa hiện, vào Docker → Networks → Sync rồi quay lại.

Trong **Advanced → Resources**, đặt Memory Limit **`1610612736`** bytes (1536 MiB), replica **1**. Nếu UI cho nhập đơn vị trực tiếp, chọn tương đương 1536 MiB. Không điền `1536m` vào trường chỉ nhận số byte.

Trong **Domains**, chưa tạo domain riêng cho Core. Cổng container là **8081**, health check lấy từ image. Gateway sau này dùng Eureka để gọi `hs-core-api`; domain public `api.homespace.id.vn` sẽ trỏ tới Gateway cổng 8080.

Triển khai **Discovery → Core → Gateway**. Core đã đặt `SPRING_CLOUD_DISCOVERY_ENABLED=true` và `EUREKA_CLIENT_ENABLED=true`, URL `http://hs-discovery-server:8761/eureka/`. Deploy Compose Discovery và xác nhận healthy trước Core. Nếu cần chạy Core riêng trước Discovery, tạm đổi cả hai cờ thành `false` trong Environment rồi bật lại khi Discovery sẵn sàng.

## 5. Deploy lần đầu và kiểm tra

Application `hs-core-api` → **General → Deploy**, mở **Deployments → View** và chờ hoàn tất. Trong **Logs**, cần thấy Spring Boot khởi động, không lặp lỗi thiếu env, kết nối DB/Redis/Kafka hoặc Keycloak.

Trong **Docker → Swarm / Containers**, Core cần running và healthy. Application App Name trên Dokploy có hậu tố riêng; copy tên đó để kiểm tra qua Ubuntu:

```bash
CORE_SERVICE='DAN_APP_NAME_CUA_APPLICATION_CORE'
sudo docker service ps "$CORE_SERVICE" --no-trunc
CORE_CONTAINER=$(sudo docker ps -q \
  --filter "label=com.docker.swarm.service.name=$CORE_SERVICE")
if [ -n "$CORE_CONTAINER" ]; then
  sudo docker exec "$CORE_CONTAINER" curl --fail --silent --show-error \
    http://127.0.0.1:8081/actuator/health
  sudo docker exec "$CORE_CONTAINER" curl --fail --silent --show-error \
    http://127.0.0.1:8081/actuator/health/readiness
  sudo docker exec "$CORE_CONTAINER" curl --fail --silent --show-error \
    http://keycloak:8080/realms/homespace-platform/.well-known/openid-configuration \
    --output /dev/null --write-out 'Internal Keycloak: %{http_code}\n'
else
  echo 'Core has no running task; inspect docker service ps and Dokploy deployment logs.'
fi
```

Kết quả cần health/readiness `UP`, Keycloak 200. Nếu không kết nối `postgres`, `redis`, `kafka` hoặc `keycloak`, kiểm tra cả Application và container infra đã vào `hs-network`.

Database của Core là `homespace_core`. Nếu thiếu DB, kiểm tra trạng thái hiện tại rồi tạo có chủ đích; không reset volume để chạy lại init. Core có thể tạo/cập nhật schema theo `JPA_DDL_AUTO` và chạy initializer trong lần đầu. Trong Keycloak realm `homespace-platform` → Users, kiểm tra hai tài khoản bootstrap và quyền; health UP không tự chứng minh admin bootstrap thành công.

## 6. Gắn webhook trên Docker Hub

1. Application **`hs-core-api` → General**: bật **Autodeploy**, Save. Provider phải là **Docker**, image `nvminh162/hs-core-api:latest`.
2. Trong **Deployments**, copy Webhook URL của chính Application Core: `https://dokploy.homespace.id.vn/api/deploy/TOKEN`.
3. Mở **Docker Hub → Repositories → nvminh162/hs-core-api → Webhooks**.
4. Name: **`dokploy-core-api`**. Webhook URL: dán URL Application Core vừa copy. Bấm **Create / Add webhook**.
5. GitHub → **Actions → Build and publish Core API → Run workflow → master** hoặc push commit mới.
6. Chờ job `publish` thành công, kiểm tra **Docker Hub → Webhooks → View History**, rồi **Dokploy → hs-core-api → Deployments** và health runtime.

```text
Push master hs-core-api
  → GitHub Actions chạy Maven test + Docker build
  → push Docker Hub nvminh162/hs-core-api:latest + SHA
  → Docker Hub webhook
  → Dokploy cập nhật riêng Application hs-core-api
```

Dokploy so khớp repository và tag `latest` đã cấu hình trong Docker provider. Webhook cho tag SHA khác có thể bị bỏ qua/từ chối vì không khớp tag; kiểm tra lượt gửi cho `latest`. Không thêm webhook GitHub hoặc job gọi Dokploy song song. Repo Core chỉ cần hai Docker Hub secrets; `DOKPLOY_DEPLOY_WEBHOOK` không còn được workflow dùng và có thể xóa nếu đã tạo nhầm. Giữ webhook infra/Keycloak riêng.

Các credentials được giữ nguyên, chưa xác minh hạn dùng/quyền của AWS, VNPT, SmartCA, SePay hoặc blockchain. Các callback URL production chỉ hoạt động sau khi Gateway/frontend được deploy. Không public port DB/Kafka/Core lên Security Group.

Tham khảo: [Dokploy Applications](https://docs.dokploy.com/docs/core/applications) · [Mạng Dokploy](https://docs.dokploy.com/docs/core/networks) · [Application Advanced](https://docs.dokploy.com/docs/core/applications/advanced).
