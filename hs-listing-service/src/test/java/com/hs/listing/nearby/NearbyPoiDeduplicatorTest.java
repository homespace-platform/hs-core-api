package com.hs.listing.nearby;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.hs.listing.nearby.NearbyTypes.*;

class NearbyPoiDeduplicatorTest {
 private final NearbySettings settings=NearbyConfigurationTest.configured();
 private final Location listing=new Location("listing","address",10.8209195,106.6872986,"hash");
 private final Map<String,Set<String>> parents=Map.of("7377",Set.of(),"7372",Set.of(),"7321",Set.of(),
   "7332005",Set.of(),"7377010",Set.of("7377"));
 private Poi poi(String id,String name,double northMeters,String kind) {
  return new Poi(id,name,listing.latitude()+northMeters/111195,listing.longitude(),
    id+" Đường khác",null,Set.of(kind),null,null,null);
 }
 private List<Poi> select(Poi... places) {return NearbyPoiDeduplicator.select(listing,List.of(places),2000,settings,parents);}
 private static List<String> ids(List<Poi> places) {return places.stream().map(Poi::externalId).toList();}
 private Poi near() {return poi("z-near","Trường Đại Học Công Nghiệp Thành Phố Hồ Chí Minh",25,"7377");}
 private Poi far() {return poi("a-far","Đại Học Công Nghiệp Thành Phố Hồ Chí Minh",220,"7377");}

 @Test void twoUniversityFrontagesKeepNearestBeforeAnyInsertNotProviderOrIdOrder() {
  assertEquals(List.of("z-near"),ids(select(far(),near())));
  assertEquals(List.of("z-near"),ids(select(near(),far())));
 }
 @Test void eachListingChoosesItsOwnNearestEntrance() {
  Location other=new Location("other","other-address",far().latitude(),far().longitude(),"other-hash");
  assertEquals(List.of("a-far"),ids(NearbyPoiDeduplicator.select(other,List.of(near(),far()),2000,settings,parents)));
 }
 @Test void reportedIndustrialUniversityCoordinatesAndTwoAddressesAreDeduplicated() {
  Poi entranceA=new Poi("nguyen-van-bao",near().name(),10.8222630,106.6868580,
    "12 Đường Nguyễn Văn Bảo, Hạnh Thông, Hồ Chí Minh",null,Set.of("7377"),null,null,null);
  Poi entranceB=new Poi("nguyen-van-nghi",far().name(),10.8224550,106.6882070,
    "12 Đường Nguyễn Văn Nghi, Hạnh Thông, Hồ Chí Minh",null,Set.of("7377"),null,null,null);
  assertEquals(List.of("nguyen-van-bao"),ids(select(entranceB,entranceA)));
 }
 @Test void normalizationHandlesAccentsSchoolPrefixAndCityAbbreviations() {
  assertEquals(NearbyPoiDeduplicator.normalizedName(near().name()),
    NearbyPoiDeduplicator.normalizedName("ĐH Công Nghiệp TP.HCM"));
  assertEquals(List.of("near"),ids(select(poi("far","DH Cong Nghiep TPHCM",180,"7377"),
    poi("near",near().name(),30,"7377"))));
 }
 @Test void tinySpellingVariationCanMatchAFullInstitutionName() {
  assertEquals(1,select(near(),poi("typo","Đại Học Công Nghiepp Thành Phố Hồ Chí Minh",140,"7377")).size());
 }
 @Test void aNameSubsetIsNotEnoughToDropADepartment() {
  assertEquals(2,select(near(),poi("department",near().name()+" Khoa Kinh Tế",20,"7377")).size());
 }
 @Test void separateNumberedCampusesAreNeverCollapsedEvenWhenCoordinatesCoincide() {
  assertEquals(2,select(poi("1",near().name()+" Cơ Sở 1",10,"7377"),
    poi("2",near().name()+" Cơ Sở 2",10,"7377")).size());
 }
 @Test void separatelyNamedBranchesAreNeverCollapsed() {
  assertEquals(2,select(poi("a",near().name()+" Chi Nhánh An Nhơn",10,"7377"),
    poi("b",near().name()+" Chi Nhánh An Nhan",10,"7377")).size());
 }
 @Test void identicalNamesBeyondInstitutionRadiusStaySeparate() {
  assertEquals(2,select(near(),poi("remote",near().name(),500,"7377")).size());
 }
 @Test void chainBrandAloneDoesNotMergeStoresOnDifferentRoads() {
  assertEquals(2,select(poi("a","Circle K",10,"7332005"),poi("b","Circle K",90,"7332005")).size());
 }
 @Test void shortBrandAtTwoNearbyAddressesIsNotEnoughEvidenceToMerge() {
  assertEquals(2,select(poi("a","Circle K",10,"7332005"),poi("b","Circle K",20,"7332005")).size());
 }
 @Test void aShortBrandCanMatchWhenTheSameAddressAndCloseCoordinatesConfirmIt() {
  Poi a=poi("a","Circle K",10,"7332005");
  Poi b=new Poi("b",a.name(),listing.latitude()+20/111195d,listing.longitude(),a.address(),
    null,a.categoryIds(),null,null,null);
  assertEquals(List.of("a"),ids(select(b,a)));
 }
 @Test void matchingNamesInDifferentCategoriesStaySeparate() {
  assertEquals(2,select(near(),poi("other",near().name(),25,"7321")).size());
 }
 @Test void verifiedChildAndParentCategoriesAreCompatible() {
  assertEquals(1,select(near(),poi("child",near().name(),100,"7377010")).size());
 }
 @Test void unknownCategoryEvidenceIsNotEnoughToMergeDifferentProviderIds() {
  assertEquals(2,select(poi("a",near().name(),20,"999999"),poi("b",near().name(),21,"999999")).size());
 }
 @Test void exactProviderIdStillDeduplicatesAndChoosesNearestCoordinate() {
  assertEquals(1,select(poi("same","Old name",100,"7377"),poi("same","New name",20,"7377")).size());
  assertEquals("New name",select(poi("same","Old name",100,"7377"),poi("same","New name",20,"7377")).getFirst().name());
 }
 @Test void noTransitiveChainCanCollapseDistantSites() {
  assertEquals(2,select(poi("a",near().name(),10,"7377"),poi("b",near().name(),250,"7377"),
    poi("c",near().name(),490,"7377")).size());
 }
 @Test void outOfRadiusCoordinatesAreNotCandidatesForSaving() {
  assertEquals(List.of("z-near"),ids(select(near(),poi("outside",near().name(),2500,"7377"))));
 }
 @Test void distanceTiesHaveAStableWinner() {
  assertEquals(List.of("a"),ids(select(poi("z",near().name(),25,"7377"),poi("a",near().name(),25,"7377"))));
 }
 @Test void nonInstitutionNamesOnlyDeduplicateInsideTheSmallRadius() {
  assertEquals(1,select(poi("near","Quán Hủ Tiếu Miền Tây",10,"7315"),
    poi("far","Quán Hủ Tiếu Miền Tây",20,"7315")).size());
 }
}
