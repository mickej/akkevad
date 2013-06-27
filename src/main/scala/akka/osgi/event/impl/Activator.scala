package akka.osgi.event.impl

import org.osgi.framework._
import org.osgi.util.tracker.{ServiceTrackerCustomizer, ServiceTracker}
import org.osgi.service.event.{EventConstants, EventAdmin, EventHandler}
import akka.actor.{ActorRef, Props, ActorSystem}
import ActivatorUtils._
import akka.osgi.ActorSystemActivator
import java.util

class Activator extends ActorSystemActivator {
  var tracker : Option[ServiceTracker[EventHandler, EventHandler]] = None
  var eventAdminReg : Option[ServiceRegistration[EventAdmin]] = None

  def configure(context: BundleContext, system: ActorSystem) {
    val eventAdminActor = system.actorOf(Props(new EventAdminActor()), "eventAdminActor")
    val eventAdmin = new EventAdminImpl(eventAdminActor)

    eventAdminReg = Option(context.registerService(classOf[EventAdmin], eventAdmin, null))

    val customizer = new EventHandlerCustomizer(context, eventAdminActor)
    tracker = Option(new ServiceTracker[EventHandler, EventHandler](context, classOf[EventHandler], customizer))
    tracker.get.open
  }

  override def stop(context: BundleContext) {
    super.stop(context)

    if (!tracker.isEmpty) {
      tracker.get.close
      tracker = None
    }

    if (!eventAdminReg.isEmpty) {
      eventAdminReg.get.unregister
      eventAdminReg = null
    }
  }
}

/**
 * Service tracker customizer taking care of event handlers and notifies the event admin actor
 * whenever a handler arrives or disappear.
 */
class EventHandlerCustomizer(context : BundleContext, eventAdminActor : ActorRef) extends ServiceTrackerCustomizer[EventHandler, EventHandler] {
  def addingService(reference: ServiceReference[EventHandler]): EventHandler = {
    val service = context.getService(reference)
    addService(reference, service)
  }

  def modifiedService(reference: ServiceReference[EventHandler], service: EventHandler) {
    removedService(reference, service)
    addService(reference, service)
  }

  def removedService(reference: ServiceReference[EventHandler], service: EventHandler) {
    topics(reference.getProperty(EventConstants.EVENT_TOPIC)).foreach(
      t => eventAdminActor ! UnregisterTopic(t, service, t))
  }

  def addService(reference: ServiceReference[EventHandler], service: EventHandler) : EventHandler = {
    val filterStr = reference.getProperty(EventConstants.EVENT_FILTER).asInstanceOf[String]
    val filter = if (filterStr == null) None else Option(FrameworkUtil.createFilter(filterStr))

    eventAdminActor ! RegisterTopics(topics(reference.getProperty(EventConstants.EVENT_TOPIC)), service, filter)
    service
  }
}

object ActivatorUtils {
  /**
   * Converts the event topics to a better suited type.
   *
   * @param tcs String, array or collection of string.
   *
   * @return A list of topics.
   */
  def topics(tcs : Any) : List[String] = tcs match {
    case str : String => List(str)
    case arr : Array[String] => arr.toList
    case coll : util.Collection[String] => coll.toArray(new Array[String](coll.size())).toList // How should this be done?
    case _ => throw new IllegalArgumentException
  }
}