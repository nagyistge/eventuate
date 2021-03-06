/*
 * Copyright 2015 - 2016 Red Bull Media House GmbH <http://www.redbullmediahouse.com> - all rights reserved.
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

package com.rbmhtechnology.eventuate

import akka.actor._
import akka.testkit.TestProbe

import org.scalatest._

import scala.collection.immutable.Seq
import scala.util._

object EventsourcedActorCausalitySpec {
  class Collaborator(val id: String, val eventLog: ActorRef, override val sharedClockEntry: Boolean, handles: Set[String], probe: ActorRef) extends EventsourcedActor {
    def onCommand = {
      case s: String => persist(s) {
        case Success(e) =>
        case Failure(e) => throw e
      }
    }

    def onEvent = {
      case s: String if handles.contains(s) =>
        probe ! ((s, lastVectorTimestamp, currentVectorTime))
    }
  }
}

trait EventsourcedActorCausalitySpec extends WordSpec with Matchers with MultiLocationSpec {
  import ReplicationIntegrationSpec.replicationConnection
  import EventsourcedActorCausalitySpec._

  def assertPartialOrder[A](events: Seq[A], sample: A*): Unit = {
    val indices = sample.map(events.indexOf)
    assert(indices == indices.sorted)
  }

  "Event-sourced actors" when {
    "located at different locations" can {
      "track causality by default (sharedClockEntry = true)" in {
        val logName = "L1"

        val locationA = location("A")
        val locationB = location("B")

        val endpointA = locationA.endpoint(Set(logName), Set(replicationConnection(locationB.port)))
        val endpointB = locationB.endpoint(Set(logName), Set(replicationConnection(locationA.port)))

        val logA = endpointA.logs(logName)
        val logB = endpointB.logs(logName)

        val logIdA = endpointA.logId(logName)
        val logIdB = endpointB.logId(logName)

        val probeA1 = new TestProbe(locationA.system)
        val probeA2 = new TestProbe(locationA.system)
        val probeA3 = new TestProbe(locationA.system)
        val probeB = new TestProbe(locationB.system)

        val actorA1 = locationA.system.actorOf(Props(new Collaborator("pa1", logA, sharedClockEntry = true, Set("e1", "e2", "e5"), probeA1.ref)))
        val actorA2 = locationA.system.actorOf(Props(new Collaborator("pa2", logA, sharedClockEntry = true, Set("e3", "e5", "e6"), probeA2.ref)))
        val actorA3 = locationA.system.actorOf(Props(new Collaborator("pa3", logA, sharedClockEntry = true, Set("e4"), probeA3.ref)))
        val actorB = locationB.system.actorOf(Props(new Collaborator("pb", logB, sharedClockEntry = true, Set("e1", "e6"), probeB.ref)))

        def vectorTime(a: Long, b: Long) = (a, b) match {
          case (0L, 0L) => VectorTime()
          case (a,  0L) => VectorTime(logIdA -> a)
          case (0L,  b) => VectorTime(logIdB -> b)
          case (a,   b) => VectorTime(logIdA -> a, logIdB -> b)
        }

        actorB ! "e1"
        probeA1.expectMsg(("e1", vectorTime(0, 1), vectorTime(1, 1)))
        probeB.expectMsg(("e1", vectorTime(0, 1), vectorTime(0, 1)))

        actorA1 ! "e2"
        probeA1.expectMsg(("e2", vectorTime(2, 1), vectorTime(2, 1)))

        actorA2 ! "e3"
        probeA2.expectMsg(("e3", vectorTime(3, 0), vectorTime(3, 0)))

        actorA3 ! "e4"
        probeA3.expectMsg(("e4", vectorTime(4, 0), vectorTime(4, 0)))

        actorA1 ! "e5"
        probeA1.expectMsg(("e5", vectorTime(5, 1), vectorTime(5, 1)))
        probeA2.expectMsg(("e5", vectorTime(5, 1), vectorTime(5, 1)))

        actorA2 ! "e6"
        probeA2.expectMsg(("e6", vectorTime(6, 1), vectorTime(6, 1)))
        probeB.expectMsg(("e6", vectorTime(6, 1), vectorTime(6, 6)))

        // -----------------------------------------------------------
        //  Please note:
        //  - e2 <-> e3 (because e1 -> e2 and e1 <-> e3)
        //  - e3 <-> e4 (but plausible clocks reports e3 -> e4)
        // -----------------------------------------------------------
      }
    }
    "located at the same location" can {
      "track causality if enabled (sharedClockEntry = false)" in {
        val logName = "L1"

        val locationA = location("A")
        val endpointA = locationA.endpoint(Set(logName), Set())

        val logA = endpointA.logs(logName)
        val logIdA = endpointA.logId(logName)

        val actorIdA = "PA"
        val actorIdB = "PB"
        val actorIdC = "PC"

        val probeA = new TestProbe(locationA.system)
        val probeB = new TestProbe(locationA.system)
        val probeC = new TestProbe(locationA.system)

        val actorA = locationA.system.actorOf(Props(new Collaborator(actorIdA, logA, sharedClockEntry = false, Set("e1", "e3"), probeA.ref)))
        val actorB = locationA.system.actorOf(Props(new Collaborator(actorIdB, logA, sharedClockEntry = false, Set("e2", "e3"), probeB.ref)))
        val actorC = locationA.system.actorOf(Props(new Collaborator(actorIdC, logA, sharedClockEntry = true, Set("e1", "e2", "e3"), probeC.ref)))

        actorA ! "e1"
        probeA.expectMsg(("e1", VectorTime(actorIdA -> 1L), VectorTime(actorIdA -> 1L)))
        probeC.expectMsg(("e1", VectorTime(actorIdA -> 1L), VectorTime(actorIdA -> 1L, logIdA -> 1L)))

        actorB ! "e2"
        probeB.expectMsg(("e2", VectorTime(actorIdB -> 1L), VectorTime(actorIdB -> 1L)))
        probeC.expectMsg(("e2", VectorTime(actorIdB -> 1L), VectorTime(actorIdA -> 1L, actorIdB -> 1L, logIdA -> 2L)))

        actorC ! "e3"
        probeA.expectMsg(("e3", VectorTime(actorIdA -> 1L, actorIdB -> 1L, logIdA -> 3L), VectorTime(actorIdA -> 2L, actorIdB -> 1L, logIdA -> 3L)))
        probeB.expectMsg(("e3", VectorTime(actorIdA -> 1L, actorIdB -> 1L, logIdA -> 3L), VectorTime(actorIdA -> 1L, actorIdB -> 2L, logIdA -> 3L)))
        probeC.expectMsg(("e3", VectorTime(actorIdA -> 1L, actorIdB -> 1L, logIdA -> 3L), VectorTime(actorIdA -> 1L, actorIdB -> 1L, logIdA -> 3L)))
      }
    }
  }
}
