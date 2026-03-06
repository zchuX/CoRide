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
      val groupSize = r.users.size
      UserGroup(groupId = r.arn, groupName = r.groupName, groupSize = groupSize, imageUrl = None)
    }
  }

  /** For transactional write path only: build locations (with plannedTime) to write to TripMetadata.
    * All pickup/dropoff group lists are derived only from current groups so that when a group's
    * start/destination changes, the old location no longer has that group in its list.
    */
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
    val dropoffs: Seq[(String, String, String, Long)] =
      groups.flatMap(g => if (g.destination.nonEmpty) Some((g.destination, g.groupName, "dropoff", g.pickupTime)) else None)
    val dropoffLocationsMap = mergeLocations(dropoffs)

    // plannedTime only from pickup groups (earliest among groups picked up at this location); dropoff-only or driver-only = 0L
    val allNames = (pickupLocationsMap.keySet ++ dropoffLocationsMap.keySet).toSeq
    val canonicalMap: Map[String, Location] = allNames.map { name =>
      val pick = pickupLocationsMap.getOrElse(name, Location(locationName = name, plannedTime = 0L))
      val drop = dropoffLocationsMap.getOrElse(name, Location(locationName = name, plannedTime = 0L))
      val planned = if (pick.pickupGroups.nonEmpty) pick.plannedTime else 0L
      name -> Location(
        locationName = name,
        plannedTime = planned,
        pickupGroups = pick.pickupGroups,
        dropOffGroups = drop.dropOffGroups,
        arrived = pick.arrived || drop.arrived,
        arrivedTime = pick.arrivedTime.orElse(drop.arrivedTime)
      )
    }.toMap

    val startLocationOpt = trip.locations.headOption
    val endLocationOpt = trip.locations.lastOption
    val pickupOrderedNames = pickups.sortBy(_._4).map(_._1).distinct
    val dropoffOrderedNames = dropoffs.sortBy(-_._4).map(_._1).distinct
    val middleOrderedNames = (pickupOrderedNames ++ dropoffOrderedNames).distinct
    val middleLocations = middleOrderedNames.flatMap(name => canonicalMap.get(name))

    // Use canonical map for start/end so we don't keep stale groups; driver-only waypoints get no plannedTime
    def locationFor(name: String, fromTrip: Option[Location]): Location =
      canonicalMap.getOrElse(name, fromTrip.fold(Location(locationName = name))(t => t.copy(pickupGroups = Nil, dropOffGroups = Nil, plannedTime = 0L)))

    val startLoc = startLocationOpt.map(s => locationFor(s.locationName, Some(s)))
    val endLoc = endLocationOpt.map(e => locationFor(e.locationName, Some(e)))

    val finalLocations = (startLoc.toList ++ middleLocations ++ endLoc.toList)
      .groupBy(_.locationName)
      .map { case (_, locs) => locs.head }
      .toList
      .sortBy(loc => {
        val idx = middleOrderedNames.indexOf(loc.locationName)
        if (idx >= 0) idx
        else if (startLocationOpt.exists(_.locationName == loc.locationName)) -1
        else Int.MaxValue
      })

    finalLocations
  }

  /** Build locations in the given order with pickup/dropoff groups and plannedTime from current groups.
    * plannedTime = earliest pickupTime among groups picked up at that location; 0L for dropoff-only or driver-only.
    * Preserves arrived/arrivedTime from existingLocations.
    */
  def locationsForOrderAndGroups(orderedNames: List[String], groups: List[UserGroupRecord], existingLocations: List[Location]): List[Location] = {
    def mergeLocations(locations: Seq[(String, String, String, Long)]): Map[String, Location] = {
      locations.foldLeft(Map.empty[String, Location]) { case (acc, (locName, groupName, kind, time)) =>
        val existing = acc.getOrElse(locName, Location(locationName = locName, plannedTime = time))
        val planned = if (existing.plannedTime == 0L) time else kind match {
          case "pickup" => existing.plannedTime.min(time)
          case "dropoff" => existing.plannedTime.max(time)
        }
        val updated = kind match {
          case "pickup"  => existing.copy(pickupGroups = existing.pickupGroups :+ groupName, plannedTime = planned)
          case "dropoff" => existing.copy(dropOffGroups = existing.dropOffGroups :+ groupName, plannedTime = planned)
        }
        acc + (locName -> updated)
      }
    }
    val pickups = groups.flatMap(g => if (g.start.nonEmpty) Some((g.start, g.groupName, "pickup", g.pickupTime)) else None)
    val dropoffs = groups.flatMap(g => if (g.destination.nonEmpty) Some((g.destination, g.groupName, "dropoff", g.pickupTime)) else None)
    val pickupMap = mergeLocations(pickups)
    val dropoffMap = mergeLocations(dropoffs)
    val allNames = (pickupMap.keySet ++ dropoffMap.keySet).toSeq
    val canonicalMap: Map[String, Location] = allNames.map { name =>
      val pick = pickupMap.getOrElse(name, Location(locationName = name, plannedTime = 0L))
      val drop = dropoffMap.getOrElse(name, Location(locationName = name, plannedTime = 0L))
      val planned = if (pick.pickupGroups.nonEmpty) pick.plannedTime else 0L
      name -> Location(
        locationName = name,
        plannedTime = planned,
        pickupGroups = pick.pickupGroups,
        dropOffGroups = drop.dropOffGroups,
        arrived = pick.arrived || drop.arrived,
        arrivedTime = pick.arrivedTime.orElse(drop.arrivedTime)
      )
    }.toMap
    val existingByName = existingLocations.map(l => l.locationName -> l).toMap
    orderedNames.map { name =>
      val existing = existingByName.get(name)
      canonicalMap.get(name) match {
        case Some(c) =>
          c.copy(
            arrived = c.arrived || existing.exists(_.arrived),
            arrivedTime = c.arrivedTime.orElse(existing.flatMap(_.arrivedTime))
          )
        case None =>
          existing.fold(Location(locationName = name))(t => t.copy(pickupGroups = Nil, dropOffGroups = Nil, plannedTime = 0L))
      }
    }
  }

  /** Validates that for every group, its dropoff location does not appear before its pickup in the ordered locations list. */
  def validateDropoffAfterPickup(locations: List[Location], groups: List[UserGroupRecord]): Option[String] =
    validateDropoffAfterPickupByNames(locations.map(_.locationName), groups)

  /** Validates that for every group, its dropoff location does not appear before or at its pickup in the ordered location names list. */
  def validateDropoffAfterPickupByNames(orderedLocationNames: List[String], groups: List[UserGroupRecord]): Option[String] = {
    val nameToIndex = orderedLocationNames.zipWithIndex.toMap
    val bad = groups.find { g =>
      (g.start.nonEmpty && g.destination.nonEmpty) && {
        val pickIdx = nameToIndex.getOrElse(g.start, -1)
        val dropIdx = nameToIndex.getOrElse(g.destination, Int.MaxValue)
        dropIdx <= pickIdx
      }
    }
    bad.map(g => s"Group ${g.groupName}: dropoff location '${g.destination}' cannot be before or same as pickup '${g.start}'")
  }

  def mergeSingleGroupIntoLocations(base: List[Location], start: String, destination: String, groupName: String, pickupTime: Long): List[Location] = {
    val byName = scala.collection.mutable.Map.from(base.map(l => l.locationName -> l))
    val sLoc = byName.getOrElse(start, Location(locationName = start, plannedTime = pickupTime))
    byName.update(start, sLoc.copy(pickupGroups = (sLoc.pickupGroups :+ groupName).distinct, plannedTime = if (sLoc.plannedTime == 0L) pickupTime else sLoc.plannedTime.min(pickupTime)))
    val dLoc = byName.getOrElse(destination, Location(locationName = destination, plannedTime = 0L))
    byName.update(destination, dLoc.copy(dropOffGroups = (dLoc.dropOffGroups :+ groupName).distinct, plannedTime = if (dLoc.pickupGroups.nonEmpty) dLoc.plannedTime else 0L))
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
      version = 1,
      userGroupArn = Some(group.arn)
    )
  }
}
