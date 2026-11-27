# Rà lại trước khi merge · lần 3

27/11/2026 · Chỉ rà phần chưa commit của luồng bãi xe khi làm phụ lục và gia hạn trong `hs-core-api` (`HEAD c753458`, đối chiếu `master 925158e`). [RECHECK-2](RECHECK-2.md) · [RECHECK-FIX](RECHECK-FIX.md) · [Chốt Q15](../04-cau-hoi.md#chủ-dự-án-trả-lời-2711--làm-rõ-q15)

## CÓ lỗi chặn merge

- **Thay phụ lục đã ký chờ hiệu lực làm mất chỗ xe kỳ gia hạn, trái Q15.** HĐ ghi A, A còn hoạt động, tin đã rời A. HĐ đã gia hạn và có một xe xuyên suốt đến 14/04/2027. Phụ lục đồng bộ tin chỉ đổi tiện ích ký xong giữ đủ chỗ ở A. Sau đó hai bên ký bản thay thế cũng chỉ đổi tiện ích, giữ nguyên số xe, thời hạn và ngày hiệu lực 14/11/2026. Bản thay thế vẫn ghi A nhưng ngày 14/02/2027 còn **0/1** chỗ ở A.
- `businessChanges` bật `refreshParkingPool` vì bản trước có đồng bộ tin. Lúc ký, `parkingChanged` vẫn đúng dù Q15 giữ đích A, nên hàm kích hoạt nhả lịch tương lai và chỉ dựng lại đến 14/01/2027 theo điều khoản cũ. Tiêu chí chặn là Q15 ở luồng thay thế được hỗ trợ. `master` cũng dựng lại lịch khi có cờ này. Nếu bản trước đã giữ nguyên chỗ ở A, bản thay thế chỉ đổi nội dung khác cần giữ nguyên các đoạn đang giữ. [Cờ dựng lại](../../../hs-contract-service/src/main/java/com/hs/contract/service/ContractAmendmentService.java#L513) · [Đường ký](../../../hs-contract-service/src/main/java/com/hs/contract/service/ContractAmendmentService.java#L325) · [Unit tái hiện](../../../hs-contract-service/src/test/java/com/hs/contract/service/ContractAmendmentServiceTest.java#L927)

## Các luồng còn lại

- Xóa A trước gửi, duyệt hoặc ký phụ lục không đổi xe/thời hạn, có và không đồng bộ tin. Chỗ từ ngày hiệu lực chuyển sang bãi độc lập, giữ nguyên số và ngày kết thúc từng đoạn, gồm kỳ gia hạn. Ca bãi đầy bị từ chối và các dòng đang giữ còn nguyên. Gia hạn đã duyệt rồi mới xóa A cũng giữ nguyên số và hai mốc ngày khi kích hoạt ở bãi độc lập.
- Phép đếm khi rơi bãi xét giao ngày, tính cả chỗ của hồ sơ khác còn ghi chi nhánh đã xóa và bỏ phần `ACTIVE` của HĐ đang thay, `HELD` của văn bản đang ký. Khi duyệt phụ lục, phần giữ thêm trừ cả chỗ hiện còn ghi A đã xóa. Ca một chỗ cũ, tăng lên hai trên tin có ba chỗ vẫn để lại đúng một chỗ cho hồ sơ khác.
- Đối chứng phụ lục đổi xe ở chi nhánh còn hoạt động dùng sức chứa chi nhánh, phụ lục tin độc lập giữ cách kiểm của `master`. Gia hạn bình thường ở cả hai loại bãi giữ nguyên bãi, số chỗ và ngày đã duyệt. Q13 với phụ lục có đổi xe/thời hạn, Q14 và ca ghi đồng thời đã nêu ở [RECHECK-2](RECHECK-2.md) tiếp tục ghi nhận theo quyết định trước.

## Kiểm chứng

```powershell
.\mvnw.cmd -o -B -pl hs-contract-service -am '-Dtest=ParkingReservationServiceTest,ContractAmendmentServiceTest,ContractLifecycleServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

184/185 unit đạt, không skip. `ContractAmendmentServiceTest` 93/94, `ContractLifecycleServiceTest` 56/56 và `ParkingReservationServiceTest` 35/35. Giữ test đỏ tái hiện Q15. Phạm vi xác minh là unit và đối chiếu mã, chưa chạy PostgreSQL hay SmartCA.
