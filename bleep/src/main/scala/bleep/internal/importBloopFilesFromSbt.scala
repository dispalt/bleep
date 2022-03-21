package bleep
package internal

import bleep.logging.Logger
import bloop.config.Config
import coursier.core.compatibility.xmlParseSax
import coursier.core.{Classifier, Configuration, Dependency, ModuleName, Organization, Project, Publication}
import coursier.error.ResolutionError
import coursier.ivy.IvyRepository
import coursier.maven.PomParser
import coursier.{MavenRepository, Module, Resolve}

import java.net.URI
import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable
import scala.jdk.CollectionConverters._

object importBloopFilesFromSbt {
  // I'm sure there is a useful difference, but it completely escapes me.
  val DefaultConfigs: Set[Configuration] =
    Set(Configuration.empty, Configuration.compile, Configuration.default, Configuration.defaultCompile)

  // These correspond to the suffixes generated by the sbt-cross plugin
  val Suffixes = Set("JS", "JVM", "Native", "3", "2_13", "2_12", "2_11")

  // we use this to remove last directory name in cross projects
  val crossProjectDirNames: Set[String] =
    Set("jvm", "js", "native").flatMap(str => List(str, s".$str"))

  def projectName(name: String): model.ProjectName = {
    var ret = name
    var isTest = false
    if (ret.endsWith("-test")) {
      ret = ret.dropRight("-test".length)
      isTest = true
    }

    var continue = true
    while (continue) {
      continue = false
      Suffixes.foreach { s =>
        if (ret.endsWith(s)) {
          continue = true
          ret = ret.dropRight(s.length)
        }
      }
    }

    model.ProjectName(if (isTest) s"$ret-test" else ret)
  }

  def cachedFn[In, Out](f: In => Out): (In => Out) = {
    val cache = mutable.Map.empty[In, Out]
    in => cache.getOrElseUpdate(in, f(in))
  }

  private case class Sources(
      sourceLayout: SourceLayout,
      sources: JsonSet[RelPath],
      generatedSources: JsonSet[RelPath],
      resources: JsonSet[RelPath],
      generatedResources: JsonSet[RelPath]
  )

  def apply(logger: Logger, sbtBuildDir: Path, destinationPaths: BuildPaths, bloopFiles: Iterable[Config.File]): ExplodedBuild = {

    val hasSources: Path => Boolean = cachedFn { path =>
      def isSource(path: Path): Boolean =
        path.toString match {
          case p if p.endsWith(".scala") => true
          case p if p.endsWith(".java")  => true
          case _                         => false
        }

      Files.exists(path) && Files.walk(path).filter(isSource).findFirst().isPresent
    }

    val pomReader: Path => Either[String, Project] =
      cachedFn { pomPath =>
        if (pomPath.toFile.exists()) {
          xmlParseSax(Files.readString(pomPath), new PomParser).project
        } else Left(s"$pomPath doesn't exist")
      }

    val parsedDependency: ((ScalaVersions, Config.Module)) => ParsedDependency =
      cachedFn { case (scalaVersions, mod) =>
        ParsedDependency.of(logger, pomReader, scalaVersions, mod)
      }

    val crossBloopProjectFiles: Map[model.CrossProjectName, Config.File] =
      bloopFiles
        .filter(bloopFile => bloopFile.project.sources.exists(hasSources.apply))
        .groupBy(file => projectName(file.project.name))
        .flatMap {
          case (name, Seq(file)) =>
            List((model.CrossProjectName(name, None), file))
          case (name, files) =>
            files.map { file =>
              val maybeCrossId = model.CrossId.defaultFrom(
                maybeScalaVersion = file.project.scala.map(s => Versions.Scala(s.version)),
                maybePlatformId = file.project.platform.flatMap(p => model.PlatformId.fromName(p.name))
              )
              (model.CrossProjectName(name, maybeCrossId), file)
            }
        }

    val projectNames = crossBloopProjectFiles.keys.map(_.name).toSet

    val projects = crossBloopProjectFiles.map { case (crossName, bloopFile) =>
      val bloopProject = bloopFile.project

      val directory =
        if (bloopProject.directory.startsWith(sbtBuildDir / ".sbt/matrix")) {
          def inferDirectory(sources: List[Path]) = {
            val src = Paths.get("src")
            def aboveSrc(p: Path): Option[Path] =
              if (p == null) None
              else if (p.getFileName == src) Some(p.getParent)
              else aboveSrc(p.getParent)

            sources.flatMap(aboveSrc).groupBy(identity).maxBy(_._2.length)._1
          }

          inferDirectory(bloopProject.sources)

        } else if (crossProjectDirNames(bloopProject.directory.getFileName.toString))
          bloopProject.directory.getParent
        else bloopProject.directory

      val folder: Option[RelPath] =
        RelPath.relativeTo(destinationPaths.buildDir, directory) match {
          case RelPath(List(crossName.name.value)) => None
          case relPath                             => Some(relPath)
        }

      val dependsOn: JsonSet[model.ProjectName] =
        JsonSet.fromIterable(bloopProject.dependencies.map(projectName).filter(projectNames))

      val scalaVersion: Option[Versions.Scala] =
        bloopProject.scala.map(s => Versions.Scala(s.version))

      val originalTarget = internal.findOriginalTargetDir(logger, bloopProject)

      val replacementsDirs = originalTarget.foldLeft(Replacements.paths(sbtBuildDir, directory))((acc, path) => acc ++ Replacements.targetDir(path))
      val replacementsVersions = Replacements.versions(scalaVersion, bloopProject.platform.map(_.name))
      val replacements = replacementsDirs ++ replacementsVersions

      val isTest = crossName.name.value.endsWith("-test")
      val scope = if (isTest) "test" else "main"

      val configuredPlatform: Option[model.Platform] =
        bloopProject.platform.map(translatePlatform(_, replacements))

      val (sourceLayout, sources, resources) = {
        val sourcesRelPaths: JsonSet[RelPath] =
          JsonSet.fromIterable(bloopProject.sources.map(absoluteDir => RelPath.relativeTo(directory, absoluteDir)))

        val resourcesRelPaths: JsonSet[RelPath] =
          JsonSet.fromIterable(bloopProject.resources.getOrElse(Nil).map(absoluteDir => RelPath.relativeTo(directory, absoluteDir)))

        val maybePlatformId = configuredPlatform.flatMap(_.name)

        val inferredSourceLayout: SourceLayout =
          SourceLayout.All.values.maxBy { layout =>
            val fromLayout = layout.sources(scalaVersion, maybePlatformId, Some(scope))
            val fromProject = sourcesRelPaths
            val matching = fromLayout.intersect(fromProject).size
            val notMatching = fromLayout.removeAll(fromProject).size
            (matching, -notMatching)
          }

        val shortenedSources =
          sourcesRelPaths
            .filterNot(inferredSourceLayout.sources(scalaVersion, maybePlatformId, Some(scope)))
            .map(replacementsVersions.templatize.relPath)

        val shortenedResources =
          resourcesRelPaths
            .filterNot(inferredSourceLayout.resources(scalaVersion, maybePlatformId, Some(scope)))
            .map(replacementsVersions.templatize.relPath)

        (inferredSourceLayout, shortenedSources, shortenedResources)
      }

      val resolution = bloopProject.resolution
        .getOrElse(sys.error(s"Expected bloop file for ${crossName.value} to have resolution"))

      val versions: ScalaVersions =
        ScalaVersions.fromExplodedScalaAndPlatform(scalaVersion, configuredPlatform) match {
          case Left(err)    => throw new BuildException.Text(crossName, err)
          case Right(value) => value
        }

      val dependencies: List[Dep] = {
        val parsed: List[(Config.Module, ParsedDependency)] =
          resolution.modules.map(bloopMod => (bloopMod, parsedDependency((versions, bloopMod))))

        val allDepsWithVersions: Map[Module, List[String]] =
          parsed
            .flatMap { case (_, ParsedDependency(_, deps)) =>
              deps.collect { case (conf, d) if DefaultConfigs(conf) => d.moduleVersion }
            }
            .groupMap { case (m, _) => m } { case (_, v) => v }

        // the sbt bloop import drops for instance dependencies on other projects test artifacts.
        // make a token effort to recover them here
        val lostInTranslation: Map[Path, List[Path]] = {
          val allPathsFromResolution = resolution.modules.flatMap(_.artifacts).map(_.path).toSet
          bloopProject.classpath
            .filter(FileUtils.isJarFileName)
            .filterNot(allPathsFromResolution)
            .groupBy(_.getParent)
        }

        parsed.flatMap { case (bloopMod, ParsedDependency(bleepDep, _)) =>
          bleepDep.dependency(versions) match {
            case Left(err)          => throw new BuildException.Text(crossName, err)
            case Right(coursierDep) =>
              // only keep those not referenced by another dependency
              val keepMain: Boolean =
                allDepsWithVersions.get(coursierDep.module) match {
                  case Some(inheritedVersions) =>
                    // todo: would be better to keep if dep.version > inheritedVersions, but would need to parse semver for that. this is good enough
                    !inheritedVersions.contains(coursierDep.version)
                  case None => true
                }

              val main: List[Dep] =
                if (keepMain) List(bleepDep) else Nil

              val extraClassifiers: List[Dep] =
                bloopMod.artifacts.headOption match {
                  case Some(a) =>
                    lostInTranslation.getOrElse(a.path.getParent, Nil).flatMap { lostJar =>
                      // ~/.cache/coursier/v1/https/repo1.maven.org/maven2/com/twitter/finatra-http_2.13/21.2.0/finatra-http_2.13-21.2.0-tests.jar
                      lostJar.toString.lastIndexOf(bloopMod.version) match {
                        case -1 => None
                        case n =>
                          val classifier = Classifier(lostJar.toString.drop(n + bloopMod.version.length + 1).dropRight(".jar".length))
                          val publication = Publication(bleepDep.publication.name, bleepDep.publication.`type`, bleepDep.publication.ext, classifier)
                          bleepDep match {
                            case x: Dep.JavaDependency  => Some(x.copy(publication = publication))
                            case x: Dep.ScalaDependency => Some(x.copy(publication = publication))
                          }
                      }
                    }

                  case None => Nil
                }

              main ++ extraClassifiers
          }
        }
      }

      val configuredJava: Option[model.Java] =
        bloopProject.java.map(translateJava(replacements))

      val configuredScala: Option[model.Scala] =
        bloopProject.scala.map { bloopScala =>
          versions match {
            case ScalaVersions.Java =>
              throw new BuildException.Text(crossName, "Need a scala version to import scala project")
            case withScala: ScalaVersions.WithScala =>
              translateScala(logger, withScala, pomReader, replacementsDirs, replacementsVersions, configuredPlatform)(bloopScala)
          }
        }

      val testFrameworks: JsonSet[model.TestFrameworkName] =
        if (isTest) JsonSet.fromIterable(bloopProject.test.toList.flatMap(_.frameworks).flatMap(_.names).map(model.TestFrameworkName.apply))
        else JsonSet.empty

      crossName -> model.Project(
        `extends` = JsonList.empty,
        cross = JsonMap.empty,
        folder = folder,
        dependsOn = dependsOn,
        sources = sources,
        resources = resources,
        dependencies = JsonSet.fromIterable(dependencies),
        java = configuredJava,
        scala = configuredScala,
        platform = configuredPlatform,
        `source-layout` = Some(sourceLayout),
        `sbt-scope` = Some(scope),
        testFrameworks = testFrameworks
      )
    }

    val buildResolvers: JsonList[model.Repository] =
      JsonList(
        crossBloopProjectFiles.toArray
          .flatMap { case (_, bloopFile) => bloopFile.project.resolution }
          .flatMap(_.modules)
          .distinct
          .flatMap(resolverUsedFor)
          .filterNot(constants.DefaultRepos.contains)
          .distinct
          .toList
      )

    ExplodedBuild(Map.empty, Map.empty, resolvers = buildResolvers, projects, Map.empty)
  }

  def resolverUsedFor(mod: Config.Module): Option[model.Repository] = {
    val https = Path.of("https")
    val jars = Path.of("jars")
    val allAfterHttps = mod.artifacts.head.path
      .iterator()
      .asScala
      .toList
      .dropWhile(_ != https)
      .drop(1)

    if (allAfterHttps.isEmpty) None
    // ivy pattern
    else if (allAfterHttps.contains(jars)) {
      val fullOrg = Path.of(mod.organization)
      val uri = URI.create(allAfterHttps.takeWhile(_ != fullOrg).map(_.toString).mkString("https://", "/", "/"))
      Some(model.Repository.Ivy(uri))
    } else {
      val initialOrg = Path.of(mod.organization.split("\\.").head)
      val uri = URI.create(allAfterHttps.takeWhile(_ != initialOrg).map(_.toString).mkString("https://", "/", ""))
      Some(model.Repository.Maven(uri))
    }
  }

  case class ParsedDependency(dep: Dep, directDeps: Seq[(Configuration, Dependency)])

  object ParsedDependency {
    case class Variant(needsScala: Boolean, fullCrossVersion: Boolean, forceJvm: Boolean, for3Use213: Boolean, for213Use3: Boolean)

    val ScalaVariants = List(
      Variant(needsScala = true, fullCrossVersion = false, forceJvm = false, for3Use213 = false, for213Use3 = false),
      Variant(needsScala = true, fullCrossVersion = false, forceJvm = false, for3Use213 = false, for213Use3 = true),
      Variant(needsScala = true, fullCrossVersion = false, forceJvm = false, for3Use213 = true, for213Use3 = false),
      Variant(needsScala = true, fullCrossVersion = false, forceJvm = true, for3Use213 = false, for213Use3 = false),
      Variant(needsScala = true, fullCrossVersion = false, forceJvm = true, for3Use213 = false, for213Use3 = true),
      Variant(needsScala = true, fullCrossVersion = false, forceJvm = true, for3Use213 = true, for213Use3 = false),
      Variant(needsScala = true, fullCrossVersion = true, forceJvm = false, for3Use213 = false, for213Use3 = false),
      Variant(needsScala = true, fullCrossVersion = true, forceJvm = true, for3Use213 = false, for213Use3 = false)
    )

    def of(logger: Logger, pomReader: Path => Either[String, Project], versions: ScalaVersions, mod: Config.Module): ParsedDependency = {
      val variantBySuffix: List[(String, Variant)] =
        ScalaVariants
          .flatMap { case v @ Variant(needsScala, needsFullCrossVersion, forceJvm, for3Use213, for213use3) =>
            versions.fullSuffix(needsScala, needsFullCrossVersion, forceJvm, for3Use213, for213use3).map(s => (s, v))
          }
          .sortBy(-_._1.length) // longest suffix first

      val chosen = variantBySuffix.collectFirst { case (suffix, variant) if mod.name.endsWith(suffix) => (mod.name.dropRight(suffix.length), variant) }

      val isSbtPlugin: Boolean = checkIsSbtPlugin(mod)

      val bleepDep: Dep = chosen match {
        case None =>
          Dep.JavaDependency(
            organization = Organization(mod.organization),
            moduleName = ModuleName(mod.name),
            version = mod.version,
            isSbtPlugin = isSbtPlugin
          )
        case Some((modName, scalaVariant)) =>
          Dep.ScalaDependency(
            organization = Organization(mod.organization),
            baseModuleName = ModuleName(modName),
            version = mod.version,
            fullCrossVersion = scalaVariant.fullCrossVersion,
            forceJvm = scalaVariant.forceJvm,
            for3Use213 = scalaVariant.for3Use213,
            for213Use3 = scalaVariant.for213Use3
          )
      }

      val dependencies: Seq[(Configuration, Dependency)] =
        mod.artifacts
          .collectFirst {
            case a if a.classifier.isEmpty =>
              val pomPath = findPomPath(a.path)
              val maybeReadProject: Option[Project] =
                pomReader(pomPath) match {
                  case Right(project)
                      // this means the version is set in a parent POM. there may also be templating involved. Let's defer to coursier for that.
                      if !project.dependencies.exists { case (_, dep) => dep.version == "" } =>
                    Some(project)
                  case _ => None
                }

              maybeReadProject match {
                case Some(project) => project.dependencies
                case None          =>
                  // slow path
                  val dep = Dependency(
                    Module(Organization(mod.organization), ModuleName(mod.name))
                      .withAttributes(if (isSbtPlugin) Dep.SbtPluginAttrs else Map.empty),
                    mod.version
                  )

                  // todo: authentication
                  val repo = resolverUsedFor(mod).toList.map {
                    case model.Repository.Maven(uri) => MavenRepository(uri.toString)
                    case model.Repository.Ivy(uri)   => IvyRepository.fromPattern(uri.toString +: coursier.ivy.Pattern.default)
                  }

                  try {
                    val resolved = Resolve().addRepositories(repo: _*).addDependencies(dep).run()
                    resolved.dependencies.toList.collect {
                      case d if d.moduleVersion != dep.moduleVersion => (d.configuration, d)
                    }
                  } catch {
                    case x: ResolutionError =>
                      logger.warn(s"Couldn't resolve $mod in order to minimize dependencies", x)
                      Nil
                  }
              }
          }
          .getOrElse(Nil)

      ParsedDependency(bleepDep, dependencies)
    }
  }

  def checkIsSbtPlugin(mod: Config.Module): Boolean = {
    def isSbtPluginPath(path: Path) =
      // sbt plugin published via maven
      if (path.toString.contains("_2.12_1.0")) true
      // and via ivy
      else if (path.iterator().asScala.contains(Path.of("sbt_1.0"))) true
      else false

    mod.artifacts.exists(a => isSbtPluginPath(a.path))
  }

  private def findPomPath(jar: Path) = {
    val isIvy = jar.getParent.getFileName.toString == "jars"

    if (isIvy)
      jar.getParent.getParent / "poms" / jar.getFileName.toString.replace(".jar", ".pom")
    else
      jar.getParent / jar.getFileName.toString.replace(".jar", ".pom")
  }

  def translateJava(templateDirs: Replacements)(java: Config.Java): model.Java =
    model.Java(options = Options.parse(java.options, Some(templateDirs)))

  def translatePlatform(platform: Config.Platform, templateDirs: Replacements): model.Platform =
    platform match {
      case Config.Platform.Js(config, mainClass) =>
        val translatedPlatform = model.Platform.Js(
          jsVersion = Some(config.version).filterNot(_.isEmpty).map(Versions.ScalaJs),
          jsMode = Some(config.mode),
          jsKind = Some(config.kind),
          jsEmitSourceMaps = Some(config.emitSourceMaps),
          jsJsdom = config.jsdom,
//          output = config.output.map(output => RelPath.relativeTo(directory, output)),
          jsMainClass = mainClass
        )
        translatedPlatform
      case Config.Platform.Jvm(config, mainClass, runtimeConfig, classpath @ _, resources @ _) =>
        val translatedPlatform = model.Platform.Jvm(
          jvmOptions = Options.parse(config.options, Some(templateDirs)),
          mainClass,
          jvmRuntimeOptions = runtimeConfig.map(rc => Options.parse(rc.options, Some(templateDirs))).getOrElse(Options.empty)
        )
        translatedPlatform
      case Config.Platform.Native(config, mainClass) =>
        val translatedPlatform = model.Platform.Native(
          nativeVersion = Some(Versions.ScalaNative(config.version)),
          nativeMode = Some(config.mode),
          nativeGc = Some(config.gc),
          nativeMainClass = mainClass
        )
        translatedPlatform
    }

  def translateScala(
      logger: Logger,
      versions: ScalaVersions.WithScala,
      pomReader: Path => Either[String, Project],
      replacementsDirs: Replacements,
      replacementsVersions: Replacements,
      platform: Option[model.Platform]
  )(s: Config.Scala): model.Scala = {
    val options = Options.parse(s.options, Some(replacementsDirs))

    val (plugins, rest) = options.values.partition {
      case Options.Opt.Flag(name) if name.startsWith(constants.ScalaPluginPrefix) => true
      case _                                                                      => false
    }

    val compilerPlugins = plugins.collect { case Options.Opt.Flag(pluginStr) =>
      val jarPath = Paths.get(pluginStr.dropWhile(_ != '/'))
      val pomPath = findPomPath(jarPath)
      val Right(pom) = pomReader(pomPath)

      ParsedDependency.of(logger, pomReader, versions.asJvm, Config.Module(pom.module.organization.value, pom.module.name.value, pom.version, None, Nil)).dep
    }

    val filteredCompilerPlugins =
      platform.flatMap(_.compilerPlugin).foldLeft(compilerPlugins) { case (all, fromPlatform) =>
        all.filterNot(_ == fromPlatform)
      }

    model.Scala(
      version = Some(Versions.Scala(s.version)),
      options = replacementsVersions.templatize.opts(new Options(rest)),
      setup = s.setup.map(setup =>
        model.CompileSetup(
          order = Some(setup.order),
          addLibraryToBootClasspath = Some(setup.addLibraryToBootClasspath),
          addCompilerToClasspath = Some(setup.addCompilerToClasspath),
          addExtraJarsToClasspath = Some(setup.addExtraJarsToClasspath),
          manageBootClasspath = Some(setup.manageBootClasspath),
          filterLibraryFromClasspath = Some(setup.filterLibraryFromClasspath)
        )
      ),
      compilerPlugins = JsonSet.fromIterable(filteredCompilerPlugins)
    )
  }
}
