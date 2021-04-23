val bitcoinsV = "0.5.0-166-9811434f-SNAPSHOT"
val akkaV = "2.6.14"

resolvers += Resolver.sonatypeRepo("snapshots")

resolvers +=
  "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"

lazy val root = (project in file("."))
  .enablePlugins(PlayScala, DebianPlugin)
  .settings(
    name := "op-return-bot",
    version := "0.1.0",
    scalaVersion := "2.13.5",
    maintainer := "benthecarman",
    libraryDependencies ++= Seq(
      guice,
      "org.scalatestplus.play" %% "scalatestplus-play" % "5.0.0" % Test,
      "org.bitcoin-s" %% "bitcoin-s-db-commons" % bitcoinsV withSources () withJavadoc (),
      "org.bitcoin-s" %% "bitcoin-s-fee-provider" % bitcoinsV withSources () withJavadoc (),
      "org.bitcoin-s" %% "bitcoin-s-lnd-rpc" % bitcoinsV withSources () withJavadoc (),
      "com.typesafe.akka" %% "akka-stream" % akkaV withSources () withJavadoc (),
      "com.typesafe.akka" %% "akka-actor-typed" % akkaV withSources () withJavadoc (),
      "com.typesafe.akka" %% "akka-serialization-jackson" % akkaV withSources () withJavadoc (),
      "com.typesafe.akka" %% "akka-slf4j" % akkaV withSources () withJavadoc ()
    ),
    scalacOptions ++= Seq(
      "-feature",
      "-deprecation",
      "-Xfatal-warnings"
    )
  )
