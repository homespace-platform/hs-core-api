# Rà soát địa chỉ trước khi thay location service bằng Vietmap

> Cập nhật theo yêu cầu mới: bỏ schema/danh mục cũ, không migration mã hay seed. Thiết kế triển khai hiện tại nằm trong `hs-core-api/docs/VIETMAP_ADDRESS.md`; phương án giữ danh mục tại các phần dưới chỉ là bản khảo sát ban đầu, không được triển khai.

Ngày rà soát: 22/10/2026. Đây là kết quả khảo sát và đề xuất, chưa triển khai thay đổi runtime/schema.
Không ghi dữ liệu vào CSDL, không xóa service, không thay đổi `.env`.

## 1. Kết luận

- Có thể bỏ tiến trình `hs-location-service`, nhưng phải thay cả chức năng danh mục tỉnh/phường mà frontend đang gọi.
- Bảng `addresses` không chỉ chứa User và Listing: Chi nhánh/tòa nhà cũng tham chiếu bảng này.
- Đề xuất tách `user_addresses`, `listing_addresses`, `branch_addresses`, có quyền và vòng đời riêng.
- Vietmap là nguồn tìm kiếm/chuẩn hóa địa chỉ; dữ liệu đã chọn và lưu vẫn nằm trong Core. Đọc tin/hợp đồng không gọi Vietmap lại.
- Không nhập toàn bộ gợi ý autocomplete vào CSDL. Chỉ lưu địa chỉ được người dùng chọn và xác nhận.
- Việc tách bảng giúp rõ quyền sở hữu và module; không tự động bảo đảm truy vấn nhanh hơn. Phải đo query/index và xử lý các đường đọc từng địa chỉ theo từng tin.

## 2. Use case hiện tại và phạm vi ảnh hưởng

Các đường dẫn dưới đây tính từ workspace `D:/Workspace/homespace`.

| Use case | Chỗ đang sử dụng | Điều phải giữ khi chuyển |
| --- | --- | --- |
| Địa chỉ hồ sơ User, cả Web và Admin | `hs-web-app/components/settings/AddressEditor.tsx`; `hs-admin-portal/src/components/settings/AddressEditor.tsx`; Core `AddressController`, `AddressServiceImpl`, `UserMapper` | GET/PUT/DELETE `/users/me/address`; riêng tư, chỉ sửa địa chỉ của chính mình; soft delete |
| Khóa/xóa User | `hs-core-api/hs-user-service/.../UserSyncConsumer.java` | Ngừng sử dụng địa chỉ User; không xóa nhầm địa chỉ Listing/Branch |
| Tạo/sửa tin đăng | `hs-web-app/app/dashboard/properties/new`; Core `ListingService.upsertAddress` | Địa chỉ NEW hoặc sao chép SAVED; kiểm tra SAVED thuộc chủ tin; sửa địa chỉ User sau đó không làm đổi địa chỉ tài sản |
| Tạo/sửa chi nhánh, tòa nhà | `hs-web-app/app/dashboard/branches/page.tsx`; Core `PropertyBranchService`, `PropertyBranch.address` | Địa chỉ Branch riêng; frontend điền địa chỉ vào form tin khi chọn Branch; giữ số phòng/căn/tầng riêng |
| Chọn khu vực toàn ứng dụng | `Header.tsx`; `homespace_selected_province` trong localStorage | Mã tỉnh có namespace/version; không đọc lại mã cũ như mã Vietmap |
| Trang chủ, địa điểm nổi bật | `LocationMapExplorer.tsx`; `data/featured-locations.ts` và JSON | Bỏ mặc định/cách lookup mã `79` khi namespace đổi; không fallback nhầm về TP.HCM |
| Tìm thuê, lọc tỉnh/phường, từ khóa | `hs-web-app/app/rent/page.tsx`; Core `ListingPublicService` | Query parameter, bộ lọc và dữ liệu lưu phải cùng hệ mã; không gọi Vietmap để render mỗi card |
| Quản lý/kiểm duyệt và xem chi tiết tin | Core `ListingQueryService`, `ListingAdminService`, `ListingPublicService` | Trả địa chỉ Listing, không lộ địa chỉ User; giữ quyền xem tin chưa công khai cho người có quan hệ phù hợp |
| Tìm kiếm AI | Core `AiListingSearchService`; AI `listing_tool_planner.py`, `listing_ask.py` | Đổi native SQL `addresses` và whitelist trường; không chỉ đổi Entity/JPA; giữ lọc địa chỉ, không suy đoán vị trí gần landmark |
| Lịch xem nhà và công cụ lịch AI | Core `ViewingAppointmentService`, `AiAppointmentQueryService` | Đổi repository, Criteria root, JPQL `from Address`; giữ lọc theo danh tính và địa chỉ lịch |
| Yêu cầu thuê/đặt cọc | Core `RentalRequestService` | Địa chỉ tài sản trong danh sách/chi tiết và snapshot không bị trộn với địa chỉ thường trú |
| Hợp đồng, phụ lục và kết xuất file | Core `ContractDataBuilder`, `ContractPartySnapshot`, `FreeFormAmendmentTerms` | Địa chỉ thường trú 2 bên lấy từ User; địa chỉ tài sản lấy từ Listing/snapshot; không tự đổi snapshot/văn bản đã ký |
| Seed, Postman, test, cấu hình triển khai | `postgres/listings_seed_hcm.sql`; `postman/location`; tests của các service; biến URL location ở Web/Admin | Seed chuyển bảng/mã đồng bộ, test quyền/snapshot; chỉ ngừng location service sau khi không còn consumer |
| Xem trước bản đồ địa chỉ | `hs-web-app/components/address/AddressMapPreview.tsx` | Hiện là Google iframe theo chuỗi địa chỉ, chưa lưu tọa độ; 4 API tìm địa chỉ không cung cấp bản đồ nền/SDK thay iframe |

### Mô hình đang có

`hs-core-api/hs-user-service/src/main/java/com/hs/user/model/Address.java`:

- Một entity/bảng chung `addresses`, với `user_id`, `listing_id`, `branch_id` đều có thể null và unique riêng.
- Không có CHECK trong entity buộc đúng một chủ thể; unique riêng không ngăn một hàng thuộc nhiều loại hoặc không có chủ thể.
- `PropertyBranch` lại giữ FK `address_id` tới `addresses`. Không thể phân loại dữ liệu Branch chỉ dựa vào cột `addresses.branch_id`:
  `PropertyBranchService.createBranch` đang tạo Address và gắn bằng `branch.address`, không gán `Address.branchId`.
- Chỉ có mã/tên tỉnh/phường, streetLine/fullAddress; chưa có ref_id/provider/lat/lng.
- Core nhận mã và tên địa chỉ từ request; hiện chưa resolve/đối chiếu chúng với Vietmap.
- Web và Admin gọi location service trực tiếp qua `province.service.ts`; service cũ đọc JSON local, không phải geocoder.
- Đường dựng summary trong `ListingQueryService` và `ListingPublicService` gọi tìm Address theo từng Listing. Khi tách nên chuyển sang batch/projection, xác nhận bằng query count/EXPLAIN.

## 3. Bốn endpoint Vietmap đã kiểm thử

Nguồn: 4 request trong folder đầu của `postman/vietmap/HomeSpace_VIETMAP_Postman_Collection.json`.
Đã đọc riêng phần 1–4 trong `response.txt`, không dùng các API routing/logistics phía sau.
Đã gọi thật bằng key người dùng cung cấp; không ghi key vào source, fixture hoặc `.env`.

| API | Đường dẫn | Kết quả kiểm thử | Vai trò |
| --- | --- | --- | --- |
| Autocomplete v4 | GET `/api/autocomplete/v4` | HTTP 200, 10 kết quả | Gợi ý khi gõ; hiển thị `display`, chọn `ref_id` |
| Geocode v4 | GET `/api/search/v4` | HTTP 200, 8 kết quả | Tìm từ địa chỉ đầy đủ; kết quả vẫn cần chọn/resolve |
| Place v4 | GET `/api/place/v4` | HTTP 200, 1 object | Resolve `refid` từ kết quả Geocode thành thành phần địa chỉ và tọa độ |
| Reverse v4 | GET `/api/reverse/v4` | HTTP 200, 1 phần tử array | Tìm địa chỉ từ lat/lng của Place; không mặc định luôn là một object |

Autocomplete, Geocode, Reverse dùng `display_type=5` theo collection: địa chỉ mới là chính, `data_old` là bản cũ nếu có.
Place nhận `refid` nguyên vẹn, gồm tiền tố. Không cắt/giải mã `ref_id` như mã hành chính.

Mẫu trực tiếp đã lưu tại `postman/vietmap/verified-v4-sample.json`:
chỉ giữ kết quả đầu được chọn của mỗi API cùng tổng số kết quả, không phải toàn bộ response, không phải seed nghiệp vụ.

### Khác biệt quan trọng đã xác minh

1. TP.HCM trong dữ liệu hiện tại/seed là `79`; trong response Vietmap là `city_id=12`.
   Phường Chợ Quán có Vietmap `ward_id=18700`, không được coi là mã hành chính của nguồn cũ.
2. Autocomplete/Geocode trả `boundaries[]`; phải tìm theo `type` (0=tỉnh/thành, 2=phường/xã), không phụ thuộc vị trí phần tử.
3. Place trả object phẳng: `city_id`, `city`, `ward_id`, `ward`, `hs_num`, `street`, `display`, `lat`, `lng`.
4. Response Place thực tế có `address="Phường Chợ Quán, Thành phố Hồ Chí Minh"`:
   KHÔNG dùng field này làm streetLine. Ghép `hs_num` + `street`; fullAddress lấy `display`.
   Nếu thiếu số nhà/đường hoặc chỉ là POI/khu vực, yêu cầu người dùng bổ sung, không tự đánh dấu địa chỉ tài sản đầy đủ.
5. Địa chỉ mới có `district_id=0`, `district=""`; không tạo quận giả hay bắt buộc có quận.
6. `data_old`, `data_new`, `entry_points`, `partner_code`, `place_detail` có thể null. Không chọn `data_old` làm địa chỉ mới.
7. Nhiều gợi ý có thể ở tỉnh khác nhau; không tự lưu dòng đầu. Dòng đầu chỉ dùng trong phép kiểm thử kỹ thuật này.
8. Không suy ra geocoding chính xác/căn hộ đã xác minh chỉ từ HTTP 200. Tọa độ là điểm từ nhà cung cấp; cần người dùng kiểm tra vị trí thực tế.

## 4. Thiết kế CSDL đề xuất

### Phân tách sở hữu

| Bảng | Chủ thể/FK | Ràng buộc |
| --- | --- | --- |
| `user_addresses` | `user_id` NOT NULL, UNIQUE → users | Một địa chỉ hồ sơ hiện tại theo hành vi đang có; dữ liệu riêng tư |
| `listing_addresses` | `listing_id` NOT NULL, UNIQUE → listings | Một địa chỉ mỗi tin; không có user_id nullable dùng chung |
| `branch_addresses` | `branch_id` NOT NULL, UNIQUE → property_branches | Một địa chỉ mỗi Branch; thay FK ngược `property_branches.address_id` sau migration |

Giữ ID địa chỉ riêng để bảo toàn thao tác SAVED trong API User. Dùng chung kiểu giá trị/address mapper trong code,
không dùng chung bảng đa chủ thể. Listing sao chép SAVED từ User phải tạo dữ liệu Listing độc lập, không chia sẻ mutable row.
Địa chỉ Branch và địa chỉ tin có thể giống nhau nhưng không tự động cập nhật tin/hợp đồng đã chốt khi sửa Branch.

### Field cho mỗi địa chỉ đã lưu

| Nhóm | Field đề xuất | Ghi chú |
| --- | --- | --- |
| Nguồn | `provider`, `provider_api_version`, `administrative_format`, `resolution_method` | VIETMAP/v4/NEW; PLACE hoặc REVERSE; địa chỉ legacy chưa resolve phải phân biệt |
| Tham chiếu | `provider_ref_id` TEXT | Opaque, không giới hạn 36 ký tự; không UNIQUE toàn bảng vì nhiều User/tin có thể cùng địa chỉ |
| Khu vực mới | `provider_province_id`, `provider_ward_id` cùng tên tỉnh/phường | Giữ namespace VIETMAP; không ghi đè mã cũ bằng cùng field mà bỏ metadata nguồn |
| Chi tiết | `house_number`, `street_name`, `street_line`, `full_address` | Tách chuỗi hiển thị và thành phần; tên đầy đủ không ghép lại nếu provider đã trả display |
| Vị trí | `latitude`, `longitude` NUMERIC(10,7), nullable cùng nhau | CHECK khoảng [-90,90]/[-180,180]; không dùng 0,0 làm mặc định cho địa chỉ chưa xác định |
| Địa chỉ cũ | `legacy_full_address` nullable | Nếu use case cần đối chiếu; không lưu toàn cây data_old/data_new cho mọi hàng |
| Kiểm soát | `resolved_at`, `created_at`, `updated_at`, `active`, optimistic version | Xác định stale data, chống ghi đè thao tác đồng thời |

Chỉ bổ sung legacy province/ward/district IDs nếu thực sự hỗ trợ tìm theo hệ cũ, có schema/namespace riêng.
Không lấy tọa độ User làm vị trí thiết bị; không công khai tọa độ thường trú trong API Listing.
Không lưu lịch sử từng lần gõ/autocomplete vào các bảng này, không tạo global cache địa chỉ User dùng chung/public.

Index đề xuất cho `listing_addresses`: UNIQUE(listing_id), composite tỉnh/phường theo provider/format cho các hàng active;
đánh giá trigram/full-text cho keyword thay vì kỳ vọng B-tree tối ưu `LIKE '%...%'`.
Chỉ thêm PostGIS/GiST khi triển khai thật tìm theo bán kính; chưa phải yêu cầu hiện tại.

### Danh mục tỉnh/phường

Bốn endpoint trên không cung cấp toàn bộ danh mục tỉnh/phường để giữ các dropdown hiện có.
Không dùng 10 gợi ý hoặc vài boundaries trong response làm bảng danh mục đầy đủ.

Đề xuất: Core phục vụ danh mục từ một bản dữ liệu hành chính Vietmap được pin phiên bản, import có kiểm chứng ID,
hoặc nguồn danh mục Vietmap được cấp quyền phù hợp. Không cần chạy một location microservice riêng.
Nếu dùng bảng danh mục, tách `geo_provinces` và `geo_wards` khỏi 3 bảng địa chỉ sở hữu; FK/hệ mã có provider+version rõ ràng.
Không giả định dữ liệu tải về tương thích ID API chỉ dựa vào tên; phải đối chiếu mẫu trước khi seed.
Rà license của bộ dữ liệu/API trước khi quyết định phân phối dữ liệu hoặc cache dài hạn; audit này chưa import bộ danh mục.

## 5. Luồng tích hợp đề xuất, chưa phải endpoint đã có

1. Web/Admin → API Core → Vietmap. API key chỉ ở server, không dùng NEXT_PUBLIC/VITE public env.
2. Bốn endpoint Core định kiểu riêng cho autocomplete/geocode/place/reverse; chỉ upstream host cố định, không nhận URL tùy ý.
3. Autocomplete debounce, hủy request cũ, giới hạn query, rate-limit/timeout. Geocode chỉ khi tìm địa chỉ hoàn chỉnh.
4. Người dùng chọn gợi ý → Place. Không tự chọn dòng đầu, không gọi Place cho tất cả gợi ý.
5. Người dùng chọn điểm trên bản đồ → Reverse; phân biệt điểm người dùng chọn và điểm nhà cung cấp trả về.
6. Khi Lưu, Core kiểm tra quyền User/Listing/Branch và resolve/kiểm chứng địa chỉ; không tin city/ward/name/tọa độ tùy ý từ client.
   Có thể dùng token resolve ký bởi server với thời hạn ngắn để tránh gọi lại ở bước save; không dùng token thay quyền sở hữu.
7. Lưu snapshot địa chỉ chuẩn hóa vào bảng đúng chủ thể; render danh sách, chi tiết và hợp đồng từ CSDL.
8. Nếu người dùng sửa chuỗi địa chỉ sau khi resolve, vô hiệu hóa kết quả cũ hoặc bắt resolve lại; không giữ tọa độ cũ cho địa chỉ mới.
9. Timeout, key lỗi/quota hết, response rỗng/sai cấu trúc: báo lỗi rõ, không báo lưu thành công, không ghi đè địa chỉ cũ, không retry vô hạn.
10. Bản đồ nền là phạm vi riêng: 4 API này không thay Google iframe bằng bản đồ Vietmap. Nếu đổi cả bản đồ phải tích hợp SDK/tile và key phù hợp.

## 6. Thứ tự chuyển đổi an toàn

1. Chốt hệ mã canonical và nguồn danh mục; cập nhật API contract/schema, không sửa schema trong listing seed.
2. Tạo Entity/Repository độc lập và migration có version; thêm mapper Vietmap và test fixture v4 đã xác minh.
3. Khi giữ dữ liệu: backfill User/Listing theo FK, Branch theo `property_branches.address_id`; phát hiện orphan/nhiều chủ thể trước khi copy.
   Không auto-geocode toàn CSDL bằng kết quả đầu; có thể tốn phí và gán sai địa chỉ.
4. Đổi toàn bộ reader/writer: User, Listing, Branch, public/admin, AI SQL/JPQL, lịch, yêu cầu thuê, ContractDataBuilder.
5. Đổi Web/Admin sang address picker và danh mục Core; xử lý mã URL/localStorage/featured locations cũ.
6. Đổi seed và test sang bảng mới; dữ liệu giả lập chưa geocode để tọa độ null, không gắn tọa độ thật tùy tiện.
7. Test parity và query count; sau đó ngừng cấu hình/consumer của `hs-location-service`.
8. Chỉ drop bảng cũ/FK cũ bằng migration đã kiểm chứng hoặc khi chủ dự án chủ động reset DB; không dùng ddl-auto=update để mong tự rename/tách bảng.

Nếu reset DB dev: vẫn cần đổi đầy đủ seed và consumer trước khi xóa bảng/service cũ.
Văn bản đã ký và snapshot hợp đồng cũ không bị geocode hay viết lại trong migration.

## 7. Checklist test bắt buộc khi triển khai

- User chỉ đọc/sửa/xóa địa chỉ của mình; User khác không dùng được SAVED ID đó.
- Chủ tin/Branch không sửa được địa chỉ tài sản của người khác.
- User/Listing/Branch cùng địa chỉ vẫn độc lập khi một bên cập nhật/soft delete.
- Saved address inactive không được dùng để lách kiểm tra.
- Autocomplete response đến sai thứ tự không ghi đè selection mới; đổi text hủy selection cũ.
- HTTP 401/423, timeout, response sai JSON, empty array, refid hỏng xử lý rõ, không leak key trong log/error.
- Place.address không bị hiểu là street; district rỗng, thiếu ward, POI chỉ có tên, số nhà chưa có không được lưu sai.
- display_type=5, data_old/null, boundary đảo thứ tự, refid dài, tọa độ ngoài khoảng đều có test.
- Không trộn hệ mã 79/12; tìm thuê theo tỉnh/phường đúng; URL/localStorage/featured locations/seed đồng bộ.
- AI tìm địa chỉ và AI xem lịch vẫn đúng quyền, native SQL không trỏ bảng cũ.
- Chủ tin, public listing detail, lịch, yêu cầu thuê dùng đúng địa chỉ tài sản, không trả địa chỉ thường trú User.
- Hợp đồng mới lấy đúng thường trú/tài sản; sửa địa chỉ không thay snapshot/văn bản đã ký.
- Danh sách nhiều tin đọc địa chỉ theo batch/projection; không gọi Vietmap trên mỗi card hoặc mỗi refresh.
- Chạy Web/Admin/Core khi location service tắt; dropdown/filter/picker vẫn hoạt động.

## 8. Nguồn đối chiếu

- Local: `postman/vietmap/response.txt`, collection, environment và `verified-v4-sample.json`.
- [Vietmap Autocomplete v4](https://maps.vietmap.vn/docs/map-api/autocomplete-version/autocomplete-v4/)
- [Vietmap Geocode v4](https://maps.vietmap.vn/docs/map-api/geocode-version/geocode-v4/)
- [Vietmap Place v4](https://maps.vietmap.vn/docs/map-api/place-v4/)
- [Vietmap Reverse v4](https://maps.vietmap.vn/docs/map-api/reverse-version/reverse-v4/)
- [Bộ dữ liệu hành chính Vietmap](https://github.com/vietmap-company/vietnam_administrative_address)

Trạng thái kết thúc khảo sát: đã kiểm thử HTTP 200 cả 4 API và lưu fixture; chưa thay service, schema hay dữ liệu nghiệp vụ.
