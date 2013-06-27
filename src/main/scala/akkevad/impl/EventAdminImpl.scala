package akkevad.impl

import org.osgi.service.event.{EventHandler, EventAdmin, Event}
import akka.pattern.ask
import akka.actor._
import scala.concurrent.{Future, Await}
import scala.concurrent.duration._
import akka.util.Timeout
import scala.Some
import scala.concurrent.ExecutionContext.Implicits.global
import org.osgi.framework.Filter

/**
 * Event used when requesting actors interested in a specific topic.
 */
case class RequestActors(topic : String, sender : ActorRef, wilder : List[ActorRef], originalTopic : String)
case class ResponseActors(interested : ActorRef, wilder : List[ActorRef])

/**
 * Event triggering call to the event handlers for a specific topic.
 */
case class PostEvent(topic : String, original : Event)

/**
 * Event used when a Done response is wanted.
 */
case class WaitEvent(topic : String, original : Event)
case class WilderWaitEvent(topic : String, original : Event)
case object Done


case class RegisterTopic(topic : String, handler : EventHandler, filter : Option[Filter], originalTopic : String)
case class UnregisterTopic(topic : String, handler : EventHandler, originalTopic : String)
case class RegisterTopics(topics : List[String], handler : EventHandler, filter : Option[Filter])

/**
 * Delegating events to the event admin actor. Send events will trigger a request for all actors
 * that are interested in a specific topic and then get the event sent to them to be handled.
 *
 * @param eventActor Event admin actor
 */
class EventAdminImpl(eventActor : ActorRef) extends EventAdmin {
  def postEvent(event : Event) {
    eventActor ! PostEvent(event.getTopic.concat("/"), event)
  }

  def sendEvent(event : Event) {
    // TODO: This is probably not very safe way of doing this...
    // Infinite duration might be a bad choice. Lets fix this some time...

    implicit val timeout = Timeout(10 seconds)
    val future = eventActor ? RequestActors(event.getTopic.concat("/"), null, List(), event.getTopic)
    val resp = Await.result(future, Duration.Inf).asInstanceOf[ResponseActors]

    val futures =
      if (resp.interested == null) List.empty
      else List(resp.interested) map (r => r ? WaitEvent(event.getTopic, event))
    val wilders = resp.wilder map (r => r ? WilderWaitEvent(event.getTopic, event))

    val futureList = Future.sequence(futures ::: wilders)
    Await result(futureList, Duration.Inf)
  }
}

/**
 * Actor responsible for handling topic actors.
 *
 * When a topic is registered a hierarchy of children will be created. Take for instance the topic
 * "akka/osgi/event/topic". The event admin actor will create the child "akka", which will then
 * create the next child "osgi" and so on. Just like the Akka path system.
 */
class EventAdminActor extends Actor {
  var handlers : Set[EventHandler] = Set()

  def receive = {
    case PostEvent(t, e) => {
      context.child(Utils.name(t)) match {
        case Some(c) => Utils.next(t) match {
          case Some(nxt) => c ! PostEvent(nxt, e)
          case None => // BUT WHY??
        }
        case _ => // Sending on unknown topic?
      }

      handlers foreach(handler => handler.handleEvent(e))
    }

    case WilderWaitEvent(t, e) => {
      handlers.foreach(h => h.handleEvent(e))
      sender ! Done
    }

    case RequestActors(t, _, w, o) => context.child(Utils.name(t)) match {
      case Some(c) => {
        Utils.next(t) match {
          case Some(nxt) => {
            if (handlers.isEmpty) c ! RequestActors(nxt, sender, w, o)
            else c ! RequestActors(nxt, sender, self :: w, o)
          }
          case None => sender ! ResponseActors(null, wilders(w)) // BUT WHY?
        }
      }
      case _ => sender ! ResponseActors(null, wilders(w)) // No child found, probably unknown topic but we might be interested
    }

    // Registering
    case RegisterTopics(topics, handler, filter) => topics.foreach(t => self ! RegisterTopic(t, handler, filter, t))

    case RegisterTopic(t, h, f, o) => {
      if (!t.isEmpty) t.concat("/") match { // Append a "/" to the topic to be able to create the child hierarchy
        case "*/" => handlers += new FilteredEventHandler(h, f)
        case topic => Utils.next(topic) match {
          case Some(nxt) => {
            Utils.get(context, Utils.name(topic)) ! RegisterTopic(nxt, h, f, o)
          }
          case None => // I'm the end of line
        }
      }
    }

    case UnregisterTopic(topic, handler, o) => {
      if (!topic.isEmpty) topic.concat("/") match {
        case "*/" => handlers -= new FilteredEventHandler(handler, None)
        case t => Utils.next(t) match {
          case Some(nxt) => context.child(Utils.name(t)) match {
            case Some(c) => c ! UnregisterTopic(nxt, handler, o)
            case None => // Shouldn't need to do anything here
          }
          case None => // Shouldn't need to do anything here
        }
      }
    }
  }

  def wilders(w : List[ActorRef]) = if (handlers.isEmpty) w else self :: w
}

/**
 * Actor responsible for holding all handlers interesting in one specific topic.
 */
class TopicActor extends Actor {
  var handlers : Set[EventHandler] = Set()
  var all : Set[EventHandler] = Set()

  def receive = {
    // Post events
    case PostEvent(t, e) => {
      Utils.next(t) match {
        case Some(n) => context.child(Utils.name(t)) match {
          case Some(c) => c ! PostEvent(n, e)
          case _ => // Sending on unknown topic?
        }
        case None => handlers foreach(handler => handler.handleEvent(e)) // Hitting end of topic line
      }

      all foreach(h => h.handleEvent(e))
    }

    // Wait event
    case WaitEvent(t, e) => {
      handlers foreach(handler => handler.handleEvent(e))
      sender ! Done
    }

    // I'm a wilder
    case WilderWaitEvent(t, e) => {
      all foreach(h => h.handleEvent(e))
      sender ! Done
    }

    case req : RequestActors => Utils.next(req.topic) match {
      case Some(n) => context.child(Utils.name(req.topic)) match {
        case Some(c) => // Might be interested as a wilder
          c ! RequestActors(n, req.sender, wilders(req.wilder), req.originalTopic)
        case None => // Sending on unknown topic? Lets respond with whatever we have gotten this far.
          req.sender ! ResponseActors(null, wilders(req.wilder))
      }
      case None => { // Hitting end of line. Lets response with a list of actors that are interested.
        if (req.originalTopic.endsWith(self.path.name)) req.sender ! ResponseActors(self, wilders(req.wilder))
        else req.sender ! ResponseActors(null, wilders(req.wilder))
      }
    }

    // Registering
    case RegisterTopic(t, h, f, o) => {
      t match {
        case "*/" => all += new FilteredEventHandler(h, f)
        case topic => Utils.next(topic) match {
          case Some(nxt) => Utils.get(context, Utils.name(topic)) ! RegisterTopic(nxt, h, f, o)
          case None if o.endsWith(self.path.name) => handlers += new FilteredEventHandler(h, f)
        }
      }
    }

    case UnregisterTopic(topic, handler, original) => topic match {
      case "*/" => all -= handler
      case t => Utils.next(t) match {
        case Some(nxt) => context.child(Utils.name(t)) match {
          case Some(c) => c ! UnregisterTopic(nxt, handler, original)
          case None => // Shouldn't need to do anything here
        }
        case None if original.endsWith(self.path.name) => handlers -= new FilteredEventHandler(handler, None)
      }
    }
  }

  def wilders(w : List[ActorRef]) = if (all.isEmpty) w else self :: w
}

/**
 * Event handler delegating to another if the filter is passed on incoming events.
 *
 * @param handler The event handler to delegate to.
 * @param filter  The filter the event must pass.
 */
class FilteredEventHandler(val handler : EventHandler, filter : Option[Filter]) extends EventHandler {
  def handleEvent(event: Event) {
    filter match {
      case Some(f) => if (event.matches(f)) handler.handleEvent(event)
      case None => handler.handleEvent(event)
    }
  }

  override def hashCode(): Int = handler.hashCode()

  override def equals(obj: Any): Boolean = handler.equals(obj.asInstanceOf[FilteredEventHandler].handler)
}

object Utils {
  /**
   * @return The next child name to be used for the topic.
   */
  def name(topic : String) = topic.substring(0, topic.indexOf('/'))

  /**
   * @return The next part of the topic to be used for the rest of the child hierarchy.
   */
  def next(topic : String) = topic.indexOf('/') match {
    case -1 => None
    case i => Some(topic.substring(i + 1))
  }

  /**
   * Get or create a child.
   */
  def get(context : ActorContext, name : String) : ActorRef = context.child(name) getOrElse create(context, name)

  /**
   * Create a child.
   */
  def create(context : ActorContext, name : String) : ActorRef = context.actorOf(Props(new TopicActor()), name)
}