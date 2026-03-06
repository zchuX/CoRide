package com.coride.tripdao

final case class Location(
  locationName: String,
  plannedTime: Long = 0L,
  pickupGroups: List[String] = Nil,
  dropOffGroups: List[String] = Nil,
  arrived: Boolean = false,
  arrivedTime: Option[Long] = None
)

final case class Car(
  plateNumber: Option[String] = None,
  color: Option[String] = None,
  model: Option[String] = None
)

final case class UserGroup(
  groupId: String,
  groupName: String,
  groupSize: Int,
  imageUrl: Option[String] = None
)

final case class TripUser(
  userId: Option[String] = None,
  name: String,
  imageUrl: Option[String] = None
)

final case class TripMetadata(
  tripArn: String,
  locations: List[Location] = Nil,
  startTime: Long,
  completionTime: Option[Long] = None,
  status: String,
  currentStop: Option[String] = None,
  driver: Option[String] = None,
  driverName: Option[String] = None,
  driverPhotoUrl: Option[String] = None,
  driverConfirmed: Option[Boolean] = None,
  car: Option[Car] = None,
  usergroups: Option[List[UserGroup]] = None,
  users: Option[List[TripUser]] = None,
  notes: Option[String] = None,
  version: Int = 1
)

final case class UserTrip(
  arn: String,
  tripArn: String,
  userStatusKey: String,
  tripDateTime: Long,
  tripStatus: String,
  start: String,
  destination: String,
  departureDateTime: Long,
  isDriver: Boolean = false,
  driverConfirmed: Boolean,
  version: Int = 1,
  userGroupArn: Option[String] = None
)

// Additional table models: UserGroups
final case class GroupUser(
  userId: String,
  name: String,
  imageUrl: Option[String] = None,
  accept: Boolean
)

final case class UserGroupRecord(
  arn: String,
  tripArn: String,
  groupName: String,
  start: String,
  destination: String,
  pickupTime: Long,
  users: List[GroupUser] = Nil,
  version: Int = 1
)