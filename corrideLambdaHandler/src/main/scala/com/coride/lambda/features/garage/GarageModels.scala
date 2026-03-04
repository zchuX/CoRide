package com.coride.lambda.features.garage

/** A car in the user's garage. All fields except carArn and userArn are optional. */
final case class GarageCar(
  carArn: String,
  userArn: String,
  make: Option[String] = None,
  model: Option[String] = None,
  color: Option[String] = None,
  carPlate: Option[String] = None,
  stateRegistered: Option[String] = None
)
