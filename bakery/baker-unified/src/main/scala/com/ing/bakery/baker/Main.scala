package com.ing.bakery.baker

import java.io.File
import java.net.InetSocketAddress
import java.util

import akka.actor.{ActorSystem, ExtendedActorSystem}
import akka.cluster.Cluster
import akka.cluster.metrics.SigarMetricsCollector
import cats.effect.{ExitCode, IO, IOApp, Resource}
import com.ing.baker.recipe.javadsl.Interaction
import com.ing.baker.runtime.akka.AkkaBakerConfig.KafkaEventSinkSettings
import com.ing.baker.runtime.akka.internal.InteractionManagerLocal
import com.ing.baker.runtime.akka.{AkkaBaker, AkkaBakerConfig}
import com.ing.baker.runtime.scaladsl.InteractionInstance
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import io.prometheus.client.Collector
import kamon.Kamon
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import org.http4s.server.Server
import org.springframework.context.annotation.AnnotationConfigApplicationContext

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext


object Main extends IOApp with LazyLogging {

  override def run(args: List[String]): IO[ExitCode] = {

    import org.hyperic.sigar.Sigar
    import kamon.sigar.SigarProvisioner
    SigarProvisioner.provision()
    val sigar = new Sigar()
    Kamon.init()

    val configPath = sys.env.getOrElse("CONFIG_DIRECTORY", "/opt/docker/conf")
    val config = ConfigFactory.load(ConfigFactory.parseFile(new File(s"$configPath/application.conf")))

    val bakery = config.getConfig("bakery")

    implicit val system: ActorSystem = ActorSystem("baker", config)
    implicit val executionContext: ExecutionContext = system.dispatcher

    val defaultDecayFactor = 2.0 / (1 + 10)
    val akkaMetricsCollector  = new SigarMetricsCollector(
      system.asInstanceOf[ExtendedActorSystem].provider.rootPath.address,
      defaultDecayFactor, sigar)
    MetricService.register(new Collector() {
      override def collect(): util.List[Collector.MetricFamilySamples] =
        seqAsJavaList(akkaMetricsCollector.metrics() map { m =>
          new Collector.MetricFamilySamples(m.name, Collector.Type.GAUGE, s"Akka value ${m.name}",
            List(new Collector.MetricFamilySamples.Sample(m.name,
              List.empty.asJava, List.empty.asJava, m.value.doubleValue())).asJava)
        } toList)
    })

    val httpServerPort = bakery.getInt("api-port")
    val metricsPort = bakery.getInt("metrics-port")
    val apiUrlPrefix = bakery.getString("api-url-prefix")
    val loggingEnabled = bakery.getBoolean("api-logging-enabled")
    logger.info(s"Logging of API: $loggingEnabled  - MUST NEVER BE SET TO 'true' IN PRODUCTION")

    val configurationClasses = bakery.getStringList("interaction-configuration-classes")

    val eventSinkSettings = config.getConfig("baker.kafka-event-sink").as[KafkaEventSinkSettings]

    val interactions = {
      if (configurationClasses.size() == 0) {
        logger.warn("No interactions configured, probably interaction-configuration-classes config parameter is empty")
      }
      (configurationClasses.asScala map { configurationClass =>
        val configClass = Class.forName(configurationClass)
        val ctx = new AnnotationConfigApplicationContext()
        ctx.register(configClass)
        ctx.refresh()
        val interactionsAsJavaMap: java.util.Map[String, Interaction] =
          ctx.getBeansOfType(classOf[com.ing.baker.recipe.javadsl.Interaction])
        val interactions = interactionsAsJavaMap.asScala.values.map(InteractionInstance.unsafeFrom).toList
        logger.info(s"Loaded ${interactions.size} interactions from $configurationClass: ${interactions.sortBy(_.name).map(_.name).mkString(",")}")
        interactions
      } toList).flatten
    }

    val interactionManager = new InteractionManagerLocal(interactions)

    val bakerConfig = AkkaBakerConfig(
      interactionManager = interactionManager,
      bakerActorProvider = AkkaBakerConfig.bakerProviderFrom(config),
      timeouts = AkkaBakerConfig.Timeouts.from(config),
      bakerValidationSettings = AkkaBakerConfig.BakerValidationSettings.from(config)
    )(system)

    logger.info("Starting Akka Baker...")
    val baker = AkkaBaker.withConfig(bakerConfig)

    val mainResource: Resource[IO, (Server[IO], Server[IO])] =
      for {
        eventSink <- KafkaEventSink.resource(eventSinkSettings)
        _ <- Resource.liftF(eventSink.attach(baker))
        _ <- Resource.liftF(RecipeLoader.loadRecipesIntoBaker(configPath, baker))
        _ <- Resource.liftF(IO.async[Unit] { callback =>
          Cluster(system).registerOnMemberUp {
            logger.info("Akka cluster is now up")
            callback(Right(()))
          }
        })
        metricsService <- MetricService.resource(
          InetSocketAddress.createUnresolved("0.0.0.0", metricsPort)
        )
        bakerService <- BakerService.resource(baker,
          InetSocketAddress.createUnresolved("0.0.0.0", httpServerPort),
          apiUrlPrefix, interactionManager, loggingEnabled)
      } yield (bakerService, metricsService)

    mainResource.use( servers => {
      logger.info(s"Bakery started at ${servers._1.address}/${servers._1.baseUri}, enabling the readiness in Akka management")
      BakerReadinessCheck.enable()
      IO.never
    }
    ).as(ExitCode.Success)
  }
}
