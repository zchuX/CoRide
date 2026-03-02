package com.coride.lambda.router

import org.scalatest.funsuite.AnyFunSuite
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import com.amazonaws.services.lambda.runtime.Context
import com.coride.lambda.util.JwtUtils
import com.coride.lambda.dao.UserGroupsDAO
import com.coride.tripdao.TripDAO
import com.coride.userdao.UserDAO
import com.coride.userfriendsdao.UserFriendsDAO
import org.mockito.Mockito.mock
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

class ApiRouterSpec extends AnyFunSuite {

  private class DummyContext extends Context {
    override def getAwsRequestId: String = "test-req"
    override def getLogGroupName: String = ""
    override def getLogStreamName: String = ""
    override def getFunctionName: String = ""
    override def getFunctionVersion: String = ""
    override def getInvokedFunctionArn: String = ""
    override def getIdentity: com.amazonaws.services.lambda.runtime.CognitoIdentity = null
    override def getClientContext: com.amazonaws.services.lambda.runtime.ClientContext = null
    override def getRemainingTimeInMillis: Int = 1000
    override def getMemoryLimitInMB: Int = 128
    override def getLogger: com.amazonaws.services.lambda.runtime.LambdaLogger = new com.amazonaws.services.lambda.runtime.LambdaLogger {
      def log(s: String): Unit = ()
      def log(bytes: Array[Byte]): Unit = ()
    }
  }

  test("unknown route returns 404 Not Found") {
    val ddb = mock(classOf[DynamoDbClient])
    val tripDao = mock(classOf[TripDAO])
    val userDao = mock(classOf[UserDAO])
    val userGroupsDAO = mock(classOf[UserGroupsDAO])
    val userFriendsDAO = mock(classOf[UserFriendsDAO])
    val jwt = mock(classOf[JwtUtils])
    val router = new ApiRouter(ddb, tripDao, userDao, userGroupsDAO, userFriendsDAO, jwt)
    val event = new APIGatewayProxyRequestEvent()
    event.setHttpMethod("GET")
    event.setPath("/nope")
    event.setResource("/nope")
    val resp = router.handleRequest(event, new DummyContext)
    assert(resp.getStatusCode == 404)
    assert(resp.getBody != null && resp.getBody.contains("Not Found"))
  }

  test("login-otp send returns 410 Gone") {
    val router = new ApiRouter()
    val event = new APIGatewayProxyRequestEvent()
    event.setHttpMethod("POST")
    event.setPath("/auth/login-otp/send")
    event.setResource("/auth/login-otp/send")
    val resp = router.handleRequest(event, new DummyContext)
    assert(resp.getStatusCode == 410)
    assert(resp.getBody != null && resp.getBody.contains("Gone"))
  }

  test("login-otp verify returns 410 Gone") {
    val router = new ApiRouter()
    val event = new APIGatewayProxyRequestEvent()
    event.setHttpMethod("POST")
    event.setPath("/auth/login-otp/verify")
    event.setResource("/auth/login-otp/verify")
    val resp = router.handleRequest(event, new DummyContext)
    assert(resp.getStatusCode == 410)
    assert(resp.getBody != null && resp.getBody.contains("Gone"))
  }
}