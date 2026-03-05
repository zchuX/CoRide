package com.coride.tripdao

import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import scala.jdk.CollectionConverters._

/** Helpers to convert models to DynamoDB AttributeValues */
object ModelCodec {
  import Attrs._

  def locationToAttr(loc: Location): AttributeValue = map(Map(
    "locationName" -> s(loc.locationName),
    "plannedTime" -> n(loc.plannedTime),
    "pickupGroups" -> list(loc.pickupGroups.map(s)),
    "dropOffGroups" -> list(loc.dropOffGroups.map(s)),
    "arrived" -> bool(loc.arrived),
    "arrivedTime" -> (loc.arrivedTime.map(n).getOrElse(AttributeValue.builder().nul(true).build()))
  ))

  def carToAttr(car: Car): AttributeValue = map(Map(
    "plateNumber" -> car.plateNumber.map(s).getOrElse(AttributeValue.builder().nul(true).build()),
    "color" -> car.color.map(s).getOrElse(AttributeValue.builder().nul(true).build()),
    "model" -> car.model.map(s).getOrElse(AttributeValue.builder().nul(true).build())
  ))

  def userGroupToAttr(g: UserGroup): AttributeValue = map(Map(
    "groupId" -> s(g.groupId),
    "groupName" -> s(g.groupName),
    "groupSize" -> nInt(g.groupSize),
    "imageUrl" -> g.imageUrl.map(s).getOrElse(AttributeValue.builder().nul(true).build())
  ))

  def tripUserToAttr(u: TripUser): AttributeValue = map(Map(
    "userId" -> u.userId.map(s).getOrElse(AttributeValue.builder().nul(true).build()),
    "name" -> s(u.name),
    "imageUrl" -> u.imageUrl.map(s).getOrElse(AttributeValue.builder().nul(true).build())
  ))

  def locationsToAttr(locs: List[Location]): AttributeValue = list(locs.map(locationToAttr))

  def usergroupsToAttr(ugs: List[UserGroup]): AttributeValue = list(ugs.map(userGroupToAttr))

  def tripMetadataToItem(t: TripMetadata): java.util.Map[String, AttributeValue] = Map(
    "tripArn" -> s(t.tripArn),
    "locations" -> locationsToAttr(t.locations),
    "startTime" -> n(t.startTime),
    "completionTime" -> t.completionTime.map(n).getOrElse(AttributeValue.builder().nul(true).build()),
    "status" -> s(t.status),
    "currentStop" -> t.currentStop.map(s).getOrElse(AttributeValue.builder().nul(true).build()),
    "driver" -> t.driver.map(s).getOrElse(AttributeValue.builder().nul(true).build()),
    "driverName" -> t.driverName.map(s).getOrElse(AttributeValue.builder().nul(true).build()),
    "driverPhotoUrl" -> t.driverPhotoUrl.map(s).getOrElse(AttributeValue.builder().nul(true).build()),
    "driverConfirmed" -> t.driverConfirmed.map(bool).getOrElse(AttributeValue.builder().nul(true).build()),
    "car" -> t.car.map(carToAttr).getOrElse(AttributeValue.builder().nul(true).build()),
    "usergroups" -> t.usergroups.map(usergroupsToAttr).getOrElse(AttributeValue.builder().nul(true).build()),
    "users" -> t.users.map(us => list(us.map(tripUserToAttr))).getOrElse(AttributeValue.builder().nul(true).build()),
    "notes" -> t.notes.map(s).getOrElse(AttributeValue.builder().nul(true).build()),
    "version" -> nInt(t.version)
  ).asJava

  def userTripToItem(t: UserTrip): java.util.Map[String, AttributeValue] = Map(
    "arn" -> s(t.arn),
    "tripArn" -> s(t.tripArn),
    "userStatusKey" -> s(t.userStatusKey),
    "tripDateTime" -> n(t.tripDateTime),
    "tripStatus" -> s(t.tripStatus),
    "start" -> s(t.start),
    "destination" -> s(t.destination),
    "departureDateTime" -> n(t.departureDateTime),
    "isDriver" -> bool(t.isDriver),
    "driverConfirmed" -> bool(t.driverConfirmed),
    "version" -> nInt(t.version)
  ).asJava

  def groupUserToAttr(u: GroupUser): AttributeValue = map(Map(
    "userId" -> s(u.userId),
    "name" -> s(u.name),
    "accept" -> bool(u.accept)
  ))

  def userGroupRecordToItem(g: UserGroupRecord): java.util.Map[String, AttributeValue] = Map(
    "groupArn" -> s(g.arn),
    "tripArn" -> s(g.tripArn),
    "groupName" -> s(g.groupName),
    "start" -> s(g.start),
    "destination" -> s(g.destination),
    "pickupTime" -> n(g.pickupTime),
    "users" -> list(g.users.map(groupUserToAttr)),
    "version" -> nInt(g.version)
  ).asJava
}
