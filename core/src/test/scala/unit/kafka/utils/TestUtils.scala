/**
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

package kafka.utils

import java.io._
import java.net._
import java.nio._
import java.nio.channels._
import java.util.Random
import java.util.Properties
import junit.framework.Assert._
import kafka.server._
import kafka.producer._
import kafka.message._
import org.I0Itec.zkclient.ZkClient
import kafka.cluster.Broker
import collection.mutable.ListBuffer
import kafka.consumer.ConsumerConfig
import scala.collection.Map
import kafka.serializer.Encoder
import kafka.api.{ProducerRequest, TopicData, PartitionData}
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.TimeUnit

/**
 * Utility functions to help with testing
 */
object TestUtils extends Logging {
  
  val Letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
  val Digits = "0123456789"
  val LettersAndDigits = Letters + Digits
  
  /* A consistent random number generator to make tests repeatable */
  val seededRandom = new Random(192348092834L)
  val random = new Random()
  
  /**
   * Choose a number of random available ports
   */
  def choosePorts(count: Int): List[Int] = {
    val sockets = 
      for(i <- 0 until count)
        yield new ServerSocket(0)
    val socketList = sockets.toList
    val ports = socketList.map(_.getLocalPort)
    socketList.map(_.close)
    ports
  }
  
  /**
   * Choose an available port
   */
  def choosePort(): Int = choosePorts(1).head
  
  /**
   * Create a temporary directory
   */
  def tempDir(): File = {
    val ioDir = System.getProperty("java.io.tmpdir")
    val f = new File(ioDir, "kafka-" + random.nextInt(1000000))
    f.mkdirs()
    f.deleteOnExit()
    f
  }
  
  /**
   * Create a temporary file
   */
  def tempFile(): File = {
    val f = File.createTempFile("kafka", ".tmp")
    f.deleteOnExit()
    f
  }
  
  /**
   * Create a temporary file and return an open file channel for this file
   */
  def tempChannel(): FileChannel = new RandomAccessFile(tempFile(), "rw").getChannel()
  
  /**
   * Create a kafka server instance with appropriate test settings
   * USING THIS IS A SIGN YOU ARE NOT WRITING A REAL UNIT TEST
   * @param config The configuration of the server
   */
  def createServer(config: KafkaConfig): KafkaServer = {
    val server = new KafkaServer(config)
    server.startup()
    server
  }
  
  /**
   * Create a test config for the given node id
   */
  def createBrokerConfigs(numConfigs: Int): List[Properties] = {
    for((port, node) <- choosePorts(numConfigs).zipWithIndex)
      yield createBrokerConfig(node, port)
  }
  
  /**
   * Create a test config for the given node id
   */
  def createBrokerConfig(nodeId: Int, port: Int): Properties = {
    val props = new Properties
    props.put("brokerid", nodeId.toString)
    props.put("port", port.toString)
    props.put("log.dir", TestUtils.tempDir().getAbsolutePath)
    props.put("log.flush.interval", "1")
    props.put("zk.connect", TestZKUtils.zookeeperConnect)
    props
  }
  
  /**
   * Create a test config for a consumer
   */
  def createConsumerProperties(zkConnect: String, groupId: String, consumerId: String,
                               consumerTimeout: Long = -1): Properties = {
    val props = new Properties
    props.put("zk.connect", zkConnect)
    props.put("groupid", groupId)
    props.put("consumerid", consumerId)
    props.put("consumer.timeout.ms", consumerTimeout.toString)
    props.put("zk.sessiontimeout.ms", "400")
    props.put("zk.synctime.ms", "200")
    props.put("autocommit.interval.ms", "1000")
    props.put("rebalance.retries.max", "4")

    props
  }

  /**
   * Wrap the message in a message set
   * @param payload The bytes of the message
   */
  def singleMessageSet(payload: Array[Byte]) = 
    new ByteBufferMessageSet(compressionCodec = NoCompressionCodec, messages = new Message(payload))
  
  /**
   * Generate an array of random bytes
   * @param numBytes The size of the array
   */
  def randomBytes(numBytes: Int): Array[Byte] = {
    val bytes = new Array[Byte](numBytes)
    seededRandom.nextBytes(bytes)
    bytes
  }
  
  /**
   * Generate a random string of letters and digits of the given length
   * @param len The length of the string
   * @return The random string
   */
  def randomString(len: Int): String = {
    val b = new StringBuilder()
    for(i <- 0 until len)
      b.append(LettersAndDigits.charAt(seededRandom.nextInt(LettersAndDigits.length)))
    b.toString
  }

  /**
   * Check that the buffer content from buffer.position() to buffer.limit() is equal
   */
  def checkEquals(b1: ByteBuffer, b2: ByteBuffer) {
    assertEquals("Buffers should have equal length", b1.limit - b1.position, b2.limit - b2.position)
    for(i <- 0 until b1.limit - b1.position)
      assertEquals("byte " + i + " byte not equal.", b1.get(b1.position + i), b2.get(b1.position + i))
  }
  
  /**
   * Throw an exception if the two iterators are of differing lengths or contain
   * different messages on their Nth element
   */
  def checkEquals[T](expected: Iterator[T], actual: Iterator[T]) {
    var length = 0
    while(expected.hasNext && actual.hasNext) {
      length += 1
      assertEquals(expected.next, actual.next)
    }

    // check if the expected iterator is longer
    if (expected.hasNext) {
     var length1 = length;
     while (expected.hasNext) {
       expected.next
       length1 += 1
     }
     assertFalse("Iterators have uneven length-- first has more: "+length1 + " > " + length, true);
    }

    // check if the actual iterator was longer
    if (actual.hasNext) {
     var length2 = length;
     while (actual.hasNext) {
       actual.next
       length2 += 1
     }
     assertFalse("Iterators have uneven length-- second has more: "+length2 + " > " + length, true);
    }
  }

  /**
   *  Throw an exception if an iterable has different length than expected
   *  
   */
  def checkLength[T](s1: Iterator[T], expectedLength:Int) {
    var n = 0
    while (s1.hasNext) {
      n+=1
      s1.next
    }
    assertEquals(expectedLength, n)
  }

  /**
   * Throw an exception if the two iterators are of differing lengths or contain
   * different messages on their Nth element
   */
  def checkEquals[T](s1: java.util.Iterator[T], s2: java.util.Iterator[T]) {
    while(s1.hasNext && s2.hasNext)
      assertEquals(s1.next, s2.next)
    assertFalse("Iterators have uneven length--first has more", s1.hasNext)
    assertFalse("Iterators have uneven length--second has more", s2.hasNext)
  }

  def stackedIterator[T](s: Iterator[T]*): Iterator[T] = {
    new Iterator[T] {
      var cur: Iterator[T] = null
      val topIterator = s.iterator

      def hasNext() : Boolean = {
        while (true) {
          if (cur == null) {
            if (topIterator.hasNext)
              cur = topIterator.next
            else
              return false
          }
          if (cur.hasNext)
            return true
          cur = null
        }
        // should never reach her
        throw new RuntimeException("should not reach here")
      }

      def next() : T = cur.next
    }
  }

  /**
   * Create a hexidecimal string for the given bytes
   */
  def hexString(bytes: Array[Byte]): String = hexString(ByteBuffer.wrap(bytes))
  
  /**
   * Create a hexidecimal string for the given bytes
   */
  def hexString(buffer: ByteBuffer): String = {
    val builder = new StringBuilder("0x")
    for(i <- 0 until buffer.limit)
      builder.append(String.format("%x", Integer.valueOf(buffer.get(buffer.position + i))))
    builder.toString
  }
  
  /**
   * Create a producer for the given host and port
   */
  def createProducer[K, V](zkConnect: String): Producer[K, V] = {
    val props = new Properties()
    props.put("zk.connect", zkConnect)
    props.put("buffer.size", "65536")
    props.put("connect.timeout.ms", "100000")
    props.put("reconnect.interval", "10000")
    new Producer[K, V](new ProducerConfig(props))
  }

  def updateConsumerOffset(config : ConsumerConfig, path : String, offset : Long) = {
    val zkClient = new ZkClient(config.zkConnect, config.zkSessionTimeoutMs, config.zkConnectionTimeoutMs, ZKStringSerializer)
    ZkUtils.updatePersistentPath(zkClient, path, offset.toString)

  }

  def getMessageIterator(iter: Iterator[MessageAndOffset]): Iterator[Message] = {
    new IteratorTemplate[Message] {
      override def makeNext(): Message = {
        if (iter.hasNext)
          return iter.next.message
        else
          return allDone()
      }
    }
  }

  def createBrokersInZk(zkClient: ZkClient, ids: Seq[Int]): Seq[Broker] = {
    val brokers = ids.map(id => new Broker(id, "localhost" + System.currentTimeMillis(), "localhost", 6667))
    brokers.foreach(b => ZkUtils.registerBrokerInZk(zkClient, b.id, b.host, b.creatorId, b.port))
    brokers
  }

  def deleteBrokersInZk(zkClient: ZkClient, ids: Seq[Int]): Seq[Broker] = {
    val brokers = ids.map(id => new Broker(id, "localhost" + System.currentTimeMillis(), "localhost", 6667))
    brokers.foreach(b => ZkUtils.deletePath(zkClient, ZkUtils.BrokerIdsPath + "/" + b))
    brokers
  }

  def getMsgStrings(n: Int): Seq[String] = {
    val buffer = new ListBuffer[String]
    for (i <- 0 until  n)
      buffer += ("msg" + i)
    buffer
  }
  
  /**
   * Create a wired format request based on simple basic information
   */
  def produceRequest(topic: String, partition: Int, message: ByteBufferMessageSet): kafka.api.ProducerRequest = {
    produceRequest(SyncProducerConfig.DefaultCorrelationId,topic,partition,message)
  }

  def produceRequest(correlationId: Int, topic: String, partition: Int, message: ByteBufferMessageSet): kafka.api.ProducerRequest = {
    produceRequestWithAcks(List(topic), List(partition), message, SyncProducerConfig.DefaultRequiredAcks)
  }

  def produceRequestWithAcks(topics: Seq[String], partitions: Seq[Int], message: ByteBufferMessageSet, acks: Int): kafka.api.ProducerRequest = {
    val correlationId = SyncProducerConfig.DefaultCorrelationId
    val clientId = SyncProducerConfig.DefaultClientId
    val ackTimeout = SyncProducerConfig.DefaultAckTimeoutMs
    val data = topics.map(new TopicData(_, partitions.map(new PartitionData(_, message)).toArray))
    new kafka.api.ProducerRequest(correlationId, clientId, acks.toShort, ackTimeout, data.toArray)
  }

  def produceJavaRequest(topic: String, message: kafka.javaapi.message.ByteBufferMessageSet): kafka.javaapi.ProducerRequest = {
    produceJavaRequest(-1,topic,-1,message)
  }

  def produceJavaRequest(topic: String, partition: Int, message: kafka.javaapi.message.ByteBufferMessageSet): kafka.javaapi.ProducerRequest = {
    produceJavaRequest(-1,topic,partition,message)
  }

  def produceJavaRequest(correlationId: Int, topic: String, partition: Int, message: kafka.javaapi.message.ByteBufferMessageSet): kafka.javaapi.ProducerRequest = {
    val clientId = "test"
    val requiredAcks: Short = 0
    val ackTimeout = 0
    var data = new Array[TopicData](1)
    var partitionData = new Array[PartitionData](1)
    partitionData(0) = new PartitionData(partition,message.underlying)
    data(0) = new TopicData(topic,partitionData)
    val pr = new kafka.javaapi.ProducerRequest(correlationId, clientId, requiredAcks, ackTimeout, data)  	
    pr
  }

  def waitUntilLeaderIsElected(zkClient: ZkClient, topic: String, partition: Int, timeoutMs: Long): Option[Int] = {
    val leaderLock = new ReentrantLock()
    val leaderExists = leaderLock.newCondition()

    info("Waiting for leader to be elected for topic %s partition %d".format(topic, partition))
    leaderLock.lock()
    try {
      // check if leader already exists
      val leader = ZkUtils.getLeaderForPartition(zkClient, topic, partition)
      leader match {
        case Some(l) => info("Leader %d exists for topic %s partition %d".format(l, topic, partition))
          leader
        case None => zkClient.subscribeDataChanges(ZkUtils.getTopicPartitionLeaderPath(topic, partition.toString),
          new LeaderExists(topic, partition, leaderExists))
        leaderExists.await(timeoutMs, TimeUnit.MILLISECONDS)
          // check if leader is elected
        val leader = ZkUtils.getLeaderForPartition(zkClient, topic, partition)
        leader match {
          case Some(l) => info("Leader %d elected for topic %s partition %d".format(l, topic, partition))
          case None => error("Timing out after %d ms since leader is not elected for topic %s partition %d"
            .format(timeoutMs, topic, partition))
        }
        leader
      }
    } finally {
      leaderLock.unlock()
    }
  }

}

object TestZKUtils {
  val zookeeperConnect = "127.0.0.1:2182"  
}

class StringSerializer extends Encoder[String] {
  def toEvent(message: Message):String = message.toString
  def toMessage(event: String):Message = new Message(event.getBytes)
  def getTopic(event: String): String = event.concat("-topic")
}

class NegativePartitioner extends Partitioner[String] {
  def partition(data: String, numPartitions: Int): Int = {
    -1
  }
}

class StaticPartitioner extends Partitioner[String] {
  def partition(data: String, numPartitions: Int): Int = {
    (data.length % numPartitions)
  }
}

class HashPartitioner extends Partitioner[String] {
  def partition(data: String, numPartitions: Int): Int = {
    (data.hashCode % numPartitions)
  }
}

class FixedValuePartitioner extends Partitioner[Int] {
  def partition(data: Int, numPartitions: Int): Int = data
}