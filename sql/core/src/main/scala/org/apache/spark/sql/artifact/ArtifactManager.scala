/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.artifact

import java.io.{File, IOException}
import java.lang.ref.Cleaner
import java.net.{URI, URL, URLClassLoader}
import java.nio.ByteBuffer
import java.nio.file.{CopyOption, Files, Path, Paths, StandardCopyOption}
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

import scala.jdk.CollectionConverters._
import scala.reflect.ClassTag

import org.apache.commons.io.{FilenameUtils, FileUtils}
import org.apache.hadoop.fs.{LocalFileSystem, Path => FSPath}

import org.apache.spark.{JobArtifactSet, JobArtifactState, SparkContext, SparkEnv, SparkException, SparkRuntimeException, SparkUnsupportedOperationException}
import org.apache.spark.internal.{Logging, LogKeys, MDC}
import org.apache.spark.internal.config.{CONNECT_SCALA_UDF_STUB_PREFIXES, EXECUTOR_USER_CLASS_PATH_FIRST}
import org.apache.spark.sql.Artifact
import org.apache.spark.sql.classic.SparkSession
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.util.ArtifactUtils
import org.apache.spark.storage.{BlockManager, CacheId, StorageLevel}
import org.apache.spark.util.{ChildFirstURLClassLoader, StubClassLoader, Utils}

/**
 * This class handles the storage of artifacts as well as preparing the artifacts for use.
 *
 * Artifacts belonging to different SparkSessions are isolated from each other with the help of the
 * `sessionUUID`.
 *
 * Jars and classfile artifacts are stored under "jars", "classes" and "pyfiles" sub-directories
 * respectively while other types of artifacts are stored under the root directory for that
 * particular SparkSession.
 *
 * @param session The object used to hold the Spark Connect session state.
 */
class ArtifactManager(session: SparkSession) extends AutoCloseable with Logging {
  import ArtifactManager._

  // The base directory where all artifacts are stored.
  protected def artifactRootPath: Path = artifactRootDirectory

  private[artifact] lazy val artifactRootURI: String = SparkEnv
    .get
    .rpcEnv
    .fileServer
    .addDirectoryIfAbsent(ARTIFACT_DIRECTORY_PREFIX, artifactRootPath.toFile)

  // The base directory/URI where all artifacts are stored for this `sessionUUID`.
  protected[artifact] val (artifactPath, artifactURI): (Path, String) =
    (ArtifactUtils.concatenatePaths(artifactRootPath, session.sessionUUID),
      s"$artifactRootURI/${session.sessionUUID}")

  // The base directory/URI where all class file artifacts are stored for this `sessionUUID`.
  protected[artifact] val (classDir, replClassURI): (Path, String) =
    (ArtifactUtils.concatenatePaths(artifactPath, "classes"), s"$artifactURI/classes/")

  private lazy val alwaysApplyClassLoader =
    session.conf.get(SQLConf.ARTIFACTS_SESSION_ISOLATION_ALWAYS_APPLY_CLASSLOADER.key).toBoolean

  private lazy val sessionIsolated =
    session.conf.get(SQLConf.ARTIFACTS_SESSION_ISOLATION_ENABLED.key).toBoolean

  protected[sql] lazy val state: JobArtifactState =
    if (sessionIsolated) JobArtifactState(session.sessionUUID, Some(replClassURI)) else null

  /**
   * Whether any artifact has been added to this artifact manager. We use this to determine whether
   * we should apply the classloader to the session, see `withClassLoaderIfNeeded`.
   */
  protected val sessionArtifactAdded = new AtomicBoolean(false)

  @volatile
  protected var cachedClassLoader: Option[ClassLoader] = None

  private def withClassLoaderIfNeeded[T](f: => T): T = {
    val log = s" classloader for session ${session.sessionUUID} because " +
      s"alwaysApplyClassLoader=$alwaysApplyClassLoader, " +
      s"sessionArtifactAdded=${sessionArtifactAdded.get()}."
    if (alwaysApplyClassLoader || sessionArtifactAdded.get()) {
      logDebug(s"Applying $log")
      Utils.withContextClassLoader(classloader) {
        f
      }
    } else {
      logDebug(s"Not applying $log")
      f
    }
  }

  def withResources[T](f: => T): T = withClassLoaderIfNeeded {
    JobArtifactSet.withActiveJobArtifactState(state) {
      f
    }
  }

  protected val cachedBlockIdList = new CopyOnWriteArrayList[CacheId]
  protected val jarsList = new CopyOnWriteArrayList[Path]
  protected val pythonIncludeList = new CopyOnWriteArrayList[String]
  protected val sparkContextRelativePaths =
    new CopyOnWriteArrayList[(SparkContextResourceType.ResourceType, Path, Option[String])]

  /**
   * Get the URLs of all jar artifacts.
   */
  def getAddedJars: Seq[URL] = jarsList
    .asScala
    .map(ArtifactUtils.concatenatePaths(artifactPath, _))
    .map(_.toUri.toURL)
    .toSeq

  /**
   * Get the py-file names added to this SparkSession.
   *
   * @return
   */
  def getPythonIncludes: Seq[String] = pythonIncludeList.asScala.toSeq

  private def transferFile(
      source: Path,
      target: Path,
      allowOverwrite: Boolean = false,
      deleteSource: Boolean = true): Unit = {
    def execute(s: Path, t: Path, opt: CopyOption*): Path =
      if (deleteSource) Files.move(s, t, opt: _*) else Files.copy(s, t, opt: _*)

    Files.createDirectories(target.getParent)
    if (allowOverwrite) {
      execute(source, target, StandardCopyOption.REPLACE_EXISTING)
    } else {
      execute(source, target)
    }
  }

  private def normalizePath(path: Path): Path = {
    // Convert the path to a string with the current system's separator
    val normalizedPathString = path.toString
      .replace('/', File.separatorChar)
      .replace('\\', File.separatorChar)
    // Convert the normalized string back to a Path object
    Paths.get(normalizedPathString).normalize()
  }
  /**
   * Add and prepare a staged artifact (i.e an artifact that has been rebuilt locally from bytes
   * over the wire) for use.
   *
   * @param remoteRelativePath
   * @param serverLocalStagingPath
   * @param fragment
   * @param deleteStagedFile
   */
  def addArtifact(
      remoteRelativePath: Path,
      serverLocalStagingPath: Path,
      fragment: Option[String],
      deleteStagedFile: Boolean = true
  ): Unit = JobArtifactSet.withActiveJobArtifactState(state) {
    require(!remoteRelativePath.isAbsolute)
    val normalizedRemoteRelativePath = normalizePath(remoteRelativePath)
    if (normalizedRemoteRelativePath.startsWith(s"cache${File.separator}")) {
      val tmpFile = serverLocalStagingPath.toFile
      Utils.tryWithSafeFinallyAndFailureCallbacks {
        val blockManager = session.sparkContext.env.blockManager
        val blockId = CacheId(
          sessionUUID = session.sessionUUID,
          hash = normalizedRemoteRelativePath.toString.stripPrefix(s"cache${File.separator}"))
        val updater = blockManager.TempFileBasedBlockStoreUpdater(
          blockId = blockId,
          level = StorageLevel.MEMORY_AND_DISK_SER,
          classTag = implicitly[ClassTag[Array[Byte]]],
          tmpFile = tmpFile,
          blockSize = tmpFile.length(),
          tellMaster = false)
        updater.save()
        cachedBlockIdList.add(blockId)
      }(finallyBlock = { tmpFile.delete() })
    } else if (normalizedRemoteRelativePath.startsWith(s"classes${File.separator}")) {
      // Move class files to the right directory.
      val target = ArtifactUtils.concatenatePaths(
        classDir,
        normalizedRemoteRelativePath.toString.stripPrefix(s"classes${File.separator}"))
      // Allow overwriting class files to capture updates to classes.
      // This is required because the client currently sends all the class files in each class file
      // transfer.
      transferFile(
        serverLocalStagingPath,
        target,
        allowOverwrite = true,
        deleteSource = deleteStagedFile)
      sessionArtifactAdded.set(true)
      cachedClassLoader = None
    } else {
      val target = ArtifactUtils.concatenatePaths(artifactPath, normalizedRemoteRelativePath)
      // Disallow overwriting with modified version
      if (Files.exists(target)) {
        // makes the query idempotent
        if (FileUtils.contentEquals(target.toFile, serverLocalStagingPath.toFile)) {
          return
        }

        throw new SparkRuntimeException(
          "ARTIFACT_ALREADY_EXISTS",
          Map("normalizedRemoteRelativePath" -> normalizedRemoteRelativePath.toString)
        )
      }
      transferFile(serverLocalStagingPath, target, deleteSource = deleteStagedFile)

      // This URI is for Spark file server that starts with "spark://".
      val uri = s"$artifactURI/${Utils.encodeRelativeUnixPathToURIRawPath(
          FilenameUtils.separatorsToUnix(normalizedRemoteRelativePath.toString))}"

      if (normalizedRemoteRelativePath.startsWith(s"jars${File.separator}")) {
        session.sparkContext.addJar(uri)
        sparkContextRelativePaths.add(
          (SparkContextResourceType.JAR, normalizedRemoteRelativePath, fragment))
        jarsList.add(normalizedRemoteRelativePath)
        sessionArtifactAdded.set(true)
        cachedClassLoader = None
      } else if (normalizedRemoteRelativePath.startsWith(s"pyfiles${File.separator}")) {
        session.sparkContext.addFile(uri)
        sparkContextRelativePaths.add(
          (SparkContextResourceType.FILE, normalizedRemoteRelativePath, fragment))
        val stringRemotePath = normalizedRemoteRelativePath.toString
        if (stringRemotePath.endsWith(".zip") || stringRemotePath.endsWith(
            ".egg") || stringRemotePath.endsWith(".jar")) {
          pythonIncludeList.add(target.getFileName.toString)
        }
      } else if (normalizedRemoteRelativePath.startsWith(s"archives${File.separator}")) {
        val canonicalUri =
          fragment.map(Utils.getUriBuilder(new URI(uri)).fragment).getOrElse(new URI(uri))
        session.sparkContext.addArchive(canonicalUri.toString)
        sparkContextRelativePaths.add(
          (SparkContextResourceType.ARCHIVE, normalizedRemoteRelativePath, fragment))
      } else if (normalizedRemoteRelativePath.startsWith(s"files${File.separator}")) {
        session.sparkContext.addFile(uri)
        sparkContextRelativePaths.add(
          (SparkContextResourceType.FILE, normalizedRemoteRelativePath, fragment))
      }
    }
  }

  /**
   * Add locally-stored artifacts to the session. These artifacts are from a user-provided
   * permanent path which are accessible by the driver directly.
   *
   * Different from the [[addArtifact]] method, this method will not delete staged artifacts since
   * they are from a permanent location.
   */
  private[sql] def addLocalArtifacts(artifacts: Seq[Artifact]): Unit = {
    artifacts.foreach { artifact =>
      artifact.storage match {
        case d: Artifact.LocalFile =>
          addArtifact(
            artifact.path,
            d.path,
            fragment = None,
            deleteStagedFile = false)
        case d: Artifact.InMemory =>
          val tempDir = Utils.createTempDir().toPath
          val tempFile = tempDir.resolve(artifact.path.getFileName)
          val outStream = Files.newOutputStream(tempFile)
          Utils.tryWithSafeFinallyAndFailureCallbacks {
            d.stream.transferTo(outStream)
            addArtifact(artifact.path, tempFile, fragment = None)
          }(finallyBlock = {
            outStream.close()
          })
        case _ =>
          throw SparkException.internalError(s"Unsupported artifact storage: ${artifact.storage}")
      }
    }
  }

  def classloader: ClassLoader = synchronized {
    cachedClassLoader.getOrElse {
      val loader = buildClassLoader
      cachedClassLoader = Some(loader)
      loader
    }
  }

  /**
   * Returns a [[ClassLoader]] for session-specific jar/class file resources.
   */
  private def buildClassLoader: ClassLoader = {
    val urls = (getAddedJars :+ classDir.toUri.toURL).toArray
    val prefixes = SparkEnv.get.conf.get(CONNECT_SCALA_UDF_STUB_PREFIXES)
    val userClasspathFirst = SparkEnv.get.conf.get(EXECUTOR_USER_CLASS_PATH_FIRST)
    val fallbackClassLoader = session.sharedState.jarClassLoader
    val loader = if (prefixes.nonEmpty) {
      // Two things you need to know about classloader for all of this to make sense:
      // 1. A classloader needs to be able to fully define a class.
      // 2. Classes are loaded lazily. Only when a class is used the classes it references are
      //    loaded.
      // This makes stubbing a bit more complicated then you'd expect. We cannot put the stubbing
      // classloader as a fallback at the end of the loading process, because then classes that
      // have been found in one of the parent classloaders and that contain a reference to a
      // missing, to-be-stubbed missing class will still fail with classloading errors later on.
      // The way we currently fix this is by making the stubbing class loader the last classloader
      // it delegates to.
      if (userClasspathFirst) {
        // USER -> SYSTEM -> STUB
        new ChildFirstURLClassLoader(urls, StubClassLoader(fallbackClassLoader, prefixes))
      } else {
        // SYSTEM -> USER -> STUB
        new ChildFirstURLClassLoader(urls, StubClassLoader(null, prefixes), fallbackClassLoader)
      }
    } else {
      if (userClasspathFirst) {
        new ChildFirstURLClassLoader(urls, fallbackClassLoader)
      } else {
        new URLClassLoader(urls, fallbackClassLoader)
      }
    }

    logDebug(s"Using class loader: $loader, containing urls: $urls")
    loader
  }

  private[sql] def clone(newSession: SparkSession): ArtifactManager = {
    val sparkContext = session.sparkContext
    val newArtifactManager = new ArtifactManager(newSession)
    if (artifactPath.toFile.exists()) {
      Utils.copyDirectory(artifactPath.toFile, newArtifactManager.artifactPath.toFile)
    }
    val blockManager = sparkContext.env.blockManager
    val newBlockIds = cachedBlockIdList.asScala.map { blockId =>
      val newBlockId = blockId.copy(sessionUUID = newSession.sessionUUID)
      copyBlock(blockId, newBlockId, blockManager)
    }

    // Re-register resources to SparkContext
    JobArtifactSet.withActiveJobArtifactState(newArtifactManager.state) {
      sparkContextRelativePaths.forEach { case (resourceType, relativePath, fragment) =>
        val uri = s"${newArtifactManager.artifactURI}/${
          Utils.encodeRelativeUnixPathToURIRawPath(
            FilenameUtils.separatorsToUnix(relativePath.toString))
        }"
        resourceType match {
          case SparkContextResourceType.JAR =>
            sparkContext.addJar(uri)
          case SparkContextResourceType.FILE =>
            sparkContext.addFile(uri)
          case SparkContextResourceType.ARCHIVE =>
            val canonicalUri =
              fragment.map(Utils.getUriBuilder(new URI(uri)).fragment).getOrElse(new URI(uri))
            sparkContext.addArchive(canonicalUri.toString)
          case _ =>
            throw SparkException.internalError(s"Unsupported resource type: $resourceType")
        }
      }
    }

    newArtifactManager.cachedBlockIdList.addAll(newBlockIds.asJava)
    newArtifactManager.jarsList.addAll(jarsList)
    newArtifactManager.pythonIncludeList.addAll(pythonIncludeList)
    newArtifactManager.sparkContextRelativePaths.addAll(sparkContextRelativePaths)
    newArtifactManager
  }

  private val cleanUpStateForGlobalResources = ArtifactStateForCleanup(
    session.sessionUUID,
    session.sparkContext,
    state,
    artifactPath)
  // Ensure that no reference to `this` is captured/help by the cleanup lambda
  private def getCleanable: Cleaner.Cleanable = cleaner.register(
    this,
    () => ArtifactManager.cleanUpGlobalResources(cleanUpStateForGlobalResources)
  )
  private var cleanable = getCleanable

  /**
   * Cleans up all resources specific to this `session`.
   */
  private def cleanUpResources(): Unit = {
    logDebug(
      s"Cleaning up resources for session with sessionUUID ${session.sessionUUID}")

    // Clean up global resources via the Cleaner process.
    // Note that this will only be run once per instance.
    cleanable.clean()

    // Clean up internal trackers
    jarsList.clear()
    pythonIncludeList.clear()
    cachedBlockIdList.clear()
    sparkContextRelativePaths.clear()

    // Removed cached classloader
    cachedClassLoader = None
  }

  override def close(): Unit = {
    cleanUpResources()
  }

  private[sql] def cleanUpResourcesForTesting(): Unit = {
    cleanUpResources()
    // Tests reuse the same instance so we need to re-register the cleanable otherwise, it is run
    // only once per instance.
    cleanable = getCleanable
  }

  def uploadArtifactToFs(
      remoteRelativePath: Path,
      serverLocalStagingPath: Path): Unit = {
    val normalizedRemoteRelativePath = normalizePath(remoteRelativePath)
    val hadoopConf = session.sparkContext.hadoopConfiguration
    assert(
      normalizedRemoteRelativePath.startsWith(
        ArtifactManager.forwardToFSPrefix + File.separator))
    val destFSPath = new FSPath(
      Paths
        .get(File.separator)
        .resolve(normalizedRemoteRelativePath.subpath(1, normalizedRemoteRelativePath.getNameCount))
        .toString)
    val localPath = serverLocalStagingPath
    val fs = destFSPath.getFileSystem(hadoopConf)
    if (fs.isInstanceOf[LocalFileSystem]) {
      val allowDestLocalConf =
        session.sessionState.conf.getConf(SQLConf.ARTIFACT_COPY_FROM_LOCAL_TO_FS_ALLOW_DEST_LOCAL)
          .getOrElse(
            session.conf.get("spark.connect.copyFromLocalToFs.allowDestLocal").contains("true"))

      if (!allowDestLocalConf) {
        // To avoid security issue, by default,
        // we don't support uploading file to local file system
        // destination path, otherwise user is able to overwrite arbitrary file
        // on spark driver node.
        // We can temporarily allow the behavior by setting spark config
        // `spark.sql.artifact.copyFromLocalToFs.allowDestLocal`
        // to `true` when starting spark driver, we should only enable it for testing
        // purpose.
        throw new SparkUnsupportedOperationException("_LEGACY_ERROR_TEMP_3161")
      }
    }
    fs.copyFromLocalFile(false, true, new FSPath(localPath.toString), destFSPath)
  }
}

object ArtifactManager extends Logging {

  val forwardToFSPrefix = "forward_to_fs"

  val ARTIFACT_DIRECTORY_PREFIX = "artifacts"

  private[artifact] lazy val artifactRootDirectory =
    Utils.createTempDir(namePrefix = ARTIFACT_DIRECTORY_PREFIX).toPath

  private[artifact] object SparkContextResourceType extends Enumeration {
    type ResourceType = Value
    val JAR, FILE, ARCHIVE = Value
  }

  private def copyBlock(fromId: CacheId, toId: CacheId, blockManager: BlockManager): CacheId = {
    require(fromId != toId)
    blockManager.getLocalBytes(fromId) match {
      case Some(blockData) =>
        Utils.tryWithSafeFinallyAndFailureCallbacks {
          val updater = blockManager.ByteBufferBlockStoreUpdater(
            blockId = toId,
            level = StorageLevel.MEMORY_AND_DISK_SER,
            classTag = implicitly[ClassTag[Array[Byte]]],
            bytes = blockData.toChunkedByteBuffer(ByteBuffer.allocate),
            tellMaster = false)
          updater.save()
          toId
        }(finallyBlock = { blockManager.releaseLock(fromId); blockData.dispose() })
      case None =>
        throw SparkException.internalError(s"Block $fromId not found in the block manager.")
    }
  }

  // Shared cleaner instance
  private val cleaner: Cleaner = Cleaner.create()

  /**
   * Helper method to clean up global resources (i.e. resources associated with the calling
   * instance but held externally in sparkContext, blockManager, disk etc.)
   */
  private def cleanUpGlobalResources(cleanupState: ArtifactStateForCleanup): Unit = {
    // Clean up added files
    val (sparkSessionUUID, sparkContext, state, artifactPath) = (
      cleanupState.sparkSessionUUID,
      cleanupState.sparkContext,
      cleanupState.jobArtifactState,
      cleanupState.artifactPath)
    val fileServer = SparkEnv.get.rpcEnv.fileServer
    if (state != null) {
      val shouldUpdateEnv = sparkContext.addedFiles.contains(state.uuid) ||
        sparkContext.addedArchives.contains(state.uuid) ||
        sparkContext.addedJars.contains(state.uuid)
      if (shouldUpdateEnv) {
        sparkContext.addedFiles.remove(state.uuid).foreach(_.keys.foreach(fileServer.removeFile))
        sparkContext.addedArchives.remove(state.uuid).foreach(_.keys.foreach(fileServer.removeFile))
        sparkContext.addedJars.remove(state.uuid).foreach(_.keys.foreach(fileServer.removeJar))
        sparkContext.postEnvironmentUpdate()
      }
    }

    // Clean up cached relations
    val blockManager = sparkContext.env.blockManager
    blockManager.removeCache(sparkSessionUUID)

    // Clean up artifacts folder
    try {
      Utils.deleteRecursively(artifactPath.toFile)
    } catch {
      case e: IOException =>
        logWarning(log"Failed to delete directory ${MDC(LogKeys.PATH, artifactPath.toFile)}: " +
          log"${MDC(LogKeys.EXCEPTION, e.getMessage)}", e)
    }
  }
}

private[artifact] case class ArtifactStateForCleanup(
  sparkSessionUUID: String,
  sparkContext: SparkContext,
  jobArtifactState: JobArtifactState,
  artifactPath: Path)
