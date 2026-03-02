package com.coride.userfriendsdao

/** Profile item stored under each user's partition (SK = PROFILE). */
final case class UserFriendProfile(
  userArn: String,
  name: String
)

/** Denormalized friendship record under a user's partition (SK = FRIEND#<friendUserArn>). */
final case class FriendRecord(
  friendUserArn: String,
  friendName: String,
  createdAt: Long = System.currentTimeMillis()
)

object UserFriendsDAOKeys {
  val SkProfile: String = "PROFILE"
  def skFriend(friendUserArn: String): String = s"FRIEND#$friendUserArn"
}
