package com.coride.userdao.tools

import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model._
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import scala.jdk.CollectionConverters._

/**
 * Simple one-off cleanup tool to delete all user profiles from the Users table
 * and corresponding entries from the UserContactIndex.
 *
 * Usage:
 *   java -cp userDAO-assembly.jar com.coride.userdao.tools.UserCleanup --force 
 *
 * Environment variables:
 *   - USERS_TABLE_NAME (default: "Users")
 *   - USER_CONTACT_INDEX_TABLE_NAME (default: "UserContactIndex")
 *   - AWS_REGION (default: "us-east-1")
 */
object UserCleanup {
  def main(args: Array[String]): Unit = {
    val force = args.contains("--force")
    val usersTableName: String = Option(System.getenv("USERS_TABLE_NAME")).getOrElse("Users")
    val contactIndexTableName: String = Option(System.getenv("USER_CONTACT_INDEX_TABLE_NAME")).getOrElse("UserContactIndex")
    val awsRegion: String = Option(System.getenv("AWS_REGION")).getOrElse("us-east-1")

    val client = DynamoDbClient.builder()
      .region(Region.of(awsRegion))
      .httpClient(UrlConnectionHttpClient.builder().build())
      .build()

    println(s"UserCleanup starting (region=$awsRegion, users=$usersTableName, contactIndex=$contactIndexTableName, force=$force)")

    var total = 0
    var deletedUsers = 0
    var deletedContacts = 0

    var lastEvaluatedKey: java.util.Map[String, AttributeValue] = null
    var continue = true
    while (continue) {
      val scanReqBuilder = ScanRequest.builder().tableName(usersTableName).limit(100)
      if (lastEvaluatedKey != null && !lastEvaluatedKey.isEmpty) scanReqBuilder.exclusiveStartKey(lastEvaluatedKey)
      val scanRes = client.scan(scanReqBuilder.build())

      val items = Option(scanRes.items()).map(_.asScala.toList).getOrElse(Nil)
      total += items.size

      items.foreach { item =>
        val userArn = Option(item.get("userArn")).flatMap(av => Option(av.s())).getOrElse("")
        val emailOpt = Option(item.get("email")).flatMap(av => Option(av.s())).filter(_.nonEmpty)
        val phoneOpt = Option(item.get("phone")).flatMap(av => Option(av.s())).filter(_.nonEmpty)

        if (!force) {
          println(s"DRY-RUN: would delete userArn=$userArn, email=${emailOpt.getOrElse("-")}, phone=${phoneOpt.getOrElse("-")}")
        } else {
          val writes = new java.util.ArrayList[TransactWriteItem]()
          // Delete Users item
          val delUser = Delete.builder()
            .tableName(usersTableName)
            .key(Map("userArn" -> AttributeValue.builder().s(userArn).build()).asJava)
            .build()
          writes.add(TransactWriteItem.builder().delete(delUser).build())

          // Delete contact index entries if present
          emailOpt.foreach { e =>
            val delEmail = Delete.builder()
              .tableName(contactIndexTableName)
              .key(Map("contactKey" -> AttributeValue.builder().s(s"email:$e").build()).asJava)
              .build()
            writes.add(TransactWriteItem.builder().delete(delEmail).build())
          }
          phoneOpt.foreach { p =>
            val delPhone = Delete.builder()
              .tableName(contactIndexTableName)
              .key(Map("contactKey" -> AttributeValue.builder().s(s"phone:$p").build()).asJava)
              .build()
            writes.add(TransactWriteItem.builder().delete(delPhone).build())
          }

          // Execute transaction
          client.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(writes).build())
          deletedUsers += 1
          deletedContacts += (emailOpt.size + phoneOpt.size)
          println(s"Deleted userArn=$userArn, contact deletions=${emailOpt.size + phoneOpt.size}")
        }
      }

      lastEvaluatedKey = scanRes.lastEvaluatedKey()
      continue = lastEvaluatedKey != null && !lastEvaluatedKey.isEmpty
    }

    println(s"Scan complete: found $total users")
    if (!force) {
      println("DRY-RUN complete. Re-run with --force to apply deletions.")
    } else {
      println(s"Deletion complete: deletedUsers=$deletedUsers, deletedContactIndexEntries=$deletedContacts")
    }
  }
}