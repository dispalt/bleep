package bleep

import bleep.internal.{BspClientDisplayProgress, MyBloopRifleLogger}
import ch.epfl.scala.bsp4j

import java.util
import scala.build.bloop.{BloopServer, BloopThreads}
import scala.build.blooprifle.internal.Operations
import scala.jdk.CollectionConverters._

trait BleepCommandRemote extends BleepCommand {
  def started: Started

  def chosenTargets(started: Started, fromCommandLine: Option[List[model.CrossProjectName]]): util.List[bsp4j.BuildTargetIdentifier] =
    buildTargets(started.buildPaths, started.chosenProjects(fromCommandLine))

  def buildTargets(buildPaths: BuildPaths, projects: List[model.CrossProjectName]): util.List[bsp4j.BuildTargetIdentifier] = {
    def targetId(name: model.CrossProjectName): bsp4j.BuildTargetIdentifier =
      new bsp4j.BuildTargetIdentifier(buildPaths.buildDir.toFile.toURI.toASCIIString.stripSuffix("/") + "/?id=" + name.value)

    projects.map(targetId).asJava
  }

  def runWithServer(bloop: BloopServer): Unit

  override final def run(): Unit = {
    val bloopRifleConfig = new BloopSetup(
      JavaCmd.javacommand,
      started,
      Some("local")
    ).bloopRifleConfig

    val buildClient: BspClientDisplayProgress = BspClientDisplayProgress(started.logger)
    val logger = new MyBloopRifleLogger(started.logger, true, true)

    BloopServer.withBuildServer(
      config = bloopRifleConfig,
      clientName = "bleep",
      clientVersion = Defaults.version,
      workspace = started.buildPaths.dotBleepDir,
      classesDir = started.buildPaths.dotBleepDir / "classes",
      buildClient = buildClient,
      threads = BloopThreads.create(),
      logger = logger
    )(runWithServer)

    Operations.exit(bloopRifleConfig.address, started.buildPaths.dotBleepDir, System.in, System.out, System.err, logger)

    buildClient.failed match {
      case empty if empty.isEmpty => ()
      case failed                 => started.logger.error(s"Failed: ${failed.map(buildClient.renderBuildTarget).mkString(", ")}")
    }
  }
}
