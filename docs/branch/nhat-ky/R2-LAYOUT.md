# Nhật ký R2-LAYOUT (Sonnet)

Worktree .wt-core-layout, nhánh wip/layout-detach-ack. Hợp đồng R-D lấy từ 04-cau-hoi.md của hs-core-api (worktree chưa có mục này).

1. [x] Code: DTO acknowledgedDetach, ListingErrorCode 4093, BranchRoomLinkService.releaseRooms(ack), guard.contractsInRooms, repo findListingIdsByRoomIds, giới hạn 5000 id/danh sách. Thứ tự: 4074, rồi validate/size (400), rồi 4075, rồi 4093.
2. [x] Test: BranchRoomLinkServiceTest (+2), BranchLayoutServiceTest (+1), BranchLayoutApiTest (+3), BranchAssociationsPostgresTest (+1).
3. [x] Verify: listing 590 run/0 fail/94 skip; contract 1378/0/250; api 165/0/1 (BUILD SUCCESS). PG (hs-r2-layout-pg, đã xoá): BranchLayoutPostgresTest 4/4, BranchAssociationsPostgresTest 22/22 (gồm ca mới), 0 skip.
