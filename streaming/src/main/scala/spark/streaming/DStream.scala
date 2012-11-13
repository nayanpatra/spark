package spark.streaming

import StreamingContext._
import Time._

import spark._
import spark.SparkContext._
import spark.rdd._
import spark.storage.StorageLevel

import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.HashMap

import java.util.concurrent.ArrayBlockingQueue
import java.io.{ObjectInputStream, IOException, ObjectOutputStream}

import org.apache.hadoop.fs.Path
import org.apache.hadoop.conf.Configuration


case class DStreamCheckpointData(rdds: HashMap[Time, Any])

abstract class DStream[T: ClassManifest] (@transient var ssc: StreamingContext)
extends Serializable with Logging {

  initLogging()

  /** 
   * ----------------------------------------------
   * Methods that must be implemented by subclasses
   * ---------------------------------------------- 
   */

  // Time by which the window slides in this DStream
  def slideTime: Time 

  // List of parent DStreams on which this DStream depends on
  def dependencies: List[DStream[_]]

  // Key method that computes RDD for a valid time
  def compute (validTime: Time): Option[RDD[T]]

  /**
   * --------------------------------------- 
   * Other general fields and methods of DStream
   * --------------------------------------- 
   */

  // RDDs generated, marked as protected[streaming] so that testsuites can access it
  @transient
  protected[streaming] var generatedRDDs = new HashMap[Time, RDD[T]] ()
  
  // Time zero for the DStream
  protected[streaming] var zeroTime: Time = null

  // Duration for which the DStream will remember each RDD created
  protected[streaming] var rememberDuration: Time = null

  // Storage level of the RDDs in the stream
  protected[streaming] var storageLevel: StorageLevel = StorageLevel.NONE

  // Checkpoint details
  protected[streaming] val mustCheckpoint = false
  protected[streaming] var checkpointInterval: Time = null
  protected[streaming] var checkpointData = new DStreamCheckpointData(HashMap[Time, Any]())

  // Reference to whole DStream graph
  protected[streaming] var graph: DStreamGraph = null

  def isInitialized = (zeroTime != null)

  // Duration for which the DStream requires its parent DStream to remember each RDD created
  def parentRememberDuration = rememberDuration

  // Set caching level for the RDDs created by this DStream
  def persist(level: StorageLevel): DStream[T] = {
    if (this.isInitialized) {
      throw new UnsupportedOperationException(
        "Cannot change storage level of an DStream after streaming context has started")
    }
    this.storageLevel = level
    this
  }

  def persist(): DStream[T] = persist(StorageLevel.MEMORY_ONLY_SER)
  
  // Turn on the default caching level for this RDD
  def cache(): DStream[T] = persist()

  def checkpoint(interval: Time): DStream[T] = {
    if (isInitialized) {
      throw new UnsupportedOperationException(
        "Cannot change checkpoint interval of an DStream after streaming context has started")
    }
    persist()
    checkpointInterval = interval
    this
  }

  /**
   * This method initializes the DStream by setting the "zero" time, based on which
   * the validity of future times is calculated. This method also recursively initializes
   * its parent DStreams.
   */
  protected[streaming] def initialize(time: Time) {
    if (zeroTime != null && zeroTime != time) {
      throw new Exception("ZeroTime is already initialized to " + zeroTime
        + ", cannot initialize it again to " + time)
    }
    zeroTime = time

    // Set the checkpoint interval to be slideTime or 10 seconds, which ever is larger
    if (mustCheckpoint && checkpointInterval == null) {
      checkpointInterval = slideTime.max(Seconds(10))
      logInfo("Checkpoint interval automatically set to " + checkpointInterval)
    }

    // Set the minimum value of the rememberDuration if not already set
    var minRememberDuration = slideTime
    if (checkpointInterval != null && minRememberDuration <= checkpointInterval) {
      minRememberDuration = checkpointInterval * 2  // times 2 just to be sure that the latest checkpoint is not forgetten
    }
    if (rememberDuration == null || rememberDuration < minRememberDuration) {
      rememberDuration = minRememberDuration
    }

    // Initialize the dependencies
    dependencies.foreach(_.initialize(zeroTime))
  }

  protected[streaming] def validate() {
    assert(
      !mustCheckpoint || checkpointInterval != null,
      "The checkpoint interval for " + this.getClass.getSimpleName + " has not been set. " +
        " Please use DStream.checkpoint() to set the interval."
    )

    assert(
      checkpointInterval == null || checkpointInterval >= slideTime,
      "The checkpoint interval for " + this.getClass.getSimpleName + " has been set to " +
        checkpointInterval + " which is lower than its slide time (" + slideTime + "). " +
        "Please set it to at least " + slideTime + "."
    )

    assert(
      checkpointInterval == null || checkpointInterval.isMultipleOf(slideTime),
      "The checkpoint interval for " + this.getClass.getSimpleName + " has been set to " +
        checkpointInterval + " which not a multiple of its slide time (" + slideTime + "). " +
        "Please set it to a multiple " + slideTime + "."
    )

    assert(
      checkpointInterval == null || storageLevel != StorageLevel.NONE,
      "" + this.getClass.getSimpleName + " has been marked for checkpointing but the storage " +
        "level has not been set to enable persisting. Please use DStream.persist() to set the " +
        "storage level to use memory for better checkpointing performance."
    )

    assert(
      checkpointInterval == null || rememberDuration > checkpointInterval,
      "The remember duration for " + this.getClass.getSimpleName + " has been set to " +
        rememberDuration + " which is not more than the checkpoint interval (" +
        checkpointInterval + "). Please set it to higher than " + checkpointInterval + "."
    )

    dependencies.foreach(_.validate())

    logInfo("Slide time = " + slideTime)
    logInfo("Storage level = " + storageLevel)
    logInfo("Checkpoint interval = " + checkpointInterval)
    logInfo("Remember duration = " + rememberDuration)
    logInfo("Initialized " + this)
  }

  protected[streaming] def setContext(s: StreamingContext) {
    if (ssc != null && ssc != s) {
      throw new Exception("Context is already set in " + this + ", cannot set it again")
    }
    ssc = s
    logInfo("Set context for " + this)
    dependencies.foreach(_.setContext(ssc))
  }

  protected[streaming] def setGraph(g: DStreamGraph) {
    if (graph != null && graph != g) {
      throw new Exception("Graph is already set in " + this + ", cannot set it again")
    }
    graph = g
    dependencies.foreach(_.setGraph(graph))
  }

  protected[streaming] def setRememberDuration(duration: Time) {
    if (duration != null && duration > rememberDuration) {
      rememberDuration = duration
      logInfo("Duration for remembering RDDs set to " + rememberDuration + " for " + this)
    }
    dependencies.foreach(_.setRememberDuration(parentRememberDuration))
  }

  /** This method checks whether the 'time' is valid wrt slideTime for generating RDD */
  protected def isTimeValid(time: Time): Boolean = {
    if (!isInitialized) {
      throw new Exception (this + " has not been initialized")
    } else if (time <= zeroTime || ! (time - zeroTime).isMultipleOf(slideTime)) {
      false
    } else {
      true
    }
  }

  /**
   * Retrieves a precomputed RDD of this DStream, or computes the RDD. This is an internal
   * method that should not be called directly.
   */  
  protected[streaming] def getOrCompute(time: Time): Option[RDD[T]] = {
    // If this DStream was not initialized (i.e., zeroTime not set), then do it
    // If RDD was already generated, then retrieve it from HashMap
    generatedRDDs.get(time) match {
      
      // If an RDD was already generated and is being reused, then 
      // probably all RDDs in this DStream will be reused and hence should be cached
      case Some(oldRDD) => Some(oldRDD)
      
      // if RDD was not generated, and if the time is valid
      // (based on sliding time of this DStream), then generate the RDD
      case None => {
        if (isTimeValid(time)) {
          compute(time) match {
            case Some(newRDD) =>
              if (storageLevel != StorageLevel.NONE) {
                newRDD.persist(storageLevel)
                logInfo("Persisting RDD for time " + time + " to " + storageLevel + " at time " + time)
              }
              if (checkpointInterval != null && (time - zeroTime).isMultipleOf(checkpointInterval)) {
                newRDD.checkpoint()
                logInfo("Marking RDD " + newRDD + " for time " + time + " for checkpointing at time " + time)
              }
              generatedRDDs.put(time, newRDD)
              Some(newRDD)
            case None => 
              None
          }
        } else {
          None
        }
      }
    }
  }

  /**
   * Generates a SparkStreaming job for the given time. This is an internal method that
   * should not be called directly. This default implementation creates a job
   * that materializes the corresponding RDD. Subclasses of DStream may override this
   * (eg. PerRDDForEachDStream).
   */
  protected[streaming] def generateJob(time: Time): Option[Job] = {
    getOrCompute(time) match {
      case Some(rdd) => {
        val jobFunc = () => {
          val emptyFunc = { (iterator: Iterator[T]) => {} } 
          ssc.sc.runJob(rdd, emptyFunc)
        }
        Some(new Job(time, jobFunc))
      }
      case None => None
    }
  }

  /**
   * Dereferences RDDs that are older than rememberDuration.
   */
  protected[streaming] def forgetOldRDDs(time: Time) {
    val keys = generatedRDDs.keys
    var numForgotten = 0
    keys.foreach(t => {
      if (t <= (time - rememberDuration)) {
        generatedRDDs.remove(t)
        numForgotten += 1
        logInfo("Forgot RDD of time " + t + " from " + this)
      }
    })
    logInfo("Forgot " + numForgotten + " RDDs from " + this)
    dependencies.foreach(_.forgetOldRDDs(time))
  }

  /* Adds metadata to the Stream while it is running. 
   * This methd should be overwritten by sublcasses of InputDStream.
   */
  protected[streaming] def addMetadata(metadata: Any) {
    if (metadata != null) {
      logInfo("Dropping Metadata: " + metadata.toString)
    }
  }

  /**
   * Refreshes the list of checkpointed RDDs that will be saved along with checkpoint of
   * this stream. This is an internal method that should not be called directly. This is
   * a default implementation that saves only the file names of the checkpointed RDDs to
   * checkpointData. Subclasses of DStream (especially those of InputDStream) may override
   * this method to save custom checkpoint data.
   */
  protected[streaming] def updateCheckpointData(currentTime: Time) {

    logInfo("Updating checkpoint data for time " + currentTime)

    // Get the checkpointed RDDs from the generated RDDs
    val newRdds = generatedRDDs.filter(_._2.getCheckpointData() != null)
                                         .map(x => (x._1, x._2.getCheckpointData()))
    // Make a copy of the existing checkpoint data
    val oldRdds = checkpointData.rdds.clone()
    // If the new checkpoint has checkpoints then replace existing with the new one
    if (newRdds.size > 0) {
      checkpointData.rdds.clear()
      checkpointData.rdds ++= newRdds
    }
    // Make dependencies update their checkpoint data
    dependencies.foreach(_.updateCheckpointData(currentTime))

    // TODO: remove this, this is just for debugging
    newRdds.foreach {
      case (time, data) => { logInfo("Added checkpointed RDD for time " + time + " to stream checkpoint") }
    }

    if (newRdds.size > 0) {
      (oldRdds -- newRdds.keySet).foreach {
        case (time, data) => {
          val path = new Path(data.toString)
          val fs = path.getFileSystem(new Configuration())
          fs.delete(path, true)
          logInfo("Deleted checkpoint file '" + path + "' for time " + time)
        }
      }
    }
    logInfo("Updated checkpoint data for time " + currentTime)
  }

  /**
   * Restores the RDDs in generatedRDDs from the checkpointData. This is an internal method
   * that should not be called directly. This is a default implementation that recreates RDDs
   * from the checkpoint file names stored in checkpointData. Subclasses of DStream that
   * override the updateCheckpointData() method would also need to override this method.
   */
  protected[streaming] def restoreCheckpointData() {
    // Create RDDs from the checkpoint data
    logInfo("Restoring checkpoint data from " + checkpointData.rdds.size + " checkpointed RDDs")
    checkpointData.rdds.foreach {
      case(time, data) => {
        logInfo("Restoring checkpointed RDD for time " + time + " from file '" + data.toString + "'")
        val rdd = ssc.sc.objectFile[T](data.toString)
        // Set the checkpoint file name to identify this RDD as a checkpointed RDD by updateCheckpointData()
        rdd.checkpointFile = data.toString
        generatedRDDs += ((time, rdd))
      }
    }
    dependencies.foreach(_.restoreCheckpointData())
    logInfo("Restored checkpoint data")
  }

  @throws(classOf[IOException])
  private def writeObject(oos: ObjectOutputStream) {
    logDebug(this.getClass().getSimpleName + ".writeObject used")
    if (graph != null) {
      graph.synchronized {
        if (graph.checkpointInProgress) {
          oos.defaultWriteObject()
        } else {
          val msg = "Object of " + this.getClass.getName + " is being serialized " +
            " possibly as a part of closure of an RDD operation. This is because " +
            " the DStream object is being referred to from within the closure. " +
            " Please rewrite the RDD operation inside this DStream to avoid this. " +
            " This has been enforced to avoid bloating of Spark tasks " +
            " with unnecessary objects."
          throw new java.io.NotSerializableException(msg)
        }
      }
    } else {
      throw new java.io.NotSerializableException("Graph is unexpectedly null when DStream is being serialized.")
    }
  }

  @throws(classOf[IOException])
  private def readObject(ois: ObjectInputStream) {
    logDebug(this.getClass().getSimpleName + ".readObject used")
    ois.defaultReadObject()
    generatedRDDs = new HashMap[Time, RDD[T]] ()
  }

  /** 
   * --------------
   * DStream operations
   * -------------- 
   */
  def map[U: ClassManifest](mapFunc: T => U): DStream[U] = {
    new MappedDStream(this, ssc.sc.clean(mapFunc))
  }

  def flatMap[U: ClassManifest](flatMapFunc: T => Traversable[U]): DStream[U] = {
    new FlatMappedDStream(this, ssc.sc.clean(flatMapFunc))
  }

  def filter(filterFunc: T => Boolean): DStream[T] = new FilteredDStream(this, filterFunc)

  def glom(): DStream[Array[T]] = new GlommedDStream(this)

  def mapPartitions[U: ClassManifest](mapPartFunc: Iterator[T] => Iterator[U]): DStream[U] = {
    new MapPartitionedDStream(this, ssc.sc.clean(mapPartFunc))
  }

  def reduce(reduceFunc: (T, T) => T): DStream[T] = this.map(x => (null, x)).reduceByKey(reduceFunc, 1).map(_._2)

  def count(): DStream[Int] = this.map(_ => 1).reduce(_ + _)
  
  def collect(): DStream[Seq[T]] = this.map(x => (null, x)).groupByKey(1).map(_._2)

  def foreach(foreachFunc: T => Unit) {
    val newStream = new PerElementForEachDStream(this, ssc.sc.clean(foreachFunc))
    ssc.registerOutputStream(newStream)
    newStream
  }

  def foreachRDD(foreachFunc: RDD[T] => Unit) {
    foreachRDD((r: RDD[T], t: Time) => foreachFunc(r))
  }

  def foreachRDD(foreachFunc: (RDD[T], Time) => Unit) {
    val newStream = new PerRDDForEachDStream(this, ssc.sc.clean(foreachFunc))
    ssc.registerOutputStream(newStream)
    newStream
  }

  def transformRDD[U: ClassManifest](transformFunc: RDD[T] => RDD[U]): DStream[U] = {
    transformRDD((r: RDD[T], t: Time) => transformFunc(r))
  }

  def transformRDD[U: ClassManifest](transformFunc: (RDD[T], Time) => RDD[U]): DStream[U] = {
    new TransformedDStream(this, ssc.sc.clean(transformFunc))
  }

  def toBlockingQueue() = {
    val queue = new ArrayBlockingQueue[RDD[T]](10000)
    this.foreachRDD(rdd => {
      queue.add(rdd)
    })
    queue
  }
  
  def print() {
    def foreachFunc = (rdd: RDD[T], time: Time) => {
      val first11 = rdd.take(11)
      println ("-------------------------------------------")
      println ("Time: " + time)
      println ("-------------------------------------------")
      first11.take(10).foreach(println)
      if (first11.size > 10) println("...")
      println()
    }
    val newStream = new PerRDDForEachDStream(this, ssc.sc.clean(foreachFunc))
    ssc.registerOutputStream(newStream)
  }

  def window(windowTime: Time): DStream[T] = window(windowTime, this.slideTime)

  def window(windowTime: Time, slideTime: Time): DStream[T] = {
    new WindowedDStream(this, windowTime, slideTime)
  }

  def tumble(batchTime: Time): DStream[T] = window(batchTime, batchTime)

  def reduceByWindow(reduceFunc: (T, T) => T, windowTime: Time, slideTime: Time): DStream[T] = {
    this.window(windowTime, slideTime).reduce(reduceFunc)
  }

  def reduceByWindow(
      reduceFunc: (T, T) => T,
      invReduceFunc: (T, T) => T,
      windowTime: Time,
      slideTime: Time
    ): DStream[T] = {
      this.map(x => (1, x))
          .reduceByKeyAndWindow(reduceFunc, invReduceFunc, windowTime, slideTime, 1)
          .map(_._2)
  }

  def countByWindow(windowTime: Time, slideTime: Time): DStream[Int] = {
    this.map(_ => 1).reduceByWindow(_ + _, _ - _, windowTime, slideTime)
  }

  def union(that: DStream[T]): DStream[T] = new UnionDStream[T](Array(this, that))

  def slice(interval: Interval): Seq[RDD[T]] = {
    slice(interval.beginTime, interval.endTime)
  }

  // Get all the RDDs between fromTime to toTime (both included)
  def slice(fromTime: Time, toTime: Time): Seq[RDD[T]] = {
    val rdds = new ArrayBuffer[RDD[T]]()
    var time = toTime.floor(slideTime)
    while (time >= zeroTime && time >= fromTime) {
      getOrCompute(time) match {
        case Some(rdd) => rdds += rdd
        case None => //throw new Exception("Could not get RDD for time " + time)
      }
      time -= slideTime
    }
    rdds.toSeq
  }

  def saveAsObjectFiles(prefix: String, suffix: String = "") {
    val saveFunc = (rdd: RDD[T], time: Time) => {
      val file = rddToFileName(prefix, suffix, time)
      rdd.saveAsObjectFile(file)
    }
    this.foreachRDD(saveFunc)
  }

  def saveAsTextFiles(prefix: String, suffix: String = "") {
    val saveFunc = (rdd: RDD[T], time: Time) => {
      val file = rddToFileName(prefix, suffix, time)
      rdd.saveAsTextFile(file)
    }
    this.foreachRDD(saveFunc)
  }

  def register() {
    ssc.registerOutputStream(this)
  }
}


abstract class InputDStream[T: ClassManifest] (@transient ssc_ : StreamingContext)
  extends DStream[T](ssc_) {
  
  override def dependencies = List()

  override def slideTime = {
    if (ssc == null) throw new Exception("ssc is null")
    if (ssc.graph.batchDuration == null) throw new Exception("batchDuration is null")
    ssc.graph.batchDuration
  }
  
  def start()  
  
  def stop()
}


/**
 * TODO
 */

class MappedDStream[T: ClassManifest, U: ClassManifest] (
    parent: DStream[T],
    mapFunc: T => U
  ) extends DStream[U](parent.ssc) {
  
  override def dependencies = List(parent)

  override def slideTime: Time = parent.slideTime

  override def compute(validTime: Time): Option[RDD[U]] = {
    parent.getOrCompute(validTime).map(_.map[U](mapFunc))
  }
}


/**
 * TODO
 */

class FlatMappedDStream[T: ClassManifest, U: ClassManifest](
    parent: DStream[T],
    flatMapFunc: T => Traversable[U]
  ) extends DStream[U](parent.ssc) {
  
  override def dependencies = List(parent)

  override def slideTime: Time = parent.slideTime

  override def compute(validTime: Time): Option[RDD[U]] = {
    parent.getOrCompute(validTime).map(_.flatMap(flatMapFunc))
  }
}


/**
 * TODO
 */

class FilteredDStream[T: ClassManifest](
    parent: DStream[T],
    filterFunc: T => Boolean
  ) extends DStream[T](parent.ssc) {
  
  override def dependencies = List(parent)

  override def slideTime: Time = parent.slideTime

  override def compute(validTime: Time): Option[RDD[T]] = {
    parent.getOrCompute(validTime).map(_.filter(filterFunc))
  }
}


/**
 * TODO
 */

class MapPartitionedDStream[T: ClassManifest, U: ClassManifest](
    parent: DStream[T],
    mapPartFunc: Iterator[T] => Iterator[U]
  ) extends DStream[U](parent.ssc) {

  override def dependencies = List(parent)

  override def slideTime: Time = parent.slideTime

  override def compute(validTime: Time): Option[RDD[U]] = {
    parent.getOrCompute(validTime).map(_.mapPartitions[U](mapPartFunc))
  }
}


/**
 * TODO
 */

class GlommedDStream[T: ClassManifest](parent: DStream[T])
  extends DStream[Array[T]](parent.ssc) {

  override def dependencies = List(parent)

  override def slideTime: Time = parent.slideTime

  override def compute(validTime: Time): Option[RDD[Array[T]]] = {
    parent.getOrCompute(validTime).map(_.glom())
  }
}


/**
 * TODO
 */

class ShuffledDStream[K: ClassManifest, V: ClassManifest, C: ClassManifest](
    parent: DStream[(K,V)],
    createCombiner: V => C,
    mergeValue: (C, V) => C,
    mergeCombiner: (C, C) => C,
    partitioner: Partitioner
  ) extends DStream [(K,C)] (parent.ssc) {
  
  override def dependencies = List(parent)

  override def slideTime: Time = parent.slideTime

  override def compute(validTime: Time): Option[RDD[(K,C)]] = {
    parent.getOrCompute(validTime) match {
      case Some(rdd) =>
        Some(rdd.combineByKey[C](createCombiner, mergeValue, mergeCombiner, partitioner))
      case None => None
    }
  }
}


/**
 * TODO
 */

class MapValuesDStream[K: ClassManifest, V: ClassManifest, U: ClassManifest](
    parent: DStream[(K, V)],
    mapValueFunc: V => U
  ) extends DStream[(K, U)](parent.ssc) {

  override def dependencies = List(parent)

  override def slideTime: Time = parent.slideTime

  override def compute(validTime: Time): Option[RDD[(K, U)]] = {
    parent.getOrCompute(validTime).map(_.mapValues[U](mapValueFunc))
  }
}


/**
 * TODO
 */

class FlatMapValuesDStream[K: ClassManifest, V: ClassManifest, U: ClassManifest](
    parent: DStream[(K, V)],
    flatMapValueFunc: V => TraversableOnce[U]
  ) extends DStream[(K, U)](parent.ssc) {

  override def dependencies = List(parent)

  override def slideTime: Time = parent.slideTime

  override def compute(validTime: Time): Option[RDD[(K, U)]] = {
    parent.getOrCompute(validTime).map(_.flatMapValues[U](flatMapValueFunc))
  }
}



/**
 * TODO
 */

class UnionDStream[T: ClassManifest](parents: Array[DStream[T]])
  extends DStream[T](parents.head.ssc) {

  if (parents.length == 0) {
    throw new IllegalArgumentException("Empty array of parents")
  }

  if (parents.map(_.ssc).distinct.size > 1) {
    throw new IllegalArgumentException("Array of parents have different StreamingContexts")
  }
  
  if (parents.map(_.slideTime).distinct.size > 1) {
    throw new IllegalArgumentException("Array of parents have different slide times")
  }

  override def dependencies = parents.toList

  override def slideTime: Time = parents.head.slideTime

  override def compute(validTime: Time): Option[RDD[T]] = {
    val rdds = new ArrayBuffer[RDD[T]]()
    parents.map(_.getOrCompute(validTime)).foreach(_ match {
      case Some(rdd) => rdds += rdd
      case None => throw new Exception("Could not generate RDD from a parent for unifying at time " + validTime)
    })
    if (rdds.size > 0) {
      Some(new UnionRDD(ssc.sc, rdds))
    } else {
      None
    }
  }
}


/**
 * TODO
 */

class PerElementForEachDStream[T: ClassManifest] (
    parent: DStream[T],
    foreachFunc: T => Unit
  ) extends DStream[Unit](parent.ssc) {
  
  override def dependencies = List(parent)

  override def slideTime: Time = parent.slideTime

  override def compute(validTime: Time): Option[RDD[Unit]] = None 

  override def generateJob(time: Time): Option[Job] = {
    parent.getOrCompute(time) match {
      case Some(rdd) =>
        val jobFunc = () => {
          val sparkJobFunc = { 
            (iterator: Iterator[T]) => iterator.foreach(foreachFunc) 
          } 
          ssc.sc.runJob(rdd, sparkJobFunc)
        }
        Some(new Job(time, jobFunc))
      case None => None
    }
  }
}


/**
 * TODO
 */

class PerRDDForEachDStream[T: ClassManifest] (
    parent: DStream[T],
    foreachFunc: (RDD[T], Time) => Unit
  ) extends DStream[Unit](parent.ssc) {

  override def dependencies = List(parent)

  override def slideTime: Time = parent.slideTime

  override def compute(validTime: Time): Option[RDD[Unit]] = None 

  override def generateJob(time: Time): Option[Job] = {
    parent.getOrCompute(time) match {
      case Some(rdd) =>
        val jobFunc = () => {
          foreachFunc(rdd, time)
        }
        Some(new Job(time, jobFunc))
      case None => None
    }
  }
}


/**
 * TODO
 */

class TransformedDStream[T: ClassManifest, U: ClassManifest] (
    parent: DStream[T],
    transformFunc: (RDD[T], Time) => RDD[U]
  ) extends DStream[U](parent.ssc) {

  override def dependencies = List(parent)

  override def slideTime: Time = parent.slideTime

  override def compute(validTime: Time): Option[RDD[U]] = {
    parent.getOrCompute(validTime).map(transformFunc(_, validTime))
  }
}
