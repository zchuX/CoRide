package com.coride.tripdao

/** Pure helpers for computing usergroups summaries, ordered locations, and UserTrip from record. */
object TripDAOLocationsHelper {

  def userTripArn(tripArn: String, userId: String): String = s"$tripArn:$userId"

  def deriveEffectiveStatus(trip: TripMetadata): String = trip.status

  def summarizeUserGroupsFromRecords(records: List[UserGroupRecord]): List[UserGroup] = {
    records.map { r =>
      val groupSize = r.users.size + r.numAnonymousUsers
      UserGroup(groupId = r.arn, groupName = r.groupName, groupSize = groupSize, numAnonymousUser = r.numAnonymousUsers, imageUrl = None)
    }
  }

  def orderedLocationsFromRecords(trip: TripMetadata, groups: List[UserGroupRecord]): List[Location] = {
    def mergeLocations(locations: Seq[(String, String, String, Long)]): Map[String, Location] = {
      locations.foldLeft(Map.empty[String, Location]) { case (acc, (locName, groupName, kind, time)) =>
        val existing = acc.getOrElse(locName, Location(locationName = locName))
        val updated = kind match {
          case "pickup"  => existing.copy(pickupGroups = existing.pickupGroups :+ groupName)
          case "dropoff" => existing.copy(dropOffGroups = existing.dropOffGroups :+ groupName)
        }
        acc + (locName -> updated)
      }
    }

    val pickups: Seq[(String, String, String, Long)] =
      groups.flatMap(g => if (g.start.nonEmpty) Some((g.start, g.groupName, "pickup", g.pickupTime)) else None)
    val pickupLocationsMap = mergeLocations(pickups)
    val pickupLocationsOrdered = pickups.sortBy(_._4).map(_._1).distinct.map(pickupLocationsMap)

    val dropoffs: Seq[(String, String, String, Long)] =
      groups.flatMap(g => if (g.destination.nonEmpty) Some((g.destination, g.groupName, "dropoff", g.pickupTime)) else None)
    val dropoffLocationsMap = mergeLocations(dropoffs)
    val dropoffLocationsOrdered = dropoffs.sortBy(-_._4).map(_._1).distinct.map(dropoffLocationsMap)

    val startLocationOpt = trip.locations.headOption
    val endLocationOpt = trip.locations.lastOption
    val middleLocations = pickupLocationsOrdered ++ dropoffLocationsOrdered

    val finalLocations = (startLocationOpt.toList ++ middleLocations ++ endLocationOpt.toList)
      .groupBy(_.locationName)
      .map { case (locName, locs) =>
        locs.reduce { (a, b) =>
          a.copy(
            pickupGroups = (a.pickupGroups ++ b.pickupGroups).distinct,
            dropOffGroups = (a.dropOffGroups ++ b.dropOffGroups).distinct
          )
        }
      }
      .toList
      .sortBy(loc => {
        val idx = middleLocations.indexWhere(_.locationName == loc.locationName)
        if (idx >= 0) idx else if (startLocationOpt.exists(_.locationName == loc.locationName)) -1 else Int.MaxValue
      })

    finalLocations
  }

  def mergeSingleGroupIntoLocations(base: List[Location], start: String, destination: String, groupName: String): List[Location] = {
    val byName = scala.collection.mutable.Map.from(base.map(l => l.locationName -> l))
    val sLoc = byName.getOrElse(start, Location(locationName = start))
    byName.update(start, sLoc.copy(pickupGroups = (sLoc.pickupGroups :+ groupName).distinct))
    val dLoc = byName.getOrElse(destination, Location(locationName = destination))
    byName.update(destination, dLoc.copy(dropOffGroups = (dLoc.dropOffGroups :+ groupName).distinct))
    byName.values.toList.sortBy(_.locationName)
  }

  def removeSingleGroupFromLocations(base: List[Location], start: String, destination: String, groupName: String): List[Location] = {
    val byName = scala.collection.mutable.Map.from(base.map(l => l.locationName -> l))
    byName.get(start).foreach { sLoc =>
      byName.update(start, sLoc.copy(pickupGroups = sLoc.pickupGroups.filterNot(_ == groupName)))
    }
    byName.get(destination).foreach { dLoc =>
      byName.update(destination, dLoc.copy(dropOffGroups = dLoc.dropOffGroups.filterNot(_ == groupName)))
    }
    byName.values.filter(l => l.pickupGroups.nonEmpty || l.dropOffGroups.nonEmpty).toList.sortBy(_.locationName)
  }

  def buildUserTripFromRecord(trip: TripMetadata, group: UserGroupRecord, user: GroupUser): UserTrip = {
    val arn = userTripArn(trip.tripArn, user.userId)
    val initialStatus = if (user.accept) deriveEffectiveStatus(trip) else "Invitation"
    val usk = s"${user.userId}-${if (initialStatus == "Completed") "completed" else "uncompleted"}"
    UserTrip(
      arn = arn,
      tripArn = trip.tripArn,
      userStatusKey = usk,
      tripDateTime = group.pickupTime,
      tripStatus = initialStatus,
      start = group.start,
      destination = group.destination,
      departureDateTime = group.pickupTime,
      isDriver = trip.driver.exists(_ == user.userId),
      driverConfirmed = trip.driverConfirmed.getOrElse(false),
      version = 1
    )
  }
}
