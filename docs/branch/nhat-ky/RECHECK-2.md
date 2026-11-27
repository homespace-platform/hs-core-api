# Rà lại trước khi merge · lần 2

27/11/2026. Rà phần chưa commit và file mới trong core (`c753458`), web (`69e0012`) và mobile (`48d7006`). Đối chiếu luồng cũ với `master` lần lượt tại `925158e`, `6dff15b` và `f5f9847`. Mốc HEAD dùng để xác định phần thay đổi của lượt này. [RECHECK](RECHECK.md) · [RECHECK-FIX](RECHECK-FIX.md) · [Q15 và PRE-MERGE-2](../04-cau-hoi.md)

| Repo | Kết luận |
| --- | --- |
| `hs-core-api` | **Phải sửa trước merge.** Phụ lục giữ nguyên xe và thời hạn vẫn bỏ sót chuyển bãi khi A đã xóa và phụ lục không kèm đồng bộ tin. |
| `hs-web-app` | **Không có lỗi phải sửa trước merge.** Đã xử lý mục 5, lưu thông thường vẫn chạy. |
| `hs-mobile-app` | **Không có lỗi phải sửa trước merge.** Đã xử lý mục 6 cho badge và thẻ chi nhánh. |

## Sáu phát hiện lần trước

| Mục | Kết quả |
| --- | --- |
| 1 · Core · Sức chứa bãi độc lập sau khi xóa chi nhánh | Đạt ca gốc. Gửi, duyệt và ký kiểm giới hạn tin khi sửa xe và phải rơi bãi. Kiểm số xe theo ngày giao nhau, bỏ phần ACTIVE của chính HĐ sẽ thay và HELD của chính phụ lục. Bãi độc lập thông thường vẫn theo quy tắc cũ. Còn ca Q15 bên dưới. |
| 2 · Core · Ký phụ lục cũ có đích null | Đạt ca gốc. Phụ lục đã duyệt trên master, thiếu snapshot và không có đồng bộ xác nhận đích null, ký về bãi A đang giữ nếu đủ chỗ. Đồng bộ đã xác nhận đích độc lập vẫn ghi chỗ ở bãi độc lập. |
| 3 · Core · Q15 | Đạt ca đồng bộ rời A còn hoạt động, không đổi xe/thời hạn, kể cả phụ lục duyệt cũ. Giữ đích A trên văn bản và HĐ, giữ dòng ACTIVE, nhả HELD độc lập cũ nếu có. Ca có đồng bộ và A bị xóa trước gửi, duyệt hoặc ký đều có kiểm sức chứa tin. Ca không kèm đồng bộ khi A đã xóa vẫn sai. |
| 4 · Core · Ký gia hạn khi A bị xóa sau duyệt | Đạt ca gốc. Kiểm bãi độc lập trước khi gọi ký và khi kích hoạt, không đếm chính HELD của gia hạn. Đủ chỗ thì chuyển HELD sang ACTIVE với `branch_id = null`; thiếu thì giữ nguyên. A còn hoạt động hoặc đích đã độc lập từ lúc duyệt chạy theo luồng cũ. |
| 5 · Web · Bỏ phòng có liên kết mới | Đạt. Đọc lại assignments trước khi bỏ phòng hoặc đổi thành lối đi. Có liên kết hoặc GET lỗi thì hỏi trước PUT. Đổi mã phòng không gọi GET này. Test kiểm PUT đúng revision, nhấp đôi, rời trang khi GET còn chờ và phục hồi sau 409. Sơ đồ chi nhánh được thêm sau master; so hành vi lưu với HEAD. |
| 6 · Mobile · Badge bị kết quả cũ đè | Đạt. Hai API core đều trả `realNow`. Page mới hơn hoặc summary mới hơn đều thắng dù trả về theo thứ tự ngược. Summary lỗi vẫn giữ số đã biết trên badge và thẻ. Master chưa có số việc ở màn này; so hành vi với HEAD. |

## Phải sửa trước merge

- **Core · Còn sót Q15, hành vi đã có trên master.** HĐ ghi A và có 1 xe ở A, tin đã rời A, A bị xóa mềm, tin độc lập còn chỗ. Gửi phụ lục chỉ đổi tiền thuê, không kèm đồng bộ tin. `pinPool` giữ A và `parkingChanged` trả false qua cả duyệt lẫn ký. Unit tạm xác nhận dòng ACTIVE ở A vẫn kéo dài đến hết kỳ. Theo chốt 27/11, phần từ ngày hiệu lực phải chuyển về bãi độc lập, kiểm giới hạn và ghi đích null trên phụ lục/HĐ. Áp cùng quy tắc fallback khi xác định đích ở cả ba bước cho trường hợp này. [ContractAmendmentService.java:253](../../../hs-contract-service/src/main/java/com/hs/contract/service/ContractAmendmentService.java#L253) · [ContractAmendmentService.java:565](../../../hs-contract-service/src/main/java/com/hs/contract/service/ContractAmendmentService.java#L565) · [ContractAmendmentService.java:599](../../../hs-contract-service/src/main/java/com/hs/contract/service/ContractAmendmentService.java#L599)

## Ghi nhận

- **Core · Lỗi mới, dữ liệu có hai hồ sơ cùng tin chồng kỳ.** Tin có 3 chỗ, A đã xóa, HĐ đang ACTIVE 1 xe ở A và phụ lục đã duyệt tăng lên 2. Bản duyệt tạo HELD 2 ở bãi độc lập vì tính phần đã giữ theo ID bãi gốc. Khi sửa nháp khác cùng tin và kỳ cần 1 chỗ, bản vá đếm cả ACTIVE 1 lẫn HELD 2 nên báo hết chỗ. Unit tạm tái hiện. Đường sửa nháp trên master bỏ dòng có ID A nên ca đó vẫn còn 1 chỗ. Đường đọc sức chứa độc lập dùng khi duyệt gia hạn vốn cộng cả hai loại dòng theo ID tin. Ghi nhận ca chồng kỳ vì luồng nhận thuê bình thường chỉ nhận tin đang đăng và chuyển tin sang giữ chỗ ngay khi duyệt. Tính số tăng của HELD theo cùng quy tắc nhận diện bãi độc lập sẽ tránh đếm trùng. [ParkingReservationService.java:76](../../../hs-listing-service/src/main/java/com/hs/listing/service/ParkingReservationService.java#L76) · [ParkingReservationService.java:378](../../../hs-listing-service/src/main/java/com/hs/listing/service/ParkingReservationService.java#L378) · [ParkingReservationService.java:538](../../../hs-listing-service/src/main/java/com/hs/listing/service/ParkingReservationService.java#L538)
- **Core · Ca cũ và ca hiếm.** Đồng bộ HĐ nháp và kích hoạt giữ chỗ của HĐ gốc vẫn có các giới hạn đã ghi ở RECHECK-FIX. Chuyển thẳng A sang B vẫn theo master, RECHECK-FIX để mở quyết định này. Q13 và Q14 đã chốt làm sau. Hai giao dịch cùng ghi bãi độc lập hoặc xóa A đúng lúc ký vẫn chưa được kiểm chứng; đường phụ lục chỉ khóa lạc quan trên tin. [RECHECK-FIX](RECHECK-FIX.md) · [ParkingReservationService.java:466](../../../hs-listing-service/src/main/java/com/hs/listing/service/ParkingReservationService.java#L466)
- **Web · Khoảng thời gian giữa GET và PUT.** Tab khác vẫn có thể thêm liên kết đúng lúc sau GET, trước PUT. Đọc mới đã thu hẹp khoảng này. Nếu GET treo, nút lưu phòng bị bỏ phải đợi vì axios client chưa có timeout riêng; khi GET trả lỗi thì cảnh báo dự phòng vẫn mở. [BranchLayoutEditor.tsx:382](../../../../hs-web-app/components/branch/BranchLayoutEditor.tsx#L382) · [axios-client.ts:23](../../../../hs-web-app/lib/axios-client.ts#L23)
- **Mobile · Hai lần đọc gần nhau.** Mốc `realNow` nằm khác phía của truy vấn mở ảnh chụp PostgreSQL ở hai API. `Date.parse` cũng chỉ có độ phân giải mili giây và chọn page khi bằng nhau. Có thể còn lệch số nếu dữ liệu đổi giữa hai ảnh chụp gần nhau. [BranchAttentionService.java:90](../../../hs-contract-service/src/main/java/com/hs/contract/service/BranchAttentionService.java#L90) · [branch-overview.ts:468](../../../../hs-mobile-app/src/lib/branch-overview.ts#L468)

## Kiểm chứng

- Core đạt **163/163** với `mvnw.cmd -o -B -pl hs-contract-service -am '-Dtest=ParkingReservationServiceTest,ContractAmendmentServiceTest,ContractLifecycleServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`. Hai unit tạm cho ca Q15 thiếu và ca đếm đôi đều đỏ đúng kỳ vọng, đã gỡ sau khi lấy kết quả.
- Web đạt **64/64** với `node --test --test-isolation=none` trên các bộ branch layout, assignment, monitoring, overview và attention. `tsc --noEmit --incremental false` đạt.
- Mobile đạt **42/42** với `node --test --test-isolation=none` trên các bộ branch editing, view, foundation, attention và attention badge. `npm.cmd run typecheck` đạt.
- Lượt rà dùng unit test và đọc mã, chưa kiểm PostgreSQL, SmartCA, trình duyệt hay thiết bị. Chỉ để lại báo cáo.
