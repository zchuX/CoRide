package com.coride.userdao

import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model._
import java.util
import scala.jdk.CollectionConverters._

final case class User(
  userArn: String,
  name: String,
  email: Option[String] = None,
  phone: Option[String] = None,
  friendList: List[String] = Nil,
  incomingInvitations: List[String] = Nil,
  outgoingInvitations: List[String] = Nil,
  description: Option[String] = None,
  photoUrl: Option[String] = None,
  totalPassengerDelivered: Int = 0,
  totalCarpoolJoined: Int = 0,
  createdAt: Long = System.currentTimeMillis(),
  updatedAt: Long = System.currentTimeMillis()
)

final case class UserProfileUpdate(
  name: Option[String] = None,
  friendList: Option[List[String]] = None,
  incomingInvitations: Option[List[String]] = None,
  outgoingInvitations: Option[List[String]] = None,
  description: Option[String] = None,
  photoUrl: Option[String] = None,
  totalPassengerDelivered: Option[Int] = None,
  totalCarpoolJoined: Option[Int] = None
)

import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region

object UserDAO {
  def apply(): UserDAO = {
    val regionName: String = Option(System.getenv("AWS_REGION")).getOrElse("us-east-1")
    val ddb = DynamoDbClient.builder()
      .region(Region.of(regionName))
      .httpClient(UrlConnectionHttpClient.builder().build())
      .build()
    val usersTableName: String = Option(System.getenv("USERS_TABLE_NAME")).getOrElse("")
    val contactIndexTableName: String = Option(System.getenv("USER_CONTACT_INDEX_NAME")).getOrElse("")
    new UserDAO(ddb, usersTableName, contactIndexTableName)
  }
}

class UserDAO(ddb: DynamoDbClient, usersTableName: String, contactIndexTableName: String) {

  def getUser(userArn: String): Option[User] = {
    val req = GetItemRequest.builder()
      .tableName(usersTableName)
      .key(Map("userArn" -> AttributeValue.builder().s(userArn).build()).asJava)
      .build()
    val res = ddb.getItem(req)
    if (res.hasItem) Some(fromItem(res.item())) else None
  }

  def createUser(user: User): Unit = {
    val now = System.currentTimeMillis()
    val emailNorm = normalizeEmail(user.email)
    val phoneNorm = normalizePhone(user.phone)
    val item = toItem(user.copy(
      email = emailNorm,
      phone = phoneNorm,
      createdAt = now,
      updatedAt = now
    ))

    val items = new util.ArrayList[TransactWriteItem]()
    // Put Users item if not exists
    items.add(TransactWriteItem.builder()
      .put(Put.builder()
        .tableName(usersTableName)
        .item(item)
        .conditionExpression("attribute_not_exists(#pk)")
        .expressionAttributeNames(Map("#pk" -> "userArn").asJava)
        .build())
      .build())

    // Put contact index entries
    emailNorm.foreach { e =>
      items.add(TransactWriteItem.builder()
        .put(Put.builder()
          .tableName(contactIndexTableName)
          .item(Map(
            "contactKey" -> AttributeValue.builder().s(s"email:$e").build(),
            "userArn" -> AttributeValue.builder().s(user.userArn).build()
          ).asJava)
          .conditionExpression("attribute_not_exists(contactKey)")
          .build())
        .build())
    }
    phoneNorm.foreach { p =>
      items.add(TransactWriteItem.builder()
        .put(Put.builder()
          .tableName(contactIndexTableName)
          .item(Map(
            "contactKey" -> AttributeValue.builder().s(s"phone:$p").build(),
            "userArn" -> AttributeValue.builder().s(user.userArn).build()
          ).asJava)
          .conditionExpression("attribute_not_exists(contactKey)")
          .build())
        .build())
    }

    val tx = TransactWriteItemsRequest.builder().transactItems(items).build()
    ddb.transactWriteItems(tx)
  }

  def updateUserProfile(userArn: String, updates: UserProfileUpdate): Unit = {
    // Guard: email/phone must not be updated via this method
    // The Scala update structure does not include email/phone by design.
    val sets = scala.collection.mutable.ListBuffer[String]()
    val names = scala.collection.mutable.Map[String, String]()
    val values = scala.collection.mutable.Map[String, AttributeValue](":updatedAt" -> AttributeValue.builder().n(System.currentTimeMillis().toString).build())

    def add(attr: String, value: AttributeValue): Unit = {
      val nameKey = s"#$attr"
      val valueKey = s":$attr"
      names += (nameKey -> attr)
      values += (valueKey -> value)
      sets += s"$nameKey = $valueKey"
    }

    updates.name.foreach { v =>
      add("name", AttributeValue.builder().s(v).build())
      val norm = normalizeName(v)
      if (norm.nonEmpty) add("normalizedName", AttributeValue.builder().s(norm).build())
    }
    updates.friendList.foreach(v => add("friendList", AttributeValue.builder().l(v.map(s => AttributeValue.builder().s(s).build()).asJava).build()))
    updates.incomingInvitations.foreach(v => add("incomingInvitations", AttributeValue.builder().l(v.map(s => AttributeValue.builder().s(s).build()).asJava).build()))
    updates.outgoingInvitations.foreach(v => add("outgoingInvitations", AttributeValue.builder().l(v.map(s => AttributeValue.builder().s(s).build()).asJava).build()))
    updates.description.foreach(v => add("description", AttributeValue.builder().s(v).build()))
    updates.photoUrl.foreach(v => add("photoUrl", AttributeValue.builder().s(v).build()))
    updates.totalPassengerDelivered.foreach(v => add("totalPassengerDelivered", AttributeValue.builder().n(v.toString).build()))
    updates.totalCarpoolJoined.foreach(v => add("totalCarpoolJoined", AttributeValue.builder().n(v.toString).build()))

    sets += "#updatedAt = :updatedAt"

    if (sets.isEmpty) return

    val req = UpdateItemRequest.builder()
      .tableName(usersTableName)
      .key(Map("userArn" -> AttributeValue.builder().s(userArn).build()).asJava)
      .updateExpression(s"SET ${sets.mkString(", ")}")
      .expressionAttributeNames(names.asJava)
      .expressionAttributeValues(values.asJava)
      .build()
    ddb.updateItem(req)
  }

  def updatePhoneNumber(userArn: String, newPhoneRaw: String): Unit = {
    val newPhone = normalizePhone(Some(newPhoneRaw)).getOrElse(throw new IllegalArgumentException("Invalid phone number"))
    val current = getUser(userArn)
    val oldPhone = current.flatMap(_.phone.flatMap(p => normalizePhone(Some(p))))

    val items = new util.ArrayList[TransactWriteItem]()
    // Update Users.phone
    items.add(TransactWriteItem.builder()
      .update(Update.builder()
        .tableName(usersTableName)
        .key(Map("userArn" -> AttributeValue.builder().s(userArn).build()).asJava)
        .updateExpression("SET #phone = :p, #updatedAt = :u")
        .expressionAttributeNames(Map("#phone" -> "phone", "#updatedAt" -> "updatedAt").asJava)
        .expressionAttributeValues(Map(":p" -> AttributeValue.builder().s(newPhone).build(), ":u" -> AttributeValue.builder().n(System.currentTimeMillis().toString).build()).asJava)
        .build())
      .build())

    // Put new contact index mapping, allow overwrite if same userArn holds it
    items.add(TransactWriteItem.builder()
      .put(Put.builder()
        .tableName(contactIndexTableName)
        .item(Map("contactKey" -> AttributeValue.builder().s(s"phone:$newPhone").build(), "userArn" -> AttributeValue.builder().s(userArn).build()).asJava)
        .conditionExpression("attribute_not_exists(contactKey) OR userArn = :ua")
        .expressionAttributeValues(Map(":ua" -> AttributeValue.builder().s(userArn).build()).asJava)
        .build())
      .build())

    // Delete old contact index mapping if exists and differs
    oldPhone.filter(_ != newPhone).foreach { op =>
      items.add(TransactWriteItem.builder()
        .delete(Delete.builder()
          .tableName(contactIndexTableName)
          .key(Map("contactKey" -> AttributeValue.builder().s(s"phone:$op").build()).asJava)
          .conditionExpression("attribute_exists(contactKey)")
          .build())
        .build())
    }

    val tx = TransactWriteItemsRequest.builder().transactItems(items).build()
    ddb.transactWriteItems(tx)
  }

  def updateEmailAddress(userArn: String, newEmailRaw: String): Unit = {
    val newEmail = normalizeEmail(Some(newEmailRaw)).getOrElse(throw new IllegalArgumentException("Invalid email address"))
    val current = getUser(userArn)
    val oldEmail = current.flatMap(_.email.flatMap(e => normalizeEmail(Some(e))))

    val items = new util.ArrayList[TransactWriteItem]()
    // Update Users.email
    items.add(TransactWriteItem.builder()
      .update(Update.builder()
        .tableName(usersTableName)
        .key(Map("userArn" -> AttributeValue.builder().s(userArn).build()).asJava)
        .updateExpression("SET #email = :e, #updatedAt = :u")
        .expressionAttributeNames(Map("#email" -> "email", "#updatedAt" -> "updatedAt").asJava)
        .expressionAttributeValues(Map(":e" -> AttributeValue.builder().s(newEmail).build(), ":u" -> AttributeValue.builder().n(System.currentTimeMillis().toString).build()).asJava)
        .build())
      .build())

    // Put new contact index mapping, allow overwrite if same userArn holds it
    items.add(TransactWriteItem.builder()
      .put(Put.builder()
        .tableName(contactIndexTableName)
        .item(Map("contactKey" -> AttributeValue.builder().s(s"email:$newEmail").build(), "userArn" -> AttributeValue.builder().s(userArn).build()).asJava)
        .conditionExpression("attribute_not_exists(contactKey) OR userArn = :ua")
        .expressionAttributeValues(Map(":ua" -> AttributeValue.builder().s(userArn).build()).asJava)
        .build())
      .build())

    // Delete old contact index mapping if exists and differs
    oldEmail.filter(_ != newEmail).foreach { oe =>
      items.add(TransactWriteItem.builder()
        .delete(Delete.builder()
          .tableName(contactIndexTableName)
          .key(Map("contactKey" -> AttributeValue.builder().s(s"email:$oe").build()).asJava)
          .conditionExpression("attribute_exists(contactKey)")
          .build())
        .build())
    }

    val tx = TransactWriteItemsRequest.builder().transactItems(items).build()
    ddb.transactWriteItems(tx)
  }

  // ---------- Reverse Lookups ----------
  def getUserByEmail(emailRaw: String): Option[User] = {
    val email = normalizeEmail(Some(emailRaw)).getOrElse(return None)
    val key = Map("contactKey" -> AttributeValue.builder().s(s"email:$email").build()).asJava
    val idxReq = GetItemRequest.builder().tableName(contactIndexTableName).key(key).build()
    val idxRes = ddb.getItem(idxReq)
    if (!idxRes.hasItem) return None
    val userArnOpt = Option(idxRes.item().get("userArn")).flatMap(av => Option(av.s())).filter(_.nonEmpty)
    userArnOpt.flatMap(getUser)
  }

  def getUserByPhone(phoneRaw: String): Option[User] = {
    val phone = normalizePhone(Some(phoneRaw)).getOrElse(return None)
    val key = Map("contactKey" -> AttributeValue.builder().s(s"phone:$phone").build()).asJava
    val idxReq = GetItemRequest.builder().tableName(contactIndexTableName).key(key).build()
    val idxRes = ddb.getItem(idxReq)
    if (!idxRes.hasItem) return None
    val userArnOpt = Option(idxRes.item().get("userArn")).flatMap(av => Option(av.s())).filter(_.nonEmpty)
    userArnOpt.flatMap(getUser)
  }

  /** List users by normalized name (no spaces, lowercase). Uses GSI gsiNormalizedName. */
  def listUsersByNormalizedName(normalizedName: String, limit: Int = 50): List[User] = {
    if (normalizedName.isEmpty) return Nil
    val req = QueryRequest.builder()
      .tableName(usersTableName)
      .indexName("gsiNormalizedName")
      .keyConditionExpression("#nk = :nv")
      .expressionAttributeNames(Map("#nk" -> "normalizedName").asJava)
      .expressionAttributeValues(Map(":nv" -> AttributeValue.builder().s(normalizedName).build()).asJava)
      .limit(limit)
      .build()
    val res = ddb.query(req)
    Option(res.items()).map(_.asScala.toList).getOrElse(Nil).map(it => fromItem(it))
  }

  private def toItem(u: User): java.util.Map[String, AttributeValue] = {
    val m = scala.collection.mutable.Map[String, AttributeValue](
      "userArn" -> AttributeValue.builder().s(u.userArn).build(),
      "name" -> AttributeValue.builder().s(u.name).build(),
      "totalPassengerDelivered" -> AttributeValue.builder().n(u.totalPassengerDelivered.toString).build(),
      "totalCarpoolJoined" -> AttributeValue.builder().n(u.totalCarpoolJoined.toString).build(),
      "createdAt" -> AttributeValue.builder().n(u.createdAt.toString).build(),
      "updatedAt" -> AttributeValue.builder().n(u.updatedAt.toString).build()
    )
    val normName = normalizeName(u.name)
    if (normName.nonEmpty) m += ("normalizedName" -> AttributeValue.builder().s(normName).build())
    u.email.foreach(e => m += ("email" -> AttributeValue.builder().s(e).build()))
    u.phone.foreach(p => m += ("phone" -> AttributeValue.builder().s(p).build()))
    u.description.foreach(d => m += ("description" -> AttributeValue.builder().s(d).build()))
    u.photoUrl.foreach(url => m += ("photoUrl" -> AttributeValue.builder().s(url).build()))
    if (u.friendList.nonEmpty) m += ("friendList" -> AttributeValue.builder().l(u.friendList.map(s => AttributeValue.builder().s(s).build()).asJava).build())
    if (u.incomingInvitations.nonEmpty) m += ("incomingInvitations" -> AttributeValue.builder().l(u.incomingInvitations.map(s => AttributeValue.builder().s(s).build()).asJava).build())
    if (u.outgoingInvitations.nonEmpty) m += ("outgoingInvitations" -> AttributeValue.builder().l(u.outgoingInvitations.map(s => AttributeValue.builder().s(s).build()).asJava).build())
    m.asJava
  }

  private def fromItem(m: java.util.Map[String, AttributeValue]): User = {
    def getS(name: String): Option[String] = Option(m.get(name)).flatMap(av => Option(av.s())).filter(_.nonEmpty)
    def getNInt(name: String, default: Int): Int = Option(m.get(name)).flatMap(av => Option(av.n())).map(_.toInt).getOrElse(default)
    def getNLong(name: String, default: Long): Long = Option(m.get(name)).flatMap(av => Option(av.n())).map(_.toLong).getOrElse(default)
    def getList(name: String): List[String] = Option(m.get(name)).filter(_.hasL).map(_.l().asScala.toList.flatMap(av => Option(av.s()))).getOrElse(Nil)

    User(
      userArn = getS("userArn").getOrElse(throw new IllegalStateException("Missing userArn")),
      name = getS("name").getOrElse(""),
      email = getS("email"),
      phone = getS("phone"),
      friendList = getList("friendList"),
      incomingInvitations = getList("incomingInvitations"),
      outgoingInvitations = getList("outgoingInvitations"),
      description = getS("description"),
      photoUrl = getS("photoUrl"),
      totalPassengerDelivered = getNInt("totalPassengerDelivered", 0),
      totalCarpoolJoined = getNInt("totalCarpoolJoined", 0),
      createdAt = getNLong("createdAt", System.currentTimeMillis()),
      updatedAt = getNLong("updatedAt", System.currentTimeMillis())
    )
  }

  private def normalizeEmail(email: Option[String]): Option[String] = email.map(_.trim.toLowerCase).filter(_.nonEmpty)
  private def normalizePhone(phone: Option[String]): Option[String] = phone.map(_.trim.replaceAll("\\s+", "")).filter(_.nonEmpty)
  /** No spaces, lowercase; used for name search GSI. */
  def normalizeName(name: String): String = name.trim.replaceAll("\\s+", "").toLowerCase
}