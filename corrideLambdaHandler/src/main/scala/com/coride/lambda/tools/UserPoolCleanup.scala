package com.coride.lambda.tools

import com.amazonaws.services.cognitoidp.AWSCognitoIdentityProviderClientBuilder
import com.amazonaws.services.cognitoidp.model.{AdminDeleteUserRequest, ListUsersRequest, ListUsersResult}

/**
 * One-off utility to delete users from a Cognito User Pool.
 *
 * Usage examples:
 *   AWS_REGION=us-east-1 USER_POOL_ID=us-east-1_XXXX java -cp corrideLambdaHandler-assembly.jar com.coride.lambda.tools.UserPoolCleanup --delete-all --force
 *   AWS_REGION=us-east-1 USER_POOL_ID=us-east-1_XXXX java -cp corrideLambdaHandler-assembly.jar com.coride.lambda.tools.UserPoolCleanup --subs=abc-def,ghi-jkl --force
 *   AWS_REGION=us-east-1 USER_POOL_ID=us-east-1_XXXX java -cp corrideLambdaHandler-assembly.jar com.coride.lambda.tools.UserPoolCleanup --usernames="alice,bob" --force
 *
 * Flags:
 *  - --delete-all : delete all users in the pool (paginated)
 *  - --subs=...   : comma-separated Cognito sub IDs to delete
 *  - --usernames= : comma-separated usernames to delete
 *  - --force      : required to actually delete; otherwise dry-run
 */
object UserPoolCleanup {
  def main(args: Array[String]): Unit = {
    val region = Option(System.getenv("AWS_REGION")).getOrElse("us-east-1")
    val poolId = Option(System.getenv("USER_POOL_ID")).getOrElse("")
    if (poolId.isEmpty) {
      System.err.println("ERROR: USER_POOL_ID must be set in environment")
      System.exit(1)
    }

    val force = args.contains("--force")
    val deleteAll = args.contains("--delete-all")
    val subsArg = args.find(_.startsWith("--subs=")).map(_.stripPrefix("--subs=")).filter(_.nonEmpty)
    val usernamesArg = args.find(_.startsWith("--usernames=")).map(_.stripPrefix("--usernames=")).filter(_.nonEmpty)

    val client = AWSCognitoIdentityProviderClientBuilder.standard().withRegion(region).build()
    println(s"UserPoolCleanup starting (region=$region, poolId=$poolId, deleteAll=$deleteAll, force=$force)")

    if (deleteAll) {
      var token: String = null
      var total = 0
      var continue = true
      while (continue) {
        val req = new ListUsersRequest().withUserPoolId(poolId).withLimit(60).withPaginationToken(token)
        val res: ListUsersResult = client.listUsers(req)
        val users = Option(res.getUsers).map(_.toArray.toList).getOrElse(Nil)
        users.foreach { uObj =>
          val u = uObj.asInstanceOf[com.amazonaws.services.cognitoidp.model.UserType]
          val username = Option(u.getUsername).getOrElse("")
          if (!force) {
            println(s"DRY-RUN: would delete username=$username")
          } else {
            client.adminDeleteUser(new AdminDeleteUserRequest().withUserPoolId(poolId).withUsername(username))
            println(s"Deleted username=$username")
          }
          total += 1
        }
        token = res.getPaginationToken
        continue = token != null && !token.isEmpty
      }
      println(s"Completed. Processed $total users.")
    }

    subsArg.foreach { raw =>
      val subs = raw.split(",").map(_.trim).filter(_.nonEmpty)
      subs.foreach { sub =>
        val req = new ListUsersRequest().withUserPoolId(poolId).withFilter(s"sub = \"$sub\"")
        val res = client.listUsers(req)
        val users = Option(res.getUsers).map(_.toArray.toList).getOrElse(Nil)
        if (users.isEmpty) println(s"No user found for sub=$sub")
        users.foreach { uObj =>
          val u = uObj.asInstanceOf[com.amazonaws.services.cognitoidp.model.UserType]
          val username = Option(u.getUsername).getOrElse("")
          if (!force) println(s"DRY-RUN: would delete sub=$sub username=$username")
          else {
            client.adminDeleteUser(new AdminDeleteUserRequest().withUserPoolId(poolId).withUsername(username))
            println(s"Deleted sub=$sub username=$username")
          }
        }
      }
    }

    usernamesArg.foreach { raw =>
      val usernames = raw.split(",").map(_.trim).filter(_.nonEmpty)
      usernames.foreach { username =>
        if (!force) println(s"DRY-RUN: would delete username=$username")
        else {
          client.adminDeleteUser(new AdminDeleteUserRequest().withUserPoolId(poolId).withUsername(username))
          println(s"Deleted username=$username")
        }
      }
    }

    if (!deleteAll && subsArg.isEmpty && usernamesArg.isEmpty) {
      println("No action specified. Use --delete-all or --subs=... or --usernames=... (with --force to apply).")
    }
  }
}