package akka.osgi.event.impl

import scala.concurrent.duration._
import org.scalatest.matchers.MustMatchers
import akka.actor.{EmptyLocalActorRef, ActorSystem}
import org.osgi.service.event.{EventHandler, Event}
import java.util.Collections
import akka.testkit._
import org.scalatest.mock.MockitoSugar
import org.mockito.{Matchers, Mockito}
import org.mockito.stubbing.Answer
import org.mockito.invocation.InvocationOnMock
import org.scalatest.{BeforeAndAfterAll, FunSuite}

class EventAdminImplTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
    with FunSuite with MustMatchers with BeforeAndAfterAll with MockitoSugar {

  val adminActor = TestActorRef(new EventAdminActor(), "admin")
  val admin : EventAdminImpl = new EventAdminImpl(adminActor)
  val handler = mock[EventHandler]

  def this() = this(ActorSystem("MySpec"))

  override def afterAll() {
    system.shutdown()
  }

  def waitForThis(path : String) : Boolean = {
    val ref = system.actorFor(path)
    !ref.isInstanceOf[EmptyLocalActorRef]
  }

  test("handle the register event") {
    adminActor ! RegisterTopic("akka/osgi/event/is/this", handler, None, "akka/osgi/event/is/this")
    assert(TestKit.awaitCond(waitForThis("/user/admin/akka/osgi/event/is/this"), 5 second, 10 millis, noThrow = true))
  }

  test("handle the synchronous send event") {
    adminActor ! RegisterTopic("akka/osgi/event", handler, None, "akka/osgi/event")
    val e = new Event("akka/osgi/event", Collections.emptyMap[String, Object])
    admin.sendEvent(e)
    Mockito.verify(handler).handleEvent(e)
    noMoreAndReset(handler)
  }

  val handler2 = mock[EventHandler]
  test("handle multiple synchronous send event") {
    adminActor ! RegisterTopic("akka/osgi/event", handler2, None, "akka/osgi/event")
    val e = new Event("akka/osgi/event", Collections.emptyMap[String, Object])
    admin.sendEvent(e)
    Mockito.verify(handler).handleEvent(e)
    Mockito.verify(handler2).handleEvent(e)
    noMoreAndReset(handler)
    noMoreAndReset(handler2)
  }

  test("No handler be invoked on unknown topic") {
    val e = new Event("akka/osgi/event2", Collections.emptyMap[String, Object])
    admin.sendEvent(e)
    noMoreAndReset(handler)
    noMoreAndReset(handler2)
  }

  test("Event admin should handle post event") {
    val e = new Event("akka/osgi/event/is/this", Collections.emptyMap[String, Object])
    admin.postEvent(e)
    Mockito.verify(handler, Mockito.timeout(10000)).handleEvent(e)
    noMoreAndReset(handler)
    noMoreAndReset(handler2)
  }

  test("Event admin should handle post event with multiple handlers") {
    val e = new Event("akka/osgi/event", Collections.emptyMap[String, Object])
    admin.postEvent(e)
    Mockito.verify(handler, Mockito.timeout(10000)).handleEvent(e)
    Mockito.verify(handler2, Mockito.timeout(10000)).handleEvent(e)
    noMoreAndReset(handler)
    noMoreAndReset(handler2)
  }

  test("handle ordered delivery of post events") {
    val events = List.fill(10)(new Event("akka/osgi/event/is/this", Collections.singletonMap[String, Object]("test.property", new Object)))

    var incoming : List[Object] = List()
    Mockito.when(handler.handleEvent(Matchers.any[Event]())).then(new Answer[Object] {
      def answer(invocation: InvocationOnMock): Object = {
        incoming = incoming :+ invocation.getArguments.head.asInstanceOf[Event].getProperty("test.property")
        //incoming = invocation.getArguments.head.asInstanceOf[Event].getProperty("test.property") ++ incoming
        null
      }
    })

    events.foreach(e => admin.postEvent(e))
    Mockito.verify(handler, Mockito.timeout(10000).times(10)).handleEvent(Matchers.any[Event]())

    // Verify the order
    assert(incoming == events.map(_.getProperty("test.property")))
    noMoreAndReset(handler)
    noMoreAndReset(handler2)
  }

  test("handle unregister event") {
    adminActor ! UnregisterTopic("akka/osgi/event", handler, "akka/osgi/event")
    val e = new Event("akka/osgi/event", Collections.emptyMap[String, Object])
    admin.sendEvent(e)
    Mockito.verify(handler2).handleEvent(e)
    noMoreAndReset(handler)
    noMoreAndReset(handler2)
  }

  def noMoreAndReset(h : EventHandler) = {
    Mockito.verifyNoMoreInteractions(h)
    Mockito.reset(h)
  }
}
