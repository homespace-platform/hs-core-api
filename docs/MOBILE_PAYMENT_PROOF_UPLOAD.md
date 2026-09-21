# HƯỚNG DẪN TEST TẢI CHỨNG TỪ CHUYỂN KHOẢN TRÊN THIẾT BỊ DI ĐỘNG (LOCAL DEVELOPMENT)

Tài liệu này hướng dẫn cách kiểm thử toàn diện tính năng **Tải chứng từ chuyển khoản** trên môi trường Localhost bao gồm cả hai hình thức:
1. **Direct Mobile Web Upload**: Mở trực tiếp trình duyệt trên điện thoại và chọn ảnh/chụp ảnh.
2. **Cross-Device QR Handoff**: Mở phiên thanh toán trên máy tính Desktop, quét mã QR dùng một lần bằng điện thoại để tải ảnh lên, sau đó màn hình Desktop tự động nhận ảnh và cho phép người thuê gửi chứng từ cho chủ nhà.

Đặc biệt, hệ thống được thiết kế để **không bao giờ phải thay đổi biến môi trường (`.env`, `application.properties`)** mỗi khi thay đổi mạng hoặc thiết bị test.

---

## 1. NGUYÊN TẮC NGHIỆP VỤ CỦA TÍNH NĂNG

1. **HomeSpace không phải là ví điện tử hay cổng thanh toán trung gian**: Tiền thuê chuyển khoản trực tiếp từ tài khoản ngân hàng của người thuê sang tài khoản của chủ nhà (theo thông tin VietQR trên phiếu hướng dẫn).
2. **Chứng từ do người thuê khai báo**: Sau khi chuyển khoản, người thuê tải biên lai lên hệ thống. `PaymentRequest` chuyển sang trạng thái `TRANSFER_REPORTED`.
3. **Chủ nhà là người duy nhất xác nhận**: Chủ nhà tự kiểm tra tài khoản ngân hàng thực tế của mình và bấm **"Xác nhận đã nhận đủ tiền"** (chuyển `PaymentRequest` sang `CONFIRMED`) hoặc **"Chưa nhận được tiền"** (chuyển sang `REJECTED`).
4. **Không tự động xác nhận thanh toán**: Việc quét QR, tải ảnh, hay bấm "Tôi đã chuyển khoản" **tuyệt đối không** tự động chuyển giao dịch thành `CONFIRMED`.

---

## 2. KIẾN TRÚC VÀ CƠ CHẾ URL ĐỘNG (ZERO ENV MUTATION)

Thay vì hardcode `localhost`, `192.168.x.x` hay một domain cố định:
- Backend `hs-payment-service` và `hs-api-service` tự động trích xuất Host và Protocol từ HTTP request hiện tại hoặc từ các Forwarded Headers (`X-Forwarded-Host`, `X-Forwarded-Proto`) do API Gateway / Reverse Proxy gửi tới.
- Cấu hình Spring Boot:
  ```properties
  server.forward-headers-strategy=framework
  ```
- Endpoint `/api/v1/payment-requests/{id}/proof-upload-sessions` luôn trả về cả hai trường:
  - `uploadPath`: `/u/payment-proof/{rawToken}` (đường dẫn tương đối an toàn)
  - `uploadPageUrl`: `http(s)://{current-host}/u/payment-proof/{rawToken}` (URL đầy đủ để hiển thị mã QR)

Vì vậy, **bạn mở Desktop Web bằng địa chỉ nào thì mã QR sẽ tự động sinh ra URL khớp với địa chỉ đó**, đảm bảo điện thoại có thể quét và mở đúng địa chỉ mà không phải sửa `.env`.

---

## 3. BA PHƯƠNG PHÁP KIỂM THỬ LOCAL KHÔNG CẦN SỬA ENV

### PHƯƠNG PHÁP 1: Dùng cáp USB với ADB Reverse (Khuyến nghị cho Android)

Phương pháp này hoàn hảo cho lập trình viên có điện thoại Android cắm cáp USB vào máy tính.

#### Cách hoạt động:
Lệnh `adb reverse` chuyển tiếp các port từ điện thoại về chính máy tính phát triển. Khi đó, điện thoại có thể truy cập `http://localhost:8080` hoặc `http://localhost:3000` y hệt như đang chạy trên máy tính.

#### Các bước thực hiện:
1. Bật chế độ **USB Debugging** (Gỡ lỗi USB) trên điện thoại Android và cắm cáp vào máy tính.
2. Mở terminal trên máy tính và chạy:
   ```bash
   # Kiểm tra thiết bị đã kết nối
   adb devices

   # Chuyển tiếp cổng Gateway (8080) và cổng Frontend (3000)
   adb reverse tcp:8080 tcp:8080
   adb reverse tcp:3000 tcp:3000
   ```
3. Mở trình duyệt trên máy tính tại `http://localhost:3000`.
4. Vào chi tiết yêu cầu thuê -> Bấm **"Tôi đã chuyển khoản"** -> Chọn tab **"Tải chứng từ từ điện thoại"**.
5. Mở camera điện thoại quét mã QR. Đường link QR sẽ là `http://localhost:8080/u/payment-proof/{token}`.
6. Điện thoại mở trang web Thymeleaf -> Chọn ảnh biên lai từ thư viện hoặc chụp ảnh -> Bấm **"Tải chứng từ lên"**.
7. Màn hình máy tính desktop sẽ nhận được ảnh ngay tức thì (qua cơ chế polling 2.5s).
8. Trên máy tính, bấm **"Gửi chứng từ cho chủ nhà xác nhận"**.

---

### PHƯƠNG PHÁP 2: Sử dụng mạng Wi-Fi nội bộ (Cùng mạng LAN)

Phù hợp cho cả iPhone (iOS) và Android khi cả máy tính và điện thoại cùng kết nối vào một mạng Wi-Fi.

#### Các bước thực hiện:
1. Tìm địa chỉ IP LAN của máy tính:
   - Trên Windows PowerShell:
     ```powershell
     Get-NetIPAddress -AddressFamily IPv4 -InterfaceAlias "Wi-Fi*" | Select-Object IPAddress
     # Ví dụ IP hiển thị là: 192.168.1.50
     ```
2. Mở Windows Firewall cho cổng phát triển (chỉ cần chạy một lần với quyền Administrator nếu máy tính đang chặn truy cập LAN):
   ```powershell
   New-NetFirewallRule -DisplayName "HomeSpace Dev LAN" -Direction Inbound -LocalPort 3000,8080 -Protocol TCP -Action Allow
   ```
3. Trên máy tính Desktop, **mở trình duyệt bằng địa chỉ IP LAN** thay vì localhost:
   `http://192.168.1.50:3000`
4. Mở phiếu thanh toán và chọn **"Tải chứng từ từ điện thoại"**.
   - Backend sẽ tự động phát hiện Host là `192.168.1.50:8080` (hoặc 3000) và mã QR sẽ chứa link:
     `http://192.168.1.50:8080/u/payment-proof/{token}`
5. Dùng điện thoại (iPhone hoặc Android) quét mã QR. Điện thoại sẽ truy cập trực tiếp vào máy tính qua mạng Wi-Fi và hiển thị form upload.

---

### PHƯƠNG PHÁP 3: Dùng Cloudflare Quick Tunnel (Development & Demo)

Phương pháp này tạo ra một đường hầm HTTPS công khai tạm thời mà **không cần mở port router, không cần domain và không cần tài khoản Cloudflare trả phí**.

#### Lưu ý:
- Chỉ sử dụng khi phát triển local hoặc demo thiết bị từ xa qua mạng 4G/5G.
- URL dạng `https://<random-hash>.trycloudflare.com` sẽ thay đổi sau mỗi lần chạy.
- Không dùng Quick Tunnel cho production.

#### Các bước thực hiện:
1. Tải công cụ chính thức `cloudflared` (nếu máy chưa có, tải từ https://github.com/cloudflare/cloudflared/releases).
2. Mở terminal và tạo Quick Tunnel trỏ vào API Gateway (cổng 8080):
   ```bash
   cloudflared tunnel --url http://localhost:8080
   ```
   Terminal sẽ in ra một đường dẫn công khai, ví dụ:
   `https://rapidly-solar-example.trycloudflare.com`
3. Bạn có thể sử dụng URL này để test endpoint `/u/payment-proof/{token}` hoặc cấu hình reverse proxy gộp chung frontend và backend để truy cập từ mạng di động 4G/5G.

---

## 4. QUY TRÌNH KIỂM THỬ ĐẦY ĐỦ CÁC TEST CASES

### Case 1: Direct Web Upload trên máy tính
1. Người thuê mở phiếu thanh toán ban đầu.
2. Bấm "Tôi đã chuyển khoản".
3. Giữ lựa chọn **"Chọn tệp trên thiết bị này"**.
4. Chọn tệp ảnh hợp lệ (`.jpg`, `.png`, `.webp`, `.pdf`).
5. Kiểm tra:
   - Preview ảnh hiển thị chuẩn xác.
   - Dung lượng tệp định dạng rõ ràng (KB/MB).
   - Tệp tự động tải lên và hiển thị badge "Tệp đã tải lên & sẵn sàng".
   - Nút submit có chữ: **"Gửi chứng từ cho chủ nhà xác nhận"**.
6. Bấm gửi:
   - Toast hiển thị: *"Đã gửi chứng từ cho chủ nhà xác nhận."*
   - Giao diện chuyển sang trạng thái: *"Đã gửi chứng từ — chờ chủ nhà xác nhận"*.
7. Đăng nhập tài khoản Chủ nhà:
   - Mở yêu cầu thuê -> Mục thanh toán hiển thị nút **"Xem chứng từ gốc (ảnh/PDF)"**.
   - Bấm vào link để kiểm tra ảnh chứng từ mở trong tab mới an toàn.
   - Bấm **"Xác nhận đã nhận đủ tiền"** -> Giao dịch chuyển sang `CONFIRMED`.

### Case 2: Direct Mobile Web (Mở web app trực tiếp trên điện thoại)
1. Mở web app trên Safari (iOS) hoặc Chrome (Android).
2. Vào phiếu thanh toán -> Chọn "Tôi đã chuyển khoản".
3. Bấm **"Chọn từ thư viện"**: Hệ thống mở thư viện ảnh/file picker mà **không bị ép mở camera**. Người dùng chọn được screenshot giao dịch ngân hàng.
4. Bấm **"Chụp ảnh"**: Hệ thống kích hoạt camera thiết bị để chụp biên lai giấy trực tiếp.

### Case 3: Cross-Device QR Mobile Handoff
1. Trên Desktop, bấm **"Tải chứng từ từ điện thoại"**.
2. Kiểm tra:
   - Mã QR hiển thị rõ ràng với độ tương phản cao.
   - Đồng hồ đếm ngược 10 phút đếm lùi từng giây (`Hết hạn: 09:59...`).
   - Huy hiệu `Đang chờ ảnh từ điện thoại...` hiển thị trạng thái động.
   - Không hiển thị đồng thời VietQR thanh toán và QR tải ảnh.
3. Điện thoại quét mã:
   - Mở trang Thymeleaf tối giản, giao diện tiếng Việt đáp ứng chuẩn mobile.
   - Hiển thị thông tin tóm tắt (Mã yêu cầu thuê, tổng tiền, nội dung CK).
   - Chọn ảnh biên lai và bấm "Tải chứng từ lên".
   - Trang mobile hiển thị thông báo thành công: *"Tải chứng từ thành công! Vui lòng quay lại màn hình máy tính..."*.
4. Màn hình Desktop:
   - Tự động nhận diện trạng thái `UPLOADED` qua polling 2.5s.
   - Toast hiển thị: *"Đã nhận chứng từ từ điện thoại."*
   - Ảnh biên lai xuất hiện ngay trên Desktop kèm kích thước tệp.
   - Nút bấm *"Yêu cầu ảnh khác"* cho phép tạo lại mã mới nếu cần.
5. Người thuê bấm **"Gửi chứng từ cho chủ nhà xác nhận"** để hoàn tất luồng báo cáo.

---

## 5. BẢO MẬT VÀ PHÒNG NGỪA RỦI RO

- **Token 32 bytes ngẫu nhiên**: Sinh bằng `SecureRandom`, mã hóa Base64 URL-safe, chỉ dùng một lần.
- **Không lưu raw token**: Database chỉ lưu chuỗi SHA-256 hash của token.
- **Magic Bytes Validation**: Kiểm tra chữ ký số byte thực tế ở header của tệp, ngăn chặn hoàn toàn việc đổi đuôi file `.exe`, `.svg`, `.html`, `.php`, `.sh`.
- **Private Storage**: File được lưu với `visibility=PRIVATE`, `purpose=PAYMENT_PROOF`, liên kết chặt với `paymentRequestId`. Chỉ người thuê, chủ nhà liên quan và admin mới được cấp URL tạm thời để xem.
- **CSRF & Security Headers**: Route `/u/payment-proof/**` được cấu hình Content-Security-Policy tối thiểu không tải script ngoài, `no-store, no-cache`, `X-Frame-Options: DENY`.
