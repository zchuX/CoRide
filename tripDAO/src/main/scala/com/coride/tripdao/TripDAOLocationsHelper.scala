package com.coride.tripdao

/** Pure helpers for computing usergroups summaries, ordered locations, and UserTrip from record.
  * orderedLocationsFromRecords is used only when building new TripMetadata.locations during
  * transactional writes (trip creation, user group add/update/remove). plannedTime is set there
  * and written to TripMetadata; it is never synced or derived on read.
  */
object TripDAOLocationsHelper {

  def userTripArn(tripArn: String, userId: String): String = s"$tripArn:$userId"

  def deriveEffectiveStatus(trip: TripMetadata): String = trip.status

  def summarizeUserGroupsFromRecords(records: List[UserGroupRecord]): List[UserGroup] = {
    records.map { r =>
      val groupSize = r.users.size + r.numAnonymousUsers
      UserGroup(groupId = r.arn, groupName = r.groupName, groupSize = groupSize, numAnonymousUser = r.numAnonymousUsers, imageUrl = None)
    }
  }

  /** For transactional write path only: build locations (with plannedTime) to write to TripMetadata. */
  def orderedLocationsFromRecords(trip: TripMetadata, groups: List[UserGroupRecord]): List[Location] = {
    def mergeLocations(locations: Seq[(String, String, String, Long)]): Map[String, Location] = {
      locations.foldLeft(Map.empty[String, Location]) { case (acc, (locName, groupName, kind, time)) =>
        val existing = acc.getOrElse(locName, Location(locationName = locName, plannedTime = time))
        val planned = if (existing.plannedTime == 0L) time else kind match {
          case "pickup"  => existing.plannedTime.min(time)
          case "dropoff" => existing.plannedTime.max(time)
        }
        val updated = kind match {
          case "pickup"  => existing.copy(pickupGroups = existing.pickupGroups :+ groupName, plannedTime = planned)
          case "dropoff" => existing.copy(dropOffGroups = existing.dropOffGroups :+ groupName, plannedTime = planned)
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
      .map { case (_, locs) =>
        locs.reduce { (a, b) =>
          a.copy(
            plannedTime = if (a.plannedTime == 0L) b.plannedTime else if (b.plannedTime == 0L) a.plannedTime else a.plannedTime.min(b.plannedTime),
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

  def mergeSingleGroupIntoLocations(base: List[Location], start: String, destination: String, groupName: String, pickupTime: Long): List[Location] = {
    val byName = scala.collection.mutable.Map.from(base.map(l => l.locationName -> l))
    val sLoc = byName.getOrElse(start, Location(locationName = start, plannedTime = pickupTime))
    byName.update(start, sLoc.copy(pickupGroups = (sLoc.pickupGroups :+ groupName).distinct, plannedTime = if (sLoc.plannedTime == 0L) pickupTime else sLoc.plannedTime.min(pickupTime)))
    val dLoc = byName.getOrElse(destination, Location(locationName = destination, plannedTime = pickupTime))
    byName.update(destination, dLoc.copy(dropOffGroups = (dLoc.dropOffGroups :+ groupName).distinct, plannedTime = if (dLoc.plannedTime == 0L) pickupTime else dLoc.plannedTime.max(pickupTime)))
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
