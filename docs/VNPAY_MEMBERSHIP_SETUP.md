# Hướng dẫn cấu hình VNPAY cho gói đăng tin

> VNPay hiện tạm dừng tạo đơn mới trên HomeSpace. Xem [thiết lập SePay](SEPAY_MEMBERSHIP_SETUP.md) cho luồng thanh toán đang sử dụng. Các bước VNPay bên dưới chỉ giữ lại để đối soát đơn cũ.

Tính năng này dùng VNPAY PAY 2.1.0 để thanh toán gói đăng tin, không dùng cho tiền thuê hoặc tiền cọc. Xem [hướng dẫn tích hợp PAY](https://sandbox.vnpayment.vn/apis/docs/thanh-toan-pay/pay.html) và [câu hỏi thường gặp của VNPAY](https://sandbox.vnpayment.vn/apis/docs/faqs/) để phân biệt trang thanh toán, Return URL và IPN.

Trước khi bật thanh toán, cơ sở dữ liệu phải có các bảng `listing_memberships`, `membership_orders` và cột `listings.publication_reservation`. Ở môi trường phát triển, `JPA_DDL_AUTO=update` sẽ tạo cấu trúc này. Nếu môi trường dùng `validate` hoặc `none`, hãy triển khai cấu trúc tương ứng theo quy trình thay đổi cơ sở dữ liệu của bạn. Khi truy cập gói thành viên lần đầu, hệ thống tính các tin đã đăng trước đó vào tổng 3 lượt miễn phí của tài khoản.

## Cấu hình

1. Nếu đã có tài khoản merchant sandbox, kiểm tra email đăng ký để lấy thông tin kết nối `TmnCode` và `HashSecret` do VNPAY cấp. Nếu chưa nhận được, [liên hệ VNPAY](https://sandbox.vnpayment.vn/apis/contact/) qua `kythuatctt@vnpay.vn` và cung cấp tài khoản merchant để xin lại thông tin. Tài liệu PAY của VNPAY vẫn dẫn tới trang đăng ký `/devreg`, nhưng địa chỉ này hiện trả 404. Điền mã và secret vào `VNPAY_TMN_CODE`, `VNPAY_HASH_SECRET` trong `hs-core-api/.env`. Không commit secret hoặc gửi secret xuống frontend.
2. Ở môi trường phát triển, đặt `VNPAY_PAY_URL=https://sandbox.vnpayment.vn/paymentv2/vpcpay.html`. Đặt `VNPAY_RETURN_URL=https://<frontend-host>/dashboard/membership/return`. Return URL đưa người dùng về trình duyệt để xem kết quả; trang này dùng `vnp_TxnRef` làm mã đơn hàng và kiểm tra `GET /api/v1/membership/orders/{id}`. Việc quay về trang này không tự kích hoạt gói.
3. Trong cấu hình merchant sandbox, khai báo IPN URL là `https://<public-api-host>/api/v1/membership/vnpay/ipn`. VNPAY phải truy cập được địa chỉ này qua HTTPS công khai; `localhost` không nhận được callback từ máy chủ VNPAY. Gateway cho phép truy cập GET không cần đăng nhập đúng tại đường dẫn này. API chỉ kích hoạt gói sau khi kiểm tra HMAC-SHA512, mã merchant, mã đơn hàng, số tiền, mã phản hồi và trạng thái giao dịch.
4. Khi lên production, dùng `TmnCode`, `HashSecret`, URL thanh toán, Return URL và IPN URL riêng cho production. Không dùng lại thông tin sandbox. Đặt URL thanh toán production do VNPAY cung cấp vào `VNPAY_PAY_URL`.

## Thử trên máy local bằng Cloudflare Tunnel

1. Khởi chạy gateway ở cổng `8080` và trong terminal khác chạy `cloudflared tunnel --url http://localhost:8080`. IPN phải đi qua **gateway**, không phải frontend cổng `3000`. Lấy địa chỉ HTTPS được cấp, ví dụ `https://abc.trycloudflare.com`.
2. Khai báo với VNPAY **IPN URL** là `https://abc.trycloudflare.com/api/v1/membership/vnpay/ipn`. Không có biến môi trường `VNPAY_IPN_URL` trong backend. Nếu không tự sửa được IPN URL trên trang quản trị, gửi địa chỉ này cho bộ phận hỗ trợ VNPAY để cấu hình merchant sandbox.
3. `VNPAY_RETURN_URL` trong `hs-core-api/.env` là đường dẫn trình duyệt quay về frontend. Khi thử trên cùng máy, giữ `http://localhost:3000/dashboard/membership/return`. Nếu VNPAY yêu cầu Return URL HTTPS công khai, mở thêm tunnel cho cổng `3000`, cập nhật biến này bằng URL frontend công khai rồi khởi động lại backend.

Quick Tunnel cấp tên miền `*.trycloudflare.com` ngẫu nhiên sau mỗi lần tạo. Wildcard chỉ giúp khớp mẫu tên miền trong cấu hình ứng dụng; nó không tạo URL cố định để VNPAY gọi IPN. Khi URL đổi, phải cập nhật IPN URL ở VNPAY. Muốn giữ nguyên URL giữa các lần chạy, [tạo named tunnel và gắn hostname thuộc domain Cloudflare của bạn](https://developers.cloudflare.com/tunnel/get-started/). Không commit URL tunnel tạm hoặc mở CORS có credentials cho toàn bộ `*.trycloudflare.com`.

Mã `TmnCode` do VNPAY cấp gắn với website/merchant đã khai báo. Nếu VNPAY yêu cầu duyệt domain, URL `trycloudflare.com` ngẫu nhiên có thể không khớp thông tin merchant; dùng hostname ổn định và nhờ VNPAY xác nhận website cùng Return URL, IPN URL trước khi thử thanh toán.

## Kiểm tra thanh toán sandbox

Sau khi có thông tin sandbox và API HTTPS công khai:

1. Gọi `GET /api/v1/membership/plans`, rồi `GET /api/v1/membership/quote?tier=STARTER`.
2. Gọi `POST /api/v1/membership/checkout` với body `{"tier":"STARTER"}`. Mở `paymentUrl` trả về và hoàn tất thanh toán sandbox.
3. Dùng `vnp_TxnRef` làm `orderId`; kiểm tra `GET /api/v1/membership/orders/{id}` cho đến khi đơn hàng có trạng thái `PAID`, rồi kiểm tra `GET /api/v1/membership/me`.
4. Kiểm tra thêm: nếu trình duyệt quay về nhưng không có IPN hợp lệ, đơn hàng phải vẫn là `PENDING`. Thanh toán bị từ chối hoặc IPN gửi lặp không được cấp thêm lượt/gói.

## Xử lý sự cố và đối soát

- **Không tạo được phiên thanh toán:** Kiểm tra `VNPAY_TMN_CODE`, `VNPAY_HASH_SECRET` và `VNPAY_RETURN_URL` trên backend; xác nhận mã merchant khớp với secret.
- **VNPAY chuyển tới `Error.html?code=71` và báo “Website này chưa được phê duyệt”:** Đây là thông báo từ trang VNPAY trước bước thanh toán. Kiểm tra trạng thái phê duyệt website/merchant gắn với `TmnCode` và domain của `VNPAY_RETURN_URL` đã khai báo với VNPAY. Nếu dùng Quick Tunnel, URL ngẫu nhiên có thể cần VNPAY cập nhật/duyệt lại; ưu tiên hostname cố định. Gửi mã tra cứu trên trang lỗi cho [hỗ trợ kỹ thuật VNPAY](https://sandbox.vnpayment.vn/apis/contact/) để xác nhận nguyên nhân. Backend không thể tự phê duyệt merchant. Đơn hàng chưa có IPN thành công thì không được cấp gói.
- **Đơn hàng giữ trạng thái `PENDING`:** Kiểm tra IPN URL đã đăng ký là HTTPS công khai và gateway cho phép truy cập HTTP không cần đăng nhập tại đúng đường dẫn IPN. Nội dung callback vẫn phải có chữ ký VNPAY hợp lệ. Đối chiếu trạng thái giao dịch trên cổng merchant trước khi thử lại hoặc xử lý thủ công. Khi tạo phiên thanh toán mới, đơn hàng cũ đang chờ sẽ thành `EXPIRED` sau thời hạn thanh toán 15 phút; nếu callback thành công đến muộn, đơn hàng chuyển sang `REVIEW` thay vì kích hoạt đè lên đơn mới.
- **IPN trả mã `97`:** Chữ ký hoặc mã merchant không khớp. Kiểm tra secret gốc trên backend và bảo đảm các tham số `vnp_` không bị bỏ hoặc đổi tên.
- **Đơn hàng ở trạng thái `REVIEW`:** VNPAY báo thanh toán thành công sau khi điều kiện nâng cấp theo báo giá đã thay đổi. Người vận hành cần đối chiếu giao dịch và xử lý việc cấp gói hoặc hoàn tiền qua quy trình merchant. Không đánh dấu đơn hàng đã thanh toán chỉ dựa vào Return URL của trình duyệt.

[FAQ của VNPAY](https://sandbox.vnpayment.vn/apis/docs/faqs/) phân biệt Return URL của trình duyệt với IPN từ máy chủ. [Tài liệu PAY 2.1.0](https://sandbox.vnpayment.vn/apis/docs/thanh-toan-pay/pay.html) quy định `vnp_Amount` bằng số tiền VND nhân 100 và cả hai trường trạng thái thành công đều có giá trị `00`.
