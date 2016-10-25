name := "ThrottlingService"

version := "1.0"

scalaVersion := "2.11.8"


val sprayVersion = "1.3.4"
val testDependencies = Seq(
  "io.spray" % "spray-routing_2.11" % sprayVersion % "test",
  "io.spray" % "spray-client_2.11" % sprayVersion % "test",
  "com.typesafe.akka" % "akka-actor_2.11" % "2.4.10" % "test",
  "org.scalamock" %% "scalamock-scalatest-support" % "3.2.2" % "test"
)

libraryDependencies ++= testDependencies