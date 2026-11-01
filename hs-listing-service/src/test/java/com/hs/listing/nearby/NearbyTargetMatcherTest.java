package com.hs.listing.nearby;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.hs.listing.nearby.NearbyTypes.*;

class NearbyTargetMatcherTest {
 private final NearbySettings settings=NearbyConfigurationTest.configured();
 private final String name="Trường Đại Học Công Nghiệp Thành Phố Hồ Chí Minh";
 private NearbyTargetMatcher.Candidate candidate(String id,String label,String address,double lat,Set<String> types) {
  return new NearbyTargetMatcher.Candidate(new Target(id,label,address,lat,106.6873),types);
 }
 private NearbyTargetMatcher.Candidate university(String id,String label,String address,double lat) {
  return candidate(id,label,address,lat,Set.of("7377"));
 }
 @Test void abbreviationsAndShortCityNameResolveFromStoredNamesWithoutAnInstitutionAliasList() {
  var actual=university("real",name,"12 Nguyễn Văn Bảo, Hạnh Thông, Hồ Chí Minh",10.822263);
  for(String query:List.of("Đại học công nghiệp HCM","ĐH Công nghiệp TP.HCM","dai hoc cong nghiep Ho Chi Minh"))
   assertEquals(List.of(actual.target()),NearbyTargetMatcher.select(NearbyTargetMatcher.parse(query),List.of(actual),settings),query);
 }
 @Test void pastedNameAndAddressSelectsTheRightCampusWithoutTreatingTheAddressAsAName() {
  var a=university("a",name+" Cơ sở 1","12 Đường Nguyễn Văn Bảo, Hạnh Thông, Hồ Chí Minh",10.82);
  var b=university("b",name+" Cơ sở 2","50 Đường Khác, An Nhơn, Hồ Chí Minh",10.89);
  String query=name+" — 12 Đường Nguyễn Văn Bảo, Hạnh Thông, Hồ Chí Minh";
  assertEquals(List.of(a.target()),NearbyTargetMatcher.select(NearbyTargetMatcher.parse(query),List.of(b,a),settings));
 }
 @Test void samePhysicalInstitutionFrontagesDoNotForceTheRenterToChooseAnEntrance() {
  var a=university("a",name,"12 Nguyễn Văn Bảo",10.822263);
  var b=university("b",name.replace("Trường ",""),"12 Nguyễn Văn Nghi",10.822455);
  assertEquals(1,NearbyTargetMatcher.select(NearbyTargetMatcher.parse("Đại học công nghiệp HCM"),List.of(a,b),settings).size());
 }
 @Test void distantOrNumberedCampusesRemainAmbiguousUntilCustomerChooses() {
  var a=university("a",name+" Cơ sở 1","12 Nguyễn Văn Bảo",10.82);
  var b=university("b",name+" Cơ sở 2","12 Nguyễn Văn Nghi",10.8201);
  assertEquals(2,NearbyTargetMatcher.select(NearbyTargetMatcher.parse("Đại học công nghiệp HCM"),List.of(a,b),settings).size());
 }
 @Test void genericMatchingWorksForOtherLandmarksAndDoesNotAcceptASubstringAsAWholeWord() {
  var right=candidate("a","Bệnh viện Bình An","15 Đường A",10.82,Set.of("7321"));
  var wrong=candidate("b","Bệnh viện An Bình","25 Đường B",10.83,Set.of("7321"));
  assertEquals(2,NearbyTargetMatcher.select(NearbyTargetMatcher.parse("Bệnh viện Bình An"),List.of(right,wrong),settings).size());
  assertTrue(NearbyTargetMatcher.select(NearbyTargetMatcher.parse("Bệnh viện Bình Anh"),List.of(right),settings).isEmpty());
 }
 @Test void providerCategoryEvidenceUsesTheStoredObjectKeysAndParents() {
  assertEquals(Set.of("7377","7377002"),NearbyTargetMatcher.categories("{\"7377\":[],\"7377002\":[\"7377\"]}"));
 }
 @Test void aNamedUniversityIsNeverConfusedWithAHospitalSharingItsProperName() {
  var school=university("school","Đại học Bình An","1 Đường A",10.82);
  var hospital=candidate("hospital","Bệnh viện Bình An","1 Đường A",10.82,Set.of("7321"));
  assertEquals(List.of(school.target()),NearbyTargetMatcher.select(NearbyTargetMatcher.parse("ĐH Bình An"),List.of(hospital,school),settings));
 }
}
