package com.coride.tripdao

/**
 * One-off backfill: set userGroupArn on UserTrip items where isDriver=false and userGroupArn is missing.
 * Run with: sbt "runMain com.coride.tripdao.BackfillUserTripGroupArn"
 * Env: AWS_REGION, USER_TRIPS_TABLE, USERGROUPS_TABLE (or USER_GROUPS_TABLE), TRIP_METADATA_TABLE.
 */
object BackfillUserTripGroupArn {
  def main(args: Array[String]): Unit = {
    val dao = TripDAO()
    var updated = 0
    var skipped = 0
    var notFound = 0
    dao.processAllUserTrips { ut =>
      if (ut.isDriver || ut.userGroupArn.isDefined) {
        skipped += 1
      } else {
        val userId = if (ut.arn.contains(":")) ut.arn.substring(ut.arn.lastIndexOf(':') + 1) else ""
        val tripArn = if (ut.arn.contains(":")) ut.arn.substring(0, ut.arn.lastIndexOf(':')) else ut.tripArn
        val groups = dao.listUserGroupRecordsByTripArn(tripArn)
        groups.find(g => g.users.exists(_.userId == userId)) match {
          case Some(g) =>
            dao.updateUserTripGroupArn(ut.arn, g.arn)
            updated += 1
            println(s"Updated ${ut.arn} -> ${g.arn}")
          case None =>
            notFound += 1
        }
      }
    }
    println(s"Backfill done: updated=$updated, skipped=$skipped, groupNotFound=$notFound")
  }
}
