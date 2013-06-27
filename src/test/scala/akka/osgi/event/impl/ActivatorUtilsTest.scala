package akka.osgi.event.impl

import org.scalatest.FunSuite
import java.util.Arrays

/**
 * Created by micke on 6/25/13.
 */
class ActivatorUtilsTest extends FunSuite {
  test("utils topics should handle a simple string") {
    assert(ActivatorUtils.topics("test/topic1") == List("test/topic1"))
  }

  test("utils topics should handle collection") {
    assert(ActivatorUtils.topics(Arrays.asList("test/topic1", "test/topic2")) == List("test/topic1", "test/topic2"))
  }

  test("utils topics should handle java array") {
    assert(ActivatorUtils.topics(Arrays.asList("test/topic3", "test/topic4").toArray) == List("test/topic3", "test/topic4"))
  }

  test("utils topics should ignore other objects") {
    intercept[IllegalArgumentException] {
      ActivatorUtils.topics(new Object)
    }
  }

  //test("utils topics should ignore other list objects") {
  //  intercept[IllegalArgumentException] {
  //    ActivatorUtils.topics(Arrays.asList(new Object, new Object))
  //  }
  //}

  test("utils topics should ignore other array objects") {
    intercept[IllegalArgumentException] {
      ActivatorUtils.topics(Arrays.asList(new Object, new Object).toArray)
    }
  }
}
