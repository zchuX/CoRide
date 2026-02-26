package com.coride.lambda.features.trips

import com.coride.tripdao.UserGroupRecord

/**
 * Validates that no user appears more than once in a trip: not both as driver and in a group,
 * and not in multiple groups. Use before create trip and when adding/updating user groups.
 */
object TripValidation {

  /**
   * Returns None if valid, or Some(errorMessage) if the trip would have duplicate users.
   * - Driver (if present) must not appear in any group's users.
   * - Each user may appear in at most one group (no user in multiple groups).
   * - Each group's user list must not contain duplicate userIds.
   */
  def validateNoDuplicateUsersInTrip(driverOpt: Option[String], groups: List[UserGroupRecord]): Option[String] = {
    val driverInGroup = driverOpt.flatMap { driverId =>
      groups.find(g => g.users.exists(_.userId == driverId)).map(_ => driverId)
    }
    if (driverInGroup.isDefined)
      return Some(s"User cannot be both driver and passenger: ${driverInGroup.get}")

    val allGroupUserIds = groups.flatMap(g => g.users.map(_.userId))
    val seen = scala.collection.mutable.Set.empty[String]
    val duplicates = scala.collection.mutable.Set.empty[String]
    for (uid <- allGroupUserIds) {
      if (seen(uid)) duplicates += uid else seen += uid
    }
    if (duplicates.nonEmpty)
      return Some(s"Duplicate user in trip: ${duplicates.head} appears in multiple groups or more than once in a group")

    None
  }
}
