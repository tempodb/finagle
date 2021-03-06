package com.twitter.finagle.builder

import org.specs.SpecificationWithJUnit
import com.twitter.concurrent.Spool
import com.twitter.util.{Return, Promise}
import collection.mutable

class ClusterSpec extends SpecificationWithJUnit {
  case class WrappedInt(val value: Int)

  class ClusterInt extends Cluster[Int] {
    var set = mutable.HashSet.empty[Int]
    var changes = new Promise[Spool[Cluster.Change[Int]]]

    def add(value: Int) = {
      set += value
      performChange(Cluster.Add(value))
    }

    def del(value: Int) = {
      set -= value
      performChange(Cluster.Rem(value))
    }

    private[this] def performChange(change: Cluster.Change[Int]) = {
      val newTail = new Promise[Spool[Cluster.Change[Int]]]
      changes() = Return(change *:: newTail)
      changes = newTail
    }

    def snap = (set.toSeq, changes)
  }

  "Cluster map" should {
    val N = 10
    val cluster1 = new ClusterInt()
    val cluster2 = cluster1.map(a => WrappedInt(a))

    "provide 1-1 mapping to the result cluster" in {
      0 until N foreach { cluster1.add(_) }
      val (seq, changes) = cluster2.snap
      var set = seq.toSet
      changes foreach { spool =>
        spool foreach {
          case Cluster.Add(elem) => set += elem
          case Cluster.Rem(elem) => set -= elem
        }
      }
      set.size must be_==(N)
      0 until N foreach { cluster1.del(_) }
      set.size must be_==(0)
    }

    "remove mapped objects in the same order they were received (for each key)" in {
      val changes = mutable.Queue[Cluster.Change[WrappedInt]]()
      val (_, spool) = cluster2.snap
      spool foreach { _ foreach { changes enqueue _ } }
      cluster1.add(1)
      cluster1.add(2)
      cluster1.add(1)
      cluster1.add(2)
      changes.toSeq must be_==(Seq(
        Cluster.Add(WrappedInt(1)),
        Cluster.Add(WrappedInt(2)),
        Cluster.Add(WrappedInt(1)),
        Cluster.Add(WrappedInt(2))
      ))

      cluster1.del(1)
      changes must haveSize(5)
      changes(4).value must be(changes(0).value)
      cluster1.del(1)
      changes must haveSize(6)
      changes(5).value must be(changes(2).value)

      cluster1.del(2)
      changes must haveSize(7)
      changes(6).value must be(changes(1).value)
      cluster1.del(2)
      changes must haveSize(8)
      changes(7).value must be(changes(3).value)

      cluster1.del(100)
      changes must haveSize(9)
      for (ch <- changes take 8)
        ch.value mustNot be(changes(8).value)
    }
  }
}
