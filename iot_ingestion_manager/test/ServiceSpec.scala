/*
 * Copyright 2017 TEAM PER LA TRASFORMAZIONE DIGITALE
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.{FileNotFoundException, IOException, File => JFile}
import java.net.ServerSocket
import java.util.{Base64, Properties}

import ServiceSpec._
import better.files._
import it.gov.daf.iotingestionmanager.client.Iot_ingestion_managerClient
import kafka.server.{KafkaConfig, KafkaServer}
import kafka.utils.{MockTime, TestUtils, ZkUtils}
import org.I0Itec.zkclient.ZkClient
import org.I0Itec.zkclient.serialize.ZkSerializer
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.hbase.{HBaseTestingUtility, TableName}
import org.apache.hadoop.test.PathUtils
import org.apache.spark.SparkConf
import org.apache.spark.opentsdb.OpenTSDBConfigurator
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAfterAll
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.ws.ahc.AhcWSClient
import play.api.test.WithServer

import scala.collection.convert.decorateAsScala._
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.{Failure, Random, Try}

@SuppressWarnings(
  Array(
    "org.wartremover.warts.NonUnitStatements",
    "org.wartremover.warts.Throw",
    "org.wartremover.warts.Null",
    "org.wartremover.warts.Var",
    "org.wartremover.warts.AsInstanceOf"
  )
)
class ServiceSpec extends Specification with BeforeAfterAll {

  val TIMEOUT = 1000

  val POLL = 1000L

  val ZKHOST = "127.0.0.1"

  val NUMBROKERS = 1

  val NUMPARTITIONS = NUMBROKERS * 4

  val BROKERHOST = "127.0.0.1"

  var zkUtils: Try[ZkUtils] = Failure[ZkUtils](new Exception(""))

  var kafkaServers: Array[Try[KafkaServer]] = new Array[Try[KafkaServer]](NUMBROKERS)

  var logDir: Try[JFile] = Failure[JFile](new Exception(""))

  def getAvailablePort: Int = {
    try {
      val socket = new ServerSocket(0)
      try {
        socket.getLocalPort
      } finally {
        socket.close()
      }
    } catch {
      case e: IOException =>
        throw new IllegalStateException(s"Cannot find available port: ${e.getMessage}", e)
    }
  }

  def constructTempDir(dirPrefix: String): Try[JFile] = Try {
    val rndrange = 10000000
    val file = new JFile(System.getProperty("java.io.tmpdir"), s"$dirPrefix${Random.nextInt(rndrange)}")
    if (!file.mkdirs())
      throw new RuntimeException("could not create temp directory: " + file.getAbsolutePath)
    file.deleteOnExit()
    file
  }

  def deleteDirectory(path: JFile): Boolean = {
    if (!path.exists()) {
      throw new FileNotFoundException(path.getAbsolutePath)
    }
    var ret = true
    if (path.isDirectory)
      path.listFiles().foreach(f => ret = ret && deleteDirectory(f))
    ret && path.delete()
  }

  def makeZkUtils(zkPort: String): Try[ZkUtils] = Try {
    val zkConnect = s"$ZKHOST:$zkPort"
    val zkClient = new ZkClient(zkConnect, Integer.MAX_VALUE, TIMEOUT, new ZkSerializer {
      def serialize(data: Object): Array[Byte] = data.asInstanceOf[String].getBytes("UTF-8")

      def deserialize(bytes: Array[Byte]): Object = new String(bytes, "UTF-8")
    })
    ZkUtils.apply(zkClient, isZkSecurityEnabled = false)
  }

  def makeKafkaServer(zkConnect: String, brokerId: Int): Try[KafkaServer] = Try {
    logDir = constructTempDir("kafka-local")
    val brokerPort = getAvailablePort
    val brokerProps = new Properties()
    brokerProps.setProperty("zookeeper.connect", zkConnect)
    brokerProps.setProperty("broker.id", s"$brokerId")
    logDir.foreach(f => brokerProps.setProperty("log.dirs", f.getAbsolutePath))
    brokerProps.setProperty("listeners", s"PLAINTEXT://$BROKERHOST:$brokerPort")
    val config = new KafkaConfig(brokerProps)
    val mockTime = new MockTime()
    TestUtils.createServer(config, mockTime)
  }

  def shutdownKafkaServers(): Unit = {
    kafkaServers.foreach(_.foreach(_.shutdown()))
    kafkaServers.foreach(_.foreach(_.awaitShutdown()))
    logDir.foreach(deleteDirectory)
  }

  def application: Application = GuiceApplicationBuilder().
    configure("hadoop_conf_dir" -> s"${ServiceSpec.confPath.pathAsString}").
    configure("pac4j.authenticator" -> "test").
    build()

  "This test" should {
    "pass" in new WithServer(app = application, port = getAvailablePort) {
      val ws: AhcWSClient = AhcWSClient()

      val plainCreds = "david:david"
      val plainCredsBytes = plainCreds.getBytes
      val base64CredsBytes = Base64.getEncoder.encode(plainCredsBytes)
      val base64Creds = new String(base64CredsBytes)
      val client = new Iot_ingestion_managerClient(ws)(s"http://localhost:$port")
      val result1 = Await.result(client.start(s"Basic $base64Creds"), Duration.Inf)

      Thread.sleep(1000)

      val result2 = Await.result(client.stop(s"Basic $base64Creds"), Duration.Inf)
    }
  }

  private val hbaseUtil = new HBaseTestingUtility()

  private var baseConf: Configuration = _

  override def beforeAll(): Unit = {
    hbaseUtil.startMiniCluster(4)
    val conf = new SparkConf().
      setAppName("daf-iot-manager-local-test").
      setMaster("local[4]").
      set("spark.io.compression.codec", "lzf")
    baseConf = hbaseUtil.getConfiguration
    hbaseUtil.createTable(TableName.valueOf("tsdb-uid"), Array("id", "name"))
    hbaseUtil.createTable(TableName.valueOf("tsdb"), Array("t"))
    hbaseUtil.createTable(TableName.valueOf("tsdb-tree"), Array("t"))
    hbaseUtil.createTable(TableName.valueOf("tsdb-meta"), Array("name"))
    val confFile: File = confPath / "hbase-site.xml"
    for {os <- confFile.newOutputStream.autoClosed} baseConf.writeXml(os)

    val zkPort = baseConf.get("hbase.zookeeper.property.clientPort")

    zkUtils = for {
      zkUtils <- makeZkUtils(zkPort)
    } yield zkUtils

    for (i <- 0 until NUMBROKERS)
      kafkaServers(i) = for {
        kafkaServer <- makeKafkaServer(s"$ZKHOST:$zkPort", i)
      } yield kafkaServer

    ()
  }

  override def afterAll(): Unit = {
    //hbaseUtil.deleteTable("tsdb-uid")
    //hbaseUtil.deleteTable("tsdb")
    //hbaseUtil.deleteTable("tsdb-tree")
    //hbaseUtil.deleteTable("tsdb-meta")
    shutdownKafkaServers()
    hbaseUtil.shutdownMiniCluster()
  }
}

@SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.Null"))
object ServiceSpec {

  private val (testDataPath, confPath) = {
    val testDataPath = s"${PathUtils.getTestDir(classOf[ServiceSpec]).getCanonicalPath}/MiniCluster"
    val confPath = s"$testDataPath/conf"
    (
      testDataPath.toFile.createIfNotExists(asDirectory = true, createParents = false),
      confPath.toFile.createIfNotExists(asDirectory = true, createParents = false)
    )
  }
}

class TestOpenTSDBConfigurator(mapConf: Map[String, String]) extends OpenTSDBConfigurator with Serializable {

  lazy val configuration: Configuration = mapConf.foldLeft(new Configuration(false)) { (conf, pair) =>
    conf.set(pair._1, pair._2)
    conf
  }

}

object TestOpenTSDBConfigurator {

  def apply(conf: Configuration): TestOpenTSDBConfigurator = new TestOpenTSDBConfigurator(
    conf.iterator().asScala.map { entry => entry.getKey -> entry.getValue }.toMap[String, String]
  )

}