name := "corrideLambdaHandler"

version := "0.1.0"

scalaVersion := "2.13.12"

libraryDependencies ++= Seq(
  "com.amazonaws" % "aws-lambda-java-core" % "1.2.2",
  "com.amazonaws" % "aws-lambda-java-events" % "3.11.0",
  "com.amazonaws" % "aws-java-sdk-cognitoidp" % "1.12.681",
  "software.amazon.awssdk" % "dynamodb" % "2.25.43",
  "software.amazon.awssdk" % "regions" % "2.25.43",
  "software.amazon.awssdk" % "url-connection-client" % "2.25.43",
  "com.fasterxml.jackson.core" % "jackson-databind" % "2.16.1",
  "com.coride" %% "tripDAO" % "0.1.0",
  "com.coride" %% "userDAO" % "0.1.0",
  "com.coride" %% "userFriendsDAO" % "0.1.0",
  "com.coride" %% "rateLimitDAO" % "0.1.0",
  "org.scalatest" %% "scalatest" % "3.2.18" % Test,
  "org.mockito" % "mockito-core" % "5.11.0" % Test,
  "org.scalatestplus" %% "mockito-4-11" % "3.2.18.0" % Test
)

ThisBuild / assemblyMergeStrategy := {
  case PathList("META-INF", xs @ _*) => MergeStrategy.discard
  case _ => MergeStrategy.first
}

assembly / assemblyJarName := "corrideLambdaHandler-assembly.jar"
