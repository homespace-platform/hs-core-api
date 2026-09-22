# HƯỚNG DẪN TEST TẢI CHỨNG TỪ TỪ ĐIỆN THOẠI (DEV TUNNEL GATEWAY 8080)

Tài liệu này hướng dẫn cách kiểm thử chức năng quét mã QR bằng điện thoại để tải chứng từ chuyển khoản sang phiên làm việc trên máy tính Desktop.

---

## 1. NGUYÊN LÝ HOẠT ĐỘNG

- **Form upload** (`GET/POST /u/payment-proof/{token}`) được phục vụ bởi **`hs-api-service`** (chạy nội bộ tại port `8081`).
- **Điện thoại** truy cập form tải chứng từ thông qua **API Gateway** (chạy tại port `8080`).
- Gateway cấu hình route `/u/**` chuyển tiếp nguyên vẹn đường dẫn sang `hs-api-service` mà không strip prefix.
- **Biến môi trường `PAYMENT_PROOF_UPLOAD_PUBLIC_BASE_URL`** cấu hình public URL của Gateway sau khi được port forwarding (Dev Tunnel).
- Backend tự động chuẩn hóa base URL và sinh link đầy đủ cho QR code:
  `{PAYMENT_PROOF_UPLOAD_PUBLIC_BASE_URL}/u/payment-proof/{token}`
- Chỉ cần forward duy nhất **Gateway port 8080**. Không cần forward port 8081 (backend), port 3000 (frontend) hay port 9000 (Keycloak).

---

## 2. CÁC BƯỚC THỰC HIỆN TEST (DEVELOPMENT)

### Bước 1: Khởi động các dịch vụ
1. Chạy Eureka Discovery Server (`hs-discovery-server`).
2. Chạy API Gateway (`hs-gateway-server`, port **8080**).
3. Chạy Core Backend (`hs-api-service`, port **8081**).
4. Chạy Frontend (`hs-web-app`, port **3000**).

### Bước 2: Forward port Gateway 8080 qua VS Code Ports
1. Mở tab **Ports** trong VS Code (hoặc phím tắt `Ctrl + ~` -> tab **Ports**).
2. Bấm **Forward a Port** (Chuyển tiếp cổng) và nhập port: `8080` (Gateway).
3. Chuột phải vào port 8080 vừa forward:
   - **Port Visibility**: Chọn **Public** (hoặc **Private** nếu dùng chung tài khoản).
   - > [!WARNING]
   - > **Cảnh báo về Public Tunnel**: Public Dev Tunnel chỉ dùng tạm thời trong quá trình phát triển (development). Token upload là token ngẫu nhiên mạnh, hết hạn sau 10 phút và chỉ dùng 1 lần, nhưng tuyệt đối không dùng dữ liệu thật nhạy cảm và nên tắt port forwarding sau khi hoàn thành test.
   - > **Lưu ý Private Tunnel**: Nếu đặt Visibility là Private, trình duyệt trên điện thoại có thể yêu cầu đăng nhập cùng tài khoản Microsoft hoặc GitHub đã tạo tunnel trước khi mở được trang tải chứng từ.
4. Bấm chuột phải vào cột **Forwarded Address** và chọn **Copy Local Address** hoặc **Copy Forwarded Address**.
   - Ví dụ: `https://13zp4kj8-8080.asse.devtunnels.ms`

### Bước 3: Cấu hình biến môi trường
Mở file `hs-core-api/.env.dev` (hoặc cấu hình environment trong IDE run configuration):
```properties
PAYMENT_PROOF_UPLOAD_PUBLIC_BASE_URL=https://13zp4kj8-8080.asse.devtunnels.ms
```
*(Lưu ý: Không thêm dấu gạch chéo `/` ở cuối URL; không commit URL tunnel cá nhân vào Git repository).*

### Bước 4: Khởi động lại (Restart) `hs-api-service`
Khởi động lại tiến trình `hs-api-service` để nhận biến môi trường mới.

### Bước 5: Mở web và quét mã QR kiểm thử
1. Trên máy tính desktop, truy cập ứng dụng web `http://localhost:3000`.
2. Vào chi tiết yêu cầu thuê phòng đang ở trạng thái cần thanh toán -> Bấm **"Tôi đã chuyển khoản"**.
3. Chọn tab **"Tải chứng từ từ điện thoại"**.
4. Mã QR sẽ được tạo chứa URL public:
   `https://13zp4kj8-8080.asse.devtunnels.ms/u/payment-proof/{token}`
   *(Bên dưới mã QR có hiển thị link và nút **"Sao chép liên kết"** để kiểm tra hoặc dán thủ công nếu cần).*
5. Dùng camera điện thoại (kết nối mạng 4G/5G hoặc Wi-Fi) quét mã QR.
6. Màn hình điện thoại hiển thị form giao diện **HomeSpace - Tải chứng từ chuyển khoản**:
   - Hiển thị mã yêu cầu, nội dung chuyển khoản, số tiền và đồng hồ đếm ngược 10 phút.
   - Chọn ảnh biên lai từ thư viện ảnh hoặc chụp ảnh trực tiếp từ camera điện thoại.
   - Bấm **"Tải chứng từ lên"**.
7. Sau khi tải lên thành công, điện thoại hiển thị trang xác nhận hoàn tất.
8. Màn hình máy tính Desktop tự động nhận diện ảnh biên lai (trạng thái `UPLOADED`) trong vòng 2.5 giây.
9. Người thuê kiểm tra ảnh và bấm **"Gửi chứng từ cho chủ nhà xác nhận"**.

---

## 3. LƯU Ý KHI GẶP LỖI

- **Lỗi "Chưa cấu hình URL công khai cho tải chứng từ"**:
  Biến môi trường `PAYMENT_PROOF_UPLOAD_PUBLIC_BASE_URL` đang để trống và hệ thống phát hiện request xuất phát từ IP nội bộ (ví dụ: `172.31.x.x` hay `localhost`). Hãy điền URL tunnel port 8080 vào `.env.dev` và restart backend.
- **Điện thoại không tải được trang form**:
  Kiểm tra xem Dev Tunnel port 8080 trong VS Code còn đang chạy (Active) không. Nếu đổi tunnel URL, hãy cập nhật lại `PAYMENT_PROOF_UPLOAD_PUBLIC_BASE_URL` trong `.env.dev` và restart `hs-api-service`.
- **Cấu hình trên Production**:
  Trên môi trường Production, biến môi trường được đặt là domain chính thức của API Gateway (ví dụ: `https://api.homespace.vn`). Hệ thống không lưu trữ bất kỳ URL Dev Tunnel nào trong database.
