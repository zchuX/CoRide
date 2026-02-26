name := "rateLimitDAO"

version := "0.1.0"

scalaVersion := "2.13.12"

organization := "com.coride"

publishMavenStyle := true

libraryDependencies ++= Seq(
  "software.amazon.awssdk" % "dynamodb" % "2.25.43",
  "software.amazon.awssdk" % "url-connection-client" % "2.25.43",
  "org.scalatest" %% "scalatest" % "3.2.18" % Test,
  "org.mockito" % "mockito-core" % "5.12.0" % Test
)

ThisBuild / assemblyMergeStrategy := {
  case PathList("META-INF", xs @ _*) => MergeStrategy.discard
  case _ => MergeStrategy.first
}

assembly / assemblyJarName := "rateLimitDAO-assembly.jar"