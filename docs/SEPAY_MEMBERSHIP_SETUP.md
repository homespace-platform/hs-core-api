# Thiết lập SePay cho gói hội viên HomeSpace

Tích hợp này dùng **Cổng thanh toán SePay** cho gói lượt đăng tin. HomeSpace tạo đơn và ký form thanh toán; trình duyệt gửi form `POST` sang SePay. Chỉ IPN được xác thực từ SePay mới có thể kích hoạt gói. Trang trình duyệt quay về chỉ tra cứu trạng thái đơn trên backend.

Tài liệu gốc: [Sandbox](https://developer.sepay.vn/vi/cong-thanh-toan/sandbox), [form checkout và chữ ký](https://developer.sepay.vn/vi/cong-thanh-toan/API/don-hang/form-thanh-toan), [IPN Cổng thanh toán](https://developer.sepay.vn/vi/cong-thanh-toan/IPN).

## 1. Lấy thông tin SePay Sandbox

1. Đăng nhập [my.sepay.vn](https://my.sepay.vn/), vào **Cổng thanh toán**, chọn đăng ký và bắt đầu với **Sandbox**.
2. Sao chép **Merchant ID** và **Secret Key** của Sandbox từ phần thông tin tích hợp. Secret Key của merchant chỉ hiện một lần; nếu mất phải tạo lại và key cũ hết hiệu lực.
3. Vào **Cổng thanh toán → Cấu hình → IPN**, chọn xác thực **SECRET_KEY** và tự đặt một **Secret Key IPN** đủ dài, ngẫu nhiên. Đây là khóa **khác** với Secret Key của merchant. SePay gửi nó trong header `X-Secret-Key` của IPN.

Không dùng API Token trong mục API Access cho tích hợp Cổng thanh toán này. Merchant ID/Secret Key Sandbox và Production là hai bộ riêng. Không đưa bất kỳ secret nào vào `hs-web-app`, Git hoặc ảnh chụp màn hình.

## 2. Điền biến môi trường backend

Điền vào `hs-core-api/.env` (đã có sẵn các dòng trống):

```dotenv
SEPAY_MERCHANT_ID=<Merchant ID Sandbox>
SEPAY_SECRET_KEY=<Secret Key merchant Sandbox>
SEPAY_CHECKOUT_URL=https://pay-sandbox.sepay.vn/v1/checkout/init
SEPAY_RETURN_URL=https://<frontend-public-host>/dashboard/membership/return
SEPAY_IPN_SECRET=<Secret Key IPN do bạn đặt trong SePay>
```

`SEPAY_SECRET_KEY` dùng để ký form checkout HMAC-SHA256; `SEPAY_IPN_SECRET` dùng để kiểm tra header IPN. Không dùng chung hai khóa. `SEPAY_RETURN_URL` là URL trang kết quả phía frontend, không phải URL IPN. Backend gắn `orderId` vào URL khi tạo từng đơn. Cấu hình `.env` cần được nạp lại bằng cách khởi động lại Core API.

Schema hiện tại thêm cột `membership_orders.provider` bằng Hibernate khi `JPA_DDL_AUTO=update`. Nếu môi trường dùng `validate` hoặc `none`, hãy thêm cột `provider varchar(16)` (cho phép `NULL` để các đơn legacy trước SePay vẫn đọc được) theo quy trình migration của môi trường đó **trước** khi khởi động phiên bản Core API mới. Đơn lịch sử có `provider=VNPAY` hoặc `NULL` vẫn hiển thị đúng; không được đổi nhãn chúng thành SePay. Backend không còn endpoint IPN VNPay.

## 3. Mở URL callback công khai trên máy local

Cần **hai cửa sổ terminal riêng**, mỗi cửa sổ chạy một tunnel và giữ mở trong suốt lúc thanh toán thử. `3000` và `8080` là cổng HTTP **trên máy bạn**; Cloudflare cấp một URL HTTPS công khai khác nhau cho mỗi tunnel. Không cần tunnel thứ ba cho `SEPAY_CHECKOUT_URL`: đó là trang do SePay vận hành.

| Cửa sổ | Lệnh tự chạy | Lấy URL HTTPS in ra để cấu hình |
| --- | --- | --- |
| 1 — frontend | `cloudflared tunnel --url http://localhost:3000` | Trong `hs-core-api/.env`: `SEPAY_RETURN_URL=https://<host-frontend>/dashboard/membership/return` |
| 2 — gateway/backend | `cloudflared tunnel --url http://localhost:8080` | Trên SePay, vào **Cổng thanh toán → Cấu hình → IPN** và đặt **IPN URL** thành `https://<host-backend>/api/v1/membership/sepay/ipn` |

Thực hiện theo thứ tự: bảo đảm frontend và gateway đang chạy ở hai cổng trên; mở hai tunnel; sao chép **đúng hostname của từng cửa sổ** vào hai chỗ trong bảng; tại SePay chọn **Auth Type: Secret Key**, điền cùng giá trị với `SEPAY_IPN_SECRET`, chọn `application/json`, bật IPN rồi bấm **Cập nhật**; cuối cùng khởi động lại **Core API** để nạp `SEPAY_RETURN_URL` mới. Chỉ khởi động lại gateway nếu bạn sửa cấu hình route của gateway. Khi các URL chưa được điền đúng, không tạo giao dịch thử mới.

`SEPAY_RETURN_URL` là trang trình duyệt quay về (`success_url`, `error_url`, `cancel_url`). **IPN URL** là endpoint SePay gọi trực tiếp để báo thanh toán; không nhập URL frontend vào ô IPN. Trang kết quả chỉ tra cứu trạng thái đơn từ HomeSpace, không xác nhận thanh toán dựa vào tham số redirect. Trong **Nhật ký IPN** của SePay, callback hợp lệ phải đến endpoint gateway ở trên và nhận HTTP `200` từ backend.

Khi chạy Next.js ở chế độ development qua `*.trycloudflare.com`, route kết quả chuyển trình duyệt về `localhost:3000` để giữ phiên đăng nhập và API local; chỉ thử trên cùng máy đang chạy frontend. Quick Tunnel đổi hostname mỗi lần tạo lại: nếu một tunnel dừng hoặc xuất hiện lỗi Cloudflare `1033`, hãy chạy lại lệnh tương ứng, cập nhật URL ở `.env` hoặc SePay theo bảng, và khởi động lại Core API nếu đã sửa `.env`. Khi cần URL ổn định, dùng named tunnel và hostname riêng.

## 4. Kiểm thử Sandbox

1. Đăng nhập tài khoản chủ nhà, chọn Starter/Pro/Business rồi chọn **SePay**.
2. HomeSpace gọi `POST /api/v1/membership/checkout` với `provider=SEPAY`, lưu đơn `PENDING`, trả về action URL và các hidden field đã ký. Trình duyệt gửi **form POST** tới `https://pay-sandbox.sepay.vn/v1/checkout/init`. Checkout với `provider=VNPAY` bị từ chối (`MEMBERSHIP_PROVIDER_UNAVAILABLE`).
3. Hoàn tất giao dịch giả lập trên SePay. Kiểm tra IPN trong trang quản lý SePay và `GET /api/v1/membership/orders/{id}` trên HomeSpace. Chỉ sau IPN hợp lệ đơn mới thành `PAID` và lượt đăng được cấp.
4. Thử gửi lại cùng IPN: lượt đăng không được cấp lần hai. Thử hủy đơn `PENDING`; nếu thông báo thanh toán thành công đến sau khi hủy hoặc hết hạn, đơn phải vào `REVIEW` để đối soát, không tự cấp lượt.

Nếu đơn vẫn `PENDING`, kiểm tra IPN URL công khai, header `X-Secret-Key`, log gateway/Core API và nhật ký IPN trong SePay. Không đánh dấu `PAID` chỉ vì trình duyệt đã quay về trang thành công. Hãy đối chiếu giao dịch thật trước khi xử lý thủ công một đơn `REVIEW`.

Nếu checkout trả HTTP `503` với thông báo SePay chưa được cấu hình, kiểm tra đủ năm biến `SEPAY_*` ở trên và khởi động lại Core API. Nếu một đơn nhận IPN thành công với mã giao dịch khác mã đã lưu, hệ thống chuyển đơn sang `REVIEW` và không cấp lượt lần hai; người vận hành phải đối soát trên SePay.

Thông báo IPN `TRANSACTION_VOID` hiện được từ chối và chưa tự đảo lượt đã cấp; cần quy trình đối soát/hoàn tiền riêng trước khi vận hành Production với trường hợp hủy giao dịch sau thanh toán.

## 5. Chuyển sang Production

Sau khi hoàn tất Sandbox và được SePay cấp thông tin Production, thay Merchant ID/Secret Key, đổi `SEPAY_CHECKOUT_URL` thành `https://pay.sepay.vn/v1/checkout/init`, cấu hình lại IPN URL và Secret Key IPN trong môi trường Production, rồi kiểm thử một giao dịch nhỏ. Không dùng key Sandbox ở Production.
